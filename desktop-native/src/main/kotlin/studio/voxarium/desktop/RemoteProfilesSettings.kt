package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.*

private enum class ProfileEdit(val key: String) {
    CREATE("createProfile"), RENAME("renameProfile"), URL("changeUrl"), TOKEN("setCredential"),
    REMOVE_TOKEN("removeCredential"), DELETE("deleteProfile"), RECOVER("recoverProfile"),
}

private data class ProfileDraft(val edit: ProfileEdit, val profile: NativeRemoteProfile? = null)

@Composable
internal fun RemoteProfilesSettings(studio: StudioApi, m: Messages, tailPadding: Dp, openAdvanced: () -> Unit) {
    val state = remember(studio) { RemoteOperation() }
    val scope = rememberCoroutineScope()
    var profiles by remember(studio) { mutableStateOf<List<NativeRemoteProfile>?>(null) }
    var currentId by remember(studio) { mutableStateOf("") }
    var draft by remember(studio) { mutableStateOf<ProfileDraft?>(null) }
    var value by remember(studio) { mutableStateOf(TextFieldValue()) }
    var newUrl by remember(studio) { mutableStateOf(TextFieldValue()) }
    var census by remember(studio) { mutableStateOf<JsonObject?>(null) }
    var reviewingCensus by remember(studio) { mutableStateOf(false) }

    fun run(action: suspend () -> Unit) { scope.launch { state.run(action) } }
    fun clearForm() { draft = null; value = TextFieldValue(); newUrl = TextFieldValue() }
    fun load() = run {
        profiles = studio.nativeRemoteProfiles()
        if (profiles?.none { it.id == currentId } != false) currentId = profiles?.firstOrNull()?.id.orEmpty()
        census = null
        reviewingCensus = false
    }
    fun start(kind: ProfileEdit, profile: NativeRemoteProfile? = null) {
        value = TextFieldValue(if (kind == ProfileEdit.RENAME) profile?.name.orEmpty() else "")
        newUrl = TextFieldValue(if (kind == ProfileEdit.URL) profile?.url.orEmpty() else "")
        draft = ProfileDraft(kind, profile)
        reviewingCensus = false
    }
    fun update() {
        val intent = draft ?: return
        val profile = intent.profile
        val path = profile?.let { "$remoteProfilesPath/${it.id}" } ?: remoteProfilesPath
        val text = value.text
        val url = newUrl.text.trim()
        clearForm()
        run {
            val revision = buildJsonObject { profile?.let { put("expected_revision", it.revision) } }
            // Each form is a separate profile intent (§16). A URL change explicitly clears
            // its credential. Token writes never leave this local client/backend boundary.
            val result = when (intent.edit) {
                ProfileEdit.CREATE -> studio.request(path, "POST", jsonFields("name" to text.trim(), "url" to url))
                ProfileEdit.RENAME -> studio.request(path, "PATCH", JsonObject(revision + ("name" to JsonPrimitive(text.trim()))))
                ProfileEdit.URL -> studio.request(path, "PATCH", JsonObject(revision + mapOf("url" to JsonPrimitive(url), "clear_credential" to JsonPrimitive(true))))
                ProfileEdit.TOKEN -> studio.request("$path/credential", "PUT", JsonObject(revision + ("token" to JsonPrimitive(text))))
                ProfileEdit.REMOVE_TOKEN -> studio.request("$path/credential", "DELETE", revision)
                ProfileEdit.DELETE -> studio.request(path, "DELETE", revision)
                ProfileEdit.RECOVER -> studio.request("$path/recover", "POST", JsonObject(revision + ("confirmation" to
                    jsonFields("profile_id" to requireNotNull(profile).id, "action" to "discard_uncertain_credential"))))
            }
            val changed = when (intent.edit) {
                ProfileEdit.DELETE -> null
                ProfileEdit.RECOVER -> result.jsonObject.obj("profile")?.nativeRemoteProfile()
                else -> result.jsonObject.nativeRemoteProfile()
            }
            if (profile != null && changed != null && (changed.id != profile.id || changed.revision < profile.revision)) remoteInvalid()
            if (profile != null && intent.edit == ProfileEdit.DELETE && result !is JsonNull) remoteInvalid()
            profiles = profiles.orEmpty().filterNot { it.id == profile?.id } + listOfNotNull(changed)
            currentId = changed?.id ?: profiles?.firstOrNull()?.id.orEmpty()
            census = null
            state.notice = "nativeRemote.saved"
        }
    }

    LaunchedEffect(studio) { load() }

    SettingsSection("nativeRemote.profilesTitle", tailPadding) {
        val idle = !state.busy && draft == null && !reviewingCensus
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            LibraryButton("refresh-cw", m["refresh"], idle) { load() }
            LibraryButton("plus", m["nativeRemote.createProfile"], idle && profiles != null) { start(ProfileEdit.CREATE) }
        }
        RemoteFeedback(state, m)
        if (profiles?.isEmpty() == true) Muted(m["nativeRemote.noProfiles"])
        val profile = profiles?.find { it.id == currentId }
        RemoteChoices(m["nativeRemote.profile"], currentId, profiles.orEmpty().map { it.id to it.name }, idle) {
            currentId = it
            census = null
        }
        if (profile != null) {
            LibraryCaption(profile.url, mono = true, maxLines = 2)
            val stateKey = profile.verification.string("state")?.takeIf {
                it in setOf("unchecked", "valid", "unauthorised", "offline", "incompatible", "insecure_blocked", "error", "unavailable", "checking")
            } ?: "unavailable"
            LibraryCaption(m["nativeRemote.verify.$stateKey"])
            profile.verification.obj("user")?.string("display_name")?.let { Text(it) }
            val source = profile.credentialSource.takeIf { it in setOf("stored", "environment") } ?: "none"
            LibraryCaption(m["nativeRemote.credential.$source"])
            val path = "$remoteProfilesPath/${profile.id}"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                LibraryButton("check", m["nativeRemote.verify"], idle && profile.hasToken) {
                    run {
                        val result = studio.request("$path/verify", "POST", buildJsonObject {
                            put("expected_revision", profile.revision)
                        }).jsonObject
                        if (result.string("state") !in setOf("valid", "unauthorised", "offline", "incompatible", "insecure_blocked", "error")) remoteInvalid()
                        profiles = profiles?.map { if (it.id == profile.id) it.copy(verification = result) else it }
                    }
                }
                LibraryButton("pencil", m["nativeRemote.renameProfile"], idle) { start(ProfileEdit.RENAME, profile) }
                LibraryButton("link-2", m["nativeRemote.changeUrl"], idle && source != "environment") { start(ProfileEdit.URL, profile) }
                LibraryButton("shield", m["nativeRemote.setCredential"], idle && source != "environment") { start(ProfileEdit.TOKEN, profile) }
                LibraryButton("x", m["nativeRemote.removeCredential"], idle && source == "stored") { start(ProfileEdit.REMOVE_TOKEN, profile) }
                LibraryButton("trash-2", m["nativeRemote.deleteProfile"], idle && source != "environment") { start(ProfileEdit.DELETE, profile) }
                if (profile.verification.string("error_code") == "profile_recovery_required") {
                    LibraryButton("archive-restore", m["nativeRemote.recoverProfile"], idle) { start(ProfileEdit.RECOVER, profile) }
                }
                LibraryButton("list-tree", m["nativeRemote.consumers"], idle) {
                    run { census = checkedCensus(studio.request("$path/consumers").jsonObject, profile) }
                }
            }
            val inventory = census
            if (inventory != null) {
                LibraryCard {
                    LibrarySectionTitle(m["nativeRemote.consumers"])
                    LibraryCaption(inventory.string("url").orEmpty(), mono = true, maxLines = 2)
                    val roots = inventory.remoteStrings("bound_roots")
                    val active = inventory.remoteStrings("active_roots").toSet()
                    if (roots.isEmpty()) Muted(m["nativeRemote.noConsumers"])
                    roots.forEach { root ->
                        LibraryCaption(if (root in active) "$root · ${m["nativeRemote.activeConsumer"]}" else root, mono = true, maxLines = 2)
                    }
                    val known = inventory.string("state") == "known"
                    if (reviewingCensus) {
                        RemoteConfirmation(m["nativeRemote.consumersReview"], m["nativeRemote.confirm"], m, !state.busy,
                            { reviewingCensus = false }) {
                            reviewingCensus = false
                            run {
                                census = checkedCensus(studio.request("$path/consumers/confirmation", "POST", buildJsonObject {
                                    put("expected_revision", profile.revision)
                                    put("url", profile.url)
                                    putJsonArray("bound_roots") { roots.forEach { add(JsonPrimitive(it)) } }
                                }).jsonObject, profile)
                            }
                        }
                    } else if (known) {
                        LibraryButton("x", m["nativeRemote.withdrawConsumers"], idle) {
                            run {
                                census = checkedCensus(studio.request("$path/consumers/confirmation", "DELETE", buildJsonObject {
                                    put("expected_revision", profile.revision)
                                }).jsonObject, profile)
                            }
                        }
                    } else {
                        LibraryButton("check", m["nativeRemote.confirmConsumers"], idle) { reviewingCensus = true }
                    }
                }
            }
        }
        draft?.let { intent ->
            LibraryCard {
                val kind = intent.edit
                LibrarySectionTitle(m["nativeRemote.${kind.key}"])
                if (kind == ProfileEdit.CREATE || kind == ProfileEdit.RENAME) {
                    RemoteField(m["nativeRemote.name"], value, { value = it }, !state.busy)
                }
                if (kind == ProfileEdit.CREATE || kind == ProfileEdit.URL) {
                    RemoteField(m["nativeRemote.url"], newUrl, { newUrl = it }, !state.busy)
                }
                if (kind == ProfileEdit.TOKEN) RemoteField(m["nativeRemote.credential"], value, { value = it }, !state.busy, secret = true)
                val valid = when (kind) {
                    ProfileEdit.CREATE -> value.text.isNotBlank() && newUrl.text.isNotBlank()
                    ProfileEdit.RENAME, ProfileEdit.TOKEN -> value.text.isNotBlank()
                    ProfileEdit.URL -> newUrl.text.isNotBlank() && newUrl.text.trim() != intent.profile?.url
                    else -> true
                }
                if (kind in setOf(ProfileEdit.URL, ProfileEdit.REMOVE_TOKEN, ProfileEdit.DELETE, ProfileEdit.RECOVER)) {
                    RemoteConfirmation(m["nativeRemote.review.${kind.key}"], m["nativeRemote.confirm"], m,
                        !state.busy && valid, ::clearForm, ::update)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        WorkAction(m["saveDocument"], "check", enabled = !state.busy && valid, primary = true, onClick = ::update)
                        WorkAction(m["cancel"], null, enabled = !state.busy, onClick = ::clearForm)
                    }
                }
            }
        }
        if (profiles == null && !state.busy) LibraryButton("external-link", m["openWeb"], idle, openAdvanced)
    }
}

private fun checkedCensus(row: JsonObject, profile: NativeRemoteProfile): JsonObject {
    if (row.string("profile_id") != profile.id || row.string("url") != profile.url ||
        row.remoteNumber("revision") != profile.revision || row.string("state") !in setOf("known", "unknown")) remoteInvalid()
    val roots = row.remoteStrings("bound_roots")
    val active = row.remoteStrings("active_roots")
    if (roots.distinct().size != roots.size || active.distinct().size != active.size || !roots.containsAll(active)) remoteInvalid()
    return row
}
