package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

private val graphifyDocumentBackend = choice("documents_backend", "fields.documentsModel", "claude_code", "codex", "qwen")
private val graphifyDocumentModel = PluginField("documents_model", "fields.documentsModel")
private val graphifyLanguage = choice("documents_language", "fields.documentsLanguage", "source", "english", labelled = true)
internal val graphifyFields = listOf(
    PluginField("executable", "fields.executable", placeholder = "graphify"),
    PluginField("path", "fields.path", placeholder = "."),
    toggle("index_on_open", "fields.indexOnOpen", true),
    toggle("index_on_task_done", "fields.indexOnTaskDone", true),
    toggle("documents", "fields.documents", true),
    graphifyDocumentBackend, graphifyDocumentModel, graphifyLanguage,
)

@Composable
internal fun GraphifySettingsPage(api: ProjectApi, title: String, state: PluginSettingsState, words: PluginWords,
                                  retry: () -> Unit, save: (() -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val m = localMessages.current
    val lock = remember(api) { Mutex() }
    var status by remember(api) { mutableStateOf<JsonObject?>(null) }
    var options by remember(api) { mutableStateOf<JsonObject?>(null) }
    var problem by remember(api) { mutableStateOf<Problem?>(null) }
    var busy by remember(api) { mutableStateOf(false) }
    val run = status?.obj("indexer")
    val running = run?.flag("running") == true
    var reset by remember(api) { mutableStateOf(false) }

    suspend fun refresh(showError: Boolean = true) = lock.withLock {
        try {
            status = api.post("/plugins/graphify/actions/status").jsonObject
            problem = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (showError || status == null) problem = error.problem()
        }
    }

    fun action(name: String, body: JsonObject = JsonObject(emptyMap())) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                lock.withLock {
                    val indexer = api.post("/plugins/graphify/actions/$name", body).jsonObject
                    status = JsonObject(status.orEmpty() + ("indexer" to indexer))
                    problem = null
                }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                problem = error.problem()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(api) {
        refresh()
        while (true) {
            delay(if (status?.obj("indexer")?.flag("running") == true) 1200L else 5000L)
            refresh(showError = false)
        }
    }
    LaunchedEffect(api, status?.obj("indexer")?.flag("running")) {
        if (status?.obj("indexer")?.flag("running") == true) {
            delay(1000)
            refresh(showError = false)
        }
    }
    LaunchedEffect(api) {
        try {
            options = api.get("/teams/member-options").jsonObject
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    PluginSettingsLayout(title, state, words, retry) {
        LibraryCard {
            LibrarySectionTitle(words["index.title"])
            problem?.let { ProblemText(it) }
            val current = status
            if (current == null) {
                if (problem == null) Muted(m["loading"])
                else WorkAction(m["refresh"], "refresh-cw") { scope.launch { refresh() } }
            } else {
                GraphifyIndexStatus(current, words, m.language)
                val progress = run?.obj("progress")?.string("percent")
                val indexLabel = when {
                    !running -> words["index.indexNow"]
                    progress == null -> words["index.indexing"]
                    else -> words.text("index.indexingPercent", "percent" to progress)
                }
                Muted(words["index.buttonsHint"])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    WorkAction(indexLabel, "play", enabled = !busy && !running && current.string("resolved") != null,
                        primary = true, busy = running) { action("index") }
                    if (running) {
                        WorkAction(words["index.cancel"], "ban", enabled = !busy) { action("cancel") }
                    } else {
                        WorkAction(words["index.rebuildAll"], "refresh-cw", enabled = !busy && current.string("resolved") != null) {
                            action("index", buildJsonObject { put("force", true) })
                        }
                    }
                    WorkAction(m["refresh"], "refresh-cw", enabled = !busy) { scope.launch { refresh() } }
                    WorkAction(words["index.reset"], "trash-2", enabled = !busy && !reset && current.string("resolved") != null,
                        danger = true) { reset = true }
                }
                if (reset) {
                    Text(words["reset.title"], color = localColors.current.danger)
                    Text(words.text(if (running) "reset.bodyRunning" else "reset.body",
                        "dir" to "${current.string("launch_root")}\\graphify-out"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        WorkAction(words["reset.confirm"], "trash-2", enabled = !busy, primary = true, danger = true) {
                            reset = false
                            action("reset")
                        }
                        WorkAction(m["cancel"], null) { reset = false }
                    }
                }
            }
        }
        LibraryCard {
            LibrarySectionTitle(words["settings.title"])
            PluginFields(graphifyFields.take(5), state, words)
            if (state.value(graphifyFields[4]).text == "true") {
                val backend = state.value(graphifyDocumentBackend).text
                val model = state.value(graphifyDocumentModel)
                val modelLabel = words["fields.documentsModel.label"]
                val backendLabels = mapOf("claude_code" to "Claude Code", "codex" to "Codex", "qwen" to "Qwen Code")
                val names = options?.obj("agent_backends")
                Text(modelLabel)
                PluginChoice(modelLabel, graphifyDocumentBackend.choices.map { it to (names?.obj(it)?.string("label")
                    ?: backendLabels.getValue(it)) }, backend, !state.saving) { next ->
                    if (next != backend) {
                        state.select(graphifyDocumentBackend, next)
                        state.select(graphifyDocumentModel, "")
                    }
                }
                StudioField(modelLabel, model, { state.edit(graphifyDocumentModel, it) },
                    Modifier.fillMaxWidth(), enabled = !state.saving,
                    placeholder = if (backend == "claude_code") "haiku" else words["index.defaultModel"])
                val suggestions = options?.objects("suggested_models").orEmpty().filter { it.string("backend") == backend }
                    .mapNotNull { option -> option.string("id")?.let { it to (option.string("label") ?: it) } }
                    .distinctBy { it.first }
                if (suggestions.isNotEmpty()) {
                    val choose = words["chooseModel"]
                    PluginChoice(choose, listOf("" to words["index.defaultModel"]) + suggestions, model.text, !state.saving) {
                        state.select(graphifyDocumentModel, it)
                    }
                }
                Muted(words["fields.documentsModel.hint"])
                PluginFields(listOf(graphifyLanguage), state, words)
            }
            PluginSaveRow(state) { save { scope.launch { refresh() } } }
        }
    }
}

@Composable
private fun GraphifyIndexStatus(status: JsonObject, words: PluginWords, language: String) {
    val timeFormat = remember(language) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(language))
            .withZone(ZoneId.systemDefault())
    }
    fun time(raw: String?) = raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { timeFormat.format(Instant.ofEpochSecond(it.toLong())) }.orEmpty()
    val executable = status.string("resolved")
    if (executable == null) {
        Text(words.text("index.notOnPath", "executable" to status.string("executable")), color = localColors.current.danger)
        Muted(words.text("index.install", "command" to status.string("install_hint")))
    }
    GraphifyLine("Graphify", executable)
    GraphifyLine(words["index.launchRoot"], status.string("launch_root"))
    GraphifyLine(words["index.code"], status.stringList("command").joinToString(" "))
    if (status.flag("documents")) {
        GraphifyLine(words["index.documents"], status.stringList("documents_command").joinToString(" "))
        val cli = status.string("documents_cli") ?: status.string("claude")
        if (cli == null) {
            val command = status.string("documents_cli_command") ?: "claude"
            Text(words.text("index.cliMissing", "command" to command,
                "hint" to (status.string("documents_cli_hint") ?: status.string("claude_hint"))), color = localColors.current.danger)
        } else {
            GraphifyLine(words["fields.documentsModel.label"], listOfNotNull(status.string("documents_backend_label"), cli,
                status.string("documents_model")?.takeIf(String::isNotBlank)).joinToString(" · "))
        }
        status.string("documents_extra_hint")?.let { Muted(words.text("index.extraNeeded", "command" to it)) }
    } else {
        GraphifyLine(words["index.documents"], words["index.documentsOff"])
    }

    status.obj("graph")?.let { graph ->
        GraphifyLine(words["index.graph"], graph.string("path"))
        if (!graph.flag("exists")) {
            Muted(words["index.notBuilt"])
        } else {
            if (graph.flag("merged")) Muted(words["index.merged"])
            if (graph["nodes"] != null) Muted(words.text("counts", "nodes" to graph.string("nodes"),
                "edges" to graph.string("edges"), "communities" to graph.string("communities")))
            val commit = graph.string("built_at_commit")?.take(7)
            Muted(words.text(if (commit == null) "index.written" else "index.writtenAtCommit",
                "time" to time(graph.string("modified_at")), "commit" to commit))
        }
    }

    status.obj("indexer")?.let { run ->
        val running = run.flag("running")
        val finished = time(run.string("finished_at"))
        val state = when {
            running -> words.text("index.running", "time" to time(run.string("started_at")), "reason" to run.string("reason"))
            finished.isEmpty() -> words["index.neverRan"]
            run.string("error") != null -> words.text("index.failed", "time" to finished)
            else -> words.text("index.finished", "time" to finished, "reason" to run.string("reason"))
        }
        GraphifyLine(words["index.lastRun"], state)
        if (running) run.obj("progress")?.let { progress ->
            Muted(listOfNotNull(progress.string("stage"), progress.string("detail")).joinToString(" · "))
        }
        run.string("pending")?.let { Muted(words.text("index.queued", "reason" to it)) }
        run.string("error")?.let { Text(it, color = localColors.current.danger) }
        if (!running && finished.isNotEmpty()) run.obj("documents")?.let { documentRun ->
            val skipped = documentRun.string("skipped")
            when {
                documentRun.flag("ran") && documentRun.string("error") == null -> Muted(words["index.documentsRead"])
                skipped != null -> Muted(words.text("index.documentsSkipped", "reason" to skipped))
                !documentRun.flag("enabled") -> Muted(words["index.documentsDisabled"])
            }
        }
        val log = run.stringList("log")
        if (log.isNotEmpty()) SelectionContainer { Text(log.joinToString("\n"), fontFamily = localStudioMono.current) }
    }
}

@Composable
private fun GraphifyLine(label: String, value: String?) {
    if (!value.isNullOrBlank()) {
        Text(label)
        SelectionContainer { Muted(value) }
    }
}
