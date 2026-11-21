package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

internal enum class ExplorerAction(val label: String) {
    NEW_FILE("explorerTree.newFile"), NEW_FOLDER("explorerTree.newFolder"), RENAME("explorerTree.rename"),
    DUPLICATE("explorerTree.duplicate"), CUT("explorerTree.cut"), COPY("libraryCopy"), PASTE("explorerTree.paste"),
    COPY_RELATIVE("explorerTree.copyRelative"), COPY_ABSOLUTE("explorerTree.copyAbsolute"),
    REVEAL("revealFile"), DELETE("work.delete"),
}

internal data class ExplorerTarget(val path: String, val folder: Boolean) {
    val parent get() = path.substringBeforeLast('/', "")
    val directory get() = if (folder) path else parent
    val modifiable get() = path.isNotEmpty() && !path.equals(".voxarium_studio", ignoreCase = true)
}

internal data class ExplorerClipboard(val source: ExplorerTarget, val cut: Boolean)

internal class ExplorerTreeRequest(
    val action: ExplorerAction,
    val target: ExplorerTarget,
    val transfer: ExplorerClipboard? = null,
    val collision: String? = null,
) {
    val needsName = action in setOf(ExplorerAction.NEW_FILE, ExplorerAction.NEW_FOLDER, ExplorerAction.RENAME)
    val initialName = if (action == ExplorerAction.RENAME) target.path.substringAfterLast('/') else ""
    var problem by mutableStateOf<Problem?>(null)
    var complete by mutableStateOf(false)
}

// §6.2: project-scoped file operations leave path validation and watcher events to ExplorerService.
internal class ExplorerTreeActions {
    var clipboard by mutableStateOf<ExplorerClipboard?>(null)
        private set
    var request by mutableStateOf<ExplorerTreeRequest?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Problem?>(null)

    fun clearClipboard() { clipboard = null }

    fun close(prompt: ExplorerTreeRequest) {
        if (request === prompt) request = null
    }

    fun start(action: ExplorerAction, target: ExplorerTarget, tab: ProjectTab, navigate: (Page) -> Unit,
              copyText: (String) -> Unit) {
        if (busy || tab.acting || request != null) return
        problem = null
        val transfer = clipboard
        when (action) {
            ExplorerAction.CUT, ExplorerAction.COPY -> clipboard = ExplorerClipboard(target, action == ExplorerAction.CUT)
            ExplorerAction.COPY_RELATIVE -> copyPath(target.path, copyText)
            ExplorerAction.COPY_ABSOLUTE -> {
                val root = tab.root.trimEnd('/', '\\')
                val separator = if ('\\' in tab.root) '\\' else '/'
                val path = if (target.path.isEmpty()) tab.root else "$root$separator${target.path.replace('/', separator)}"
                copyPath(path, copyText)
            }
            ExplorerAction.REVEAL -> tab.action { post("/explorer/reveal", jsonFields("path" to target.path)) }
            ExplorerAction.PASTE -> if (transfer != null) submit(tab, ExplorerTreeRequest(action, target, transfer), navigate)
            ExplorerAction.DUPLICATE -> submit(tab, ExplorerTreeRequest(action, target), navigate)
            else -> request = ExplorerTreeRequest(action, target)
        }
    }

    private fun copyPath(path: String, copyText: (String) -> Unit) {
        try {
            copyText(path)
        } catch (error: Exception) {
            problem = error.problem("explorerTree.clipboardDenied")
        }
    }

