package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.*

internal const val remoteProfilesPath = "/api/remote/servers"
internal val remoteProjectId = Regex("[a-z0-9][a-z0-9._-]{0,63}")
internal val remoteProfileId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
internal val remoteStorageId = Regex("[0-9a-f]{32}")
internal val remoteDigest = Regex("[0-9a-f]{64}")

internal fun JsonObject.remoteNumber(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

internal fun JsonObject.remoteFlag(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

internal fun JsonObject.remoteArray(key: String): JsonArray = get(key) as? JsonArray ?: remoteInvalid()
internal fun JsonObject.remoteRows(key: String) = remoteArray(key).map { it as? JsonObject ?: remoteInvalid() }
internal fun JsonObject.remoteStrings(key: String) = remoteArray(key).map {
    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: remoteInvalid()
}

internal fun remoteInvalid(): Nothing = throw RemoteFailure("nativeRemote.invalidResponse")
internal class RemoteFailure(val key: String) : Exception(key)

internal class RemoteOperation {
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)

    suspend fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        notice = null
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Remote inputs, especially credentials, are never rendered in an error.
            problem = when (error) {
                is RemoteFailure -> error.key
                is ApiFailure -> when (error.message) {
                    "insecure_blocked" -> "nativeRemote.insecureBlocked"
                    "pending_edits", "invalid_editor_ack", "room_unresolved", "editor_lease_expired" -> "nativeRemote.pendingEdits"
                    "snapshot_changed", "preview_stale", "preview_not_found" -> "nativeRemote.previewStale"
                    "preview_expired" -> "nativeRemote.previewExpired"
                    "recovery_required", "profile_recovery_required", "resume_required" -> "nativeRemote.recoveryRequired"
                    "reload_required", "unknown_clients" -> "nativeRemote.reloadRequired"
                    "unauthorised", "studio_unauthorised" -> "nativeRemote.unauthorized"
                    "outcome_unknown", "create_unknown" -> "nativeRemote.unknownOutcome"
                    else -> when (error.status) {
                        401, 403 -> "nativeRemote.unauthorized"
                        409 -> "nativeRemote.stale"
                        422, 400 -> "nativeRemote.invalidInput"
                        404, 503 -> "nativeRemote.unavailable"
                        else -> "nativeRemote.failed"
                    }
                }
                else -> "nativeRemote.failed"
            }
        } finally {
            busy = false
        }
    }
}

internal data class NativeRemoteProfile(
    val id: String, val name: String, val url: String, val revision: Long,
    val hasToken: Boolean, val credentialSource: String, val verification: JsonObject,
) {
    fun matches(other: NativeRemoteProfile) = id == other.id && url == other.url && revision == other.revision && other.hasToken
}

internal fun JsonObject.nativeRemoteProfile() = NativeRemoteProfile(
    string("profile_id")?.takeIf(remoteProfileId::matches) ?: remoteInvalid(),
    string("name") ?: remoteInvalid(), string("url") ?: remoteInvalid(),
    remoteNumber("revision")?.takeIf { it >= 1 } ?: remoteInvalid(),
    remoteFlag("has_token") ?: remoteInvalid(), string("credential_source") ?: remoteInvalid(),
    obj("verification") ?: remoteInvalid(),
)

internal suspend fun StudioApi.nativeRemoteProfiles() = request(remoteProfilesPath).jsonObject
    .remoteRows("items").map(JsonObject::nativeRemoteProfile).also { rows ->
        if (rows.distinctBy(NativeRemoteProfile::id).size != rows.size) remoteInvalid()
    }

