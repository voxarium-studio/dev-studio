package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.*

internal const val nativeAiAccess = "/api/ai-access"
internal val aiLocalId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
internal val aiPermissions = listOf("view_status", "view_transcript", "create_task", "edit_task", "run_task",
    "write_task", "cancel_task", "create_chat", "run_chat", "cancel_chat", "operate_existing")
private val aiScopes = listOf("created_by_this_grantee", "selected_resources", "all_resources_in_selected_teams")

internal fun JsonObject.aiId(key: String): String = string(key)?.takeIf(aiLocalId::matches) ?: remoteInvalid()
internal fun JsonObject.aiRevision(key: String): Long = remoteNumber(key)?.takeIf { it in 1 until Long.MAX_VALUE } ?: remoteInvalid()
private fun normalizedName(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
private fun validAiName(value: String) = normalizedName(value).let { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 480 }
internal fun Messages.ai(key: String) = this["nativeRemote.ai.$key"]

private fun Messages.aiLine(key: String, vararg values: Pair<String, Any?>): String =
    values.fold(ai(key)) { text, (name, value) -> text.replace("{$name}", value?.toString().orEmpty()) }

internal data class NativeAiRegistration(
    val id: String, val profileId: String, val server: String, val url: String, val user: String, val client: String,
    val name: String, val kind: String, val enabled: Boolean, val hasToken: Boolean, val revision: Long,
) {
    fun sameDevice(other: NativeAiRegistration) = id == other.id && profileId == other.profileId && server == other.server &&
        url == other.url && user == other.user && client == other.client
}

internal fun JsonObject.nativeAiRegistration() = NativeAiRegistration(
    aiId("registration_id"), aiId("profile_id"), aiId("server_instance_id"), string("url") ?: remoteInvalid(),
    aiId("user_id"), aiId("client_id"), string("display_name") ?: remoteInvalid(), string("kind") ?: remoteInvalid(),
    remoteFlag("publication_enabled") ?: remoteInvalid(), remoteFlag("has_device_token") ?: remoteInvalid(), aiRevision("revision"),
)

internal suspend fun StudioApi.aiRegistrations() = request("$nativeAiAccess/registrations").jsonObject.remoteRows("registrations")
    .map(JsonObject::nativeAiRegistration).also { if (it.distinctBy(NativeAiRegistration::id).size != it.size) remoteInvalid() }

private data class AiCapability(val profile: NativeRemoteProfile, val server: String)

@Composable
internal fun RemoteAiDeviceSettings(studio: StudioApi, m: Messages, tailPadding: Dp, openAdvanced: () -> Unit) {
    val op = remember(studio) { RemoteOperation() }
    val scope = rememberCoroutineScope()
    var profiles by remember(studio) { mutableStateOf(emptyList<NativeRemoteProfile>()) }
    var registrations by remember(studio) { mutableStateOf<List<NativeAiRegistration>?>(null) }
    var profileId by remember(studio) { mutableStateOf("") }
    var capability by remember(studio) { mutableStateOf<AiCapability?>(null) }
    var deviceName by remember(studio) { mutableStateOf(TextFieldValue()) }
    var remove by remember(studio) { mutableStateOf<NativeAiRegistration?>(null) }
    val profile = profiles.find { it.id == profileId }

    fun run(work: suspend () -> Unit) { scope.launch { op.run(work) } }
    fun refresh() = run {
        capability = null
        coroutineScope {
            val rows = async { studio.aiRegistrations() }
            val candidates = studio.nativeRemoteProfiles()
            registrations = rows.await()
            profiles = candidates
        }
    }
    LaunchedEffect(studio) { refresh() }

    SettingsSection("nativeRemote.ai.devicesTitle", tailPadding) {
        LibraryButton("refresh-cw", m["refresh"], !op.busy && remove == null) { refresh() }
        RemoteFeedback(op, m)
        RemoteChoices(m.ai("profile"), profileId, profiles.map { it.id to it.name }, !op.busy && remove == null) {
            profileId = it
            capability = null
        }
        if (profile?.hasToken == true) {
            LibraryButton("search", m.ai("discover"), !op.busy && remove == null) {
                run {
                    capability = null
                    val result = studio.request("$nativeAiAccess/profiles/${profile.id}/capabilities").jsonObject
                    when (result.string("kind")) {
                        "supported" -> capability = AiCapability(profile, result.aiId("server_instance_id"))
                        "unsupported" -> op.notice = "nativeRemote.ai.unsupported"
                        "auth_failure" -> op.notice = "nativeRemote.ai.discoveryAuth"
                        else -> op.notice = "nativeRemote.ai.discoveryUnavailable"
                    }
                }
            }
        }
        capability?.let { proof ->
            LibraryCard {
                LibraryCaption("${proof.profile.url} · ${proof.server}", mono = true, maxLines = 2)
                Muted(m.ai("accountAfterRegistration"))
                RemoteField(m.ai("deviceName"), deviceName, { deviceName = it }, !op.busy)
                LibraryButton("plus", m.ai("register"), !op.busy && validAiName(deviceName.text)) {
                    val name = normalizedName(deviceName.text)
                    capability = null
                    run {
                        if (studio.nativeRemoteProfiles().none { proof.profile.matches(it) }) throw RemoteFailure("nativeRemote.stale")
                        val result = studio.request("$nativeAiAccess/registrations", "POST", jsonFields(
                            "profile_id" to proof.profile.id, "server_instance_id" to proof.server, "display_name" to name,
                        )).jsonObject.nativeAiRegistration()
                        if (result.profileId != proof.profile.id || result.url != proof.profile.url || result.server != proof.server || result.enabled) remoteInvalid()
                        registrations = registrations.orEmpty().filterNot { it.id == result.id } + result
                        deviceName = TextFieldValue()
                    }
                }
            }
        }
        if (registrations?.isEmpty() == true) Muted(m.ai("noRegistrations"))
        registrations.orEmpty().forEach { device ->
            LibraryCard {
                LibrarySectionTitle(device.name)
                LibraryCaption(m.aiLine("registrationIdentity", "url" to device.url, "server" to device.server, "user" to device.user,
                    "registration" to device.id, "client" to device.client), mono = true, maxLines = 3)
                Muted(m.ai(if (device.enabled) "enabled" else "disabled"))
                val saved = profiles.find { it.id == device.profileId && it.url == device.url && it.hasToken }
                if (saved == null) Muted(m.ai("profileChanged"))
                if (remove == device) {
                    RemoteConfirmation(m.ai("unregisterNote"), m.ai("confirmUnregister"), m, !op.busy, { remove = null }) {
                        remove = null
                        run {
                            studio.request("$nativeAiAccess/registrations/${device.id}", "DELETE", buildJsonObject {
                                put("expected_revision", device.revision)
                            })
                            registrations = registrations.orEmpty().filterNot { it.id == device.id }
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                        LibraryButton("power", m.ai(if (device.enabled) "disablePublication" else "enablePublication"),
                            !op.busy && remove == null && (device.enabled || device.hasToken && saved != null)) {
                            run {
                                if (!device.enabled && studio.nativeRemoteProfiles().none { requireNotNull(saved).matches(it) }) {
                                    throw RemoteFailure("nativeRemote.stale")
                                }
                                val result = studio.request("$nativeAiAccess/registrations/${device.id}/publication", "PUT", buildJsonObject {
                                    put("enabled", !device.enabled)
                                    put("expected_revision", device.revision)
                                }).jsonObject.nativeAiRegistration()
                                if (!device.sameDevice(result) || result.enabled == device.enabled || result.revision <= device.revision) remoteInvalid()
                                registrations = registrations?.map { if (it.id == device.id) result else it }
                            }
                        }
                        LibraryButton("trash-2", m.ai("unregister"), !op.busy && remove == null) { remove = device }
                    }
                }
            }
        }
        if (registrations == null && !op.busy) LibraryButton("external-link", m["openWeb"], onClick = openAdvanced)
    }
}

internal data class NativeAiTarget(
    val server: String, val executor: String, val export: String, val project: String, val storage: String, val revision: Long,
) {
    fun sameExport(other: NativeAiTarget) = copy(revision = other.revision) == other
    fun payload() = buildJsonObject {
        put("server_instance_id", server); put("executor_registration_id", executor); put("export_id", export)
        put("persistent_id", project); put("storage_epoch", storage); put("export_revision", revision)
    }
}

internal fun JsonObject.nativeAiTarget(): NativeAiTarget {
    if (keys != setOf("server_instance_id", "executor_registration_id", "export_id", "persistent_id", "storage_epoch", "export_revision")) remoteInvalid()
    return NativeAiTarget(aiId("server_instance_id"), aiId("executor_registration_id"), aiId("export_id"),
        string("persistent_id")?.takeIf(remoteProjectId::matches) ?: remoteInvalid(),
        string("storage_epoch")?.takeIf(remoteStorageId::matches) ?: remoteInvalid(), aiRevision("export_revision"))
}

private data class NativeAiExport(val target: NativeAiTarget, val name: String, val enabled: Boolean, val mirror: String)
private fun JsonObject.nativeAiExport() = NativeAiExport(
    obj("target")?.nativeAiTarget() ?: remoteInvalid(), string("display_name") ?: remoteInvalid(),
    remoteFlag("enabled") ?: remoteInvalid(), string("mirror_state") ?: remoteInvalid(),
)

private data class NativeAiDevice(val id: String, val user: String, val name: String) {
    constructor(row: JsonObject) : this(row.aiId("registration_id"), row.aiId("user_id"), row.string("display_name") ?: remoteInvalid())
}

internal data class NativeGrantIntent(
    val target: NativeAiTarget, val recipient: String, val user: String, val team: String, val member: String,
    val permissions: Set<String>, val scope: String, val resources: List<String>, val hours: Long,
    val existingId: String?, val existingRevision: Long?,
) {
    val valid: Boolean get() = listOf(recipient, user, team, member).all(aiLocalId::matches) &&
        permissions.all(aiPermissions::contains) && "view_status" in permissions && scope in aiScopes && hours in setOf(1L, 24L, 168L) &&
        ("operate_existing" !in permissions || scope != "created_by_this_grantee" &&
            permissions.any { it !in setOf("view_status", "view_transcript", "operate_existing") }) &&
        (scope != "selected_resources" || resources.isNotEmpty() && resources.size <= 1024 && resources.all(aiLocalId::matches))

    fun body(digest: String, now: Long) = buildJsonObject {
        put("expected_export_revision", target.revision)
        put("execution_policy_digest", digest)
        put("expires_at", now + hours * 3_600_000)
        put("resource_scope", scope)
        putJsonArray("team_ids") { add(JsonPrimitive(team)) }
        putJsonArray("member_ids") { add(JsonPrimitive(member)) }
        putJsonArray("operations") { aiPermissions.filter(permissions::contains).forEach { add(JsonPrimitive(it)) } }
        putJsonArray("resource_ids") { resources.forEach { add(JsonPrimitive(it)) } }
        if (existingId != null) put("expected_revision", requireNotNull(existingRevision)) else {
            put("grantee_user_id", user)
            put("grantee_registration_id", recipient)
        }
    }
}

private data class NativeGrantPreview(val plan: NativeGrantIntent, val policy: JsonObject) {
    val digest = policy.string("execution_policy_digest")?.takeIf(remoteDigest::matches) ?: remoteInvalid()
    init {
        if (policy.obj("target")?.nativeAiTarget() != plan.target || policy.string("team_id") != plan.team ||
            policy.string("member_id") != plan.member || policy.obj("tools")?.remoteFlag("backend_native_tools_may_include_shell") == null) remoteInvalid()
    }
}

@Composable
internal fun RemoteAiProjectSettings(tab: ProjectTab, api: ProjectApi, studio: StudioApi, m: Messages,
    tailPadding: Dp, openAdvanced: () -> Unit) {
    val op = remember(api) { RemoteOperation() }
    var binding by remember(api) { mutableStateOf<RemoteBindingSnapshot?>(null) }
    var registrations by remember(api) { mutableStateOf(emptyList<NativeAiRegistration>()) }
    var exports by remember(api) { mutableStateOf<List<NativeAiExport>?>(null) }
    var executorId by remember(api) { mutableStateOf("") }
    var exportId by remember(api) { mutableStateOf("") }
    var createdHere by remember(api) { mutableStateOf("") }
    var name by remember(api) { mutableStateOf(TextFieldValue()) }
    var consent by remember(api) { mutableStateOf(false) }

    fun refresh() = tab.scope.launch {
        op.run {
            consent = false
            createdHere = ""
            exportId = ""
            coroutineScope {
                val regs = async { studio.aiRegistrations() }
                val rows = async { studio.request("$nativeAiAccess/exports").jsonObject.remoteRows("exports").map(JsonObject::nativeAiExport) }
                binding = api.remoteBinding()
                registrations = regs.await()
                exports = rows.await()
            }
        }
    }
    LaunchedEffect(api) { refresh() }

    SettingsSection("nativeRemote.ai.projectTitle", tailPadding) {
        LibraryButton("refresh-cw", m["refresh"], !op.busy) { refresh() }
        RemoteFeedback(op, m)
        val bound = binding?.takeIf { it.stable && it.hasCatalogBinding && it.revision != null }
        val matches = registrations.filter { bound != null && it.profileId == bound.profileId && it.url == bound.url && it.hasToken }
        val executor = matches.find { it.id == executorId }
        if (bound == null && exports != null) Muted(m.ai("noBinding"))
        if (bound != null && createdHere.isEmpty()) {
            LibraryCaption(m.aiLine("projectBinding", "project" to bound.projectId, "url" to bound.url, "profile" to bound.profileId,
                "id" to bound.persistentId, "epoch" to bound.storageEpoch, "revision" to bound.revision), mono = true, maxLines = 3)
            if (matches.isEmpty()) Muted(m.ai("noExecutor"))
            RemoteChoices(m.ai("executor"), executorId, matches.map { it.id to "${it.name} · ${it.id}" }, !op.busy) {
                executorId = it
                consent = false
            }
            if (executor != null) {
                LibraryCaption(m.aiLine("registrationIdentity", "url" to executor.url, "server" to executor.server,
                    "user" to executor.user, "registration" to executor.id, "client" to executor.client), mono = true, maxLines = 3)
                RemoteField(m.ai("exportName"), name, { name = it; consent = false }, !op.busy)
                WorkCheckbox(m.ai("draftConsent"), consent, !op.busy) { consent = it }
                LibraryButton("plus", m.ai("createDraft"), !op.busy && consent && validAiName(name.text)) {
                    val displayName = normalizedName(name.text)
                    consent = false
                    tab.scope.launch {
                        op.run {
                            val fresh = api.remoteBinding()
                            if (!bound.sameTarget(fresh) || !fresh.stable) throw RemoteFailure("nativeRemote.stale")
                            val result = studio.request("$nativeAiAccess/exports", "POST", buildJsonObject {
                                put("registration_id", executor.id); put("profile_id", executor.profileId); put("server_instance_id", executor.server)
                                put("project_id", bound.projectId); put("project_epoch", bound.projectEpoch)
                                put("persistent_id", requireNotNull(bound.persistentId)); put("storage_epoch", requireNotNull(bound.storageEpoch))
                                put("identity_revision", requireNotNull(bound.revision)); put("display_name", displayName)
                            }).jsonObject.nativeAiExport()
                            val target = result.target
                            if (result.enabled || result.mirror != "unpublished" || target.executor != executor.id || target.server != executor.server ||
                                target.project != bound.persistentId || target.storage != bound.storageEpoch) remoteInvalid()
                            exports = exports.orEmpty().filterNot { it.target.export == target.export } + result
                            createdHere = target.export
                            exportId = target.export
                            name = TextFieldValue()
                        }
                    }
                }
            }
        }
        if (exports.orEmpty().isNotEmpty()) {
            Muted(m.ai("savedExportsNote"))
            RemoteChoices(m.ai("savedExports"), exportId, exports.orEmpty().map { it.target.export to "${it.name} · ${it.target.project} · ${it.target.export}" }, !op.busy) {
                exportId = it
            }
        }
        exports?.find { it.target.export == exportId }?.let { entry ->
            val device = registrations.find { it.id == entry.target.executor && it.server == entry.target.server }
            key(entry.target) {
                RemoteAiExportCard(tab, api, studio, m, entry, device, exportId == createdHere) { next ->
                    exports = exports?.map { if (it.target == entry.target) next else it }
                }
            }
        }
        if (exports == null && !op.busy) LibraryButton("external-link", m["openWeb"], onClick = openAdvanced)
    }
}

@Composable
private fun RemoteAiExportCard(tab: ProjectTab, api: ProjectApi, studio: StudioApi, m: Messages, entry: NativeAiExport,
                               device: NativeAiRegistration?, localProof: Boolean, updated: (NativeAiExport) -> Unit) {
    val op = remember(entry.target) { RemoteOperation() }
    var recipients by remember { mutableStateOf<List<NativeAiDevice>?>(null) }
    var grants by remember { mutableStateOf<List<JsonObject>?>(null) }
    var teams by remember { mutableStateOf(emptyList<JsonObject>()) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var reviewedRevocation by remember { mutableStateOf<JsonObject?>(null) }
    val target = entry.target
    val path = "$nativeAiAccess/exports/${target.export}"
    val canGrant = entry.enabled && entry.mirror == "effective" && device?.enabled == true && device.hasToken

    fun run(work: suspend () -> Unit) { tab.scope.launch { op.run(work) } }
    fun keep(receipt: JsonObject) {
        val grant = receipt.obj("grant") ?: remoteInvalid()
        val id = grant.aiId("grant_id")
        grants = grants.orEmpty().filterNot { it.obj("grant")?.string("grant_id") == id } + receipt
    }

    LibraryCard {
        LibrarySectionTitle(entry.name)
        LibraryCaption(m.aiLine("targetIdentity", "server" to target.server, "registration" to target.executor, "export" to target.export,
            "project" to target.project, "epoch" to target.storage, "revision" to target.revision), mono = true, maxLines = 3)
        if (device != null) LibraryCaption(m.aiLine("registrationIdentity", "url" to device.url, "server" to device.server,
            "user" to device.user, "registration" to device.id, "client" to device.client), mono = true, maxLines = 3)
        Muted(m.ai(when {
            entry.mirror == "conflict" -> "mirrorConflict"
            entry.mirror == "pending" -> "mirrorPending"
            entry.enabled && entry.mirror == "effective" -> "published"
            !entry.enabled && entry.mirror == "unpublished" -> "draft"
            else -> "publicationUnknown"
        }))
        if (device?.enabled != true) Muted(m.ai("enableDeviceFirst"))
        RemoteFeedback(op, m)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            LibraryButton("power", m.ai(if (entry.enabled) "unpublish" else "publish"),
                !op.busy && (entry.enabled || device?.enabled == true && device.hasToken)) {
                run {
                    val result = studio.request("$path/publication", "PUT", buildJsonObject {
                        put("enabled", !entry.enabled); put("expected_revision", target.revision)
                    }).jsonObject.nativeAiExport()
                    if (!target.sameExport(result.target) || result.target.revision < target.revision || result.enabled == entry.enabled) remoteInvalid()
                    updated(result)
                }
            }
            LibraryButton("list-tree", m.ai("readGrants"), !op.busy) {
                editing = null
                run {
                    grants = studio.request("$path/grants").jsonObject.remoteRows("grants").also { rows ->
                        if (rows.any { it.obj("grant")?.obj("target")?.nativeAiTarget()?.let(target::sameExport) != true }) remoteInvalid()
                    }
                }
            }
            if (canGrant) LibraryButton("users", m.ai("readDevices"), !op.busy) {
                editing = null
                run {
                    coroutineScope {
                        val rows = async { studio.request("$nativeAiAccess/registrations/${target.executor}/directory").jsonObject.remoteRows("registrations") }
                        teams = if (localProof) api.get("/teams").objects() else emptyList()
                        recipients = rows.await().map(::NativeAiDevice).filter { it.id != target.executor }
                    }
                }
            }
        }
        if (canGrant && (recipients != null || editing != null)) key(editing) {
            NativeGrantForm(studio, path, target, recipients.orEmpty(), teams, localProof, editing, op, tab, m,
                onCancel = { editing = null; recipients = null }, onSaved = { result -> keep(result); editing = null; recipients = null })
        }
        grants?.let { rows ->
            if (rows.isEmpty()) Muted(m.ai("noGrants"))
            rows.forEach { receipt ->
                val grant = receipt.obj("grant") ?: return@forEach
                val id = grant.aiId("grant_id")
                val revision = grant.aiRevision("revision")
                val revoked = grant.remoteNumber("revoked_at") != null
                LibraryCard {
                    LibrarySectionTitle(grant.string("grantee_registration_id").orEmpty())
                    Muted(grant.remoteStrings("operations").filter(aiPermissions::contains).joinToString(" · ") { m.ai(it) })
                    LibraryCaption("$id · r$revision", mono = true)
                    if (revoked) Muted(m.ai("grantRevoked"))
                    if (reviewedRevocation == grant) {
                        RemoteConfirmation(m["nativeRemote.ai.confirmRevoke"], m.ai("revoke"), m, !op.busy, { reviewedRevocation = null }) {
                            reviewedRevocation = null
                            run {
                                val result = studio.request("$nativeAiAccess/grants/$id", "DELETE", buildJsonObject {
                                    put("expected_revision", revision)
                                }).jsonObject
                                val next = result.obj("grant") ?: remoteInvalid()
                                if (next.string("grant_id") != id || next.aiRevision("revision") <= revision || next.remoteNumber("revoked_at") == null ||
                                    next.obj("target")?.nativeAiTarget() != grant.obj("target")?.nativeAiTarget()) remoteInvalid()
                                keep(result)
                            }
                        }
                    } else if (!revoked) {
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                            if (canGrant && grant.obj("target")?.nativeAiTarget() == target) {
                                LibraryButton("pencil", m.ai("editGrant"), !op.busy) { editing = grant }
                            }
                            LibraryButton("x", m.ai("revoke"), !op.busy) { reviewedRevocation = grant }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeGrantForm(studio: StudioApi, path: String, target: NativeAiTarget, devices: List<NativeAiDevice>,
                            teams: List<JsonObject>, localProof: Boolean, previous: JsonObject?, op: RemoteOperation,
                            tab: ProjectTab, m: Messages, onCancel: () -> Unit, onSaved: (JsonObject) -> Unit) {
    var deviceId by remember { mutableStateOf("") }
    var team by remember { mutableStateOf(TextFieldValue()) }
    var member by remember { mutableStateOf(TextFieldValue()) }
    var permissions by remember { mutableStateOf(emptySet<String>()) }
    var scope by remember { mutableStateOf("created_by_this_grantee") }
    var resources by remember { mutableStateOf(TextFieldValue()) }
    var hours by remember { mutableLongStateOf(24L) }
    var preview by remember { mutableStateOf<NativeGrantPreview?>(null) }
    var consent by remember { mutableStateOf(false) }
    val selected = devices.find { it.id == deviceId }
    val members = teams.find { it.string("id") == team.text }?.objects("members").orEmpty().filter { it.string("kind") == "agent" }
    val resourceIds = if (scope == "selected_resources") resources.text.split(Regex("[\\s,]+")).filter(String::isNotBlank).distinct() else emptyList()
    val intent = NativeGrantIntent(target, previous?.string("grantee_registration_id") ?: selected?.id.orEmpty(),
        previous?.string("grantee_user_id") ?: selected?.user.orEmpty(), team.text.trim(), member.text.trim(), permissions,
        scope, resourceIds, hours, previous?.string("grant_id"), previous?.remoteNumber("revision"))
    fun resetReview() { preview = null; consent = false }
    LibraryCard {
        if (previous == null) {
            RemoteChoices(m.ai("grantDevice"), deviceId, devices.map { it.id to it.name }, !op.busy) { deviceId = it; resetReview() }
            if (devices.isEmpty()) Muted(m.ai("noDevices"))
        } else LibraryCaption(previous.string("grantee_registration_id").orEmpty(), mono = true)
        if (localProof && teams.isNotEmpty()) {
            RemoteChoices(m.ai("team"), team.text, teams.mapNotNull { row -> row.string("id")?.let { it to (row.string("name") ?: it) } }, !op.busy) {
                team = TextFieldValue(it); member = TextFieldValue(); resetReview()
            }
            RemoteChoices(m.ai("member"), member.text, members.mapNotNull { row -> row.string("id")?.let { it to (row.string("name") ?: it) } }, !op.busy) {
                member = TextFieldValue(it); resetReview()
            }
        } else if (!localProof) {
            Muted(m.ai("savedTeamNote"))
            RemoteField(m.ai("teamId"), team, { team = it; resetReview() }, !op.busy)
            RemoteField(m.ai("memberId"), member, { member = it; resetReview() }, !op.busy)
        }
        LibrarySectionTitle(m.ai("permissions"))
        aiPermissions.forEach { permission ->
            WorkCheckbox(m.ai(permission), permission in permissions, !op.busy) { enabled ->
                permissions = if (enabled) permissions + permission else permissions - permission
                resetReview()
            }
        }
        if ("view_transcript" in permissions) Muted(m.ai("transcriptRisk"))
        RemoteChoices(m.ai("scope"), scope, aiScopes.map { it to m.ai(it) }, !op.busy) { scope = it; resetReview() }
        if (scope != "created_by_this_grantee") Muted(m.ai("existingRisk"))
        if (scope == "selected_resources") RemoteField(m.ai("resources"), resources, { resources = it; resetReview() }, !op.busy)
        RemoteChoices(m.ai("lifetime"), hours.toString(), listOf("1" to m.ai("oneHour"), "24" to m.ai("oneDay"), "168" to m.ai("sevenDays")), !op.busy) {
            hours = it.toLong(); resetReview()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            LibraryButton("list-tree", m.ai("preview"), !op.busy && intent.valid && (!localProof || members.any { it.string("id") == intent.member })) {
                resetReview()
                tab.scope.launch {
                    op.run {
                        val policy = studio.request("$path/teams/${intent.team}/members/${intent.member}/execution-policy").jsonObject
                        preview = NativeGrantPreview(intent, policy)
                    }
                }
            }
            WorkAction(m["cancel"], null, onClick = onCancel, enabled = !op.busy)
        }
        preview?.takeIf { it.plan == intent }?.let { review ->
            val policy = review.policy
            LibrarySectionTitle(m.ai("policy"))
            LibraryCaption(m.aiLine("grantIdentity", "user" to intent.user, "registration" to intent.recipient,
                "team" to intent.team, "member" to intent.member), mono = true, maxLines = 3)
            Muted(m.aiLine("policyModes", "backend" to policy.string("backend"), "mode" to policy.string("permission_mode")))
            val mcp = policy.obj("mcp") ?: remoteInvalid()
            Muted(m.aiLine("mcpPolicy", "strict" to m.ai(if (mcp.flag("strict")) "enabled" else "disabled"), "count" to mcp.remoteNumber("project_server_count")))
            policy.obj("limits")?.let { limits ->
                Muted(m.aiLine("runLimits", "turns" to (limits.remoteNumber("max_turns") ?: m.ai("noExplicitLimit")),
                    "continuations" to limits.remoteNumber("max_run_continuations"), "slots" to limits.remoteNumber("project_agent_slots"),
                    "export" to limits.remoteNumber("remote_runs_per_export"), "grant" to limits.remoteNumber("remote_runs_per_grant")))
            }
            LibraryCaption(m.aiLine("policyDigest", "digest" to review.digest), mono = true, maxLines = 2)
            Muted(m.ai("runRisk"))
            if (policy.obj("tools")?.flag("backend_native_tools_may_include_shell") == true) Muted(m.ai("shellRisk"))
            WorkCheckbox(m.ai("consent"), consent, !op.busy) { consent = it }
            LibraryButton("check", m.ai(if (previous == null) "grant" else "updateGrant"), !op.busy && consent) {
                resetReview()
                tab.scope.launch {
                    op.run {
                        val endpoint = if (intent.existingId == null) "$path/grants" else "$path/grants/${intent.existingId}"
                        val result = studio.request(endpoint, if (intent.existingId == null) "POST" else "PUT",
                            intent.body(review.digest, System.currentTimeMillis())).jsonObject
                        val grant = result.obj("grant") ?: remoteInvalid()
                        if (grant.obj("target")?.nativeAiTarget() != target || grant.string("grantee_user_id") != intent.user ||
                            grant.string("grantee_registration_id") != intent.recipient || intent.existingId != null &&
                            (grant.string("grant_id") != intent.existingId || grant.aiRevision("revision") <= requireNotNull(intent.existingRevision))) remoteInvalid()
                        onSaved(result)
                    }
                }
            }
        }
    }
}
