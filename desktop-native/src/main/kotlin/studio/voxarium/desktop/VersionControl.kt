package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.*

// §9.6: the page consumes the independent backend catalog and sends per-card patches.
internal class VersionControlPageState {
    var overview by mutableStateOf<JsonObject?>(null)
        private set
    var status by mutableStateOf<JsonObject?>(null)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set
    var statusProblem by mutableStateOf<Problem?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var diffOpen by mutableStateOf(false)
        private set
    var diff by mutableStateOf<String?>(null)
        private set
    var diffProblem by mutableStateOf<Problem?>(null)
        private set
    var message by mutableStateOf(TextFieldValue())
    var addUntracked by mutableStateOf(false)
    var commitResult by mutableStateOf<JsonObject?>(null)
        private set
    private val drafts = mutableStateMapOf<Pair<String, String>, TextFieldValue>()

    val hasUnsavedChanges: Boolean
        get() = message.text.isNotBlank() || drafts.any { (key, value) -> value.text.trim() != stored(key) }

    private fun stored(key: Pair<String, String>): String =
        overview?.objects("backends")?.firstOrNull { it.string("id") == key.first }
            ?.obj("settings")?.string(key.second).orEmpty()

    fun field(backend: String, key: String): TextFieldValue =
        drafts[backend to key] ?: TextFieldValue(stored(backend to key))

    fun edit(backend: String, key: String, value: TextFieldValue) {
        drafts[backend to key] = value
    }

    fun changed(backend: String): Map<String, String> = drafts
        .filter { (key, value) -> key.first == backend && value.text.trim() != stored(key) }
        .mapKeys { (key, _) -> key.second }
        .mapValues { (_, value) -> value.text.trim() }

    private fun adoptOverview(next: JsonObject) {
        // Prune untouched field values so refresh can update them. Keep drafts on other cards.
        drafts.keys.filter { drafts[it]?.text == stored(it) }.forEach(drafts::remove)
        overview = next
    }

    suspend fun refresh(api: ProjectApi) = io {
        adoptOverview(api.get("/vcs").jsonObject)
        refreshWorkingCopy(api)
    }

    suspend fun select(api: ProjectApi, choice: String) = io {
        adoptOverview(api.put("/vcs/settings", jsonFields("backend" to choice)).jsonObject)
        commitResult = null
        status = null
        diff = null
        refreshWorkingCopy(api)
    }

    suspend fun save(api: ProjectApi, backend: String) {
        val patch = changed(backend)
        if (patch.isEmpty()) return
        io {
            val next = api.put("/vcs/settings", buildJsonObject {
                putJsonObject("settings") { put(backend, jsonFields(*patch.toList().toTypedArray())) }
            }).jsonObject
            patch.keys.forEach { drafts.remove(backend to it) }
            adoptOverview(next)
            refreshWorkingCopy(api)
        }
    }

    suspend fun toggleDiff(api: ProjectApi) = io {
        diffOpen = !diffOpen
        if (diffOpen) {
            diff = null
            loadDiff(api)
        }
    }

    suspend fun commit(api: ProjectApi) {
        val text = message.text.trim()
        if (text.isEmpty()) return
        io {
            commitResult = null
            val result = api.post("/vcs/commit", buildJsonObject {
                put("message", text)
                put("add_untracked", addUntracked)
            }).jsonObject
            commitResult = result
            if (result.flag("committed")) message = TextFieldValue()
            refreshWorkingCopy(api)
        }
    }

    private suspend fun refreshWorkingCopy(api: ProjectApi) {
        statusProblem = null
        try {
            status = api.get("/vcs/status").jsonObject
        } catch (error: Exception) {
            status = null
            statusProblem = error.problem()
        }
        if (overview?.obj("active") == null) {
            diffOpen = false
            diff = null
            diffProblem = null
        } else if (diffOpen) {
            loadDiff(api)
        }
    }

    private suspend fun loadDiff(api: ProjectApi) {
        diffProblem = null
        try {
            diff = api.get("/vcs/diff").jsonObject.string("diff").orEmpty()
        } catch (error: Exception) {
            diffProblem = error.problem()
        }
    }

    private suspend fun io(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        try {
            action()
        } catch (error: Exception) {
            problem = error.problem()
        } finally {
            busy = false
        }
    }
}

