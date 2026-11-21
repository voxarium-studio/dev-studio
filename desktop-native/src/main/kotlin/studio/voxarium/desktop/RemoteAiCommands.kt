package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

private val receiptStates = setOf("accepted", "dispatching", "running", "completed", "failed", "cancelled", "rejected", "denied_before_dispatch")
private val knownRejections = setOf("denied", "not_available", "offline", "stale_target", "revision_conflict",
    "busy", "expired", "rate_limited", "capacity_exceeded", "invalid_request", "auth_failure", "disabled")

private data class RemoteCommand(val id: String, val operation: String)

internal class RemoteConsumerCommands(private val studio: StudioApi, private val device: NativeAiRegistration,
    private val pin: RemoteConsumerTarget) {
    private val pending = mutableStateMapOf<String, RemoteCommand>()
    val unknownIds: List<String> get() = pending.keys.sorted()
    val ownedIds = mutableStateListOf<String>()
    var ready by mutableStateOf(false)
        private set
    var state by mutableStateOf<String?>(null)
        private set
    var created by mutableStateOf<Pair<String, String>?>(null)
        private set
    val canWrite: Boolean get() = ready && pending.isEmpty() && pin.available

    suspend fun load() {
        ready = false
        val rows = studio.request("$nativeAiAccess/receipts").jsonObject.remoteRows("receipts")
        for (row in rows) {
            if (row.string("registration_id") != device.id || row.obj("target")?.nativeAiTarget() != pin.target) continue
            val command = RemoteCommand(row.aiId("command_id"), row.string("op") ?: remoteInvalid())
            val response = row.obj("response")
            if (response?.string("command_id") == command.id && response.obj("target")?.nativeAiTarget() == pin.target &&
                accept(command, row.string("state"), response.obj("result"))) pending.remove(command.id)
            else pending[command.id] = command
        }
        ready = true
    }

    // §16: a lost response is ambiguous. Check the original receipt without resending the intent.
    suspend fun recover(id: String) {
        val command = pending[id] ?: return
        if (!pin.available) throw RemoteFailure("nativeRemote.stale")
        val response = studio.request("$nativeAiAccess/receipts/${segment(id)}/recover", "POST", buildJsonObject {
            put("executor_epoch", requireNotNull(pin.epoch))
            put("resend", false)
        }).jsonObject
        if (!pin.available || response.obj("target")?.nativeAiTarget() != pin.target || response.string("command_id") != null) remoteInvalid()
        val result = response.obj("result")
        if (response.string("state") != "completed" || response.string("code") != null ||
            result?.string("command_id") != command.id || result.string("op") != command.operation ||
            !accept(command, result.string("state"), result)) throw RemoteFailure("nativeRemote.unknownOutcome")
        state = result.string("state")
        pending.remove(id)
    }

    suspend fun send(operation: String, payload: JsonObject, expected: JsonObject = JsonObject(emptyMap())): Boolean {
        if (!canWrite) return false
        val command = RemoteCommand(UUID.randomUUID().toString(), operation)
        state = null
        val fields = JsonObject(expected + buildJsonObject {
            put("command_id", command.id)
            put("issued_at", System.currentTimeMillis())
        })
        pending[command.id] = command
        try {
            val response = studio.request("$nativeAiAccess/requests", "POST",
                pinnedConsumerRequest(device, pin, operation, payload, fields)).jsonObject
            if (!pin.available || response.obj("target")?.nativeAiTarget() != pin.target ||
                response.string("command_id") != command.id) throw RemoteFailure("nativeRemote.unknownOutcome")
            val code = response.string("code")
            val reported = response.string("state")
            if (code in knownRejections || accept(command, reported, response.obj("result"))) pending.remove(command.id)
            if (pending.containsKey(command.id)) throw RemoteFailure("nativeRemote.unknownOutcome")
            state = reported
            if (code != null) throw commandRejection(code)
            return reported in setOf("accepted", "dispatching", "running", "completed")
        } catch (error: ApiFailure) {
            if (error.message in knownRejections) {
                pending.remove(command.id)
                throw commandRejection(error.message)
            }
            throw RemoteFailure("nativeRemote.unknownOutcome")
        } catch (error: Exception) {
            if (error is CancellationException || error is RemoteFailure) throw error
            throw RemoteFailure("nativeRemote.unknownOutcome")
        }
    }

    private fun accept(command: RemoteCommand, state: String?, result: JsonObject?): Boolean {
        if (state !in receiptStates) return false
        val matches = result?.string("command_id") == command.id && result.string("op") == command.operation &&
            result.string("state") == state
        if (state == "completed" && command.operation in setOf("task.create", "chat.create")) {
            val kind = command.operation.substringBefore('.')
            val id = result?.string("resource_id")
            if (!matches || result.string("resource_kind") != kind || id == null || !aiLocalId.matches(id)) return false
            if (id !in ownedIds) ownedIds.add(id)
            created = id to kind
        }
        return matches || state == "rejected"
    }
}

