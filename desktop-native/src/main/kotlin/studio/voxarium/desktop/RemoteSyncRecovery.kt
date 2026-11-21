package studio.voxarium.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal data class NativeRecovery(val observation: RemoteBindingSnapshot, val changeId: String,
                                   val outcome: String, val url: String, val id: String?)

internal fun nativeRecovery(status: RemoteBindingSnapshot, data: JsonObject): NativeRecovery? {
    if (!data.flag("available")) return null
    val change = data.string("change_id")?.takeIf(remoteStorageId::matches) ?: return null
    val outcome = data.string("outcome")?.takeIf { it == "previous" || it == "commit" } ?: return null
    val target = data.obj("target") ?: return null
    val url = target.string("url") ?: return null
    val id = target.string("persistent_id")
    if (id == null && target["persistent_id"] !is JsonNull || id != null && !remoteProjectId.matches(id)) return null
    return NativeRecovery(status, change, outcome, url, id)
}

internal suspend fun ProjectApi.recoverNativeRemote(review: NativeRecovery): RemoteBindingSnapshot {
    val before = review.observation
    val live = remoteBinding()
    val now = live.recovery?.let { nativeRecovery(live, it) }
    if (!before.sameTarget(live) || now?.copy(observation = before) != review) throw RemoteFailure("nativeRemote.stale")
    val result = post("/remote/recover", buildJsonObject {
        put("change_id", review.changeId)
        put("expected_revision", before.revision ?: 0L)
        put("config_fingerprint", before.fingerprint)
        put("backend_epoch", before.backendEpoch)
        putJsonObject("confirmation") {
            put("action", "recover")
            put("url", review.url)
            put("persistent_id", review.id?.let(::JsonPrimitive) ?: JsonNull)
        }
    }).jsonObject
    val loaded = remoteBinding()
    if (result.remoteFlag("recovered") != true || result.string("change_id") != review.changeId ||
        result.remoteFlag("enabled") != false || result.string("backend_epoch") != before.backendEpoch ||
        result.string("url") != review.url || result.string("persistent_id") != review.id || loaded.enabled ||
        loaded.url != review.url || loaded.persistentId != review.id || loaded.recovery != null || loaded.backendEpoch != before.backendEpoch) {
        throw RemoteFailure("nativeRemote.unknownOutcome")
    }
    return loaded
}

internal data class NativeMigrationSelection(val plan: String, val profile: String, val catalog: String, val storage: String)

private data class NativeMigrationPlan(val id: String, val state: String, val url: String?, val selector: String?, val credential: Boolean) {
    companion object {
        fun read(row: JsonObject, status: RemoteBindingSnapshot): NativeMigrationPlan {
            if (row.string("config_fingerprint") != status.fingerprint || row.remoteNumber("identity_revision") != status.revision) {
                throw RemoteFailure("nativeRemote.stale")
            }
            return NativeMigrationPlan(row.string("migration_plan_id")?.takeIf { it.length in 1..128 } ?: remoteInvalid(),
                row.string("state") ?: remoteInvalid(), row.string("legacy_url"), row.string("legacy_selector"), row.flag("has_legacy_key"))
        }
    }
}

private data class NativeMigrationProfile(val id: String, val revision: Long, val credential: String)