internal data class RemoteBindingSnapshot(
    val projectId: String, val projectEpoch: String, val backendEpoch: String,
    val generation: Long, val revision: Long?, val fingerprint: String,
    val persistentId: String?, val url: String, val profileId: String?, val storageEpoch: String?,
    val enabled: Boolean, val state: String, val recovery: JsonObject?, val change: JsonObject?,
    val reason: String?, val activeRooms: Long, val pendingWrites: Long,
) {
    val hasCatalogBinding: Boolean get() = persistentId != null && profileId != null && storageEpoch != null && url.isNotEmpty()
    val changing: Boolean get() = change != null && change.string("state") !in setOf("committed", "cancelled", "expired", "recovered")
    val stable: Boolean get() = !changing && recovery == null && state !in setOf("migration_required", "quiescing", "blocked")
    fun sameTarget(other: RemoteBindingSnapshot) =
        projectId == other.projectId && projectEpoch == other.projectEpoch && backendEpoch == other.backendEpoch &&
            generation == other.generation && revision == other.revision && fingerprint == other.fingerprint &&
            persistentId == other.persistentId && url == other.url && profileId == other.profileId &&
            storageEpoch == other.storageEpoch && enabled == other.enabled

    fun writeEvidence() = buildJsonObject {
        put("backend_epoch", backendEpoch)
        put("expected_revision", revision ?: throw RemoteFailure("nativeRemote.stale"))
        put("config_fingerprint", fingerprint)
    }
}

internal fun remoteBindingSnapshot(row: JsonObject, binding: ProjectBinding): RemoteBindingSnapshot {
    if (row.string("project_id") != binding.id || row.string("epoch") != binding.epoch) throw RemoteFailure("nativeRemote.reloadRequired")
    return RemoteBindingSnapshot(
        projectId = binding.id, projectEpoch = binding.epoch,
        backendEpoch = row.string("backend_epoch")?.takeIf(String::isNotBlank) ?: remoteInvalid(),
        generation = row.remoteNumber("binding_generation")?.takeIf { it >= 0 && it < Long.MAX_VALUE - 1 } ?: remoteInvalid(),
        revision = row.remoteNumber("identity_revision")?.takeIf { it >= 1 && it < Long.MAX_VALUE },
        fingerprint = row.string("config_fingerprint")?.takeIf(remoteDigest::matches) ?: remoteInvalid(),
        persistentId = row.string("persistent_id")?.takeIf(remoteProjectId::matches), url = row.string("url") ?: remoteInvalid(),
        profileId = row.string("profile_id")?.takeIf(remoteProfileId::matches),
        storageEpoch = row.string("space_instance_id")?.takeIf(remoteStorageId::matches),
        enabled = row.remoteFlag("enabled") ?: remoteInvalid(), state = row.string("state") ?: remoteInvalid(),
        recovery = row.obj("recovery"), change = row.obj("binding_change"), reason = row.string("reason"),
        activeRooms = row.remoteNumber("active_rooms") ?: remoteInvalid(), pendingWrites = row.remoteNumber("pending_writes") ?: remoteInvalid(),
    )
}

internal suspend fun ProjectApi.remoteBinding() = remoteBindingSnapshot(get("/remote").jsonObject, binding)

internal data class CatalogProject(val id: String, val name: String, val storageEpoch: String) {
    companion object {
        fun read(row: JsonObject): CatalogProject {
            if (row.keys != setOf("id", "display_name", "storage_epoch", "metadata_revision", "created_at") ||
                (row.remoteNumber("metadata_revision") ?: 0L) < 1 || row.string("created_at").isNullOrEmpty()) remoteInvalid()
            return CatalogProject(row.string("id")?.takeIf(remoteProjectId::matches) ?: remoteInvalid(),
                row.string("display_name") ?: remoteInvalid(),
                row.string("storage_epoch")?.takeIf(remoteStorageId::matches) ?: remoteInvalid())
        }
    }
}

private data class RemoteCatalog(val profile: NativeRemoteProfile, val projects: List<CatalogProject>, val cursor: String?)
private data class RemoteCreate(val profile: NativeRemoteProfile, val id: String, val name: String, val op: String = UUID.randomUUID().toString())

internal data class RemoteBindingIntent(
    val observation: RemoteBindingSnapshot, val operation: String, val id: String, val url: String,
    val profile: NativeRemoteProfile? = null, val target: CatalogProject? = null,
    val migration: NativeMigrationSelection? = null,
) {
    val revision get() = observation.revision ?: if (migration != null) 0L else throw RemoteFailure("nativeRemote.stale")
    fun confirmation() = jsonFields("url" to url, "persistent_id" to id)

    fun prepareBody() = buildJsonObject {
        put("backend_epoch", observation.backendEpoch)
        put("expected_revision", revision)
        put("config_fingerprint", observation.fingerprint)
        put("operation", operation)
        put("persistent_id", id)
        put("url", url)
        if (operation == "select") {
            put("profile_id", requireNotNull(profile).id)
            put("catalog_id", requireNotNull(target).id)
            put("catalog_instance_id", target.storageEpoch)
        } else if (operation == "disable") {
            observation.profileId?.let { put("profile_id", it) }
        } else if (operation == "migrate") {
            val selected = requireNotNull(migration)
            put("migration_plan_id", selected.plan)
            put("profile_id", selected.profile)
            put("catalog_id", selected.catalog)
            put("catalog_instance_id", selected.storage)
        }
    }
}

