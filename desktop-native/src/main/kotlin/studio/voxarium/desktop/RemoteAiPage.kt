package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

internal data class RemoteConsumerTarget(
    val target: NativeAiTarget, val grantId: String, val grantRevision: Long, val expires: Long,
    val name: String, val executorName: String?, val epoch: String?, val permissions: Set<String>, val teams: Set<String>,
    val members: Set<String> = emptySet(), val scope: String? = null,
) {
    val key get() = target.export to grantId
    val available: Boolean get() = epoch != null && executorName != null && expires > System.currentTimeMillis()
}

private data class ConsumerPage(val items: List<RemoteConsumerTarget>, val cursor: String?)
private data class ResourcePage(val rows: List<JsonObject>, val cursor: String?)
private data class TranscriptPage(val chunks: List<JsonObject>, val cursor: String?, val epoch: String, val seq: Long)

private suspend fun StudioApi.consumerTargets(device: NativeAiRegistration, cursor: String?): ConsumerPage = coroutineScope {
    val path = "$nativeAiAccess/registrations/${device.id}"
    val directory = async { request("$path/directory").jsonObject.remoteRows("registrations") }
    val catalog = request("$path/targets" + (cursor?.let { query("after" to it) } ?: "")).jsonObject
    val executors = directory.await().groupBy { it.aiId("registration_id") }
    val next = catalog.string("next_after")
    if (next != null && (next == cursor || !aiLocalId.matches(next))) remoteInvalid()
    val rows = catalog.remoteRows("targets").map { entry ->
        val target = entry.obj("target")?.nativeAiTarget() ?: remoteInvalid()
        val grant = entry.obj("grant") ?: remoteInvalid()
        val expires = grant.remoteNumber("expires_at")?.takeIf { it >= 0 } ?: remoteInvalid()
        val epoch = entry.string("executor_epoch")
        val status = entry.string("state")
        if (target.server != device.server || status !in setOf("online", "offline") ||
            (status == "online") != (epoch?.let(aiLocalId::matches) == true)) remoteInvalid()
        val executor = executors[target.executor]?.singleOrNull()?.takeIf { it.string("kind") == "studio" }
        RemoteConsumerTarget(target, grant.aiId("grant_id"), grant.aiRevision("revision"), expires,
            entry.string("display_name") ?: remoteInvalid(), executor?.string("display_name"), epoch,
            grant.remoteStrings("operations").toSet(), grant.remoteStrings("team_ids").toSet(),
            grant.remoteStrings("member_ids").toSet(), grant.string("resource_scope"))
    }.filter { it.expires > System.currentTimeMillis() }
    if (rows.distinctBy(RemoteConsumerTarget::key).size != rows.size) remoteInvalid()
    ConsumerPage(rows, next)
}

internal fun consumerReadEnvelope(device: NativeAiRegistration, pin: RemoteConsumerTarget, op: String, payload: JsonObject): JsonObject {
    val permission = when (op) {
        "transcript.page" -> "view_transcript"
        "stream.subscribe" -> when (payload.string("topic")) {
            "status" -> "view_status"
            "transcript" -> "view_transcript"
            else -> remoteInvalid()
        }
        "resource.list", "resource.get", "catalog.list" -> "view_status"
        "stream.unsubscribe" -> null
        else -> remoteInvalid()
    }
    if (pin.epoch == null || pin.target.server != device.server || !device.hasToken || device.kind != "studio" ||
        permission != null && (!pin.available || permission !in pin.permissions)) throw RemoteFailure("nativeRemote.stale")
    return pinnedConsumerRequest(device, pin, op, payload)
}

internal fun pinnedConsumerRequest(device: NativeAiRegistration, pin: RemoteConsumerTarget, op: String, payload: JsonObject,
    fields: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("registration_id", device.id)
        putJsonObject("request") {
            fields.forEach { (key, value) -> put(key, value) }
            put("target", pin.target.payload()); put("grant_id", pin.grantId); put("grant_revision", pin.grantRevision)
            put("expected_executor_epoch", requireNotNull(pin.epoch)); put("op", op); put("payload", payload)
        }
}

