package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

internal data class TeamSettingField(val key: String, val prompt: Boolean = false)

private val permissionModeField = TeamSettingField("permission_mode")
private val maximumTurnsField = TeamSettingField("max_turns")

internal fun teamSettingsPatch(values: Map<TeamSettingField, JsonElement>) = buildJsonObject {
    putJsonObject("teams") {
        for ((field, value) in values) if (!field.prompt) put(field.key, value)
        val prompts = values.filterKeys { it.prompt }
        if (prompts.isNotEmpty()) putJsonObject("prompts") {
            for ((field, value) in prompts) put(field.key, value)
        }
    }
}

// §10.2, §10.3, §10.10: one ordered stream of project-scoped patches for the two settings sections.
internal class TeamSettingsPageState(private val scope: CoroutineScope, private val refreshRoster: () -> Unit) {
    private val requests = Mutex()
    private val pending = mutableStateMapOf<TeamSettingField, JsonElement>()
    private val textDrafts = mutableStateMapOf<TeamSettingField, TextFieldValue>()
    private var timer: Job? = null
    private var settings by mutableStateOf<JsonObject?>(null)
    var loadProblem by mutableStateOf<Problem?>(null)
        private set
    var saveProblem by mutableStateOf<Problem?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    val ready: Boolean get() = settings != null
    val hasUnsavedChanges: Boolean get() = saving || pending.isNotEmpty() ||
        textDrafts[maximumTurnsField]?.let { validTeamTurns(it.text) == null } == true

    fun value(field: TeamSettingField): JsonElement? = pending[field] ?: saved(field)

    private fun saved(field: TeamSettingField): JsonElement? =
        (if (field.prompt) settings?.obj("prompts") else settings)?.get(field.key)

    fun text(field: TeamSettingField, default: String = "") =
        textDrafts[field] ?: TextFieldValue((value(field) as? JsonPrimitive)?.contentOrNull ?: default)

    suspend fun load(api: ProjectApi) = requests.withLock {
        loadProblem = null
        try {
            val refreshed = requireNotNull(api.get("/settings").jsonObject.obj("teams"))
            val previous = textDrafts.keys.associateWith(::saved)
            settings = refreshed
            // Keep unfinished or rejected edits, including a temporarily invalid turn count.
            textDrafts.keys.filter { field ->
                field !in pending && previous[field] != saved(field) &&
                    (field != maximumTurnsField || validTeamTurns(text(field).text) != null)
            }.forEach(textDrafts::remove)
        } catch (failure: Exception) {
            loadProblem = failure.problem("nativeTeam.loadSettingsFailed")
        }
    }

    fun editText(
        api: ProjectApi, field: TeamSettingField, input: TextFieldValue, value: JsonElement?,
        default: String = "", immediate: Boolean = false,
    ) {
        val sameText = input.text == text(field, default).text
        textDrafts[field] = input
        if (!sameText || immediate) edit(api, field, value, immediate)
    }

    fun edit(api: ProjectApi, field: TeamSettingField, value: JsonElement?, immediate: Boolean = true) {
        if (value == null) pending.remove(field) else pending[field] = value
        saveProblem = null
        timer?.cancel()
        if (immediate) flush(api)
        else timer = scope.launch {
            delay(600)
            timer = null
            flush(api)
        }
    }

    fun flushOnLeave(api: ProjectApi) {
        if (timer?.isActive == true) flush(api)
    }

    fun flush(api: ProjectApi) {
        timer?.cancel()
        timer = null
        if (pending.isEmpty()) return
        scope.launch {
            requests.withLock {
                val patch = pending.toMap()
                if (patch.isEmpty()) return@withLock
                saving = true
                saveProblem = null
                try {
                    settings = requireNotNull(api.put("/settings", teamSettingsPatch(patch)).jsonObject.obj("teams"))
                    for ((field, sent) in patch) if (pending[field] == sent) {
                        pending.remove(field)
                        // A required prompt cleared in the editor resolves back to its built-in text.
                        if (sent == JsonNull) textDrafts.remove(field)
                    }
                    // §10.11: the backend derives the built-in Producer's roster responsibility from this prompt.
                    if (TeamSettingField("producer_mode", prompt = true) in patch) refreshRoster()
                } catch (failure: Exception) {
                    saveProblem = failure.problem("nativeTeam.saveFailed")
                } finally {
                    saving = false
                }
            }
        }
    }
}

internal fun validTeamTurns(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..1000 }

