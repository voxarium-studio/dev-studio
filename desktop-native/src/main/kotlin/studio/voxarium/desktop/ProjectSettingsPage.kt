package studio.voxarium.desktop

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.ui.component.*

internal enum class ProjectSettingsPanel(val label: String) {
    PROJECT("projectSettings.project"), AGENTS("projectSettings.agents"), TASKS("tasks"),
    MAINTENANCE("projectSettings.maintenance"),
}

internal enum class ProjectSetting(val panel: ProjectSettingsPanel, val group: String?, val field: String, private val default: String) {
    ACCENT(ProjectSettingsPanel.PROJECT, "theme", "accent", "#a394e8"),
    PARALLEL(ProjectSettingsPanel.AGENTS, "concurrency", "agents", "8"),
    LAUNCH_ROOT(ProjectSettingsPanel.AGENTS, "teams", "launch_root", "."),
    COMMIT_BACKEND(ProjectSettingsPanel.AGENTS, "teams", "commit_backend", "claude_code"),
    COMMIT_MODEL(ProjectSettingsPanel.AGENTS, "teams", "commit_model", "haiku"),
    CODE(ProjectSettingsPanel.TASKS, null, "project_code", "");

    fun clean(value: String): String = when (this) {
        ACCENT -> value.trim().lowercase(Locale.ROOT)
        PARALLEL -> value.trim().let { it.toIntOrNull()?.toString() ?: it }
        LAUNCH_ROOT -> value.trim().ifEmpty { "." }
        CODE -> value.trim().uppercase(Locale.ROOT)
        else -> value.trim()
    }

    fun saved(config: JsonObject?): String {
        val groupConfig = if (group == null) config else config?.obj(group)
        return groupConfig?.string(field) ?: default
    }
}

internal enum class ProjectCache(val endpoint: String) {
    NODE("/node-cache"), ASSET("/asset-cache"),
}

// §5.2 and §5.3: keep drafts in their project tab and patch only the edited project fields.
internal class ProjectSettingsPageState {
    var project by mutableStateOf<JsonObject?>(null)
        private set
    var config by mutableStateOf<JsonObject?>(null)
        private set
    var options by mutableStateOf<JsonObject?>(null)
        private set
    var optionProblem by mutableStateOf<Problem?>(null)
        private set
    private var failure by mutableStateOf<Pair<ProjectSettingsPanel?, Problem>?>(null)
    var busy by mutableStateOf(false)
        private set
    var cacheResult by mutableStateOf<Pair<ProjectCache, JsonObject>?>(null)
        private set
    private val drafts = mutableStateMapOf<ProjectSetting, TextFieldValue>()

    val hasUnsavedChanges: Boolean get() = drafts.keys.any(::changed)

    fun input(setting: ProjectSetting) = drafts[setting] ?: TextFieldValue(setting.saved(config))

    fun value(setting: ProjectSetting) = input(setting).text

    fun edit(setting: ProjectSetting, value: TextFieldValue) {
        drafts[setting] = value
    }

    fun choose(setting: ProjectSetting, value: String) = edit(setting, TextFieldValue(value))

    fun changed(setting: ProjectSetting) = setting.clean(value(setting)) != setting.clean(setting.saved(config))

    fun problemFor(panel: ProjectSettingsPanel? = null) = failure?.takeIf { it.first == panel }?.second

    suspend fun load(api: ProjectApi) = operation {
        optionProblem = null
        coroutineScope {
            launch {
                try {
                    options = api.get("/teams/member-options").jsonObject
                } catch (error: Exception) {
                    optionProblem = error.problem()
                }
            }
            val loaded = api.get("/project").jsonObject
            val properties = requireNotNull(loaded.obj("config"))
            drafts.keys.filterNot(::changed).forEach(drafts::remove)
            project = loaded
            config = properties
        }
    }

    suspend fun save(api: ProjectApi, patch: JsonObject, vararg fields: ProjectSetting, applied: () -> Unit) =
        operation(fields.first().panel) {
            config = api.put("/settings", patch).jsonObject
            fields.forEach(drafts::remove)
            applied()
        }

    suspend fun clear(api: ProjectApi, cache: ProjectCache) = operation(ProjectSettingsPanel.MAINTENANCE) {
        cacheResult = cache to api.delete(cache.endpoint).jsonObject
    }