    fun submit(tab: ProjectTab, prompt: ExplorerTreeRequest, navigate: (Page) -> Unit, name: String = "") {
        val api = tab.api ?: return
        if (busy || tab.acting || prompt.complete) return
        busy = true
        prompt.problem = null
        problem = null
        val current = tab.page
        val action = prompt.action
        val target = prompt.target
        val transfer = prompt.transfer
        tab.scope.launch {
            try {
                val path = when (action) {
                    ExplorerAction.NEW_FILE, ExplorerAction.NEW_FOLDER -> api.mutate("create", jsonFields(
                        "parent" to target.directory, "name" to name,
                        "kind" to if (action == ExplorerAction.NEW_FOLDER) "directory" else "file",
                    ))
                    ExplorerAction.RENAME -> api.mutate("rename", jsonFields("path" to target.path, "name" to name))
                    ExplorerAction.DELETE -> api.mutate("delete", explorerPaths(target.path))
                    ExplorerAction.DUPLICATE -> api.mutate("copy", explorerPaths(target.path, target.parent))
                    ExplorerAction.PASTE -> {
                        val source = requireNotNull(transfer)
                        api.mutate(if (source.cut) "move" else "copy",
                            explorerPaths(source.source.path, target.directory, overwrite = prompt.collision != null))
                    }
                    else -> error("Unexpected Explorer mutation: $action")
                }
                when (action) {
                    ExplorerAction.RENAME -> updatePaths(tab, target.path, path)
                    ExplorerAction.DELETE -> updatePaths(tab, target.path, null)
                    ExplorerAction.PASTE -> if (transfer?.cut == true) {
                        updatePaths(tab, transfer.source.path, path)
                        clearClipboard()
                    }
                    else -> Unit
                }
                if (tab.api === api && tab.page == current) {
                    val next = if (action != ExplorerAction.DELETE) path else {
                        current.query.path?.let { if (isUnder(it, target.path)) target.parent.ifEmpty { null } else it }
                    }
                    if (next != null) {
                        var parent = next.substringBeforeLast('/', "")
                        while (parent.isNotEmpty()) {
                            tab.libraryWorkspace.openDirectories[parent] = true
                            parent = parent.substringBeforeLast('/', "")
                        }
                    }
                    val page = Page(Section.EXPLORER.route, Selectors(path = next))
                    if (page != current) navigate(page) else tab.refresh()
                }
                prompt.complete = true
            } catch (error: Exception) {
                if (action == ExplorerAction.PASTE && prompt.collision == null && error is ApiFailure &&
                    error.status == 409 && error.message.orEmpty().endsWith(" already exists.")) {
                    request = ExplorerTreeRequest(action, target, transfer, error.message)
                } else if (request === prompt) prompt.problem = error.problem()
                else problem = error.problem()
            } finally {
                busy = false
            }
        }
    }

    private fun updatePaths(tab: ProjectTab, previous: String, next: String?) {
        val folders = tab.libraryWorkspace.openDirectories
        folders.entries.filter { isUnder(it.key, previous) }.map { it.key to it.value }.forEach { (path, open) ->
            folders.remove(path)
            if (next != null) folders[followRenamedFolder(path, previous, next)] = open
        }
        val entry = clipboard ?: return
        if (isUnder(entry.source.path, previous)) {
            clipboard = next?.let { entry.copy(source = entry.source.copy(path = followRenamedFolder(entry.source.path, previous, it))) }
        }
    }
}

private fun isUnder(path: String, parent: String) = path == parent || path.startsWith("$parent/")

private fun explorerPaths(source: String, destination: String? = null, overwrite: Boolean = false) = buildJsonObject {
    put("paths", buildJsonArray { add(JsonPrimitive(source)) })
    if (destination != null) {
        put("destination", destination)
        put("overwrite", overwrite)
    }
}