@Composable
internal fun VersionControlPage(tab: ProjectTab, modifier: Modifier = Modifier) {
    val api = tab.api ?: return
    val state = tab.versionControlPage
    val m = localMessages.current
    val colors = localColors.current
    LaunchedEffect(api) {
        // Conflate file events during a refresh instead of restarting each VCS probe.
        snapshotFlow { tab.pageRefresh }.collect { state.refresh(api) }
    }

    val scroll = rememberScrollState()
    val overview = state.overview
    val active = overview?.obj("active")
    val choice = overview?.string("configured") ?: "auto"
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            LibraryHeader(m["versionControl"], state.status?.string("root") ?: active?.string("root") ?: m["libraryVcsNoWorkspace"])
            state.problem?.let {
                WorkProblemBanner(it)
            }
            Column(Modifier.widthIn(max = 768.dp).fillMaxWidth().padding(StudioTokens.panePad),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space5)) {
                if (overview == null) {
                    if (state.problem == null) SettingsLoadingBars()
                    else Text(m["libraryVcsUnreadable"], color = colors.muted)
                } else {
                    val arrival = rememberSettingsArrival()
                    Column(Modifier.settingsArrival(arrival, 0), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LibrarySectionTitle(m["vcsActiveSystem"], Modifier.weight(1f))
                            SettingsRefreshButton(m[if (state.busy) "libraryVcsRefreshing" else "refresh"], state.busy) {
                                tab.scope.launch { state.refresh(api) }
                            }
                        }
                        LibraryCard { VcsOverviewHeader(overview, active, state) }
                    }
                    Column(Modifier.settingsArrival(arrival, 1), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        LibrarySectionTitle(m["libraryVcsChoose"])
                        Text(m["libraryVcsChooseIntro"], color = colors.muted, fontSize = StudioTokens.textSmall)
                        VcsChoiceCard(m["vcsAutomatic"], m["libraryVcsAutoHint"], checked = choice == "auto", enabled = !state.busy) {
                            tab.scope.launch { state.select(api, "auto") }
                        }
                        overview.objects("backends").forEach { backend ->
                            val id = backend.string("id") ?: return@forEach
                            key(id) {
                                VcsSettingsCard(state, id, backend, choice == id,
                                    onSelect = { tab.scope.launch { state.select(api, id) } },
                                    onSave = { tab.scope.launch { state.save(api, id) } })
                            }
                        }
                        VcsChoiceCard(m["libraryVcsNone"], m["libraryVcsNoneHint"], checked = choice == "none", enabled = !state.busy) {
                            tab.scope.launch { state.select(api, "none") }
                        }
                    }
                    if (active != null) {
                        Box(Modifier.settingsArrival(arrival, 2)) { VcsOperations(tab, api, state, active.flag("available")) }
                        Box(Modifier.settingsArrival(arrival, 3)) { VcsDiffSection(tab, api, state, active.flag("available")) }
                    }
                }
            }
        }
        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun VcsOverviewHeader(overview: JsonObject, active: JsonObject?, state: VersionControlPageState) {
    val m = localMessages.current
    val colors = localColors.current
    if (active == null) {
        val off = overview.string("configured") == "none"
        LibrarySectionTitle(m[if (off) "libraryVcsOffTitle" else "vcsUndetected"])
        Text(m[if (off) "libraryVcsOffBody" else "libraryVcsUndetectedBody"], color = colors.muted)
        return
    }
    val id = active.string("id").orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        StudioIcon("git-branch", Modifier.size(StudioTokens.iconSize), colors.accent)
        LibrarySectionTitle(active.string("name") ?: id)
        VcsBackendId(id)
        LibraryCaption(if (overview.string("configured") == "auto") m["libraryVcsAutoDetected"]
            else m["libraryVcsConfigured"].replace("{id}", id))
    }
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        VcsValue(m["libraryVcsWorkspace"], status?.string("root") ?: active.string("root") ?: "—")
        VcsValue(m["vcsBranch"], status?.string("branch") ?: overview.string("branch") ?: "—")
        VcsValue(m["vcsRevision"], status?.string("revision") ?: overview.string("revision") ?: "—")
        VcsValue(m["vcsTaskIsolation"], m[if (active.flag("supports_isolation")) "vcsSupported" else "libraryVcsNoIsolation"],
            mono = false)
    }
    LibraryCaption(m[if (active.flag("supports_isolation")) "libraryVcsIsolated" else "libraryVcsShared"], maxLines = 5)
    state.statusProblem?.let { ProblemText(it) }
    if (!active.flag("available")) VcsWarning(active.string("reason")?.takeIf(String::isNotBlank) ?: m.status("unavailable"))
}