@Composable
internal fun TeamHelp(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = localColors.current.faint, fontSize = StudioTokens.textSmall,
        lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
}

@Composable
internal fun TeamSettingsRail(tab: ProjectTab, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val api = tab.api
    val state = tab.teamSettingsPage
    var reload by remember(tab) { mutableIntStateOf(0) }

    LaunchedEffect(api, reload) { if (api != null) state.load(api) }
    DisposableEffect(api) { onDispose { if (api != null) state.flushOnLeave(api) } }

    Column(modifier.background(colors.surface)) {
        WorkHeader {
            Text(m["work.teamSettings"], Modifier.weight(1f), fontSize = StudioTokens.titleSize, fontWeight = FontWeight.Medium)
            if (state.saving) TeamHelp(m["nativeTeam.saving"])
        }
        Rule()
        val saveProblem = state.saveProblem
        if (saveProblem != null && api != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                TeamSettingsProblem(saveProblem, !state.saving) { state.flush(api) }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(StudioTokens.panePad),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
            TeamHelp(m["work.teamSettingsDescription"])
            state.loadProblem?.let {
                TeamSettingsProblem(it) { reload++ }
            }
            if (api != null && state.ready) {
                TeamSessionDefaults(api, state)
                Rule()
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    Text(m["work.teamRequired"].uppercase(), color = colors.muted, fontWeight = FontWeight.SemiBold,
                        fontSize = StudioTokens.textSmall)
                    TeamHelp(m["work.teamRequiredHint"])
                }
                for (kind in TeamRequirement.entries) TeamRequiredMaterials(api, state, kind)
                Rule()
                TeamPromptSettings(api, state)
            } else if (state.loadProblem == null) {
                Muted(m["loading"])
            }
        }
    }
}