private suspend fun ProjectApi.applyRemoteBinding(intent: RemoteBindingIntent): RemoteBindingSnapshot {
    val old = intent.observation
    val fresh = remoteBinding()
    val revision = intent.revision
    if (!old.sameTarget(fresh) || old.state != fresh.state || fresh.recovery != null || fresh.changing) {
        throw RemoteFailure("nativeRemote.stale")
    }
    var change: String? = null
    var sentCommit = false
    try {
        val prepared = post("/remote/prepare", intent.prepareBody()).jsonObject
        val changeId = prepared.string("change_id")?.takeIf(remoteStorageId::matches) ?: remoteInvalid()
        val path = "/remote/changes/$changeId"
        change = path
        val destination = prepared.obj("to") ?: remoteInvalid()
        if (destination.string("persistent_id") != intent.id || destination.string("url") != intent.url ||
            destination.remoteFlag("enabled") != false || prepared.remoteArray("conflicts").isNotEmpty() ||
            prepared.remoteNumber("expected_generation") != old.generation) remoteInvalid()
        val drain = post("$path/quiesce", buildJsonObject { put("expected_generation", old.generation) }).jsonObject
        val nextGeneration = old.generation + 1
        if (drain.string("state") != "ready" || drain.remoteArray("missing_acks").isNotEmpty()) {
            throw RemoteFailure("nativeRemote.pendingEdits")
        }
        if (drain.string("change_id") != changeId || drain.remoteNumber("binding_generation") != nextGeneration ||
            drain.remoteFlag("fenced") != true) remoteInvalid()
        sentCommit = true
        val committed = post("$path/commit", buildJsonObject {
            put("expected_revision", revision)
            put("expected_generation", nextGeneration)
            put("config_fingerprint", old.fingerprint)
            put("confirmation", intent.confirmation())
        }).jsonObject
        val observed = remoteBinding()
        if (committed.remoteFlag("committed") != true || committed.string("change_id") != changeId ||
            observed.persistentId != intent.id || observed.url != intent.url || observed.enabled ||
            observed.backendEpoch != old.backendEpoch || observed.revision != revision + 1 ||
            observed.generation != committed.remoteNumber("binding_generation") || observed.generation != nextGeneration + 1 ||
            committed.string("config_fingerprint") != observed.fingerprint || observed.profileId != when (intent.operation) {
                "select" -> intent.profile?.id
                "migrate" -> intent.migration?.profile
                "disable" -> old.profileId
                else -> null
            } || observed.storageEpoch != when (intent.operation) {
                "select" -> intent.target?.storageEpoch
                "migrate" -> intent.migration?.storage
                "disable" -> old.storageEpoch
                else -> null
            }) remoteInvalid()
        return observed
    } catch (error: Exception) {
        if (!sentCommit && change != null) withContext(NonCancellable) { runCatching { delete(change) } }
        if (error is CancellationException) throw error
        if (sentCommit) throw RemoteFailure("nativeRemote.unknownOutcome")
        throw error
    }
}

private data class RemotePreview(val observation: RemoteBindingSnapshot, val result: JsonObject, val expiresAt: Long) {
    val archive: Boolean get() = result.string("base") in setOf("epoch_mismatch", "cursor_rollback")
}

