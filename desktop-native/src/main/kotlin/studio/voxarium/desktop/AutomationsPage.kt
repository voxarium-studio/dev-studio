package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.*

private const val AUTOMATIONS_PATH = "/automations"
private const val AUTOMATIONS_FILE = ".voxarium_studio/automations.yaml"
private val automationChanges = listOf("added", "modified", "deleted")
private val automationStatuses = listOf("todo", "pending", "in_progress", "needs_review", "blocked", "done", "cancelled")
private val categories = linkedMapOf(
    "task" to listOf("task.status_changed", "task.created"),
    "directory" to listOf("path.changed"),
    "pipeline" to listOf("run.finished"),
)
private val triggerLabels = mapOf(
    "task.status_changed" to "statusChanged", "task.created" to "created",
    "path.changed" to "filesChanged", "run.finished" to "runFinished",
)
private val actionLabels = linkedMapOf("notify" to "notify", "pipeline.run" to "runPipeline", "plugin.action" to "plugin")
private val notificationPlaceholders = mapOf(
    "task.status_changed" to listOf("task", "title", "status", "previous", "assignee"),
    "task.created" to listOf("task", "title", "assignee"),
    "path.changed" to listOf("path", "paths", "count", "folder"),
    "run.finished" to listOf("pipeline", "outcome", "error"),
)