private fun commandRejection(code: String?) = RemoteFailure(when (code) {
    "denied", "auth_failure", "expired", "disabled" -> "nativeRemote.unauthorized"
    "stale_target", "revision_conflict" -> "nativeRemote.stale"
    "offline", "not_available" -> "nativeRemote.unavailable"
    else -> "nativeRemote.failed"
})

private fun validRemoteText(value: String, limit: Int) = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= limit

@Composable
internal fun RemoteConsumerCreate(
    pin: RemoteConsumerTarget, commands: RemoteConsumerCommands, m: Messages, enabled: Boolean,
    launch: (suspend () -> Unit) -> Unit, openResource: (String, String) -> Unit,
) {
    val kinds = listOf("task", "chat").filter { "create_$it" in pin.permissions }
    if (kinds.isEmpty()) return
    var kind by remember(pin) { mutableStateOf(kinds.first()) }
    var team by remember(pin) { mutableStateOf("") }
    var member by remember(pin) { mutableStateOf("") }
    var title by remember(pin) { mutableStateOf(TextFieldValue()) }
    var brief by remember(pin) { mutableStateOf(TextFieldValue()) }
    var paths by remember(pin) { mutableStateOf(TextFieldValue()) }
    val task = kind == "task"
    val writePaths = paths.text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val valid = team in pin.teams && member in pin.members && validRemoteText(title.text.trim(), 480) &&
        (!task || validRemoteText(brief.text, 16 * 1024) && writePaths.size <= 64 && writePaths.all { validRemoteText(it, 256) })
    LibraryCard {
        LibrarySectionTitle(m[if (task) "nativeRemote.ai.createTask" else "nativeRemote.ai.createChat"])
        if (kinds.size > 1) RemoteChoices(m["nativeRemote.ai.resourceKind"], kind,
            kinds.map { it to m[if (it == "task") "tasks" else "chats"] }, enabled) { kind = it }
        RemoteChoices(m["nativeRemote.ai.teamId"], team, pin.teams.sorted().map { it to it }, enabled) { team = it }
        RemoteChoices(m["nativeRemote.ai.memberId"], member, pin.members.sorted().map { it to it }, enabled) { member = it }
        RemoteField(m["nativeRemote.ai.resourceTitle"], title, { title = it }, enabled)
        if (task) {
            RemoteCommandText(m["nativeRemote.ai.brief"], brief, enabled) { brief = it }
            RemoteCommandText(m["nativeRemote.ai.paths"], paths, enabled) { paths = it }
        }
        LibraryButton("plus", m[if (task) "nativeRemote.ai.createTask" else "nativeRemote.ai.createChat"],
            enabled && commands.canWrite && valid) {
            val body = buildJsonObject {
                put("team_id", team)
                put("title", title.text.trim())
                put("locale", m.language)
                put(if (task) "assignee_id" else "member_id", member)
                if (task) {
                    put("brief", brief.text)
                    putJsonArray("write_paths") { writePaths.forEach { add(it) } }
                }
            }
            launch {
                if (commands.send("$kind.create", body)) {
                    title = TextFieldValue()
                    brief = TextFieldValue()
                    paths = TextFieldValue()
                }
            }
        }
        commands.created?.let { (id, createdKind) ->
            LibraryCaption(id, mono = true)
            if ("view_status" in pin.permissions) LibraryButton("folder-open", m["open"], enabled) { openResource(id, createdKind) }
        }
    }
}