private fun parsePreview(observation: RemoteBindingSnapshot, row: JsonObject): RemotePreview {
    if (row.string("preview_id")?.let(remoteStorageId::matches) != true ||
        row.string("fingerprint")?.let(remoteDigest::matches) != true ||
        row.string("base") !in setOf("none", "matched", "epoch_mismatch", "cursor_rollback") ||
        row.obj("incarnation")?.string("space_instance_id") != observation.storageEpoch ||
        (row.obj("incarnation")?.remoteNumber("seq") ?: -1L) < 0) remoteInvalid()
    val counts = row.obj("counts") ?: remoteInvalid()
    if (counts.keys.any { (counts.remoteNumber(it) ?: -1L) < 0 }) remoteInvalid()
    row.remoteStrings("conflicts")
    val until = row.remoteNumber("expires_at")?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
        ?: row.string("expires_at")?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { (it * 1000).toLong() }
        ?: runCatching { Instant.parse(row.string("expires_at")).toEpochMilli() }.getOrNull()
        ?: remoteInvalid()
    if (until <= System.currentTimeMillis()) throw RemoteFailure("nativeRemote.previewExpired")
    return RemotePreview(observation, row, until)
}

private suspend fun ProjectApi.completePreview(preview: RemotePreview): RemoteBindingSnapshot {
    val old = preview.observation
    if (preview.expiresAt <= System.currentTimeMillis()) throw RemoteFailure("nativeRemote.previewExpired")
    val now = remoteBinding()
    if (!old.sameTarget(now) || !now.stable || now.enabled) throw RemoteFailure("nativeRemote.previewStale")
    val body = buildJsonObject {
        old.writeEvidence().forEach { (key, value) -> put(key, value) }
        put("expected_generation", old.generation)
        put("preview_id", requireNotNull(preview.result.string("preview_id")))
        put("fingerprint", requireNotNull(preview.result.string("fingerprint")))
        putJsonObject("confirmation") {
            put("url", old.url)
            put("persistent_id", requireNotNull(old.persistentId))
            put("action", if (preview.archive) "archive_checkpoint" else "reconcile")
        }
    }
    val result = post(if (preview.archive) "/remote/checkpoint/archive" else "/remote/enable", body).jsonObject
    val loaded = remoteBinding()
    if (preview.archive) {
        if (result.remoteFlag("archived") != true || result.remoteFlag("preview_required") != true ||
            result.string("backend_epoch") != old.backendEpoch || result.string("base") != preview.result.string("base") ||
            loaded.enabled || !old.sameTarget(loaded)) throw RemoteFailure("nativeRemote.unknownOutcome")
    } else if (result.remoteFlag("enabled") != true || result.string("persistent_id") != old.persistentId ||
        result.string("url") != old.url || result.string("backend_epoch") != old.backendEpoch ||
        !loaded.enabled || loaded.persistentId != old.persistentId || loaded.url != old.url || loaded.backendEpoch != old.backendEpoch ||
        loaded.profileId != old.profileId || loaded.storageEpoch != old.storageEpoch) throw RemoteFailure("nativeRemote.unknownOutcome")
    return loaded
}