    private suspend fun operation(panel: ProjectSettingsPanel? = null, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        cacheResult = null
        try {
            work()
        } catch (error: Exception) {
            failure = panel to error.problem()
        } finally {
            busy = false
        }
    }
}

@Composable
internal fun ProjectSettingsPage(
    tab: ProjectTab,
    api: ProjectApi,
    studio: StudioApi,
    m: Messages,
    openRemote: () -> Unit,
    openRemoteAi: () -> Unit,
    openProjectPicker: () -> Unit,
) {
    val state = tab.projectSettingsPage
    val colors = localColors.current
    var confirmAssetCache by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<Int, Int>() }
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalStudioReducedMotion.current

    LaunchedEffect(api, tab.pageRefresh) { state.load(api) }

    fun save(patch: JsonObject, vararg fields: ProjectSetting) {
        tab.scope.launch { state.save(api, patch, *fields, applied = tab::refreshChrome) }
    }

    val project = state.project
    val sections = listOf(ProjectSettingsPanel.PROJECT.label, ProjectSettingsPanel.AGENTS.label,
        ProjectSettingsPanel.TASKS.label, "nativeRemote.syncTitle", "nativeRemote.ai.projectTitle", ProjectSettingsPanel.MAINTENANCE.label)
    var headerHeight by remember { mutableIntStateOf(0) }
    val headerInset = StudioTokens.panePad + with(LocalDensity.current) { headerHeight.toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showContents = maxWidth >= 896.dp && project != null
        val tailPadding = if (showContents) 216.dp else 0.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = StudioTokens.panePad)) {
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .clip(RoundedCornerShape(StudioTokens.radiusMd)).background(colors.surface)
                .padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m["projectSettings"], fontSize = StudioTokens.titleSize, fontWeight = FontWeight.Medium)
                        project?.string("data_dir")?.let { dataDirectory ->
                            LibraryCaption(Path.of(dataDirectory).resolve("config.yaml").toString(), mono = true, maxLines = 2)
                        }
                    }
                    LibraryButton("refresh-cw", m["refresh"], enabled = !state.busy) { tab.refresh() }
                }
                val problem = state.problemFor()
                problem?.let {
                    Spacer(Modifier.height(StudioTokens.space2))
                    ProblemText(it)
                }
                if (project == null && problem == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                        StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                        Muted(m["loading"])
                    }
                }
            }
            if (project == null) return@Column
            val arrival = rememberSettingsArrival()
            fun band(index: Int) = Modifier.onGloballyPositioned {
                anchors[index] = it.positionInParent().y.roundToInt()
            }.settingsArrival(arrival, index)

            SettingsSection(ProjectSettingsPanel.PROJECT.label, tailPadding, band(0)) {
                ProjectAccent(state, m) { value ->
                    save(buildJsonObject { putJsonObject("theme") { put("accent", value) } }, ProjectSetting.ACCENT)
                }
                state.problemFor(ProjectSettingsPanel.PROJECT)?.let { ProblemText(it) }
                SettingsRow(m["projectRoot"]) {
                    LibraryCaption(api.binding.root, mono = true, maxLines = 3)
                }
                SettingsRow(m["versionControl"]) {
                    Text(projectVcsSummary(project.obj("vcs"), m))
                }
                SettingsRow(m["projectSettings.authentication"]) {
                    Text(m[if (project.flag("auth_required")) "projectSettings.tokenRequired" else "projectSettings.loopback"])
                }
                LibraryButton("folder-open", m["projectSettings.openAnother"], onClick = openProjectPicker)
            }

            SettingsSection(ProjectSettingsPanel.AGENTS.label, tailPadding, band(1)) {
                state.problemFor(ProjectSettingsPanel.AGENTS)?.let { ProblemText(it) }
                val parallel = state.value(ProjectSetting.PARALLEL).trim().toIntOrNull()
                ProjectSettingField(state, ProjectSetting.PARALLEL, m["projectSettings.parallel"], m,
                    valid = parallel != null && parallel in 1..16, width = 90.dp) { _ ->
                    save(buildJsonObject { putJsonObject("concurrency") { put("agents", requireNotNull(parallel)) } },
                        ProjectSetting.PARALLEL)
                }
                ProjectSettingsNote(m["projectSettings.parallelHint"])

                ProjectSettingField(state, ProjectSetting.LAUNCH_ROOT, m["projectSettings.launchRoot"], m) { value ->
                    save(buildJsonObject { putJsonObject("teams") { put("launch_root", value) } }, ProjectSetting.LAUNCH_ROOT)
                }
                ProjectSettingsNote(m["projectSettings.launchRootHint"])
                val root = Path.of(api.binding.root)
                val launch = launchRootPreview(root, ProjectSetting.LAUNCH_ROOT.clean(state.value(ProjectSetting.LAUNCH_ROOT)))
                SettingsRow(m["projectSettings.startIn"]) {
                    Text(launch.toString(), color = if (state.changed(ProjectSetting.LAUNCH_ROOT)) colors.warning else colors.text)
                }
                if (!launch.startsWith(root)) ProjectSettingsNote(m["projectSettings.outside"], colors.warning)

                Rule()
                CommitDescriptionSettings(state, m, onRetry = { tab.scope.launch { state.load(api) } }) { backend, model ->
                    save(buildJsonObject {
                        putJsonObject("teams") {
                            put("commit_backend", backend)
                            put("commit_model", model)
                        }
                    }, ProjectSetting.COMMIT_BACKEND, ProjectSetting.COMMIT_MODEL)
                }
            }

            SettingsSection(ProjectSettingsPanel.TASKS.label, tailPadding, band(2)) {
                state.problemFor(ProjectSettingsPanel.TASKS)?.let { ProblemText(it) }
                val code = ProjectSetting.CODE.clean(state.value(ProjectSetting.CODE))
                ProjectSettingField(state, ProjectSetting.CODE, m["projectSettings.code"], m,
                    valid = code.isEmpty() || projectCodePattern.matches(code), width = 120.dp) { value ->
                    save(jsonFields("project_code" to value), ProjectSetting.CODE)
                }
                ProjectSettingsNote(m["projectSettings.codeHint"])
                SettingsRow(m["projectSettings.readAs"]) {
                    val example = projectCodePreview(project.string("name") ?: api.binding.name, code)
                    Text(example, color = if (state.changed(ProjectSetting.CODE)) colors.warning else colors.text)
                }
            }

            Box(band(3)) { RemoteSyncSettings(tab, api, studio, m, tailPadding, openRemote) }
            Box(band(4)) { RemoteAiProjectSettings(tab, api, studio, m, tailPadding, openRemoteAi) }

            SettingsSection(ProjectSettingsPanel.MAINTENANCE.label, tailPadding, band(5)) {
                fun clear(cache: ProjectCache) { tab.scope.launch { state.clear(api, cache) } }
                WorkAction(m["projectSettings.clearNodeCache"], null, enabled = !state.busy && !confirmAssetCache) {
                    clear(ProjectCache.NODE)
                }
                StudioDisclosure(confirmAssetCache) {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        ProjectSettingsNote(m["projectSettings.assetCacheConfirm"], colors.warning)
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                            WorkAction(m["projectSettings.clearAssetCache"], null, primary = true, enabled = confirmAssetCache && !state.busy) {
                                confirmAssetCache = false
                                clear(ProjectCache.ASSET)
                            }
                            WorkAction(m["cancel"], null, enabled = confirmAssetCache, onClick = { confirmAssetCache = false })
                        }
                    }
                }
                if (!confirmAssetCache) {
                    WorkAction(m["projectSettings.clearAssetCache"], null, enabled = !state.busy) { confirmAssetCache = true }
                    ProjectSettingsNote(m["projectSettings.assetCacheHint"])
                }
                state.cacheResult?.let { (cache, result) -> ProjectSettingsNote(cacheSummary(cache, result, m)) }
                state.problemFor(ProjectSettingsPanel.MAINTENANCE)?.let { ProblemText(it) }

                Rule()
                val limits = project.obj("concurrency_limits")
                listOf("gpu", "cpu", "io", "agent").forEach { key ->
                    val limit = if (key == "agent") ProjectSetting.PARALLEL.saved(state.config) else limits?.string(key)
                    if (limit != null) SettingsRow(m["projectSettings.limit.$key"]) { Text(limit) }
                }
                ProjectSettingsNote(m["projectSettings.concurrencyHint"])
            }
        }
        if (showContents) {
            val cardsWidth = (maxWidth - StudioTokens.panePad * 2 - tailPadding).coerceAtMost(672.dp)
            val active = when {
                scroll.value > 0 && !scroll.canScrollForward -> sections.lastIndex
                else -> sections.indices.lastOrNull { (anchors[it] ?: Int.MAX_VALUE) <= scroll.value } ?: 0
            }
            SettingsContents(sections, active, Modifier.align(Alignment.TopStart)
                .padding(start = StudioTokens.panePad * 2 + StudioTokens.space4 + cardsWidth, top = headerInset).width(176.dp)) { index ->
                anchors[index]?.let { offset ->
                    scope.launch {
                        if (reducedMotion) scroll.scrollTo(offset)
                        else scroll.animateScrollTo(offset, tween(StudioMotion.slow, easing = StudioMotion.standard))
                    }
                }
            }
        }
    }
}