@Composable
private fun VcsBackendId(id: String) {
    LibraryCaption(id, Modifier.background(localColors.current.inset, RoundedCornerShape(StudioTokens.radiusSm))
        .padding(horizontal = StudioTokens.space2), mono = true)
}

@Composable
private fun VcsChoice(checked: Boolean, enabled: Boolean, onSelect: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = localColors.current
    StudioSelectionRow(checked, onSelect, Modifier.fillMaxWidth(), enabled = enabled) {
        val mark = StudioTokens.ensureContrast(colors.accent, colors.surface)
        Box(Modifier.padding(top = StudioTokens.space1).size(StudioTokens.checkboxSize)
            .border(StudioTokens.borderWidth, studioColor(if (checked) mark else colors.controlBorder), CircleShape),
            contentAlignment = Alignment.Center) {
            val dot = studioColor(if (checked) mark else Color.Transparent)
            Spacer(Modifier.size(StudioTokens.statusDotSize).background(dot, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair), content = content)
    }
}

@Composable
private fun VcsChoiceCard(title: String, hint: String, checked: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    LibraryCard {
        VcsChoice(checked, enabled, onSelect) {
            Text(title)
            LibraryCaption(hint, maxLines = 3)
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun VcsSettingsCard(state: VersionControlPageState, id: String, backend: JsonObject, checked: Boolean,
                            onSelect: () -> Unit, onSave: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    LibraryCard(selected = backend.flag("active"), gap = StudioTokens.space3) {
        VcsChoice(checked, !state.busy, onSelect) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                LibrarySectionTitle(backend.string("name") ?: id)
                VcsBackendId(id)
                if (backend.flag("active")) Text(m["vcsActive"], color = colors.good, fontSize = StudioTokens.textSmall)
                else if (backend.flag("detected")) LibraryCaption(m["vcsDetected"])
            }
            val program = backend.string("program") ?: id
            LibraryCaption(m[if (backend.flag("supports_isolation")) "libraryVcsBuiltInIsolates" else "libraryVcsBuiltIn"]
                .replace("{program}", program))
            if (!backend.flag("available")) {
                VcsWarning(backend.string("reason")?.takeIf(String::isNotBlank) ?: m.status("unavailable"))
            }
        }
        val fields = backend.objects("settings_fields")
        if (fields.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = StudioTokens.rowPadX + StudioTokens.checkboxSize + StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                for (field in fields) {
                    val key = field.string("key") ?: continue
                    val (label, hint) = when (key) {
                        "executable" -> m["vcsExecutable"] to m["libraryVcsExecutableHint"]
                            .replace("{program}", backend.string("program").orEmpty())
                        "workspace" -> m["vcsWorkspace"] to m["libraryVcsWorkspaceHint"]
                        "port" -> m["vcsServer"] to m["libraryVcsServerHint"]
                        "user" -> m["vcsUser"] to m["libraryVcsUserHint"]
                        "client" -> m["vcsClient"] to m["libraryVcsClientHint"]
                        "charset" -> m["vcsCharset"] to m["libraryVcsCharsetHint"]
                        else -> key to null
                    }
                    val placeholder = field.string("default") ?: backend.string("program")?.takeIf { key == "executable" }
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                        Text(label, color = colors.muted, fontSize = StudioTokens.textSmall)
                        StudioField(
                            label = label,
                            value = state.field(id, key),
                            onValueChange = { state.edit(id, key, it) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            placeholder = placeholder,
                        )
                        if (hint != null) LibraryCaption(hint, maxLines = 3)
                    }
                }
                if (id == "perforce") LibraryCaption(m["vcsPerforceAuthentication"], maxLines = 3)
                WorkAction(m["saveDocument"], "check", enabled = !state.busy && state.changed(id).isNotEmpty(), onClick = onSave)
            }
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun VcsOperations(tab: ProjectTab, api: ProjectApi, state: VersionControlPageState, available: Boolean) {
    val m = localMessages.current
    val colors = localColors.current
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        LibrarySectionTitle(m["libraryVcsCommit"])
        LibraryCard(gap = StudioTokens.space3) {
            val count = state.status?.objects("changes")?.size
            if (count != null) LibraryCaption(if (count == 0) m["libraryVcsClean"] else
                "${m["vcsChangedFileCount"]} · $count")
            val messageLabel = m["vcsCommitMessage"]
            TextArea(
                value = state.message,
                onValueChange = { state.message = it },
                modifier = Modifier.fillMaxWidth().height(74.dp).semantics { contentDescription = messageLabel },
                placeholder = { LibraryCaption(m["libraryVcsCommitPlaceholder"]) },
                enabled = !state.busy,
            )
            Column {
                WorkCheckbox(m["vcsIncludeUntracked"], checked = state.addUntracked, enabled = !state.busy) {
                    state.addUntracked = it
                }
                LibraryCaption(m["libraryVcsUntrackedHint"], Modifier.padding(start = StudioTokens.checkboxSize + StudioTokens.space2), maxLines = 3)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                WorkAction(m["libraryVcsCommitAll"], "git-commit-vertical", primary = true,
                    enabled = !state.busy && available && state.message.text.isNotBlank()) {
                    tab.scope.launch { state.commit(api) }
                }
                LibraryCaption(m["vcsCommitScope"], Modifier.padding(top = StudioTokens.space1), maxLines = 3)
            }
            state.commitResult?.let { result ->
                Text(m[if (result.flag("committed")) "vcsCommitted" else "vcsNothingToCommit"],
                    Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(StudioTokens.radiusMd)).padding(StudioTokens.space2), color = colors.muted)
                result.string("revision")?.takeIf(String::isNotBlank)?.let { VcsValue(m["vcsRevision"], it) }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun VcsDiffSection(tab: ProjectTab, api: ProjectApi, state: VersionControlPageState, available: Boolean) {
    val m = localMessages.current
    val clipboard = LocalClipboardManager.current
    val colors = localColors.current
    val diff = state.diff
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            LibrarySectionTitle(m["libraryVcsDiff"])
            if (state.diffOpen) LibraryCaption(m["libraryVcsDiffScope"], mono = true)
            Spacer(Modifier.weight(1f))
            LibraryButton("file-diff", m[if (state.diffOpen) "vcsHideDiff" else "vcsShowDiff"], !state.busy && available) {
                tab.scope.launch { state.toggleDiff(api) }
            }
        }
        StudioDisclosure(state.diffOpen) {
            Box(Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(StudioTokens.radiusLg))
                .border(StudioTokens.borderWidth, colors.border, RoundedCornerShape(StudioTokens.radiusLg))) {
                when {
                    state.diffProblem != null -> Box(Modifier.padding(StudioTokens.space3)) { ProblemText(requireNotNull(state.diffProblem)) }
                    diff == null -> Text(m["loading"], Modifier.padding(StudioTokens.space3), color = colors.muted)
                    diff.isBlank() -> Text(m["vcsNoTextDiff"], Modifier.padding(StudioTokens.space3), color = colors.muted)
                    else -> {
                        val lines = remember(diff) { diff.lines() }
                        val height = (lines.size * 19 + 24).coerceIn(60, 384).dp
                        LibraryCodePreview(diff, withNumbers = false, modifier = Modifier.height(height))
                        if (state.diffOpen) {
                            Box(Modifier.align(Alignment.TopEnd).padding(top = StudioTokens.space2, end = StudioTokens.space3)) {
                                LibraryIconAction("copy", m["vcsCopyDiff"], bordered = true) {
                                    clipboard.setText(AnnotatedString(diff))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VcsValue(label: String, value: String, mono: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Text(label, Modifier.width(128.dp), color = localColors.current.muted)
        Text(value, Modifier.weight(1f), fontFamily = if (mono) localStudioMono.current else null)
    }
}

@Composable
private fun VcsWarning(message: String) {
    val danger = localColors.current.danger
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        StudioIcon("triangle-alert", Modifier.size(StudioTokens.iconSize), danger)
        Text(message, color = danger)
    }
}