@Composable
internal fun NativeRemoteMigration(tab: ProjectTab, api: ProjectApi, m: Messages, status: RemoteBindingSnapshot,
                                   profiles: List<NativeRemoteProfile>, op: RemoteOperation, idle: Boolean,
                                   openAdvanced: () -> Unit, review: (RemoteBindingIntent) -> Unit) {
    var plan by remember { mutableStateOf<NativeMigrationPlan?>(null) }
    var choice by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf(TextFieldValue()) }
    var copyKey by remember { mutableStateOf(false) }
    var confirmUrl by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NativeMigrationProfile?>(null) }
    var resolved by remember { mutableStateOf<JsonObject?>(null) }
    fun run(block: suspend () -> Unit) { tab.scope.launch { op.run(block) } }

    LibraryCard {
        LibrarySectionTitle(m["nativeRemote.migrationTitle"])
        LibraryButton("list-tree", m["nativeRemote.migrationRead"], idle) {
            run {
                selected = null
                resolved = null
                choice = ""
                confirmUrl = false
                copyKey = false
                plan = NativeMigrationPlan.read(api.get("/remote/migration").jsonObject, status)
            }
        }
        val loaded = plan
        if (loaded != null) {
            LibraryCaption(listOfNotNull(loaded.url, loaded.selector).joinToString(" · "), mono = true, maxLines = 2)
            val endpoint = "/remote/migration/${segment(loaded.id)}"
            if (loaded.state == "needs_profile" && loaded.url != null) {
                val current = selected
                if (current == null) {
                    val matching = profiles.filter { it.url == loaded.url }
                    RemoteChoices(m["nativeRemote.profile"], choice, listOf("" to m["nativeRemote.createProfile"]) + matching.map { it.id to it.name }, idle) {
                        choice = it
                        confirmUrl = false
                    }
                    if (choice.isEmpty()) RemoteField(m["nativeRemote.name"], profileName, { profileName = it; confirmUrl = false }, idle)
                    if (loaded.credential) WorkCheckbox(m["nativeRemote.migrationImport"], copyKey, idle) { copyKey = it; confirmUrl = false }
                    WorkCheckbox(m["nativeRemote.migrationConfirmUrl"], confirmUrl, idle) { confirmUrl = it }
                    val observedId = choice
                    val localName = profileName.text.trim()
                    val import = loaded.credential && copyKey
                    LibraryButton("check", m["nativeRemote.migrationProfile"], idle && confirmUrl &&
                        (if (observedId.isEmpty()) localName.isNotEmpty() else matching.any { it.id == observedId })) {
                        confirmUrl = false
                        run {
                            val live = api.remoteBinding()
                            if (!status.sameTarget(live) || live.recovery != null || live.changing) throw RemoteFailure("nativeRemote.stale")
                            val result = api.post("$endpoint/profile", buildJsonObject {
                                put("config_fingerprint", status.fingerprint)
                                if (observedId.isEmpty()) put("name", localName) else put("profile_id", observedId)
                                put("import_legacy_credential", import)
                                put("confirmation", jsonFields("url" to loaded.url))
                            }).jsonObject
                            val profile = result.string("profile_id")?.takeIf(remoteProfileId::matches) ?: remoteInvalid()
                            val credential = result.string("credential")?.takeIf { it in setOf("stored", "environment", "missing") } ?: remoteInvalid()
                            if (result.string("migration_plan_id") != loaded.id || result.string("url") != loaded.url ||
                                observedId.isNotEmpty() && profile != observedId) remoteInvalid()
                            selected = NativeMigrationProfile(profile, result.aiRevision("profile_revision"), credential)
                        }
                    }
                } else if (current.credential == "missing") {
                    Muted(m["nativeRemote.migrationNeedKey"])
                } else if (resolved == null) {
                    LibraryButton("search", m["nativeRemote.migrationResolve"], idle) {
                        run {
                            val live = api.remoteBinding()
                            if (!status.sameTarget(live) || live.recovery != null || live.changing) throw RemoteFailure("nativeRemote.stale")
                            val result = api.post("$endpoint/resolve", buildJsonObject {
                                put("profile_id", current.id); put("expected_profile_revision", current.revision)
                                put("config_fingerprint", status.fingerprint)
                            }).jsonObject
                            if (result.string("migration_plan_id") != loaded.id || result.string("legacy_selector") != loaded.selector ||
                                result.string("catalog_id")?.let(remoteProjectId::matches) != true ||
                                result.string("space_instance_id")?.let(remoteStorageId::matches) != true) remoteInvalid()
                            result.remoteArray("conflicts")
                            resolved = result
                        }
                    }
                } else {
                    val result = requireNotNull(resolved)
                    val catalog = requireNotNull(result.string("catalog_id"))
                    val storage = requireNotNull(result.string("space_instance_id"))
                    LibraryCaption("$catalog · $storage", mono = true, maxLines = 2)
                    val conflicts = result.remoteArray("conflicts")
                    if (conflicts.isNotEmpty()) Muted(m["nativeRemote.migrationConflict"])
                    LibraryButton("link-2", m["nativeRemote.migrationApply"], idle && conflicts.isEmpty()) {
                        review(RemoteBindingIntent(status, "migrate", catalog, loaded.url,
                            migration = NativeMigrationSelection(loaded.id, current.id, catalog, storage)))
                    }
                }
            } else {
                Muted(m["nativeRemote.migrationConflict"])
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    LibraryButton("external-link", m["nativeRemote.advancedSync"], idle, openAdvanced)
                }
            }
        }
    }
}