private val accentPalette = listOf("#a394e8", "#58a6ff", "#39c5cf", "#3fb950", "#d29922", "#f85149", "#db61a2")
private val accentHex = Regex("#[0-9a-f]{6}")
private val projectCodePattern = Regex("(?=.*[A-Z])[A-Z0-9]{1,6}")
private val projectNameWords = Regex("[A-Za-z0-9]+")

@Composable
private fun ProjectAccent(state: ProjectSettingsPageState, m: Messages, onSave: (String) -> Unit) {
    val colors = localColors.current
    val saved = ProjectSetting.ACCENT.clean(ProjectSetting.ACCENT.saved(state.config))
    val draft = ProjectSetting.ACCENT.clean(state.value(ProjectSetting.ACCENT))
    SettingsRow(m["projectSettings.accent"]) {
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            accentPalette.forEach { hex ->
                val selected = saved == hex
                val swatch = Color(0xff000000L or hex.drop(1).toLong(16))
                val selection = StudioTokens.ensureContrast(colors.text, swatch)
                Box(Modifier.size(StudioTokens.controlMinHeight)
                    .studioFocusRing(CircleShape, enabled = !state.busy, background = colors.surface)
                    .clip(CircleShape).background(swatch)
                    .border(if (selected) StudioTokens.focusWidth else StudioTokens.borderWidth,
                        studioColor(if (selected) selection else colors.controlBorder), CircleShape)
                    .semantics { contentDescription = hex }
                    .selectable(selected, enabled = !state.busy, role = Role.RadioButton, onClick = {
                        if (!selected) onSave(hex)
                    }))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            StudioField(m["projectSettings.customAccent"], state.input(ProjectSetting.ACCENT), { state.edit(ProjectSetting.ACCENT, it) },
                Modifier.width(125.dp), enabled = !state.busy, placeholder = "#a394e8")
            WorkAction(m["saveDocument"], null, enabled = !state.busy && accentHex.matches(draft) && draft != saved) { onSave(draft) }
        }
    }
}

