package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.*

internal class McpPageState {
    var overview by mutableStateOf<JsonObject?>(null)
    var form by mutableStateOf<McpForm?>(null)
        private set
    var draft by mutableStateOf<McpForm?>(null)
    var rawBase by mutableStateOf<String?>(null)
        private set
    var rawDraft by mutableStateOf(TextFieldValue())
    var problem by mutableStateOf<Problem?>(null)
    var loadProblem by mutableStateOf<Problem?>(null)

    val rawDirty: Boolean get() = rawBase != null && rawDraft.text != rawBase
    val hasUnsavedChanges: Boolean get() = rawDirty || draft != form

    fun startForm(value: McpForm?) {
        form = value
        draft = value
        problem = null
    }

    fun openRaw(text: String) {
        rawBase = text
        rawDraft = TextFieldValue(text)
    }

    fun closeRaw() {
        rawBase = null
        rawDraft = TextFieldValue()
    }

    fun accept(value: JsonObject) {
        if (rawBase != null && !rawDirty && rawBase != value.string("text")) openRaw(value.string("text").orEmpty())
        overview = value
        loadProblem = null
    }
}

internal data class McpForm(
    val name: String = "",
    val type: String = "stdio",
    val command: String = "",
    val args: String = "",
    val env: String = "",
    val url: String = "",
    val headers: String = "",
) {
    constructor(server: JsonObject) : this(
        name = server.string("name").orEmpty(),
        type = server.string("type") ?: "stdio",
        command = server.string("command").orEmpty(),
        args = server.mcpStrings("args").joinToString("\n"),
        env = server.mcpPairs("env"),
        url = server.string("url").orEmpty(),
        headers = server.mcpPairs("headers"),
    )

    fun payload(current: JsonObject? = null): JsonObject = buildJsonObject {
        val previous = current?.let(::McpForm)?.takeIf { it.type == type }
        put("type", type)
        if (type == "stdio") {
            put("command", if (previous?.command == command) command else command.trim())
            put("args", current?.get("args")?.takeIf { previous?.args == args }
                ?: JsonArray(mcpLines(args).map(::JsonPrimitive).toList()))
            put("env", current?.get("env")?.takeIf { previous?.env == env } ?: mcpKeyValues(env))
        } else {
            put("url", if (previous?.url == url) url else url.trim())
            put("headers", current?.get("headers")?.takeIf { previous?.headers == headers } ?: mcpKeyValues(headers))
        }
    }
}

private fun JsonObject.mcpStrings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.mcpPairs(key: String): String =
    obj(key).orEmpty().entries.joinToString("\n") { (name, value) -> "$name=${value.jsonPrimitive.content}" }

private fun mcpLines(value: String) = value.lineSequence().map(String::trim).filter(String::isNotEmpty)

private fun mcpKeyValues(value: String): JsonObject = buildJsonObject {
    mcpLines(value).forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "mcp.keyValueHint" }
        put(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
    }
}