private suspend fun StudioApi.consumerRead(device: NativeAiRegistration, pin: RemoteConsumerTarget, op: String, payload: JsonObject): JsonObject {
    val response = request("$nativeAiAccess/requests", "POST", consumerReadEnvelope(device, pin, op, payload)).jsonObject
    if (!pin.available || response.obj("target")?.nativeAiTarget() != pin.target || response.string("command_id") != null) {
        throw RemoteFailure("nativeRemote.stale")
    }
    if (response.string("state") != "completed" || response.string("code") != null) {
        throw RemoteFailure(when (response.string("code")) {
            "denied", "expired", "auth_failure" -> "nativeRemote.unauthorized"
            "snapshot_changed", "stale_target", "revision_conflict" -> "nativeRemote.stale"
            "offline", "unavailable", "not_available" -> "nativeRemote.unavailable"
            else -> "nativeRemote.failed"
        })
    }
    return response.obj("result") ?: remoteInvalid()
}

private fun JsonObject.checkedResource(pin: RemoteConsumerTarget, id: String? = null): JsonObject {
    val reportedId = aiId("resource_id")
    if (id != null && id != reportedId || string("kind") !in setOf("task", "chat") ||
        string("team_id") !in pin.teams || string("title") == null || string("status").isNullOrEmpty() ||
        remoteFlag("is_running") == null || remoteFlag("waiting") == null) remoteInvalid()
    aiRevision("revision")
    return this
}

private data class ConsumerWatermark(val epoch: String, var seq: Long)
private fun JsonObject.consumerWatermark() = ConsumerWatermark(
    string("stream_epoch")?.takeIf(String::isNotBlank) ?: remoteInvalid(),
    remoteNumber("seq")?.takeIf { it in 0 until Long.MAX_VALUE } ?: remoteInvalid(),
)

private data class ConsumerSubscription(val id: String, val topic: String, val mark: ConsumerWatermark)