private fun JsonObject.kind() = string("kind").orEmpty()
private fun JsonObject.changes() = (this["changes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.replacing(key: String, value: JsonElement) = JsonObject(this + (key to value))
private fun triggerCategory(kind: String) = categories.entries.first { kind in it.value }.key
private fun Messages.automation(key: String, vararg values: Pair<String, Any>): String =
    values.fold(this["automations.$key"]) { message, (name, value) -> message.replace("{$name}", value.toString()) }

private fun blankTrigger(kind: String) = buildJsonObject {
    put("kind", kind)
    when (kind) {
        "task.status_changed" -> put("status", "needs_review")
        "path.changed" -> {
            put("path", "")
            put("pattern", "")
            put("changes", JsonArray(automationChanges.map(::JsonPrimitive)))
        }
        "run.finished" -> put("outcome", "any")
    }
}

// §6.5: only explicit Save changes project rules. Keep the mutable draft in the tab across transfers.
internal class AutomationDraft(source: JsonObject?) {
    val id = source?.string("id")
    var name by mutableStateOf(TextFieldValue(source?.string("name").orEmpty()))
    var enabled by mutableStateOf(source?.flag("enabled") ?: true)
    var trigger by mutableStateOf(source?.obj("trigger")?.let { stored ->
        val path = stored.string("path").orEmpty()
        if (stored.kind() == "path.changed" && path.isNotEmpty()) stored.replacing("path", JsonPrimitive("./$path/")) else stored
    } ?: blankTrigger("task.status_changed"))
        private set
    var action by mutableStateOf(source?.obj("action") ?: jsonFields("kind" to "notify", "title" to "", "message" to ""))
        private set
    var saveProblem by mutableStateOf<Problem?>(null)
    private val original = input()
    private val triggers = mutableMapOf<String, JsonObject>()
    private val previousKind = mutableMapOf<String, String>()
    val changed get() = input() != original

    fun setCategory(category: String) {
        if (triggerCategory(trigger.kind()) != category) {
            setTriggerKind(previousKind[category] ?: categories.getValue(category).first())
        }
    }

    fun setTriggerKind(kind: String) {
        val previous = trigger.kind()
        if (previous == kind) return
        triggers[previous] = trigger
        previousKind[triggerCategory(previous)] = previous
        trigger = triggers[kind] ?: blankTrigger(kind)
    }

    fun setTrigger(key: String, value: JsonElement) {
        trigger = trigger.replacing(key, value)
    }

    fun setAction(key: String, value: JsonElement) {
        action = action.replacing(key, value)
    }

    fun setActionKind(kind: String, pipelines: List<JsonObject>, offered: List<JsonObject>) {
        if (action.kind() == kind) return
        action = buildJsonObject {
            put("kind", kind)
            when (kind) {
                "notify" -> {
                    put("title", "")
                    put("message", "")
                }
                "pipeline.run" -> {
                    put("pipeline_id", pipelines.firstOrNull()?.string("id").orEmpty())
                    put("use_cache", true)
                }
                "plugin.action" -> {
                    put("plugin_id", offered.firstOrNull()?.string("plugin_id").orEmpty())
                    put("action_id", offered.firstOrNull()?.string("action_id").orEmpty())
                }
            }
        }
    }

    fun validate(): String? = when {
        trigger.kind() == "path.changed" && normalizeAutomationPath(trigger.string("path").orEmpty()) == null -> "pathOutside"
        trigger.kind() == "path.changed" && trigger.changes().isEmpty() -> "noChanges"
        action.kind() == "pipeline.run" && action.string("pipeline_id").isNullOrBlank() -> "noPipeline"
        action.kind() == "plugin.action" && (action.string("plugin_id").isNullOrBlank() || action.string("action_id").isNullOrBlank()) -> "noPluginAction"
        else -> null
    }

    fun input() = buildJsonObject {
        put("name", name.text.trim())
        put("enabled", enabled)
        val outgoing = if (trigger.kind() == "path.changed") {
            val path = trigger.string("path").orEmpty()
            trigger.replacing("path", JsonPrimitive(normalizeAutomationPath(path) ?: path))
                .replacing("pattern", JsonPrimitive(trigger.string("pattern").orEmpty().trim()))
        } else trigger
        put("trigger", outgoing)
        put("action", action)
    }
}

private val drivePrefix = Regex("^[A-Za-z]:")

internal fun normalizeAutomationPath(value: String): String? {
    val path = value.trim().replace('\\', '/')
    if (path.startsWith('/') || drivePrefix.containsMatchIn(path)) return null
    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
    if (".." in parts) return null
    return parts.joinToString("/")
}

internal class AutomationsPageState {
    var overview by mutableStateOf<JsonObject?>(null)
        private set
    var pipelines by mutableStateOf<List<JsonObject>>(emptyList())
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var draft by mutableStateOf<AutomationDraft?>(null)
        private set
    val hasUnsavedChanges get() = draft?.changed == true
    private val requests = Mutex()
    private var currentApi: ProjectApi? = null

    fun bind(api: ProjectApi) {
        if (currentApi == api) return
        currentApi = api
        overview = null
        pipelines = emptyList()
        loading = true
        problem = null
    }

    suspend fun refresh(api: ProjectApi) = requests.withLock {
        try {
            val snapshot = api.get(AUTOMATIONS_PATH).jsonObject
            if (api == currentApi) {
                overview = snapshot
                problem = null
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (api == currentApi) problem = error.problem()
        } finally {
            if (api == currentApi) loading = false
        }
    }

    suspend fun loadCatalogue(api: ProjectApi) {
        try {
            val result = api.get("/pipelines").objects()
            if (api == currentApi) pipelines = result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // The definitions and history still display pipeline IDs if names are unavailable.
        }
    }

    fun edit(source: JsonObject?) {
        if (!busy) draft = AutomationDraft(source)
    }

    fun closeDraft() {
        if (!busy) draft = null
    }

    suspend fun save(api: ProjectApi, draft: AutomationDraft) {
        if (draft.validate() != null || this.draft !== draft) return
        val input = draft.input()
        write(api, draft) {
            val result = draft.id?.let { api.put("$AUTOMATIONS_PATH/${segment(it)}", input) }
                ?: api.post(AUTOMATIONS_PATH, input)
            if (api == currentApi) this.draft = null
            result
        }
    }

    suspend fun runNow(api: ProjectApi, id: String) = write(api) {
        val run = requireNotNull(api.post("$AUTOMATIONS_PATH/${segment(id)}/run").jsonObject.obj("run"))
        val snapshot = requireNotNull(overview)
        val history = snapshot.objects("runs").filterNot { it.string("id") == run.string("id") }
        snapshot.replacing("runs", JsonArray((listOf(run) + history).take(50)))
    }

    suspend fun write(api: ProjectApi, draft: AutomationDraft? = null, action: suspend () -> JsonElement) {
        if (busy || api != currentApi) return
        busy = true
        problem = null
        draft?.saveProblem = null
        try {
            // §6.5: write snapshots contain the whole file and log. Do not adopt a read that started before a write after it.
            requests.withLock {
                if (api == currentApi) {
                    val next = action().jsonObject
                    if (api == currentApi) overview = next
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (api == currentApi) {
                if (draft == null) problem = error.problem() else draft.saveProblem = error.problem()
            }
        } finally {
            busy = false
        }
    }
}

@Composable
internal fun AutomationsPage(tab: ProjectTab, api: ProjectApi, m: Messages, navigate: (Page) -> Unit = tab::navigate) {
    val state = tab.automationsPage
    LaunchedEffect(api) {
        state.bind(api)
        launch { state.loadCatalogue(api) }
        snapshotFlow { tab.pageRefresh }.conflate().collect { refresh ->
            state.refresh(api)
            if (refresh != 0L) delay(300)
        }
    }
    val scroll = rememberScrollState()
    val overview = state.overview
    val path = overview?.string("path") ?: AUTOMATIONS_FILE
    val list = overview?.objects("automations").orEmpty()
    val history = overview?.objects("runs").orEmpty()
    val offered = overview?.objects("plugin_actions").orEmpty()
    val catalogue = AutomationCatalogue(state.pipelines, offered)
    var confirming by remember(api) { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val subtitle = if (overview != null && !overview.flag("exists")) m.automation("notCreated", "path" to path) else path
            LibraryHeader(m["automations"], subtitle) {
                LibraryButton("refresh-cw", m["refresh"], !state.busy && !state.loading) {
                    tab.scope.launch { state.refresh(api) }
                }
            }
            Column(Modifier.widthIn(max = 1050.dp).fillMaxWidth().padding(StudioTokens.panePad), verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                state.problem?.let { ProblemText(it) }
                if (state.loading && overview == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                        StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                        Muted(m["loading"])
                    }
                }
                overview?.let { snapshot ->
                    WorkExplanation(m.automation("intro"), modifier = Modifier.studioArrival())
                    val parseError = snapshot.string("error")
                    Column(Modifier.fillMaxWidth().studioArrival(delay = StudioMotion.stagger),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                        if (parseError != null) {
                            LibraryCard {
                                LibrarySectionTitle(m.automation("parseError.title"))
                                Text(parseError, color = localColors.current.danger)
                                Muted(m.automation("parseError.hint", "file" to path))
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LibrarySectionTitle(m.automation("list.title"), Modifier.weight(1f))
                                LibraryButton("plus", m.automation("list.add"), !state.busy) { state.edit(null) }
                            }
                            if (list.isEmpty()) LibraryEmpty("zap", m.automation("list.emptyTitle"), m.automation("list.emptyHint"))
                            val lastRuns = history.distinctBy { it.string("automation_id") }.associateBy { it.string("automation_id") }
                            list.forEach { automation ->
                                val id = automation.string("id") ?: return@forEach
                                key(id) {
                                    AutomationCard(automation, lastRuns[id], catalogue, m, !state.busy,
                                        onRun = { tab.scope.launch { state.runNow(api, id) } },
                                        onToggle = {
                                            tab.scope.launch {
                                                state.write(api) {
                                                    api.patch("$AUTOMATIONS_PATH/${segment(id)}", buildJsonObject {
                                                        put("enabled", !automation.flag("enabled"))
                                                    })
                                                }
                                            }
                                        }, onEdit = { state.edit(automation) }, onRemove = { confirming = id })
                                }
                            }
                            snapshot.objects("invalid").forEach { entry ->
                                val index = (entry.string("index")?.toIntOrNull() ?: 0) + 1
                                LibraryCard {
                                    LibrarySectionTitle(entry.string("id") ?: m.automation("list.entryNumber", "index" to index))
                                    Text(entry.string("error").orEmpty(), color = localColors.current.danger)
                                    Muted(m.automation("list.notRun"))
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().studioArrival(delay = StudioMotion.stagger * 2),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                        AutomationHistory(history, list, catalogue, m, !state.busy,
                            onClear = { confirming = "" },
                            onPipeline = { navigate(Page("/pipelines/${segment(it)}")) })
                    }
                }
            }
        }
        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    state.draft?.let { draft ->
        AutomationEditor(draft, catalogue, m, state.busy, state::closeDraft,
            onSave = { tab.scope.launch { state.save(api, draft) } })
    }
    confirming?.let { id ->
        val title = if (id.isEmpty()) m.automation("log.clearConfirm") else m.automation("list.confirmDelete")
        val action = if (id.isEmpty()) m.automation("log.clear") else m["work.delete"]
        AutomationConfirm(title, action, m, onClose = { confirming = null }) {
            confirming = null
            tab.scope.launch {
                val target = when (id) {
                    "" -> "runs"
                    // §6.5: the short URL for a hand-authored `runs` rule is the activity log.
                    "runs" -> "runs/definition"
                    else -> segment(id)
                }
                state.write(api) { api.delete("$AUTOMATIONS_PATH/$target") }
            }
        }
    }
}

private data class AutomationCatalogue(val pipelines: List<JsonObject>, val actions: List<JsonObject>) {
    fun pipelineName(id: String) = pipelines.firstOrNull { it.string("id") == id }?.string("name")?.takeIf(String::isNotBlank) ?: id

    fun pluginAction(action: JsonObject) = actions.firstOrNull {
        it.string("plugin_id") == action.string("plugin_id") && it.string("action_id") == action.string("action_id")
    }
}

@Composable
private fun AutomationCard(
    automation: JsonObject, lastRun: JsonObject?, catalogue: AutomationCatalogue, m: Messages, enabled: Boolean,
    onRun: () -> Unit, onToggle: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit,
) {
    val colors = localColors.current
    val active = automation.flag("enabled")
    LibraryCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            StudioIcon("zap", Modifier.size(StudioTokens.iconSize), if (active) colors.accent else colors.faint)
            val name = automation.string("name")?.takeIf(String::isNotBlank)
            val sentence = automationSentence(automation, catalogue, m)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                LibrarySectionTitle(name ?: sentence)
                if (name != null) Muted(sentence)
            }
            if (!active) WorkStatus("disabled")
        }
        val problem = automation.string("problem")
        if (problem != null) Text(problem, color = colors.danger)
        else if (lastRun == null) Muted(m.automation("list.neverRan"))
        else Muted(m.automation(if (lastRun.string("status") == "running") "list.running" else "list.lastRun",
            "when" to runTime(lastRun, m), "cause" to runCause(lastRun, m)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            LibraryButton("play", m.automation("list.runNow"), enabled, onRun)
            LibraryIconAction("pencil", m["referenceEdit"], enabled = enabled, onClick = onEdit)
            LibraryIconAction("power", m[if (active) "work.disable" else "work.enable"], enabled = enabled, onClick = onToggle)
            LibraryIconAction("trash-2", m["work.delete"], enabled = enabled, onClick = onRemove)
        }
    }
}

@Composable
private fun AutomationHistory(
    history: List<JsonObject>, automations: List<JsonObject>, catalogue: AutomationCatalogue, m: Messages,
    enabled: Boolean, onClear: () -> Unit, onPipeline: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LibrarySectionTitle(m.automation("log.title"), Modifier.weight(1f))
        if (history.isNotEmpty()) LibraryButton("trash-2", m.automation("log.clear"), enabled, onClear)
    }
    if (history.isEmpty()) Muted(m.automation("log.empty"))
    val byId = automations.associateBy { it.string("id") }
    for (run in history) key(run.string("id")) {
        val data = run.obj("data")
        val live = byId[run.string("automation_id")]
        val name = sequenceOf(live?.string("name"), run.string("automation_name")).filterNotNull().firstOrNull(String::isNotBlank)
            ?: (live ?: data?.obj("definition"))?.let { automationSentence(it, catalogue, m, includeSubject = false) }
            ?: run.string("automation_id").orEmpty()
        LibraryCard(gap = StudioTokens.space1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                LibraryCaption(runTime(run, m), mono = true)
                LibrarySectionTitle(name, Modifier.weight(1f))
                val status = run.string("status").orEmpty()
                WorkStatus(when (status) { "ok" -> "done"; "error" -> "failed"; "running" -> "in_progress"; else -> status })
            }
            val detail = runDetail(run, catalogue, m)
            Muted(runCause(run, m))
            val pipeline = data?.string("pipeline_id")
            if (pipeline != null) Link(detail.ifBlank { catalogue.pipelineName(pipeline) }, onClick = { onPipeline(pipeline) })
            else if (detail.isNotBlank()) Muted(detail)
        }
    }
}

private fun runTime(run: JsonObject, m: Messages) = libraryTime(run.string("created_at"), m.language)

private fun fileCountForm(count: Int, language: String) = when {
    language != "ru" -> "other"
    count % 100 in 11..14 -> "many"
    count % 10 == 1 -> "one"
    count % 10 in 2..4 -> "few"
    else -> "many"
}

private fun runCause(run: JsonObject, m: Messages): String {
    val data = run.obj("data")
    val values = data?.obj("values")
    val task = values?.string("task")
    val fallback = run.string("cause").orEmpty()
    return when (data?.string("trigger")) {
        "manual" -> m.automation("log.manual")
        "task.status_changed" -> task?.let { "$it → ${m.status(values.string("status").orEmpty())}" } ?: fallback
        "task.created" -> task?.let { m.automation("notification.createdTitle", "task" to it) } ?: fallback
        "path.changed" -> {
            val count = values?.string("count")?.toIntOrNull() ?: 1
            if (count > 1) m.automation("log.filesUnder.${fileCountForm(count, m.language)}", "count" to count,
                "folder" to (values?.string("folder") ?: "./"))
            else values?.string("path") ?: fallback
        }
        "run.finished" -> {
            val key = when (values?.string("outcome")) {
                "success" -> "runSucceeded"
                "failed" -> "runFailed"
                else -> "runCancelled"
            }
            m.automation("notification.$key", "pipeline" to values?.string("pipeline").orEmpty())
        }
        else -> fallback
    }
}

private fun runDetail(run: JsonObject, catalogue: AutomationCatalogue, m: Messages): String {
    val data = run.obj("data")
    val status = run.string("status")
    val fallback = run.string("detail").orEmpty()
    if (status == "skipped") return when (data?.string("guard")) {
        "flood" -> m.automation("log.guardFlood")
        "chain" -> m.automation("log.guardChain")
        else -> fallback
    }
    if (data?.flag("interrupted") == true) return m.automation("log.interrupted")
    val pipeline = data?.string("pipeline_id")
    if (data?.string("action") == "pipeline.run" && pipeline != null) {
        val key = when (status) {
            "running" -> "pipelineRunning"
            "ok" -> "pipelineFinished"
            "cancelled" -> "pipelineCancelled"
            else -> return fallback
        }
        return m.automation("log.$key", "pipeline" to catalogue.pipelineName(pipeline))
    }
    if (data?.string("action") == "notify" && status == "ok") return m.automation("log.notified")
    return fallback.takeIf { status != "running" }.orEmpty()
}

private fun automationSentence(automation: JsonObject, catalogue: AutomationCatalogue, m: Messages, includeSubject: Boolean = true): String {
    val trigger = automation.obj("trigger") ?: return ""
    val action = automation.obj("action") ?: return ""
    return listOfNotNull(
        trigger.kind().takeIf { includeSubject && it in triggerLabels }?.let { m.automation("subject.${triggerCategory(it)}") },
        triggerText(trigger, catalogue, m), actionText(action, catalogue, m),
    ).joinToString(" → ")
}

private fun triggerText(trigger: JsonObject, catalogue: AutomationCatalogue, m: Messages): String = when (trigger.kind()) {
    "task.status_changed" -> trigger.string("status")?.let { m.automation("sentence.statusTo", "status" to m.status(it)) }
        ?: m.automation("sentence.statusAny")
    "task.created" -> m.automation("sentence.taskCreated")
    "path.changed" -> {
        val path = trigger.string("path").orEmpty().takeIf(String::isNotBlank)?.let { "./$it/" } ?: "./"
        m.automation("sentence.pathUpdated", "path" to (path + trigger.string("pattern").orEmpty()))
    }
    "run.finished" -> {
        val pipeline = trigger.string("pipeline_id")?.let(catalogue::pipelineName) ?: m.automation("sentence.anyPipeline")
        val outcome = when (trigger.string("outcome")) {
            "success" -> "runSucceeded"
            "failed" -> "runFailed"
            else -> "runFinished"
        }
        m.automation("sentence.$outcome", "pipeline" to pipeline)
    }
    else -> trigger.kind()
}

private fun actionText(action: JsonObject, catalogue: AutomationCatalogue, m: Messages): String = when (action.kind()) {
    "notify" -> m.automation("sentence.notify")
    "pipeline.run" -> m.automation("sentence.runPipeline", "pipeline" to catalogue.pipelineName(action.string("pipeline_id").orEmpty()))
    "plugin.action" -> catalogue.pluginAction(action)?.string("label") ?: pluginActionId(action)
    else -> action.kind()
}

private fun pluginActionId(action: JsonObject) = listOfNotNull(action.string("plugin_id"), action.string("action_id")).joinToString(".")

@Composable
private fun AutomationEditor(
    draft: AutomationDraft, catalogue: AutomationCatalogue, m: Messages, busy: Boolean,
    onClose: () -> Unit, onSave: () -> Unit,
) {
    val colors = localColors.current
    val title = m.automation(if (draft.id == null) "form.addTitle" else "form.editTitle")
    DialogWindow(onCloseRequest = onClose, title = title,
        state = rememberDialogState(width = 760.dp, height = 620.dp, position = WindowPosition.Aligned(Alignment.Center))) {
        Column(Modifier.fillMaxSize().background(colors.elevated)
            .studioArrival()) {
            LibraryHeader(title, null)
            Rule()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(StudioTokens.space4),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                AutomationField(m.automation("form.name"), draft.name, { draft.name = it }, !busy, maxLength = 120,
                    placeholder = m.automation("form.namePlaceholder"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                    Column(Modifier.weight(1f)) {
                        LibraryCard {
                            LibrarySectionTitle(m.automation("form.when"))
                            AutomationTriggerForm(draft, catalogue, m, !busy)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        LibraryCard {
                            LibrarySectionTitle(m.automation("form.then"))
                            AutomationActionForm(draft, catalogue, m, !busy)
                        }
                    }
                }
                LibraryCard { Text(automationSentence(draft.input(), catalogue, m)) }
                WorkCheckbox(m.automation("form.enabled"), draft.enabled, !busy) { draft.enabled = it }
            }
            Rule()
            Row(Modifier.fillMaxWidth().padding(StudioTokens.space4), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                val validation = draft.validate()
                Column(Modifier.weight(1f)) {
                    draft.saveProblem?.let { ProblemText(it) }
                    if (draft.saveProblem == null && validation != null) Muted(m.automation("form.$validation"))
                }
                WorkAction(m["cancel"], null, onClick = onClose, enabled = !busy)
                WorkAction(m["work.save"], null, primary = true, onClick = onSave, enabled = !busy && validation == null)
            }
        }
    }
}

@Composable
private fun AutomationTriggerForm(draft: AutomationDraft, catalogue: AutomationCatalogue, m: Messages, enabled: Boolean) {
    val trigger = draft.trigger
    val kind = trigger.kind()
    val category = triggerCategory(kind)
    AutomationChoice(m.automation("form.category"), category,
        categories.keys.map { it to m.automation("trigger.category.$it") }, enabled, onChange = draft::setCategory)
    AutomationChoice(m.automation("form.event"), kind,
        categories.getValue(category).map { it to m.automation("trigger.event.${triggerLabels.getValue(it)}") }, enabled,
        onChange = draft::setTriggerKind)
    key(kind) {
        when (kind) {
            "task.status_changed" -> AutomationChoice(m.automation("form.statusTo"), trigger.string("status").orEmpty(),
                listOf("" to m.automation("form.anyStatus")) + automationStatuses.map { it to m.status(it) }, enabled) {
                draft.setTrigger("status", if (it.isEmpty()) JsonNull else JsonPrimitive(it))
            }
            "path.changed" -> {
                AutomationRememberedField(m.automation("form.path"), trigger.string("path").orEmpty(), enabled, placeholder = "./models/") {
                    draft.setTrigger("path", JsonPrimitive(it))
                }
                AutomationRememberedField(m.automation("form.pattern"), trigger.string("pattern").orEmpty(), enabled, placeholder = "*.fbx") {
                    draft.setTrigger("pattern", JsonPrimitive(it))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    val selected = trigger.changes().toSet()
                    automationChanges.forEach { change ->
                        WorkCheckbox(m.automation("change.$change"), change in selected, enabled) { checked ->
                            val next = if (checked) selected + change else selected - change
                            draft.setTrigger("changes", JsonArray(automationChanges.filter(next::contains).map(::JsonPrimitive)))
                        }
                    }
                }
                Muted(m.automation("form.pathHint"))
            }
            "run.finished" -> {
                val pipeline = trigger.string("pipeline_id").orEmpty()
                val choices = listOf("" to m.automation("form.anyPipeline")) + pipelineChoices(catalogue, pipeline)
                AutomationChoice(m.automation("form.pipeline"), pipeline, choices, enabled) {
                    draft.setTrigger("pipeline_id", if (it.isEmpty()) JsonNull else JsonPrimitive(it))
                }
                AutomationChoice(m.automation("form.outcome"), trigger.string("outcome") ?: "any", listOf(
                    "any" to m.automation("form.outcomeAny"), "success" to m.automation("form.outcomeSuccess"),
                    "failed" to m.automation("form.outcomeFailed"),
                ), enabled) { draft.setTrigger("outcome", JsonPrimitive(it)) }
            }
        }
    }
}

@Composable
private fun AutomationActionForm(draft: AutomationDraft, catalogue: AutomationCatalogue, m: Messages, enabled: Boolean) {
    val action = draft.action
    val kind = action.kind()
    val choices = actionLabels.filterKeys { it != "plugin.action" || kind == it || catalogue.actions.isNotEmpty() }
        .map { (id, label) -> id to m.automation("action.$label") }
    AutomationChoice(m.automation("form.then"), kind, choices, enabled) {
        draft.setActionKind(it, catalogue.pipelines, catalogue.actions)
    }
    key(kind) {
        when (kind) {
            "notify" -> {
                val placeholder = m.automation("form.notifyAuto")
                AutomationRememberedField(m.automation("form.notifyTitle"), action.string("title").orEmpty(), enabled, 120, placeholder) {
                    draft.setAction("title", JsonPrimitive(it))
                }
                AutomationRememberedField(m.automation("form.notifyMessage"), action.string("message").orEmpty(), enabled, 2000,
                    placeholder, multiline = true) { draft.setAction("message", JsonPrimitive(it)) }
                val placeholders = notificationPlaceholders.getValue(draft.trigger.kind()).joinToString(" ") { "{$it}" }
                Muted("${m.automation("form.placeholders")} $placeholders")
            }
            "pipeline.run" -> {
                val id = action.string("pipeline_id").orEmpty()
                val pipelines = pipelineChoices(catalogue, id)
                val emptyLabel = if (pipelines.isEmpty()) "noPipelines" else "noPipeline"
                AutomationChoice(m.automation("form.pipeline"), id, pipelines, enabled, fallback = m.automation("form.$emptyLabel")) {
                    draft.setAction("pipeline_id", JsonPrimitive(it))
                }
                WorkCheckbox(m.automation("form.useCache"), action.flag("use_cache"), enabled) { draft.setAction("use_cache", JsonPrimitive(it)) }
                if (draft.trigger.kind() == "path.changed") Muted(m.automation("form.loopHint"))
            }
            "plugin.action" -> {
                val selected = action.pluginKey()
                val offered = catalogue.actions.mapNotNull { entry ->
                    entry.pluginKey()?.let { id -> id to (entry.string("label") ?: pluginActionId(entry)) }
                }
                AutomationChoice(m.automation("form.pluginAction"), selected, offered, enabled,
                    fallback = pluginActionId(action).ifBlank { m.automation("form.noPluginAction") }) { id ->
                    draft.setAction("plugin_id", JsonPrimitive(id.first))
                    draft.setAction("action_id", JsonPrimitive(id.second))
                }
                catalogue.pluginAction(action)?.string("description")?.let { Muted(it) }
            }
        }
    }
}

private fun JsonObject.pluginKey(): Pair<String, String>? {
    val plugin = string("plugin_id") ?: return null
    val action = string("action_id") ?: return null
    return plugin to action
}

private fun pipelineChoices(catalogue: AutomationCatalogue, selected: String): List<Pair<String, String>> {
    val available = catalogue.pipelines.mapNotNull { it.string("id") }.map { it to catalogue.pipelineName(it) }
    return if (selected.isNotEmpty() && available.none { it.first == selected }) listOf(selected to selected) + available else available
}

@Composable
private fun <T> AutomationChoice(
    label: String, selected: T?, options: List<Pair<T, String>>, enabled: Boolean, fallback: String = "", onChange: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Muted(label)
        val values = buildMap<T?, String> { options.forEach { (key, title) -> put(key, title) } }
        StudioChoice(label, selected, values, fallback, enabled = enabled) { picked ->
            options.firstOrNull { it.first == picked }?.let { onChange(it.first) }
        }
    }
}

@Composable
private fun AutomationRememberedField(
    label: String, initial: String, enabled: Boolean, maxLength: Int? = null, placeholder: String? = null,
    multiline: Boolean = false, onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial)) }
    AutomationField(label, text, {
        text = it
        onChange(it.text)
    }, enabled, maxLength, placeholder, multiline)
}