@Composable
private fun TeamSessionDefaults(api: ProjectApi, state: TeamSettingsPageState) {
    val m = localMessages.current
    val colors = localColors.current
    var permissions by remember(api) { mutableStateOf(emptyList<String>()) }
    var permissionsProblem by remember(api) { mutableStateOf<Problem?>(null) }
    var reload by remember(api) { mutableIntStateOf(0) }
    LaunchedEffect(api, reload) {
        permissionsProblem = null
        try {
            permissions = api.get("/teams/member-options").jsonObject.stringList("permission_modes")
        } catch (failure: Exception) {
            permissionsProblem = failure.problem("nativeTeam.loadPermissionsFailed")
        }
    }

    val mode = (state.value(permissionModeField) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val turns = state.text(maximumTurnsField)
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        val label = m["work.teamPermission"]
        TeamHelp(label)
        StudioSelect(label, mode, permissions.associateWith { it }, Modifier.fillMaxWidth(),
            enabled = permissions.isNotEmpty()) { state.edit(api, permissionModeField, JsonPrimitive(it)) }
        permissionsProblem?.let { TeamSettingsProblem(it) { reload++ } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        val label = m["work.teamMaxTurns"]
        val invalid = validTeamTurns(turns.text) == null
        TeamHelp(label)
        StudioField(label, turns, { input ->
            state.editText(api, maximumTurnsField, input, validTeamTurns(input.text)?.let(::JsonPrimitive))
        }, Modifier.fillMaxWidth(), error = invalid, errorMessage = m["nativeTeam.invalidTurns"])
        if (invalid) {
            Text(m["nativeTeam.invalidTurns"], color = colors.warning, fontSize = StudioTokens.textSmall)
        }
    }
}

private enum class TeamRequirement(val kind: String, val endpoint: String, val icon: String) {
    SKILLS("skills", "/skills", "graduation-cap"),
    DOCUMENTS("documents", "/docs", "file-text");

    val field get() = TeamSettingField("required_$kind")

    suspend fun load(api: ProjectApi): List<TeamMaterial> {
        val source = api.get(endpoint)
        return when (this) {
            SKILLS -> source.jsonObject.objects("skills").mapNotNull { skill ->
                val id = skill.string("name") ?: return@mapNotNull null
                TeamMaterial(id, skill.string("display_name")?.takeIf(String::isNotBlank) ?: id,
                    listOfNotNull(skill.string("source"), skill.string("path")).filter(String::isNotBlank).joinToString(" · "),
                    skill.string("description").orEmpty())
            }
            DOCUMENTS -> source.objects().mapNotNull { document ->
                val id = document.string("path") ?: return@mapNotNull null
                TeamMaterial(id, document.string("title")?.takeIf(String::isNotBlank) ?: id, id)
            }
        }
    }
}

private data class TeamMaterial(val id: String, val title: String, val subtitle: String, val description: String = "")

@Composable
private fun TeamRequiredMaterials(api: ProjectApi, state: TeamSettingsPageState, kind: TeamRequirement) {
    val m = localMessages.current
    val colors = localColors.current
    val prefix = "work.teamRequired.${kind.kind}"
    var open by remember(api) { mutableStateOf(false) }
    var query by remember(api) { mutableStateOf(TextFieldValue()) }
    var catalogue by remember(api) { mutableStateOf<List<TeamMaterial>?>(null) }
    var loadProblem by remember(api) { mutableStateOf<Problem?>(null) }
    var loading by remember(api) { mutableStateOf(true) }
    var reload by remember(api) { mutableIntStateOf(0) }
    LaunchedEffect(api, reload) {
        loading = true
        loadProblem = null
        try {
            catalogue = kind.load(api)
        } catch (failure: Exception) {
            loadProblem = failure.problem("nativeTeam.loadMaterialsFailed")
        } finally {
            loading = false
        }
    }

    val selected = (state.value(kind.field) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val byId = remember(catalogue) { catalogue.orEmpty().associateBy { it.id } }
    fun update(values: List<String>) = state.edit(api, kind.field, JsonArray(values.map(::JsonPrimitive)))

    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon(kind.icon, Modifier.size(StudioTokens.iconSize), colors.accent)
            Text(m[prefix], fontWeight = FontWeight.Medium)
        }
        TeamHelp(m["$prefix.hint"])
        for (id in selected) {
            val material = byId[id]
            val title = material?.title ?: id
            val missing = catalogue != null && !loading && loadProblem == null && material == null
            Row(Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(StudioTokens.radiusMd)).padding(start = StudioTokens.space2),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = StudioTokens.space1)) {
                    Text(title, fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (missing) Text(m["nativeTeam.${kind.kind}.missing"], fontSize = StudioTokens.textSmall, color = colors.warning)
                    else material?.subtitle?.takeIf { it.isNotBlank() && it != title }?.let { TeamHelp(it) }
                }
                WorkAction(m["nativeTeam.${kind.kind}.remove"].replace("{name}", title), "x", iconOnly = true, outlined = false) {
                    update(selected.filterNot { it == id })
                }
            }
        }
        WorkAction(m["nativeTeam.${kind.kind}.add"], if (open) "chevron-up" else "plus") {
            open = !open
            if (open) { query = TextFieldValue(); reload++ }
        }
        StudioDisclosure(open) {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                val label = m["nativeTeam.${kind.kind}.search"]
                LibrarySearchField(query, { query = it }, label, Modifier.fillMaxWidth())
                val terms = remember(query.text) { query.text.trim().split(Regex("\\s+")).filter(String::isNotBlank) }
                val available = remember(catalogue, selected, terms) {
                    val used = selected.toSet()
                    catalogue.orEmpty().filter { material -> material.id !in used && terms.all { word ->
                        listOf(material.id, material.title, material.subtitle, material.description).any { it.contains(word, ignoreCase = true) }
                    } }
                }
                val failure = loadProblem
                when {
                    loading -> Muted(m["loading"])
                    failure != null -> TeamSettingsProblem(failure) { reload++ }
                    available.isEmpty() -> Muted(m["nativeTeam.${kind.kind}.empty"])
                    else -> {
                        val lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier
                        val resultHeight = with(LocalDensity.current) { (lineHeight * 2).toDp() } + StudioTokens.space4
                        ScrollList(Modifier.fillMaxWidth().height(resultHeight * available.size.coerceAtMost(5))) {
                            items(available, key = { it.id }) { material ->
                                StudioSelectionRow(false, { update(selected + material.id); open = false },
                                    Modifier.fillMaxWidth().heightIn(min = resultHeight), role = Role.Button) {
                                    Column {
                                        Text(material.title, fontSize = StudioTokens.textSmall,
                                            lineHeight = lineHeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (material.subtitle.isNotBlank() && material.subtitle != material.title) {
                                            Text(material.subtitle, color = colors.faint, fontSize = StudioTokens.textSmall,
                                                lineHeight = lineHeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}

private fun promptGroup(key: String): String = when {
    key == "identity" || key.startsWith("identity_") -> "identity"
    key.startsWith("chat_") -> "chat"
    key.startsWith("producer_") -> "producer"
    key in setOf("resume", "resume_notes", "review_changes", "stall_continue", "mid_run_message", "result_required", "task_compact") -> "openers"
    key in setOf("task_context", "task_temp", "studio_skill", "no_studio_tools", "member_skills", "docs_location", "project_memory",
        "subagents", "code_graph", "isolated_checkout", "outside_project", "finish", "finish_tool") -> "system"
    else -> "extra"
}

@Composable
private fun TeamPromptSettings(api: ProjectApi, state: TeamSettingsPageState) {
    val m = localMessages.current
    var specs by remember(api) { mutableStateOf<JsonObject?>(null) }
    var problem by remember(api) { mutableStateOf<Problem?>(null) }
    var reload by remember(api) { mutableIntStateOf(0) }
    LaunchedEffect(api, reload) {
        problem = null
        try {
            specs = api.get("/settings/prompts").jsonObject
        } catch (failure: Exception) {
            problem = failure.problem("nativeTeam.loadPromptsFailed")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Text(m["work.teamPrompts"], fontWeight = FontWeight.Medium)
        TeamHelp(m["work.teamPromptsHint"])
        val currentProblem = problem
        if (currentProblem != null) TeamSettingsProblem(currentProblem) { reload++ }
        val groups = remember(specs) { specs.orEmpty().entries.groupBy { promptGroup(it.key) } }
        if (specs == null && currentProblem == null) Muted(m["loading"])
        for (group in listOf("identity", "system", "openers", "chat", "producer", "extra")) {
            val entries = groups[group] ?: continue
            Text(m["nativeTeam.prompts.group.$group"], color = localColors.current.muted, fontSize = StudioTokens.textSmall)
            for ((id, value) in entries) key(id) {
                TeamPromptFragment(api, state, id, value.jsonObject)
            }
        }
    }
}

@Composable
private fun TeamPromptFragment(api: ProjectApi, state: TeamSettingsPageState, id: String, spec: JsonObject) {
    val m = localMessages.current
    val colors = localColors.current
    val label = remember(m, id) { runCatching { m["nativeTeam.prompts.fragment.$id"] }.getOrDefault(id) }
    val hint = remember(m, id) { runCatching { m["nativeTeam.prompts.hint.$id"] }.getOrNull() }
    val field = TeamSettingField(id, prompt = true)
    val default = spec.string("default").orEmpty()
    val required = spec.flag("required")
    val value = state.text(field, default)
    val override = (state.value(field) as? JsonPrimitive)?.contentOrNull
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.inset).border(StudioTokens.borderWidth, colors.border, shape)) {
        Row(Modifier.fillMaxWidth().studioFocusRing(shape, background = colors.inset)
            .clickable(role = Role.Button) { expanded = !expanded }
            .heightIn(min = StudioTokens.controlMinHeight).padding(StudioTokens.space2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            StudioIcon(if (expanded) "chevron-up" else "chevron-down", Modifier.size(StudioTokens.compactIconSize), colors.muted)
            Text(label, Modifier.weight(1f), fontSize = StudioTokens.textSmall)
            override?.let {
                Text(m[if (it.isBlank()) "nativeTeam.prompts.removed" else "nativeTeam.prompts.edited"],
                    color = if (it.isBlank()) colors.warning else colors.accent, fontSize = StudioTokens.textTiny)
            }
        }
        StudioDisclosure(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = StudioTokens.space2, end = StudioTokens.space2, bottom = StudioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                Rule()
                hint?.let { TeamHelp(it) }
                val placeholders = spec.stringList("placeholders").joinToString(", ") { "{$it}" }
                if (placeholders.isNotEmpty()) TeamHelp(m["nativeTeam.prompts.variables"].replace("{names}", placeholders))
                TextArea(value, { input ->
                    val body = input.text
                    val change = if (body == default || required && body.isBlank()) JsonNull
                        else JsonPrimitive(if (body.isBlank()) "" else body)
                    state.editText(api, field, input, change, default)
                }, modifier = Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = label })
                if (override != null || value.text != default) {
                    WorkAction(m["nativeTeam.prompts.reset"], "refresh-cw") {
                        state.editText(api, field, TextFieldValue(default), JsonNull, default, immediate = true)
                    }
                }
                TeamHelp(m[if (required) "nativeTeam.prompts.required" else "nativeTeam.prompts.optional"])
            }
        }
    }
}

@Composable
private fun TeamSettingsProblem(problem: Problem, enabled: Boolean = true, retry: () -> Unit) {
    ProblemText(problem)
    WorkAction(localMessages.current["retry"], "refresh-cw", enabled = enabled, onClick = retry)
}