// §16: take snapshots, then replay only frames for the pinned resource and exact subscriptions.
private suspend fun StudioApi.observeConsumerResource(
    device: NativeAiRegistration,
    pin: RemoteConsumerTarget,
    snapshot: JsonObject,
    onStatus: (JsonObject) -> Unit,
    onTranscript: (TranscriptPage) -> Unit,
    onChunk: (JsonObject) -> Unit,
    onLive: () -> Unit,
) = coroutineScope {
    val id = snapshot.aiId("resource_id")
    val subscriptions = mutableListOf<ConsumerSubscription>()
    val ready = CompletableDeferred<Unit>()
    val frames = Channel<StudioEvent>(32)
    val read = launch {
        try {
            remoteEvents().collect { frame ->
                if (frame == null) ready.complete(Unit)
                else if (frame.type == "resync_required" || frame.payload?.string("resource_id") == id) {
                    if (!frames.trySend(frame).isSuccess) remoteInvalid()
                }
            }
            throw RemoteFailure("nativeRemote.unavailable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ready.completeExceptionally(error)
            frames.close(error)
        }
    }

    suspend fun subscribe(topic: String, mark: ConsumerWatermark) {
        val cursor = buildJsonObject {
            put("resource_id", id)
            put("topic", topic)
            put("stream_epoch", mark.epoch)
            put("after_seq", mark.seq)
        }
        val response = consumerRead(device, pin, "stream.subscribe", cursor)
        val sid = response.aiId("subscription_id")
        val ack = response.consumerWatermark()
        if (subscriptions.any { it.id == sid }) remoteInvalid()
        subscriptions.add(ConsumerSubscription(sid, topic, ack))
        if (ack != mark) remoteInvalid()
    }

    try {
        withTimeoutOrNull(10_000) { ready.await(); true } ?: throw RemoteFailure("nativeRemote.unavailable")
        subscribe("status", snapshot.consumerWatermark())
        if ("view_transcript" in pin.permissions) {
            val page = consumerTranscript(device, pin, id)
            onTranscript(page)
            subscribe("transcript", ConsumerWatermark(page.epoch, page.seq))
        }
        onLive()
        var revision = snapshot.aiRevision("revision")
        for (event in frames) {
            if (!pin.available) throw RemoteFailure("nativeRemote.stale")
            val data = event.payload ?: remoteInvalid()
            val sid = data.string("subscription_id")
            if (event.type == "resync_required" && sid == null) throw RemoteFailure("nativeRemote.stale")
            val sub = subscriptions.find { it.id == sid } ?: continue
            if (event.type == "resync_required") throw RemoteFailure("nativeRemote.stale")
            if (data.obj("target")?.nativeAiTarget() != pin.target || data.string("resource_id") != id) remoteInvalid()
            val mark = data.consumerWatermark()
            if (mark.epoch != sub.mark.epoch || mark.seq > sub.mark.seq + 1) throw RemoteFailure("nativeRemote.stale")
            if (mark.seq <= sub.mark.seq) continue
            val payload = data.obj("payload") ?: remoteInvalid()
            if (sub.topic == "status") {
                val next = payload.checkedResource(pin, id)
                val nextRevision = next.aiRevision("revision")
                if (next.string("kind") != snapshot.string("kind") || nextRevision != data.aiRevision("resource_revision") ||
                    nextRevision < revision) remoteInvalid()
                revision = nextRevision
                onStatus(next)
            } else {
                onChunk(payload)
            }
            sub.mark.seq = mark.seq
        }
    } finally {
        read.cancel()
        withContext(NonCancellable) {
            withTimeoutOrNull(3_000) {
                for (sub in subscriptions) {
                    try {
                        request("$nativeAiAccess/requests", "POST",
                            consumerReadEnvelope(device, pin, "stream.unsubscribe", jsonFields("subscription_id" to sub.id)))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
            }
        }
    }
}

private suspend fun StudioApi.consumerResources(device: NativeAiRegistration, pin: RemoteConsumerTarget, cursor: String?): ResourcePage {
    val result = consumerRead(device, pin, "resource.list", buildJsonObject { cursor?.let { put("cursor", it) } })
    val rows = result.remoteRows("items").map { it.checkedResource(pin) }
    val next = result.string("next_cursor")
    if (rows.size > 50 || rows.distinctBy { it.string("resource_id") }.size != rows.size || next != null && (next.isEmpty() || next == cursor)) remoteInvalid()
    return ResourcePage(rows, next)
}

private suspend fun StudioApi.consumerTranscript(device: NativeAiRegistration, pin: RemoteConsumerTarget, resource: String,
                                                prior: TranscriptPage? = null): TranscriptPage {
    val result = consumerRead(device, pin, "transcript.page", buildJsonObject {
        put("resource_id", resource); prior?.cursor?.let { put("cursor", it) }
    })
    val cursor = result.string("next_cursor")
    val epoch = result.string("stream_epoch")?.takeIf(String::isNotEmpty) ?: remoteInvalid()
    val seq = result.remoteNumber("seq")?.takeIf { it >= 0 } ?: remoteInvalid()
    if (prior != null && (epoch != prior.epoch || seq != prior.seq || cursor != null && cursor == prior.cursor)) throw RemoteFailure("nativeRemote.stale")
    val rows = result.remoteRows("items")
    if (rows.size > 50) remoteInvalid()
    val all = prior?.chunks.orEmpty() + rows
    validateTranscriptChunks(all, resource)
    return TranscriptPage(all, cursor, epoch, seq)
}

private fun validateTranscriptChunks(chunks: List<JsonObject>, resource: String) {
    if (chunks.size > 1000) throw RemoteFailure("nativeRemote.ai.transcriptLimit")
    var bytes = 0
    for (item in chunks) {
        val text = item.string("text") ?: remoteInvalid()
        val size = text.toByteArray(Charsets.UTF_8).size
        bytes += size
        if (bytes > 4 * 1024 * 1024) throw RemoteFailure("nativeRemote.ai.transcriptLimit")
        val offset = item.remoteNumber("offset") ?: remoteInvalid()
        val total = item.remoteNumber("total_bytes") ?: remoteInvalid()
        val omitted = item.remoteFlag("content_omitted") ?: remoteInvalid()
        if (item.string("resource_id") != resource || item.string("message_id").isNullOrEmpty() || item.string("role") == null ||
            offset < 0 || total !in 0..1_048_576L || offset > total || size > total - offset ||
            omitted && (text.isNotEmpty() || offset != 0L) || !omitted && size == 0 && offset < total) remoteInvalid()
    }
}

private data class RemoteMessage(val role: String, val text: String, val omitted: Boolean, val next: Long, val total: Long)

private fun combineRemoteMessage(chunks: List<JsonObject>): RemoteMessage {
    val first = chunks.first()
    val role = first.string("role") ?: remoteInvalid()
    val total = first.remoteNumber("total_bytes") ?: remoteInvalid()
    val omitted = first.flag("content_omitted")
    var position = 0L
    val text = buildString {
        for (chunk in chunks.sortedBy { it.remoteNumber("offset") }.distinctBy { it.remoteNumber("offset") }) {
            if (chunk.string("role") != role || chunk.flag("content_omitted") != omitted || chunk.remoteNumber("total_bytes") != total) break
            if (chunk.remoteNumber("offset") != position) break
            val part = chunk.string("text") ?: break
            append(part)
            position += part.toByteArray(Charsets.UTF_8).size
        }
    }
    return RemoteMessage(role, text, omitted, position, total)
}

@Composable
internal fun RemoteAiPage(tab: ProjectTab?, studio: StudioApi, m: Messages, openAdvanced: () -> Unit) {
    val workspaceKey = tab?.root
    val op = remember(workspaceKey) { RemoteOperation() }
    val scope = rememberCoroutineScope()
    var registrations by remember(workspaceKey) { mutableStateOf<List<NativeAiRegistration>?>(null) }
    var requesterId by remember(workspaceKey) { mutableStateOf("") }
    var targets by remember(workspaceKey) { mutableStateOf(ConsumerPage(emptyList(), null)) }
    var pinned by remember(workspaceKey) { mutableStateOf<RemoteConsumerTarget?>(null) }
    var resources by remember(workspaceKey) { mutableStateOf(ResourcePage(emptyList(), null)) }
    var selected by remember(workspaceKey) { mutableStateOf<JsonObject?>(null) }
    var transcript by remember(workspaceKey) { mutableStateOf<TranscriptPage?>(null) }
    var arriving by remember(workspaceKey) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var online by remember(workspaceKey) { mutableStateOf(false) }
    var liveProblem by remember(workspaceKey) { mutableStateOf<String?>(null) }
    val device = registrations?.find { it.id == requesterId }
    val pin = pinned
    val commands = remember(studio, device, pin) {
        if (device == null || pin == null) null else RemoteConsumerCommands(studio, device, pin)
    }
    val colors = localColors.current

    fun closeResource() {
        selected = null
        transcript = null
        arriving = emptyList()
        online = false
        liveProblem = null
    }
    fun clearTarget() {
        closeResource()
        pinned = null
        resources = ResourcePage(emptyList(), null)
    }

    fun run(work: suspend () -> Unit) {
        scope.launch {
            op.run {
                try {
                    work()
                } catch (error: RemoteFailure) {
                    if (error.key in setOf("nativeRemote.stale", "nativeRemote.unauthorized", "nativeRemote.unavailable")) {
                        clearTarget()
                        targets = ConsumerPage(emptyList(), null)
                    }
                    throw error
                }
            }
        }
    }
    fun refresh() {
        clearTarget()
        targets = ConsumerPage(emptyList(), null)
        run {
            registrations = studio.aiRegistrations().filter { it.hasToken && it.kind == "studio" }
            if (registrations?.none { it.id == requesterId } != false) requesterId = ""
            registrations?.find { it.id == requesterId }?.let { targets = studio.consumerTargets(it, null) }
        }
    }

    fun openResource(id: String, kind: String) {
        if (op.busy || device == null || pin == null) return
        closeResource()
        run {
            val result = studio.consumerRead(device, pin, "resource.get", jsonFields("resource_id" to id)).checkedResource(pin, id)
            if (kind != result.string("kind")) remoteInvalid()
            if (pinned == pin) selected = result
        }
    }

    LaunchedEffect(studio, workspaceKey) { refresh() }
    LaunchedEffect(commands) { if (commands != null) op.run(commands::load) }
    LaunchedEffect(device, pin) {
        if (device != null && pin != null) {
            try {
                while (pin.available) {
                    delay((pin.expires - System.currentTimeMillis()).coerceIn(0, 12_000))
                    if (!pin.available) break
                    val catalog = studio.consumerRead(device, pin, "catalog.list", buildJsonObject {})
                    if (catalog.obj("target")?.nativeAiTarget() != pin.target) remoteInvalid()
                }
                throw RemoteFailure("nativeRemote.stale")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (pinned == pin) {
                    clearTarget()
                    targets = ConsumerPage(emptyList(), null)
                    liveProblem = (error as? RemoteFailure)?.key ?: "nativeRemote.unavailable"
                }
            }
        }
    }
    val resourceId = selected?.string("resource_id")
    LaunchedEffect(device, pin, resourceId) {
        val snapshot = selected
        if (device == null || pin == null || snapshot == null || resourceId == null) return@LaunchedEffect
        try {
            studio.observeConsumerResource(device, pin, snapshot,
                onStatus = { update ->
                    selected = update
                    resources = resources.copy(rows = resources.rows.map { if (it.string("resource_id") == resourceId) update else it })
                },
                onTranscript = { transcript = it },
                onChunk = { chunk ->
                    val joined = transcript?.chunks.orEmpty() + arriving + chunk
                    validateTranscriptChunks(joined, resourceId)
                    arriving = arriving + chunk
                },
                onLive = { online = true })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (pinned == pin && selected?.string("resource_id") == resourceId) {
                clearTarget()
                targets = ConsumerPage(emptyList(), null)
                liveProblem = (error as? RemoteFailure)?.key ?: "nativeRemote.unavailable"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        WorkHeader {
            Heading(m["remote"], Modifier.weight(1f), StudioTokens.titleSize.value.toInt())
            LibraryButton("refresh-cw", m["refresh"], !op.busy, ::refresh)
            LibraryButton("external-link", m["openWeb"], !op.busy, openAdvanced)
        }
        ScrollList(Modifier.weight(1f).fillMaxWidth().padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    RemoteFeedback(op, m)
                    liveProblem?.let { ProblemText(Problem(it)) }
                    if (registrations?.isEmpty() == true) Muted(m.ai("noRegistrations"))
                    RemoteChoices(m["nativeRemote.ai.thisDevice"], requesterId, registrations.orEmpty().map { it.id to it.name }, !op.busy) { id ->
                        requesterId = id
                        clearTarget()
                        targets = ConsumerPage(emptyList(), null)
                        val chosen = registrations?.find { it.id == id }
                        if (chosen != null) run { targets = studio.consumerTargets(chosen, null) }
                    }
                    if (device != null && targets.items.isEmpty() && !op.busy) Muted(m["nativeRemote.ai.noTargets"])
                }
            }
            items(targets.items, key = { "target:${it.target.export}:${it.grantId}" }) { target ->
                LibraryCard(selected = target == pin) {
                    LibrarySectionTitle(target.name)
                    LibraryCaption(listOfNotNull(target.target.project, target.executorName, target.target.export).joinToString(" · "), mono = true, maxLines = 2)
                    if (!target.available) Muted(m["nativeRemote.state.offline"])
                    else if (device != null && target != pin) {
                        LibraryButton("folder-open", m["open"], !op.busy && "view_status" in target.permissions) {
                            clearTarget()
                            run {
                                val rows = studio.consumerResources(device, target, null)
                                pinned = target
                                resources = rows
                            }
                        }
                    }
                }
            }
            if (device != null && targets.cursor != null) item {
                LibraryButton("arrow-down", m["nativeRemote.more"], !op.busy) {
                    run {
                        val next = studio.consumerTargets(device, targets.cursor)
                        targets = ConsumerPage((targets.items + next.items).distinctBy(RemoteConsumerTarget::key), next.cursor)
                    }
                }
            }
            if (pin != null && device != null && pin.available) {
                if (commands != null) {
                    item { RemoteConsumerReceiptControls(commands, m, !op.busy, ::run) }
                    item { RemoteConsumerCreate(pin, commands, m, !op.busy, ::run, ::openResource) }
                }
                item {
                    Rule()
                    LibrarySectionTitle(m["nativeRemote.ai.resourcesTitle"])
                    LibraryButton("refresh-cw", m["refresh"], !op.busy) {
                        run { resources = studio.consumerResources(device, pin, null) }
                    }
                    if (resources.rows.isEmpty()) Muted(m["nativeRemote.ai.noResources"])
                }
                items(resources.rows, key = { "resource:${it.string("resource_id")}" }) { row ->
                    val id = row.aiId("resource_id")
                    ListRow(row.string("title").orEmpty(), row.string("assignee_label"), selected = selected?.string("resource_id") == id,
                        status = row.string("status")) {
                        openResource(id, row.string("kind").orEmpty())
                    }
                }
                if (resources.cursor != null) item {
                    LibraryButton("arrow-down", m["nativeRemote.more"], !op.busy) {
                        run {
                            val next = studio.consumerResources(device, pin, resources.cursor)
                            val rows = resources.rows + next.rows
                            if (rows.distinctBy { it.string("resource_id") }.size != rows.size) remoteInvalid()
                            resources = ResourcePage(rows, next.cursor)
                        }
                    }
                }
                selected?.let { row ->
                    item {
                        val id = row.aiId("resource_id")
                        LibraryCard {
                            LibrarySectionTitle(row.string("title").orEmpty())
                            LibraryCaption("$id · ${row.string("assignee_label").orEmpty()}", mono = true)
                            row.string("status")?.let { WorkStatus(it) }
                            Muted(m[if (online) "nativeRemote.ai.live" else "loading"])
                            LibraryButton("x", m["close"], !op.busy, ::closeResource)
                            if (commands != null && online) {
                                RemoteConsumerResourceActions(pin, row, commands, m, !op.busy, ::run)
                            }
                        }
                    }
                    transcript?.let { page ->
                        val chunksByMessage = (page.chunks + arriving).groupBy { it.string("message_id") }
                        items(chunksByMessage.entries.toList(), key = { "message:${it.key}" }) { (messageId, chunks) ->
                            val message = remember(chunks) { combineRemoteMessage(chunks) }
                            LibraryCard {
                                LibraryCaption(m[when (message.role) {
                                    "user" -> "nativeRemote.ai.roleUser"
                                    "assistant" -> "nativeRemote.ai.roleAgent"
                                    else -> "nativeRemote.ai.roleOther"
                                }])
                                if (message.omitted) Muted(m["nativeRemote.ai.omitted"]) else Text(message.text)
                                if (messageId != null && !message.omitted && message.next < message.total) {
                                    LibraryButton("arrow-down", m["nativeRemote.ai.moreMessage"], !op.busy) {
                                        run {
                                            val id = row.aiId("resource_id")
                                            val result = studio.consumerRead(device, pin, "transcript.page", buildJsonObject {
                                                put("resource_id", id)
                                                put("message_id", messageId)
                                                put("offset", message.next)
                                            })
                                            val mark = result.consumerWatermark()
                                            if (mark.epoch != page.epoch || mark.seq < page.seq ||
                                                result.string("message_id") != messageId || result.remoteNumber("offset") != message.next) {
                                                throw RemoteFailure("nativeRemote.stale")
                                            }
                                            if (selected?.string("resource_id") == id && pinned == pin) {
                                                if (arriving.none { it.string("message_id") == messageId && it.remoteNumber("offset") == message.next }) {
                                                    validateTranscriptChunks(page.chunks + arriving + result, id)
                                                    arriving = arriving + result
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (page.cursor != null) item {
                            LibraryButton("arrow-down", m["nativeRemote.more"], !op.busy) {
                                run {
                                    val id = row.aiId("resource_id")
                                    val next = studio.consumerTranscript(device, pin, id, page)
                                    if (selected?.string("resource_id") == id && pinned == pin) {
                                        validateTranscriptChunks(next.chunks + arriving, id)
                                        transcript = next
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