@Composable
private fun ProjectSettingField(
    state: ProjectSettingsPageState,
    setting: ProjectSetting,
    label: String,
    m: Messages,
    valid: Boolean = true,
    width: Dp? = null,
    onSave: (String) -> Unit,
) {
    SettingsRow(label) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            val modifier = if (width == null) Modifier.weight(1f) else Modifier.widthIn(max = width).weight(1f, fill = false)
            StudioField(label, state.input(setting), { state.edit(setting, it) }, modifier, enabled = !state.busy)
            WorkAction(m["saveDocument"], null, enabled = !state.busy && valid && state.changed(setting)) {
                onSave(setting.clean(state.value(setting)))
            }
        }
    }
}

@Composable
private fun CommitDescriptionSettings(
    state: ProjectSettingsPageState,
    m: Messages,
    onRetry: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val backend = state.value(ProjectSetting.COMMIT_BACKEND)
    val model = state.value(ProjectSetting.COMMIT_MODEL)
    val backends = state.options?.obj("agent_backends") ?: JsonObject(emptyMap())
    val models = state.options?.objects("suggested_models").orEmpty()
        .filter { it.string("backend") == null || it.string("backend") == backend }
        .distinctBy { it.string("id") }
    val ready = !state.busy && backends.isNotEmpty()
    Text(m["projectSettings.commitTitle"], fontWeight = FontWeight.Medium)
    ProjectSettingsNote(m["projectSettings.commitHint"])
    SettingsRow(m["projectSettings.commitBackend"]) {
        StudioSelect(m["projectSettings.commitBackend"], backend,
            backends.keys.associateWith { backends.obj(it)?.string("label") ?: it },
            Modifier.widthIn(max = 340.dp), enabled = ready) { option ->
            if (option != backend) {
                state.choose(ProjectSetting.COMMIT_BACKEND, option)
                state.choose(ProjectSetting.COMMIT_MODEL, "")
            }
        }
        val backendInfo = backends.obj(backend)
        if (backendInfo != null && !backendInfo.flag("available")) {
            backendInfo.string("reason")?.let { ProjectSettingsNote(it, localColors.current.warning) }
        }
    }
    SettingsRow(m["projectSettings.commitModel"]) {
        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            StudioField(m["projectSettings.commitModel"], state.input(ProjectSetting.COMMIT_MODEL),
                { state.edit(ProjectSetting.COMMIT_MODEL, it) }, Modifier.fillMaxWidth(), enabled = ready,
                placeholder = m["projectSettings.defaultCommitModel"])
            val suggested = m["projectSettings.suggestedModels"]
            StudioChoice(suggested, model, buildMap {
                put("", m["projectSettings.defaultCommitModel"])
                models.forEach { entry ->
                    val id = entry.string("id") ?: return@forEach
                    put(id, entry.string("label") ?: id)
                }
            }, model, Modifier.widthIn(max = 340.dp), enabled = ready, visibleLabel = suggested) {
                state.choose(ProjectSetting.COMMIT_MODEL, it)
            }
            WorkAction(m["saveDocument"], null, enabled = ready && backend in backends &&
                (state.changed(ProjectSetting.COMMIT_BACKEND) || state.changed(ProjectSetting.COMMIT_MODEL))) {
                onSave(backend, model.trim())
            }
        }
    }
    state.optionProblem?.let {
        ProblemText(it)
        WorkAction(m["retry"], null, onClick = onRetry, enabled = !state.busy)
    }
}