private suspend fun ProjectApi.mutate(route: String, body: JsonObject): String =
    requireNotNull((post("/explorer/$route", body).jsonObject["paths"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull)

private val explorerMenuIcons = mapOf(
    ExplorerAction.NEW_FILE to "file-plus-2", ExplorerAction.NEW_FOLDER to "folder-plus",
    ExplorerAction.RENAME to "pencil", ExplorerAction.DUPLICATE to "files",
    ExplorerAction.CUT to "file-output", ExplorerAction.COPY to "copy", ExplorerAction.PASTE to "clipboard-list",
    ExplorerAction.COPY_RELATIVE to "link-2", ExplorerAction.COPY_ABSOLUTE to "link-2",
    ExplorerAction.REVEAL to "folder-open", ExplorerAction.DELETE to "trash-2",
)

private val explorerMenuShortcuts = mapOf(
    ExplorerAction.RENAME to "F2", ExplorerAction.CUT to "Ctrl+X", ExplorerAction.COPY to "Ctrl+C",
    ExplorerAction.PASTE to "Ctrl+V", ExplorerAction.DELETE to "Del",
)

private fun explorerTreeMenu(messages: Messages, target: ExplorerTarget, canPaste: Boolean,
                             execute: (ExplorerAction) -> Unit): List<StudioMenuItem> = buildList {
    fun group(vararg actions: ExplorerAction) {
        actions.forEachIndexed { index, action ->
            add(StudioMenuItem(messages[action.label], icon = explorerMenuIcons[action], dividerBefore = index == 0,
                shortcut = explorerMenuShortcuts[action], danger = action == ExplorerAction.DELETE) { execute(action) })
        }
    }
    group(ExplorerAction.NEW_FILE, ExplorerAction.NEW_FOLDER)
    if (target.modifiable) group(ExplorerAction.RENAME, ExplorerAction.DUPLICATE)
    val clipboard = buildList {
        if (target.modifiable) addAll(listOf(ExplorerAction.CUT, ExplorerAction.COPY))
        if (canPaste) add(ExplorerAction.PASTE)
    }
    group(*clipboard.toTypedArray())
    if (target.path.isNotEmpty()) group(ExplorerAction.COPY_ABSOLUTE, ExplorerAction.COPY_RELATIVE, ExplorerAction.REVEAL)
    else group(ExplorerAction.COPY_ABSOLUTE, ExplorerAction.REVEAL)
    if (target.modifiable) group(ExplorerAction.DELETE)
}

/** §6.2: same target for the context popup and tree keyboard actions. The popup anchor
 * lives at the actual pointer (or at the focused row for keyboard invocation). */
@Composable
internal fun ExplorerContextTarget(
    target: ExplorerTarget,
    subject: String,
    enabled: Boolean,
    hasClipboard: Boolean,
    clearClipboard: () -> Unit,
    select: () -> Unit,
    execute: (ExplorerAction) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val m = localMessages.current
    StudioContextTarget(subject, if (enabled) explorerTreeMenu(m, target, hasClipboard, execute) else emptyList(),
        Modifier.fillMaxWidth(), onOpen = select, onTargetKey = keys@{ event ->
            if (!enabled) return@keys false
            val chord = (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed
            val action = when {
                target.modifiable && event.key == Key.F2 -> ExplorerAction.RENAME
                target.modifiable && event.key == Key.Delete -> ExplorerAction.DELETE
                target.modifiable && chord && event.key == Key.X -> ExplorerAction.CUT
                target.modifiable && chord && event.key == Key.C -> ExplorerAction.COPY
                hasClipboard && chord && event.key == Key.V -> ExplorerAction.PASTE
                else -> null
            }
            when {
                action != null -> {
                    if (event.type == KeyEventType.KeyUp) execute(action)
                    true
                }
                event.key == Key.Escape && hasClipboard -> {
                    if (event.type == KeyEventType.KeyUp) clearClipboard()
                    true
                }
                else -> false
            }
        }, content = content)
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun BoxScope.ExplorerTreeDialog(tab: ProjectTab, actions: ExplorerTreeActions, prompt: ExplorerTreeRequest,
                                        navigate: (Page) -> Unit) {
    val m = localMessages.current
    val deleting = prompt.action == ExplorerAction.DELETE
    val initial = prompt.initialName
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.lastIndexOf('.').takeIf {
        it > 0 && !prompt.target.folder
    } ?: initial.length))) }
    val name = field.text.trim()
    val canSubmit = !actions.busy && !tab.acting && !prompt.complete &&
        (!prompt.needsName || name.isNotEmpty() && name != initial)
    val replacing = prompt.collision != null
    val title = m[if (replacing) "explorerTree.conflict" else prompt.action.label]
    val subject = if (prompt.action in setOf(ExplorerAction.NEW_FILE, ExplorerAction.NEW_FOLDER, ExplorerAction.PASTE))
        prompt.target.directory else prompt.target.path
    val focus = remember { FocusRequester() }
    val submit = { if (canSubmit) actions.submit(tab, prompt, navigate, name) }
    WorkDialog(title, { actions.close(prompt) }, Modifier.widthIn(max = StudioTokens.modalMaxWidth).fillMaxWidth(.9f),
        canDismiss = !actions.busy && !tab.acting) { dismiss ->
        LaunchedEffect(prompt.complete) { if (prompt.complete) dismiss() }
        Column(Modifier.padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
            Heading(title, size = StudioTokens.titleSize.value.toInt())
            LibraryCaption(subject.ifEmpty { m["explorerTree.root"] }, mono = subject.isNotEmpty(), maxLines = 3)
            if (deleting) Muted(m[if (prompt.target.folder) "explorerTree.deleteFolder" else "explorerTree.deleteFile"])
            if (replacing) {
                Muted(prompt.collision.orEmpty())
                Muted(m["explorerTree.replaceHint"])
            }
            if (prompt.needsName) {
                val label = m["explorerTree.name"]
                StudioField(label, field, { field = it; prompt.problem = null }, Modifier.fillMaxWidth().focusRequester(focus)
                    .onPreviewKeyEvent {
                        if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { submit(); true } else false
                    }, enabled = !actions.busy, placeholder = label)
                LaunchedEffect(focus) {
                    withFrameNanos { }
                    focus.requestFocus()
                }
            }
            prompt.problem?.let { ProblemText(it) }
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                WorkAction(m["cancel"], null, enabled = !actions.busy, onClick = dismiss)
                WorkAction(m[when {
                    replacing -> "explorerTree.replace"
                    deleting -> "work.delete"
                    prompt.action == ExplorerAction.RENAME -> "explorerTree.rename"
                    else -> "create"
                }], null, enabled = canSubmit, primary = !deleting, danger = deleting, busy = actions.busy, onClick = submit)
            }
        }
    }
}
