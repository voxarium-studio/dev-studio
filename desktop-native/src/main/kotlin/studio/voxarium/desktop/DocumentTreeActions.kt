package studio.voxarium.desktop

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text

internal enum class DocumentTreeAction(val label: String) {
    CREATE("docTree.create"), MOVE("docTree.move"), DELETE("docTree.delete"),
    FOLDER_DELETE("docTree.folderDelete"), FOLDER_STYLE("docTree.folderStyle"), FOLDER_RENAME("docTree.folderRename"),
}

internal class DocumentTreeRequest(
    val action: DocumentTreeAction,
    val path: String = "",
    style: JsonObject? = null,
    initialName: String = "",
) {
    val previousColor = style?.string("color")?.let { if (it == "grey") "gray" else it }
    val previousIcon = style?.string("icon")
    var fence by mutableStateOf<NativeDocumentFence?>(null)
    var problem by mutableStateOf<Problem?>(null)
    var busy by mutableStateOf(false)
    var field by mutableStateOf(TextFieldValue(when (action) {
        DocumentTreeAction.CREATE -> initialName
        DocumentTreeAction.MOVE -> path
        DocumentTreeAction.FOLDER_RENAME -> path.substringAfterLast('/')
        else -> ""
    }))
    var renamedTo by mutableStateOf<String?>(null)
    var renamedResult by mutableStateOf<JsonObject?>(null)
    val relinks = mutableStateMapOf<String, JsonObject>()
    var color by mutableStateOf(previousColor)
    var icon by mutableStateOf(previousIcon)
}

internal data class NativeDocumentFence(val epoch: String, val generation: Long) {
    fun body(properties: JsonObject): JsonObject = buildJsonObject {
        properties.forEach { (key, value) -> put(key, value) }
        put("backend_epoch", epoch)
        put("expected_binding_generation", generation)
    }

    fun params(): String = query("backend_epoch" to epoch, "expected_binding_generation" to generation.toString())
}

// §5.3 and §16.4: a dialog captures the project binding before a write can be
// requested. No native action acquires a remote editor lease or uses a room SID.
internal suspend fun ProjectApi.captureDocumentFence(): NativeDocumentFence {
    val status = get("/remote").jsonObject
    return NativeDocumentFence(requireNotNull(status.string("backend_epoch")),
        requireNotNull(status.string("binding_generation")?.toLongOrNull()))
}

internal fun nativeDocumentPath(value: String, folder: String = ""): String? {
    val path = value.trim().replace('\\', '/')
    val name = path.substringAfterLast('/')
    val hasSuffix = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "markdown", "txt")
    if (hasSuffix && name.substringBeforeLast('.').isBlank()) return null
    if (path.split('/').any { !validDocumentSegment(it) }) return null
    val target = if (hasSuffix) path else "$path.md"
    return if (folder.isEmpty()) target else "$folder/$target"
}

private fun validDocumentSegment(value: String): Boolean = value.isNotBlank() && !value.endsWith('.') && !value.endsWith(' ') &&
    value.none { it.isISOControl() || it in "<>:\"|?*/\\" }

private fun DocumentTreeRequest.contains(path: String): Boolean = when (action) {
    DocumentTreeAction.DELETE, DocumentTreeAction.MOVE -> this.path == path
    DocumentTreeAction.FOLDER_DELETE, DocumentTreeAction.FOLDER_RENAME -> path.startsWith("${this.path}/")
    else -> false
}