@Composable
internal fun McpPage(tab: ProjectTab, modifier: Modifier) {
    val m = localMessages.current
    val api = tab.api ?: return
    val state = tab.mcpPage
    val overview = state.overview
    val canWrite = !tab.acting && overview != null
    var removal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, tab.pageRefresh) {
        try {
            state.accept(api.get("/mcp").jsonObject)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.loadProblem = error.problem()
        }
    }

    fun save(draft: McpForm) {
        val name = draft.name.trim()
        if (name.isEmpty()) {
            state.problem = Problem("mcp.nameRequired")
            return
        }
        val original = state.form
        tab.action {
            // §10.7: the editable entries come from the project's .mcp.json.
            val latest = get("/mcp").jsonObject
            val current = latest.objects("servers").firstOrNull { it.string("name") == name }
            if (original?.name.isNullOrEmpty()) {
                if (current != null || latest.objects("invalid").any { it.string("name") == name }) {
                    state.problem = Problem("mcp.nameInUse")
                    return@action
                }
            } else if (current == null || McpForm(current) != original) {
                state.problem = Problem("mcp.entryChanged")
                return@action
            }
            val payload = try {
                draft.payload(current)
            } catch (_: IllegalArgumentException) {
                state.problem = Problem("mcp.keyValueHint")
                return@action
            }
            state.accept(put("/mcp/servers/${segment(name)}", payload).jsonObject)
            state.startForm(null)
        }
    }

    Box(modifier) {
        McpContent(tab, canWrite, ::save) { removal = it }
        removal?.let { name ->
            WorkDialog(m["mcp.remove"], { removal = null }, Modifier.widthIn(max = StudioTokens.modalMaxWidth).fillMaxWidth(.9f)) { dismiss ->
                Column(Modifier.padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                    Text(m["mcp.confirmRemove"].replace("{name}", name))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End)) {
                        WorkAction(m["cancel"], null, onClick = dismiss)
                        WorkAction(m["mcp.remove"], "trash-2", enabled = canWrite, danger = true) {
                            removal = null
                            tab.action { state.accept(delete("/mcp/servers/${segment(name)}").jsonObject) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpContent(tab: ProjectTab, canWrite: Boolean, save: (McpForm) -> Unit, remove: (String) -> Unit) {
    val m = localMessages.current
    val state = tab.mcpPage
    val overview = state.overview
    Column(Modifier.fillMaxSize()) {
        WorkHeader {
            Column(Modifier.weight(1f)) {
                Heading(m["mcp"], size = StudioTokens.titleSize.value.toInt())
                LibraryCaption(overview?.string("path") ?: ".mcp.json", mono = true)
            }
            SettingsRefreshButton(m["refresh"], working = overview == null && state.loadProblem == null,
                enabled = !tab.acting && (overview != null || state.loadProblem != null)) { tab.refresh() }
        }
        WorkErrors(tab)
        listOfNotNull(state.loadProblem, state.problem).forEach { problem ->
            Box(Modifier.padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space1)) { ProblemText(problem) }
        }
        if (overview == null) {
            if (state.loadProblem == null) SettingsLoadingBars(Modifier.padding(StudioTokens.panePad))
            return@Column
        }

        val servers = overview.objects("servers")
        val fileError = overview.string("error")
        val rawText = overview.string("text").orEmpty()
        val rawOpened = state.rawBase != null
        val arrival = rememberSettingsArrival()
        ScrollList(Modifier.weight(1f).widthIn(max = 1040.dp).fillMaxWidth().padding(horizontal = StudioTokens.panePad)) {
            item("intro") {
                Column(Modifier.fillMaxWidth().settingsArrival(arrival, 0).padding(vertical = StudioTokens.space3),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    Muted(m["mcp.intro"])
                    if (fileError != null) {
                        LibraryCard {
                            LibrarySectionTitle(m["mcp.parseError"])
                            Text(fileError, color = localColors.current.danger)
                            if (!rawOpened) LibraryButton("file-json", m["mcp.openRaw"]) { state.openRaw(rawText) }
                        }
                    }
                    if (fileError == null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            LibraryButton("plus", m["mcp.add"], canWrite && state.form == null) {
                                state.startForm(McpForm())
                            }
                        }
                    }
                }
            }
            state.draft?.takeIf { fileError == null }?.let { draft ->
                item("server-editor") {
                    val editorArrival = rememberSettingsArrival()
                    Box(Modifier.settingsArrival(editorArrival, 0)) {
                        McpServerEditor(draft, state.form?.name.isNullOrEmpty(), canWrite, { state.draft = it },
                            close = { state.startForm(null) }, save = { save(draft) })
                    }
                }
            }
            items(servers, key = { "mcp:${it.string("name")}" }) { server ->
                val name = server.string("name").orEmpty()
                val fileEntry = !server.flag("builtin")
                val enabled = server.flag("enabled")
                Box(Modifier.settingsArrival(arrival, 1)) {
                    McpServerCard(server, canWrite, canWrite && state.form == null && fileEntry && fileError == null,
                        toggle = {
                            val action = if (enabled) "disable" else "enable"
                            tab.action { state.accept(post("/mcp/servers/${segment(name)}/$action").jsonObject) }
                        }, edit = { state.startForm(McpForm(server)) }, remove = { remove(name) })
                }
            }
            if (servers.none { !it.flag("builtin") } && fileError == null) {
                item { Muted(m["mcp.empty"], modifier = Modifier.padding(vertical = StudioTokens.space2)) }
            }
            items(overview.objects("invalid")) { invalid ->
                LibraryCard {
                    LibrarySectionTitle(invalid.string("name").orEmpty())
                    Text(invalid.string("error").orEmpty(), color = localColors.current.danger)
                }
            }
            item("settings") {
                Column(Modifier.fillMaxWidth().padding(vertical = StudioTokens.space3), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                    Box(Modifier.settingsArrival(arrival, 2)) {
                        LibraryCard {
                            WorkCheckbox(m["mcp.strict"], overview.flag("strict"), canWrite) { strict ->
                                tab.action {
                                    state.accept(patch("/mcp/settings", buildJsonObject { put("strict", strict) }).jsonObject)
                                }
                            }
                            Muted(m["mcp.strictHint"])
                        }
                    }
                    StudioDisclosure(rawOpened, Modifier.settingsArrival(arrival, 3)) {
                        McpRawEditor(state, canWrite && rawOpened, rawText) {
                            val draft = state.rawDraft.text
                            val base = state.rawBase
                            tab.action {
                                val latest = get("/mcp").jsonObject
                                if (base != latest.string("text")) {
                                    state.accept(latest)
                                    state.problem = Problem("mcp.rawChanged")
                                } else {
                                    val saved = put("/mcp/file", jsonFields("content" to draft)).jsonObject
                                    state.openRaw(saved.string("text").orEmpty())
                                    state.accept(saved)
                                    state.problem = null
                                }
                            }
                        }
                    }
                    if (!rawOpened && fileError == null) LibraryButton("file-json", m["mcp.openRaw"]) { state.openRaw(rawText) }
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(server: JsonObject, canWrite: Boolean, canEdit: Boolean,
                          toggle: () -> Unit, edit: () -> Unit, remove: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val builtIn = server.flag("builtin")
    val enabled = server.flag("enabled")
    val type = server.string("type")
    LibraryCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            StudioIcon(if (builtIn) "package" else "plug", Modifier.size(StudioTokens.iconSize), colors.accent)
            LibrarySectionTitle(server.string("name").orEmpty())
            if (builtIn) LibraryCaption(m["mcp.builtin"]) else type?.let { LibraryCaption(it, mono = true) }
            if (!enabled) LibraryCaption(m["mcp.disabled"])
        }
        val target = when {
            builtIn -> server.mcpStrings("tools").joinToString(" · ")
            type == "stdio" -> (listOfNotNull(server.string("command")) + server.mcpStrings("args")).joinToString(" ")
            else -> server.string("url").orEmpty()
        }
        LibraryCaption(target, mono = true, maxLines = 3)
        if (server.string("available") == "false") {
            Text(server.string("detail").orEmpty(), color = colors.danger)
        } else if (server.string("available") == "true" && !builtIn) {
            server.string("detail")?.let { LibraryCaption(it, mono = true, maxLines = 2) }
        }
        server.string("tool_prefix")?.let { LibraryCaption("$it*", mono = true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            LibraryButton("power", m[if (enabled) "mcp.disable" else "mcp.enable"], canWrite, toggle)
            if (!builtIn) {
                LibraryButton("pencil", m["mcp.edit"], canEdit, edit)
                LibraryButton("trash-2", m["mcp.remove"], canEdit, remove)
            }
        }
    }
    Spacer(Modifier.height(StudioTokens.space2))
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun McpServerEditor(draft: McpForm, adding: Boolean, enabled: Boolean, update: (McpForm) -> Unit,
                             close: () -> Unit, save: () -> Unit) {
    val m = localMessages.current
    LibraryCard {
        LibrarySectionTitle(m[if (adding) "mcp.add" else "mcp.edit"])
        McpField(m["mcp.name"], draft.name, enabled && adding) { update(draft.copy(name = it)) }
        LibraryCaption(m["mcp.transport"])
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            listOf("stdio", "http", "sse").forEach { type ->
                StudioChip(type, type == draft.type, { if (type != draft.type) update(draft.copy(type = type)) }, enabled = enabled)
            }
        }
        if (draft.type == "stdio") {
            McpField(m["mcp.command"], draft.command, enabled) { update(draft.copy(command = it)) }
            McpField(m["mcp.arguments"], draft.args, enabled, multiline = true) { update(draft.copy(args = it)) }
            LibraryCaption(m["mcp.argumentsHint"])
            McpField(m["mcp.environment"], draft.env, enabled, multiline = true) { update(draft.copy(env = it)) }
        } else {
            McpField(m["mcp.url"], draft.url, enabled) { update(draft.copy(url = it)) }
            McpField(m["mcp.headers"], draft.headers, enabled, multiline = true) { update(draft.copy(headers = it)) }
        }
        LibraryCaption(m["mcp.keyValueHint"])
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            WorkAction(m["saveDocument"], "check", enabled = enabled && draft.name.isNotBlank(), primary = true, onClick = save)
            WorkAction(m["cancel"], null, enabled = enabled, onClick = close)
        }
    }
    Spacer(Modifier.height(StudioTokens.space2))
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun McpField(label: String, value: String, enabled: Boolean, multiline: Boolean = false, update: (String) -> Unit) {
    LibraryCaption(label)
    val field = Modifier.fillMaxWidth().semantics { contentDescription = label }
    var editor by remember(label) { mutableStateOf(TextFieldValue(value)) }
    val shown = if (editor.text == value) editor else TextFieldValue(value)
    val change: (TextFieldValue) -> Unit = {
        editor = it
        update(it.text)
    }
    if (multiline) TextArea(shown, change, field.height(76.dp), enabled = enabled)
    else StudioField(label, shown, change, Modifier.fillMaxWidth(), enabled = enabled)
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun McpRawEditor(state: McpPageState, canWrite: Boolean, rawText: String, save: () -> Unit) {
    val m = localMessages.current
    LibraryCard {
        LibrarySectionTitle(m["mcp.openRaw"])
        TextArea(state.rawDraft, { state.rawDraft = it },
            Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = m["mcp.openRaw"] }, enabled = canWrite)
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            WorkAction(m["saveDocument"], null, primary = true, onClick = save, enabled = canWrite && state.rawDirty)
            WorkAction(m["discardDocumentDraft"], null, enabled = canWrite && state.rawDirty) {
                state.openRaw(rawText)
                state.problem = null
            }
            WorkAction(m["close"], null, onClick = state::closeRaw, enabled = !state.rawDirty && canWrite)
        }
    }
}