@Composable
private fun AutomationField(
    label: String, text: TextFieldValue, onChange: (TextFieldValue) -> Unit, enabled: Boolean,
    maxLength: Int? = null, placeholder: String? = null, multiline: Boolean = false,
) {
    val update: (TextFieldValue) -> Unit = { if (maxLength == null || it.text.length <= maxLength) onChange(it) }
    val hint: (@Composable () -> Unit)? = placeholder?.let { { Muted(it) } }
    val field = Modifier.fillMaxWidth().semantics { contentDescription = label }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Muted(label)
        if (multiline) TextArea(text, update, modifier = field.heightIn(min = 72.dp, max = 140.dp),
            enabled = enabled, placeholder = hint)
        else StudioField(label, text, update, Modifier.fillMaxWidth(), enabled = enabled, placeholder = placeholder)
    }
}

@Composable
private fun AutomationConfirm(title: String, action: String, m: Messages, onClose: () -> Unit, onConfirm: () -> Unit) {
    val colors = localColors.current
    DialogWindow(onCloseRequest = onClose, title = title, resizable = false,
        state = rememberDialogState(width = 420.dp, height = 156.dp, position = WindowPosition.Aligned(Alignment.Center))) {
        Column(Modifier.fillMaxSize().background(colors.elevated)
            .studioArrival().padding(StudioTokens.space4),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
            LibrarySectionTitle(title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End)) {
                WorkAction(m["cancel"], null, onClick = onClose)
                WorkAction(action, null, primary = true, onClick = onConfirm)
            }
        }
    }
}