@Composable
internal fun RemoteConsumerResourceActions(
    pin: RemoteConsumerTarget, row: JsonObject, commands: RemoteConsumerCommands, m: Messages, enabled: Boolean,
    launch: (suspend () -> Unit) -> Unit,
) {
    val id = row.aiId("resource_id")
    val kind = row.string("kind") ?: return
    if ("operate_existing" !in pin.permissions && pin.scope != "created_by_this_grantee" && id !in commands.ownedIds) return
    val revision = row.aiRevision("revision")
    val runId = row.string("run_id")
    val running = row.flag("is_running")
    val status = row.string("status")
    val start = kind == "task" && "run_task" in pin.permissions && !running && !row.flag("waiting") && status in setOf("todo", "pending")
    val send = kind == "chat" && "run_chat" in pin.permissions && !running && status in setOf("idle", "error")
    val note = kind == "task" && "write_task" in pin.permissions
    val cancel = "cancel_$kind" in pin.permissions && runId != null && running
    var waitRevision by remember(pin, id) { mutableStateOf<Long?>(null) }
    var cancelledRun by remember(pin, id, runId) { mutableStateOf(false) }
    var message by remember(pin, id) { mutableStateOf(TextFieldValue()) }
    val allow = enabled && commands.canWrite
    fun act(action: String, text: String? = null) {
        val payload = buildJsonObject {
            put("resource_id", id)
            if (text != null) put("text", text)
        }
        val expected = buildJsonObject {
            if (action == "cancel" && runId != null) put("expected_run_id", runId)
            if (action == "start" || action == "send") put("expected_resource_revision", revision)
        }
        launch {
            if (commands.send("$kind.$action", payload, expected)) {
                if (text != null) message = TextFieldValue()
                if (action == "start" || action == "send") waitRevision = revision
                if (action == "cancel") cancelledRun = true
            }
        }
    }
    if (start || send) Muted(m["nativeRemote.ai.executionHint"])
    if (start || cancel) Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        if (start) LibraryButton("play", m["nativeRemote.ai.start"], allow && waitRevision != revision) { act("start") }
        if (cancel) LibraryButton("ban", m["nativeRemote.ai.cancelRun"], allow && !cancelledRun) { act("cancel") }
    }
    if (note || send) {
        RemoteCommandText(m["nativeRemote.ai.message"], message, allow) { message = it }
        LibraryButton("send", m[if (note) "nativeRemote.ai.addNote" else "nativeRemote.ai.sendMessage"],
            allow && validRemoteText(message.text, 16 * 1024) && (!send || waitRevision != revision)) {
            act(if (note) "note" else "send", message.text)
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RemoteCommandText(label: String, value: TextFieldValue, enabled: Boolean, change: (TextFieldValue) -> Unit) {
    LibraryCaption(label)
    TextArea(value, change, Modifier.fillMaxWidth().heightIn(66.dp, 145.dp).semantics { contentDescription = label },
        enabled = enabled)
}

@Composable
internal fun RemoteConsumerReceiptControls(commands: RemoteConsumerCommands, m: Messages, enabled: Boolean,
    launch: (suspend () -> Unit) -> Unit) {
    if (commands.ready && commands.unknownIds.isEmpty() && commands.state == null) return
    LibraryCard {
        LibrarySectionTitle(m["nativeRemote.ai.receipts"])
        commands.state?.let { state ->
            if (state in receiptStates) Text(m["nativeRemote.ai.receipt.$state"])
        }
        if (!commands.ready) LibraryButton("refresh-cw", m["retry"], enabled) { launch(commands::load) }
        commands.unknownIds.forEach { id ->
            LibraryCaption(id, mono = true)
            LibraryButton("refresh-cw", m["nativeRemote.ai.checkReceipt"], enabled) { launch { commands.recover(id) } }
        }
    }
}