@Composable
internal fun RemoteFeedback(op: RemoteOperation, m: Messages) {
    op.problem?.let { ProblemText(Problem(it)) }
    op.notice?.let { Muted(m[it]) }
    StudioDisclosure(op.busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            if (op.busy) StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
            Muted(m["loading"])
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun RemoteField(label: String, value: TextFieldValue, update: (TextFieldValue) -> Unit, enabled: Boolean, secret: Boolean = false) {
    LibraryCaption(label)
    StudioField(label, value, update, Modifier.fillMaxWidth(), enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@OptIn(ExperimentalJewelApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun RemoteChoices(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean, change: (String) -> Unit) {
    LibraryCaption(label)
    StudioChoice(label, selected, options.toMap(), label, enabled = enabled, onSelect = change)
}

@Composable
internal fun RemoteConfirmation(text: String, confirm: String, m: Messages, enabled: Boolean, cancel: () -> Unit, onConfirm: () -> Unit) {
    Text(text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        WorkAction(confirm, null, enabled = enabled, primary = true, onClick = onConfirm)
        WorkAction(m["cancel"], null, enabled = enabled, onClick = cancel)
    }
}

@Composable
internal fun RemoteSyncSettings(tab: ProjectTab, api: ProjectApi, studio: StudioApi, m: Messages,
    tailPadding: Dp, openAdvanced: () -> Unit) {
    val op = remember(api) { RemoteOperation() }
    var binding by remember(api) { mutableStateOf<RemoteBindingSnapshot?>(null) }
    var profiles by remember(api) { mutableStateOf(emptyList<NativeRemoteProfile>()) }
    var profileId by remember(api) { mutableStateOf("") }
    var catalog by remember(api) { mutableStateOf<RemoteCatalog?>(null) }
    var confirmation by remember(api) { mutableStateOf<RemoteBindingIntent?>(null) }
    var preview by remember(api) { mutableStateOf<RemotePreview?>(null) }
    var reviewPreview by remember(api) { mutableStateOf(false) }
    var recoveryReview by remember(api) { mutableStateOf<NativeRecovery?>(null) }
    var idText by remember(api) { mutableStateOf(TextFieldValue()) }
    var createId by remember(api) { mutableStateOf(TextFieldValue()) }
    var createName by remember(api) { mutableStateOf(TextFieldValue()) }
    var createIntent by remember(api) { mutableStateOf<RemoteCreate?>(null) }
    var createResult by remember(api) { mutableStateOf<JsonObject?>(null) }
    val profile = profiles.find { it.id == profileId }
    val idle = !op.busy && confirmation == null && !reviewPreview && recoveryReview == null

    suspend fun observe(reset: Boolean) {
        val current = api.remoteBinding()
        if (binding?.sameTarget(current) == false || !current.stable) {
            preview = null
            reviewPreview = false
            confirmation = null
            recoveryReview = null
        }
        if (reset || binding?.persistentId != current.persistentId) idText = TextFieldValue(current.persistentId.orEmpty())
        binding = current
    }

    fun refresh() = tab.scope.launch {
        op.run {
            observe(reset = true)
            profiles = studio.nativeRemoteProfiles()
            if (profileId.isEmpty()) profileId = binding?.profileId.orEmpty()
            if (catalog?.profile?.let { saved -> profiles.none { saved.matches(it) } } == true) catalog = null
        }
    }

    LaunchedEffect(api) { refresh() }
    LaunchedEffect(api, tab.pageRefresh) {
        if (binding != null && idle) op.run { observe(reset = false) }
    }

    fun loadCatalog(more: Boolean) {
        val captured = profile ?: return
        val previous = catalog?.takeIf { more && captured.matches(it.profile) }
        if (more && previous?.cursor == null) return
        tab.scope.launch {
            op.run {
                val currentProfiles = studio.nativeRemoteProfiles()
                if (currentProfiles.none { captured.matches(it) }) throw RemoteFailure("nativeRemote.stale")
                val cursor = previous?.cursor
                val row = studio.request("$remoteProfilesPath/${captured.id}/projects" +
                    (cursor?.let { query("cursor" to it) } ?: "")).jsonObject
                val next = row.string("next_cursor")
                val rows = row.remoteRows("items").map(CatalogProject::read)
                if (next != null && (!remoteProjectId.matches(next) || cursor != null && next <= cursor) ||
                    rows.distinctBy(CatalogProject::id).size != rows.size || cursor != null && rows.any { it.id <= cursor }) remoteInvalid()
                catalog = RemoteCatalog(captured, previous?.projects.orEmpty() + rows, next)
            }
        }
    }

    fun create(observe: Boolean) {
        val chosen = catalog?.profile ?: return
        val input = createIntent ?: RemoteCreate(chosen, createId.text, createName.text.trim())
        if (observe != (createIntent != null) || !input.profile.matches(chosen)) return
        createIntent = input
        tab.scope.launch {
            op.run {
                val path = "$remoteProfilesPath/${input.profile.id}/projects"
                val request = buildJsonObject {
                    put("expected_revision", input.profile.revision)
                    if (!observe) {
                        put("id", input.id)
                        put("display_name", input.name)
                        put("operation_id", input.op)
                    }
                }
                val result = studio.request(if (observe) "$path/operations/${input.op}/observe" else path, "POST", request).jsonObject
                val state = result.string("state")
                if (state !in setOf("created", "found", "absent", "conflict", "unknown", "rejected") || observe && state !in setOf("found", "absent")) remoteInvalid()
                val found = result.obj("project")?.let(CatalogProject::read)
                if (found?.id != input.id && state in setOf("created", "found") || found != null && state !in setOf("created", "found")) remoteInvalid()
                createResult = result
            }
        }
    }

    SettingsSection("nativeRemote.syncTitle", tailPadding) {
        LibraryButton("refresh-cw", m["refresh"], idle) { refresh() }
        RemoteFeedback(op, m)
        val status = binding
        if (status != null) {
            val label = when (status.state) {
                "disabled", "unconfigured", "connecting", "syncing", "connected", "offline", "unauthorised", "error", "binding_required",
                "migration_required", "quiescing", "blocked" -> m["nativeRemote.state.${status.state}"]
                else -> m["nativeRemote.state.unknown"]
            }
            LibrarySectionTitle(label)
            LibraryCaption(listOfNotNull(status.persistentId, status.url.takeIf(String::isNotEmpty)).joinToString(" · "), mono = true)
            if (status.revision != null) {
                RemoteField(m["nativeRemote.projectId"], idText, { idText = it }, idle)
                val id = idText.text
                val mayChange = status.stable || status.state == "blocked" && !status.enabled && status.reason == null &&
                    status.recovery == null && status.change == null && status.activeRooms == 0L && status.pendingWrites == 0L
                LibraryButton("pencil", m["nativeRemote.reviewId"], idle && mayChange && remoteProjectId.matches(id) && id != status.persistentId) {
                    confirmation = RemoteBindingIntent(status, "edit_id", id, status.url)
                }
            }
            val recovery = status.recovery?.let { nativeRecovery(status, it) }
            if (recovery != null) {
                LibraryCard {
                    LibrarySectionTitle(m["nativeRemote.recoverBinding"])
                    LibraryCaption(listOfNotNull(recovery.id, recovery.url.takeIf(String::isNotEmpty)).joinToString(" · "), mono = true)
                    if (recoveryReview != recovery) LibraryButton("archive-restore", m["nativeRemote.recoverBinding"], idle) { recoveryReview = recovery }
                    else {
                        val draft = tab.documentDrafts.values.any(DocumentDraft::dirty)
                        if (draft) Muted(m["nativeRemote.saveDocumentFirst"])
                        RemoteConfirmation(m[if (recovery.outcome == "previous") "nativeRemote.recoverPrevious" else "nativeRemote.recoverCommitted"],
                            m["nativeRemote.confirm"], m, !op.busy && !draft, { recoveryReview = null }) {
                            recoveryReview = null
                            tab.scope.launch {
                                op.run {
                                    binding = null
                                    binding = api.recoverNativeRemote(recovery)
                                    idText = TextFieldValue(binding?.persistentId.orEmpty())
                                    op.notice = "nativeRemote.recoveryDone"
                                }
                            }
                        }
                    }
                }
            } else if (status.recovery != null) {
                Muted(m["nativeRemote.recoveryRequired"])
                LibraryButton("external-link", m["nativeRemote.advancedSync"], idle, openAdvanced)
            }
            if (status.enabled && status.persistentId != null && status.stable && status.revision != null) {
                LibraryButton("pause", m["nativeRemote.disableSync"], idle) {
                    confirmation = RemoteBindingIntent(status, "disable", status.persistentId, status.url)
                }
            }
            if (!status.enabled && status.stable && status.hasCatalogBinding && status.revision != null) {
                LibraryButton("list-tree", m["nativeRemote.preview"], idle) {
                    tab.scope.launch {
                        op.run {
                            preview = null
                            val row = api.post("/remote/reconcile-preview", status.writeEvidence()).jsonObject
                            val fresh = api.remoteBinding()
                            if (!status.sameTarget(fresh) || !fresh.stable) throw RemoteFailure("nativeRemote.previewStale")
                            preview = parsePreview(status, row)
                        }
                    }
                }
            }
        }
        confirmation?.let { intent ->
            LibraryCard {
                LibrarySectionTitle(m["nativeRemote.reviewBinding"])
                LibraryCaption(listOf(intent.id, intent.url).filter(String::isNotEmpty).joinToString(" · "), mono = true)
                val draft = tab.documentDrafts.values.any(DocumentDraft::dirty)
                if (draft) Muted(m["nativeRemote.saveDocumentFirst"])
                RemoteConfirmation(m["nativeRemote.${intent.operation}Review"], m["nativeRemote.confirm"], m, !op.busy && !draft,
                    { confirmation = null }) {
                    confirmation = null
                    preview = null
                    tab.scope.launch {
                        op.run {
                            val selected = intent.profile
                            if (selected != null && studio.nativeRemoteProfiles().none { selected.matches(it) }) throw RemoteFailure("nativeRemote.stale")
                            binding = api.applyRemoteBinding(intent)
                            idText = TextFieldValue(binding?.persistentId.orEmpty())
                            op.notice = "nativeRemote.saved"
                        }
                    }
                }
            }
        }
        preview?.let { item ->
            val stats = item.result.obj("counts").orEmpty()
            LibraryCard {
                LibrarySectionTitle(m["nativeRemote.preview"])
                stats.keys.sorted().forEach { key ->
                    val known = key.takeIf { it in setOf("unchanged", "download", "upload", "conflict", "delete_local", "delete_remote") } ?: "other"
                    LibraryCaption("${m["nativeRemote.preview.$known"]} · ${stats[key]}")
                }
                item.result.remoteStrings("conflicts").take(50).forEach { LibraryCaption(it, mono = true, maxLines = 2) }
                if (!reviewPreview) {
                    LibraryButton(if (item.archive) "archive" else "check", m[if (item.archive) "nativeRemote.archive" else "nativeRemote.enableSync"], idle) {
                        reviewPreview = true
                    }
                } else {
                    val draft = tab.documentDrafts.values.any(DocumentDraft::dirty)
                    if (draft) Muted(m["nativeRemote.saveDocumentFirst"])
                    RemoteConfirmation(m[if (item.archive) "nativeRemote.archiveReview" else "nativeRemote.enableReview"],
                        m["nativeRemote.confirm"], m, !op.busy && !draft, { reviewPreview = false }) {
                        preview = null
                        reviewPreview = false
                        tab.scope.launch {
                            op.run {
                                binding = api.completePreview(item)
                                op.notice = if (item.archive) "nativeRemote.archived" else "nativeRemote.enabled"
                            }
                        }
                    }
                }
            }
        }
        Rule()
        RemoteChoices(m["nativeRemote.profile"], profileId, profiles.map { it.id to it.name }, idle && createIntent == null) {
            profileId = it
            catalog = null
        }
        if (profile?.hasToken == true) LibraryButton("list-tree", m["nativeRemote.catalog"], idle) { loadCatalog(false) }
        val entries = catalog?.takeIf { current -> profile?.let(current.profile::matches) == true }
        if (entries != null) {
            if (entries.projects.isEmpty()) Muted(m["nativeRemote.emptyCatalog"])
            entries.projects.forEach { project ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    Column(Modifier.weight(1f)) { Text(project.name); LibraryCaption(project.id, mono = true) }
                    if (status?.stable == true && status.revision != null) LibraryButton("link-2", m["nativeRemote.selectProject"], idle) {
                        confirmation = RemoteBindingIntent(status, "select", project.id, entries.profile.url, entries.profile, project)
                    }
                }
            }
            if (entries.cursor != null) LibraryButton("arrow-down", m["nativeRemote.more"], idle) { loadCatalog(true) }
            RemoteField(m["nativeRemote.newProjectId"], createId, { createId = it }, idle && createIntent == null)
            RemoteField(m["nativeRemote.name"], createName, { createName = it }, idle && createIntent == null)
            if (createIntent == null) {
                LibraryButton("plus", m["nativeRemote.createProject"], idle && remoteProjectId.matches(createId.text) && createName.text.isNotBlank()) { create(false) }
            } else {
                createResult?.string("state")?.let { state -> if (state in setOf("created", "found", "absent", "conflict", "unknown", "rejected")) {
                    Muted(m["nativeRemote.create.$state"])
                } }
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    LibraryButton("refresh-cw", m["nativeRemote.checkCreate"], idle) { create(true) }
                    LibraryButton("x", m["nativeRemote.newCreate"], idle) { createIntent = null; createResult = null }
                }
            }
        }
        if (status != null && status.state == "migration_required" && status.recovery == null) {
            key(status.fingerprint, status.backendEpoch) {
                NativeRemoteMigration(tab, api, m, status, profiles, op, idle, openAdvanced) { confirmation = it }
            }
        }
    }
}