internal fun followRenamedFolder(path: String, old: String, new: String): String = when {
    path == old -> new
    path.startsWith("$old/") -> new + path.removePrefix(old)
    else -> path
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun BoxScope.DocumentTreeDialog(tab: ProjectTab, request: DocumentTreeRequest,
                                         navigate: (Page) -> Unit, close: () -> Unit) {
    val api = tab.api ?: return
    val m = localMessages.current
    val title = m[request.action.label]
    var fence by request::fence
    var problem by request::problem
    var busy by request::busy
    var field by request::field
    var renamedTo by request::renamedTo
    var renamedResult by request::renamedResult
    val relinks = request.relinks
    val previousColor = request.previousColor
    val previousIcon = request.previousIcon
    var color by request::color
    var icon by request::icon
    val hasDrafts = tab.documentDrafts.any { (path, draft) -> request.contains(path) && draft.dirty }
    val target = when (request.action) {
        DocumentTreeAction.CREATE -> nativeDocumentPath(field.text, request.path)
        DocumentTreeAction.MOVE -> nativeDocumentPath(field.text)?.takeIf { it != request.path }
        DocumentTreeAction.FOLDER_RENAME -> field.text.trim().takeIf { it != request.path.substringAfterLast('/') && validDocumentSegment(it) }
        else -> null
    }
    val deleting = request.action == DocumentTreeAction.DELETE || request.action == DocumentTreeAction.FOLDER_DELETE
    val canSubmit = !busy && !tab.acting && !hasDrafts && fence != null && when (request.action) {
        DocumentTreeAction.CREATE, DocumentTreeAction.MOVE, DocumentTreeAction.FOLDER_RENAME -> target != null && renamedTo == null
        DocumentTreeAction.FOLDER_STYLE -> color != previousColor || icon != previousIcon
        else -> true
    }
    LaunchedEffect(api, request) {
        if (fence != null) return@LaunchedEffect
        try {
            fence = api.captureDocumentFence()
        } catch (error: Exception) {
            problem = error.problem()
        }
    }

    fun submit() {
        val captured = fence ?: return
        if (!canSubmit) return
        val newPath = target
        val newColor = color
        val newIcon = icon
        busy = true
        problem = null
        tab.scope.launch {
            val selection = tab.page.query.path
            fun adoptPath(path: String?) {
                val page = Page("/documents", Selectors(path = path))
                if (tab.page.path == page.path && page != tab.page) navigate(page) else tab.refresh()
            }
            fun followRename(destination: String) {
                val folds = tab.libraryWorkspace.closedDocFolders
                folds.entries.filter { (path, _) -> path == request.path || request.contains(path) }
                    .map { (path, value) -> path to value }.forEach { (path, value) ->
                        folds.remove(path)
                        folds[followRenamedFolder(path, request.path, destination)] = value
                    }
                tab.documentDrafts.keys.filter(request::contains).forEach(tab.documentDrafts::remove)
                adoptPath(selection?.let { followRenamedFolder(it, request.path, destination) })
            }
            try {
                val open = when (request.action) {
                    DocumentTreeAction.CREATE -> {
                        val path = requireNotNull(newPath)
                        val name = path.substringAfterLast('/').substringBeforeLast('.')
                        api.put(documentEndpoint(path), captured.body(buildJsonObject {
                            put("exclusive", true)
                            put("content", "# $name\n\n")
                        })).jsonObject.string("path") ?: path
                    }
                    DocumentTreeAction.MOVE -> {
                        val path = requireNotNull(newPath)
                        api.post("/docs/move", captured.body(jsonFields("source" to request.path, "target" to path)))
                            .jsonObject.string("path") ?: path
                    }
                    DocumentTreeAction.DELETE -> {
                        api.delete(documentEndpoint(request.path) + captured.params())
                        selection?.takeUnless(request::contains)
                    }
                    DocumentTreeAction.FOLDER_DELETE -> {
                        api.post("/docs/folders/delete", captured.body(jsonFields("path" to request.path)))
                        val folds = tab.libraryWorkspace.closedDocFolders
                        folds.keys.filter { it == request.path || request.contains(it) }.forEach(folds::remove)
                        selection?.takeUnless(request::contains)
                    }
                    DocumentTreeAction.FOLDER_RENAME -> {
                        api.postFrames("/docs/folders/rename", captured.body(jsonFields("path" to request.path,
                            "name" to requireNotNull(newPath)))).collect { frame ->
                            when (frame.string("type")) {
                                "renamed" -> {
                                    renamedTo = requireNotNull(frame.string("path"))
                                    frame.objects("documents").forEach { document ->
                                        document.string("path")?.let { relinks[it] = document }
                                    }
                                }
                                "document" -> frame.string("path")?.let { relinks[it] = frame }
                                "done" -> {
                                    renamedTo = renamedTo ?: requireNotNull(frame.string("path"))
                                    renamedResult = frame
                                }
                                "error" -> throw IOException(frame.string("error") ?: "Folder rename interrupted")
                            }
                        }
                        if (renamedResult == null) problem = Problem("docTree.renameInterrupted")
                        renamedTo?.let(::followRename) ?: tab.refresh()
                        return@launch
                    }
                    DocumentTreeAction.FOLDER_STYLE -> {
                        api.post("/docs/folders/style", captured.body(buildJsonObject {
                            put("path", request.path)
                            if (newColor != previousColor) put("color", newColor?.let(::JsonPrimitive) ?: JsonNull)
                            if (newIcon != previousIcon) put("icon", newIcon?.let(::JsonPrimitive) ?: JsonNull)
                        }))
                        selection
                    }
                }
                tab.documentDrafts.keys.filter(request::contains).forEach(tab.documentDrafts::remove)
                open?.substringBeforeLast('/', "")?.let { folder ->
                    var parent = folder
                    while (parent.isNotEmpty()) {
                        tab.libraryWorkspace.closedDocFolders.remove(parent)
                        parent = parent.substringBeforeLast('/', "")
                    }
                }
                adoptPath(open)
                close()
            } catch (error: Exception) {
                problem = error.problem()
                renamedTo?.let(::followRename) ?: tab.refresh()
            } finally {
                busy = false
            }
        }
    }

    WorkDialog(title, close, Modifier.widthIn(max = StudioTokens.modalMaxWidth).fillMaxWidth(.9f),
        canDismiss = !busy && !tab.acting) { dismiss ->
        Column(Modifier.padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                if (busy) StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                Heading(title, size = StudioTokens.titleSize.value.toInt())
            }
            if (request.path.isNotEmpty()) LibraryCaption(request.path, mono = true, maxLines = 2)
            when (request.action) {
                DocumentTreeAction.CREATE, DocumentTreeAction.MOVE -> {
                    val label = m["docTree.path"]
                    StudioField(label, field, { field = it }, Modifier.fillMaxWidth(), enabled = !busy, placeholder = label)
                    target?.let { LibraryCaption(it, mono = true, maxLines = 2) }
                    if (request.action == DocumentTreeAction.MOVE) Muted(m["docTree.moveHint"])
                }
                DocumentTreeAction.DELETE -> Muted(m["docTree.deleteHint"])
                DocumentTreeAction.FOLDER_DELETE -> Muted(m["docTree.folderDeleteHint"])
                DocumentTreeAction.FOLDER_STYLE -> DocumentFolderAppearance(color, icon, !busy, { color = it }, { icon = it })
                DocumentTreeAction.FOLDER_RENAME -> {
                    val moved = renamedTo
                    if (moved == null) {
                        val label = m["docTree.folderName"]
                        StudioField(label, field, { field = it }, Modifier.fillMaxWidth(), enabled = !busy, placeholder = label)
                        if (busy) Muted(m["docTree.folderMoving"])
                    } else {
                        Text(m["docTree.folderMoved"].replace("{path}", moved))
                        val result = renamedResult
                        if (result == null && busy) Muted(m["docTree.relinking"])
                        if (result != null) Muted(m["docTree.relinkSummary"]
                            .replace("{links}", result.string("links") ?: "0")
                            .replace("{failed}", result.string("failed") ?: "0"))
                        if (relinks.isNotEmpty()) ScrollList(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(relinks.entries.sortedBy { it.key }, key = { it.key }) { (path, item) ->
                                val status = when (item.string("status")) {
                                    "updated", "unchanged", "failed" -> item.string("status")
                                    else -> "pending"
                                }
                                Row(Modifier.padding(vertical = StudioTokens.space1), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                                    if (status == "pending") StudioLoadingIcon(Modifier.size(StudioTokens.compactIconSize))
                                    Column {
                                        LibraryCaption(path, mono = true, maxLines = 2)
                                        Muted(item.string("error") ?: m["docTree.relink.$status"])
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (hasDrafts) Muted(m["docTree.drafts"])
            if (fence == null && problem == null) Muted(m["loading"])
            problem?.let { ProblemText(it) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End)) {
                if (renamedTo != null || renamedResult != null) {
                    WorkAction(m["close"], null, enabled = !busy, onClick = dismiss)
                } else {
                    WorkAction(m["cancel"], null, enabled = !busy, onClick = dismiss)
                    WorkAction(m[when {
                            request.action == DocumentTreeAction.CREATE -> "create"
                            deleting -> "work.delete"
                            else -> "saveDocument"
                        }], if (deleting) "trash-2" else "check", enabled = canSubmit, primary = !deleting,
                        danger = deleting, onClick = ::submit)
                }
            }
        }
    }
}

private val documentFolderIcons = listOf("book-open", "file-text", "clipboard-list", "archive", "lightbulb", "users",
    "message-square", "pen-tool", "palette", "code", "terminal", "bug", "flask-conical", "wrench", "database", "gamepad-2",
    "map", "crown", "image", "music", "sparkles", "zap", "star")

@Composable
private fun DocumentFolderAppearance(color: String?, icon: String?, enabled: Boolean,
                                     setColor: (String?) -> Unit, setIcon: (String?) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val theme = localStudioTheme.current
    LibrarySectionTitle(m["docTree.color"])
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        DocumentColorSwatch(m["docTree.defaultColor"], colors.muted, color == null, enabled, icon = "ban") { setColor(null) }
        documentFolderColors.forEach { each ->
            DocumentColorSwatch(m["docTree.color.$each"], graphFolderColor(each, colors, theme), each == color, enabled) {
                setColor(each)
            }
        }
    }
    LibrarySectionTitle(m["docTree.icon"])
    FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        LibraryIconAction("folder", m["docTree.defaultIcon"], enabled = enabled, selected = icon == null) { setIcon(null) }
        documentFolderIcons.forEach { name ->
            LibraryIconAction(name, m["docTree.icon.$name"], enabled = enabled, selected = icon == name) { setIcon(name) }
        }
    }
}