@Composable
private fun ProjectSettingsNote(text: String, color: Color = localColors.current.muted) {
    Text(text, fontSize = StudioTokens.textSmall, color = color)
}

private fun launchRootPreview(root: Path, value: String): Path = try {
    val expanded = when {
        value == "~" -> System.getProperty("user.home")
        value.startsWith("~/") || value.startsWith("~\\") -> System.getProperty("user.home") + value.drop(1)
        else -> value
    }
    root.resolve(expanded).normalize()
} catch (_: IllegalArgumentException) {
    root
}

private fun projectCodePreview(projectName: String, chosen: String): String {
    val words = projectNameWords.findAll(projectName).map { it.value }.take(4).toList()
    val derived = when (words.size) {
        0 -> "TSK"
        1 -> words.single().take(3)
        else -> words.joinToString("") { it.take(1) }
    }.uppercase(Locale.ROOT)
    val code = chosen.ifEmpty { derived.takeIf(projectCodePattern::matches) ?: "TSK" }
    return "$code-01"
}

private fun projectVcsSummary(vcs: JsonObject?, m: Messages): String {
    if (vcs == null) return m["projectSettings.noVcs"]
    val detail = when {
        !vcs.flag("available") -> vcs.string("reason")
        vcs.flag("supports_isolation") -> m["projectSettings.vcsIsolated"]
        else -> m["projectSettings.vcsShared"]
    }
    return listOfNotNull(vcs.string("name"), detail?.takeIf(String::isNotBlank)).joinToString(" — ")
}

private fun cacheSummary(cache: ProjectCache, result: JsonObject, m: Messages): String {
    val removed = result.string("removed").orEmpty()
    return when (cache) {
        ProjectCache.NODE -> m["projectSettings.nodeCacheCleared"].replace("{count}", removed)
        ProjectCache.ASSET -> {
            val format = NumberFormat.getNumberInstance(Locale.forLanguageTag(m.language)).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            val size = format.format((result.string("bytes")?.toLongOrNull() ?: 0L) / 1048576.0)
            m["projectSettings.assetCacheCleared"].replace("{count}", removed).replace("{size}", size)
                .replace("{failed}", result.string("failed").orEmpty())
        }
    }
}
