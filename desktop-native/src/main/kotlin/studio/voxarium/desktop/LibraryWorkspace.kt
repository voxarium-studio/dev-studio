package studio.voxarium.desktop

import androidx.compose.ui.unit.TextUnit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar

internal class LibraryWorkspaceState {
    val openDirectories = mutableStateMapOf<String, Boolean>()
    val closedDocFolders = mutableStateMapOf<String, Boolean>()
    var explorerWidth by mutableStateOf(288.dp)
    var docsWidth by mutableStateOf(256.dp)
    var docsPanel by mutableStateOf(true)
    var documentAction by mutableStateOf<DocumentTreeRequest?>(null)
    val explorerActions = ExplorerTreeActions()
}

private data class LibraryTreeEntry(
    val path: String,
    val title: String,
    val folder: Boolean,
    val kind: String = "text",
)

private data class LibraryTreeRow(val entry: LibraryTreeEntry, val depth: Int, val empty: Boolean = false)

private fun List<JsonObject>.fileEntries(): List<LibraryTreeEntry> = mapNotNull { file ->
    val path = file.string("path") ?: return@mapNotNull null
    LibraryTreeEntry(path, file.string("name") ?: path.substringAfterLast('/'),
        file.flag("is_dir"), file.string("kind").orEmpty())
}

private fun visibleTree(
    children: Map<String, List<LibraryTreeEntry>>,
    expanded: (String) -> Boolean,
    indentEmpty: Boolean,
): List<LibraryTreeRow> = buildList {
    fun addChildren(path: String, depth: Int) {
        val entries = children[path] ?: return
        if (indentEmpty && depth > 0 && entries.isEmpty()) {
            add(LibraryTreeRow(LibraryTreeEntry(path, "", false), depth, empty = true))
        }
        for (entry in entries) {
            add(LibraryTreeRow(entry, depth))
            if (entry.folder && expanded(entry.path)) addChildren(entry.path, depth + 1)
        }
    }
    addChildren("", 0)
}

private fun documentChildren(items: List<WorkspaceItem>): Map<String, List<LibraryTreeEntry>> {
    val children = mutableMapOf<String, MutableMap<String, LibraryTreeEntry>>()
    for (item in items) {
        var parent = ""
        val names = item.id.split('/')
        for ((index, name) in names.withIndex()) {
            val path = if (parent.isEmpty()) name else "$parent/$name"
            val folder = index < names.lastIndex
            children.getOrPut(parent) { mutableMapOf() }.putIfAbsent(path,
                LibraryTreeEntry(path, if (folder) name else item.title, folder))
            parent = path
        }
    }
    return children.mapValues { (_, entries) ->
        entries.values.sortedWith(compareBy({ !it.folder }, { it.title.lowercase() }))
    }
}

// §6.2 and §10.4: Explorer and Documents share their tree geometry with Electron.
@Suppress("DEPRECATION")
@Composable
internal fun LibraryWorkspaceSection(
    section: Section,
    tab: ProjectTab,
    navigate: (Page) -> Unit,
    workspace: Workspace,
    modifier: Modifier,
) {
    val m = localMessages.current
    val colors = localColors.current
    val state = tab.libraryWorkspace
    val reducedMotion = LocalStudioReducedMotion.current
    val docs = section == Section.DOCUMENTS
    val selection = tab.page.selection(section)
    val api = tab.api
    val explorerChildren = remember(api) { mutableStateMapOf<String, List<LibraryTreeEntry>>() }
    val treeErrors = remember(api) { mutableStateMapOf<String, Problem>() }
    var searchOpen by remember(tab) { mutableStateOf(false) }
    var search by remember(tab) { mutableStateOf(TextFieldValue()) }
    var searchResults by remember(tab) { mutableStateOf<List<JsonObject>?>(null) }
    var searchProblem by remember(tab) { mutableStateOf<Problem?>(null) }
    var explorerRefreshing by remember(api, tab.pageRefresh) { mutableStateOf(!docs && api != null) }
    val searchQuery = search.text.trim()
    val searching = docs && searchOpen && searchQuery.isNotEmpty()

    LaunchedEffect(api, selection, tab.list?.directory) {
        if (docs || selection == null) return@LaunchedEffect
        val parent = selection.substringBeforeLast('/', "")
        var path = if (tab.list?.directory == selection) selection else parent
        while (path.isNotEmpty()) {
            if (!state.openDirectories.containsKey(path)) state.openDirectories[path] = true
            path = path.substringBeforeLast('/', "")
        }
    }
    if (!docs && api != null) {
        val paths = listOf("") + state.openDirectories.keys.filter { path ->
            var folder = path
            while (folder.isNotEmpty() && state.openDirectories[folder] == true) {
                folder = folder.substringBeforeLast('/', "")
            }
            folder.isEmpty()
        }
        for (path in paths) {
            key(path) {
                LaunchedEffect(api, tab.pageRefresh) {
                    treeErrors.remove(path)
                    try {
                        explorerChildren[path] = api.get("/explorer/tree" + query("path" to path))
                            .jsonObject.objects("entries").fileEntries()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        treeErrors[path] = error.problem()
                    } finally {
                        if (path.isEmpty()) explorerRefreshing = false
                    }
                }
            }
        }
    }
    LaunchedEffect(api, searching, searchQuery, tab.list) {
        searchResults = null
        searchProblem = null
        if (!searching || api == null) return@LaunchedEffect
        delay(150)
        try {
            searchResults = api.get("/docs/search" + query("q" to searchQuery)).objects()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            searchProblem = error.problem()
        }
    }
    val list = tab.list
    var folderLooks by remember(api) { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    LaunchedEffect(api, docs, tab.pageRefresh) {
        if (docs && api != null) {
            try {
                folderLooks = api.get("/docs/folders").objects().mapNotNull { item ->
                    item.string("path")?.let { it to item }
                }.toMap()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }
    val docsChildren = remember(list) { documentChildren(list?.items.orEmpty()) }
    val explorerRows = if (docs) emptyList() else visibleTree(explorerChildren, { state.openDirectories[it] == true }, true)
    val displayRows = if (docs) visibleTree(docsChildren, { state.closedDocFolders[it] != true }, false) else explorerRows
    val explorerActions = state.explorerActions
    val clipboard = LocalClipboardManager.current
    val rootTarget = ExplorerTarget("", true)
    val selectedTarget = selection?.takeUnless { docs }?.let { path ->
        ExplorerTarget(path, list?.directory == path || explorerRows.any { it.entry.path == path && it.entry.folder })
    } ?: rootTarget
    val canOperate = api != null && !tab.acting && !explorerActions.busy && explorerActions.request == null
    fun adoptExplorerPath(page: Page) {
        tab.navigate(page)
        workspace.scheduleSave()
    }
    fun explorerAction(action: ExplorerAction, target: ExplorerTarget) {
        if (canOperate) explorerActions.start(action, target, tab, ::adoptExplorerPath) {
            clipboard.setText(AnnotatedString(it))
        }
    }
    @Composable
    fun TreeContext(target: ExplorerTarget, subject: String, select: () -> Unit = {},
                    content: @Composable (Modifier) -> Unit) {
        ExplorerContextTarget(target, subject, canOperate, explorerActions.clipboard != null,
            explorerActions::clearClipboard, select, { explorerAction(it, target) }, content)
    }
    val defaultWidth = if (docs) 256.dp else 288.dp
    val maxPane = if (docs) 480.dp else 560.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val ceiling = (maxWidth - 320.dp).coerceIn(200.dp, maxPane)
        val chosen = if (docs) state.docsWidth else state.explorerWidth
        val width = chosen.coerceAtMost(ceiling)
        fun resize(value: Dp) {
            if (docs) state.docsWidth = value else state.explorerWidth = value
        }
        Row(Modifier.fillMaxSize()) {
            val paneShape = RoundedCornerShape(StudioTokens.radiusLg)
            Column(Modifier.width(width).fillMaxHeight().clip(paneShape).background(colors.surface)) {
                Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.rowHeight).padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space1),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    val title: @Composable (Modifier) -> Unit = { titleModifier ->
                        Text(m[if (docs) "documents" else "libraryExplorerTitle"].uppercase(Locale.forLanguageTag(m.language)),
                            modifier = titleModifier,
                            color = colors.muted, fontSize = StudioTokens.textSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.weight(1f)) {
                        if (docs) title(Modifier) else TreeContext(rootTarget, m["explorerTree.root"]) { keys ->
                            val shape = RoundedCornerShape(StudioTokens.radiusSm)
                            title(keys.fillMaxWidth().studioFocusRing(shape, canOperate, colors.surface)
                                .focusable(canOperate).padding(vertical = StudioTokens.space1))
                        }
                    }
                    if (docs) {
                        LibraryIconAction("file-plus", m["docTree.create"], enabled = api != null && !tab.acting) {
                            state.documentAction = DocumentTreeRequest(DocumentTreeAction.CREATE, selection?.substringBeforeLast('/', "").orEmpty())
                        }
                        LibraryIconAction("search", m["librarySearch"], selected = searchOpen) {
                            searchOpen = !searchOpen
                        }
                    } else {
                        LibraryIconAction("file-plus-2", m[ExplorerAction.NEW_FILE.label], enabled = canOperate) {
                            explorerAction(ExplorerAction.NEW_FILE, selectedTarget)
                        }
                        LibraryIconAction("folder-plus", m[ExplorerAction.NEW_FOLDER.label], enabled = canOperate) {
                            explorerAction(ExplorerAction.NEW_FOLDER, selectedTarget)
                        }
                        LibraryIconAction("refresh-cw", m["refresh"], loading = explorerRefreshing) {
                            tab.refresh()
                        }
                    }
                }
                Rule()
                StudioDisclosure(docs && searchOpen) {
                    Column {
                        LibrarySearchField(search, { search = it }, m["libraryDocSearch"], Modifier.padding(StudioTokens.space2))
                        Rule()
                    }
                }
                val problem = if (searching) searchProblem else if (docs) tab.listError else treeErrors.values.firstOrNull()
                problem?.let { WorkProblemBanner(it) }
                if (!docs) explorerActions.problem?.let { failure ->
                    Row(Modifier.fillMaxWidth().background(colors.dangerSurface).padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space2),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ProblemText(failure) }
                        LibraryIconAction("x", m["close"]) { explorerActions.problem = null }
                    }
                }
                LibraryTreeList(Modifier.weight(1f).fillMaxWidth().padding(vertical = StudioTokens.space1)) {
                    if (searching) {
                        val results = searchResults.orEmpty()
                        if (results.isEmpty() && problem == null) item {
                            LibraryTreeHint(m[if (searchResults == null) "loading" else "noMatches"], loading = searchResults == null)
                        }
                        items(results, key = { it.string("path").orEmpty() }) { result ->
                            val path = requireNotNull(result.string("path"))
                            StudioSelectionRow(path == selection, onClick = {
                                searchOpen = false
                                navigate(Page(section.route, Selectors(path = path)))
                            }, modifier = Modifier.fillMaxWidth(), role = Role.Button) {
                                Column {
                                    Text(result.string("title") ?: path, fontWeight = FontWeight.Medium, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    result.objects("matches").forEach { hit ->
                                        LibraryCaption(hit.string("text").orEmpty())
                                    }
                                }
                            }
                        }
                    } else {
                        if (displayRows.isEmpty() && problem == null) item {
                            val loaded = if (docs) list != null else explorerChildren.containsKey("")
                            LibraryTreeHint(m[if (!loaded) "loading" else if (docs) "libraryDocsEmpty" else "libraryExplorerEmpty"],
                                loading = !loaded)
                        }
                        items(displayRows, key = { (entry, _, empty) -> (if (empty) "empty:" else "entry:") + entry.path }) { row ->
                            val entry = row.entry
                            val movement = Modifier.animateItem(
                                fadeInSpec = if (reducedMotion) null else tween(StudioMotion.quick),
                                placementSpec = if (reducedMotion) null else tween(StudioMotion.normal, easing = StudioMotion.standard),
                                fadeOutSpec = if (reducedMotion) null else tween(StudioMotion.quick),
                            )
                            if (row.empty) {
                                Text(m["libraryFolderEmpty"], movement.padding(start = StudioTokens.space3 * row.depth + StudioTokens.space5),
                                    color = colors.faint, fontStyle = FontStyle.Italic)
                            } else {
                                val expanded = entry.folder && if (docs) state.closedDocFolders[entry.path] != true
                                    else state.openDirectories[entry.path] == true
                                val style = folderLooks[entry.path]
                                val rowContent: @Composable (Modifier) -> Unit = { keys ->
                                    val cut = !docs && explorerActions.clipboard?.let { it.cut && it.source.path == entry.path } == true
                                    LibraryTreeItem(row, entry.path == selection, expanded, style, keys.then(
                                        if (cut) movement.graphicsLayer { alpha = StudioTokens.draggedOpacity } else movement)) {
                                        if (entry.folder) {
                                            if (docs) state.closedDocFolders[entry.path] = expanded
                                            else state.openDirectories[entry.path] = !expanded
                                        }
                                        if (!docs || !entry.folder) navigate(Page(section.route, Selectors(path = entry.path)))
                                    }
                                }
                                if (!docs) TreeContext(ExplorerTarget(entry.path, entry.folder), entry.title, select = {
                                    if (selection != entry.path) adoptExplorerPath(Page(section.route, Selectors(path = entry.path)))
                                }, content = rowContent) else {
                                    val choices = if (entry.folder) listOf(DocumentTreeAction.CREATE, DocumentTreeAction.FOLDER_RENAME,
                                        DocumentTreeAction.FOLDER_STYLE, DocumentTreeAction.FOLDER_DELETE)
                                    else listOf(DocumentTreeAction.MOVE, DocumentTreeAction.DELETE)
                                    StudioContextTarget(entry.title, choices.map { action ->
                                        val danger = action == DocumentTreeAction.DELETE || action == DocumentTreeAction.FOLDER_DELETE
                                        StudioMenuItem(m[action.label], enabled = !tab.acting, dividerBefore = danger, danger = danger) {
                                            state.documentAction = DocumentTreeRequest(action, entry.path, style)
                                        }
                                    }, Modifier.fillMaxWidth(), content = rowContent)
                                }
                            }
                        }
                        if (!docs) item(key = "explorer-root-menu") {
                            TreeContext(rootTarget, m["explorerTree.root"]) { keys ->
                                Spacer(keys.fillMaxWidth().heightIn(min = StudioTokens.controlMinHeight)
                                    .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), canOperate, colors.surface)
                                    .focusable(canOperate)
                                    .semantics { contentDescription = m["explorerTree.root"] })
                            }
                        }
                    }
                }
                if (!docs) explorerActions.clipboard?.let { copied ->
                    Rule()
                    Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space1),
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                        LibraryCaption(m[if (copied.cut) "explorerTree.cutStatus" else "explorerTree.copyStatus"]
                            .replace("{name}", copied.source.path.substringAfterLast('/')), Modifier.weight(1f))
                        LibraryIconAction("clipboard-list", m[ExplorerAction.PASTE.label], enabled = canOperate) {
                            explorerAction(ExplorerAction.PASTE, selectedTarget)
                        }
                        LibraryIconAction("x", m["explorerTree.clearClipboard"], enabled = canOperate) {
                            explorerActions.clearClipboard()
                        }
                    }
                }
                if (!docs && !workspace.preferences.flag("show_data_dir")) {
                    Rule()
                    LibraryCaption(m["libraryDataDirHidden"], Modifier.padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space2),
                        maxLines = 3)
                }
            }
            LibraryPaneResize(width, defaultWidth.coerceAtMost(ceiling), ceiling, ::resize)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                tab.actionError?.let { WorkProblemBanner(it) }
                val detail = tab.detail?.takeIf { it.item.id == selection }
                val error = tab.detailError
                when {
                    docs && selection != null && (detail != null || selection in tab.documentDrafts) ->
                        key(selection) { DocumentPanel(tab, selection, detail, navigate, workspace,
                            manageDocument = { state.documentAction = DocumentTreeRequest(it, selection) }) }
                    error != null -> Centered { ProblemText(error) }
                    !docs && detail != null -> key(selection) { ExplorerPreview(tab, detail, workspace) }
                    selection != null && (docs || tab.list?.directory != selection) -> Centered {
                        StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                        Muted(m["loading"])
                    }
                    docs -> LibraryEmpty("file-text", m["libraryPickDoc"], m["libraryPickDocHint"])
                    else -> LibraryEmpty("file-search", m["libraryPickFile"])
                }
            }
        }
        if (docs) state.documentAction?.let { request ->
            key(request) { DocumentTreeDialog(tab, request, navigate) { state.documentAction = null } }
        }
        if (!docs) explorerActions.request?.let { request ->
            key(request) { ExplorerTreeDialog(tab, explorerActions, request, ::adoptExplorerPath) }
        }
    }
}

@Composable
private fun LibraryTreeItem(row: LibraryTreeRow, selected: Boolean, expanded: Boolean, folderStyle: JsonObject?,
                            modifier: Modifier, activate: () -> Unit) {
    val colors = localColors.current
    val entry = row.entry
    StudioSelectionRow(selected, activate, modifier.fillMaxWidth().heightIn(min = StudioTokens.rowHeight), role = Role.Button) {
        Box(Modifier.padding(start = StudioTokens.space3 * row.depth).size(StudioTokens.iconSize),
            contentAlignment = Alignment.Center) {
            if (entry.folder) LibraryChevron(expanded)
        }
        val savedIcon = folderStyle?.string("icon")
        val icon = graphFolderIcon(savedIcon)
        StudioIcon(when {
            entry.folder -> if (icon == "folder" && expanded) "folder-open" else icon
            entry.kind == "text" -> "file-text"
            entry.kind == "image" -> "file-image"
            entry.kind == "model" -> "box"
            else -> "file"
        }, Modifier.size(StudioTokens.iconSize), if (entry.folder) graphFolderColor(folderStyle?.string("color"), colors, localStudioTheme.current)
            else colors.faint)
        Text(entry.title, color = if (selected) colors.onAccentContainer else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LibraryChevron(expanded: Boolean, modifier: Modifier = Modifier, size: Dp = StudioTokens.compactIconSize) {
    val duration = if (LocalStudioReducedMotion.current) 0 else StudioMotion.fast
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(duration, easing = StudioMotion.standard))
    StudioIcon("chevron-right", modifier.size(size).graphicsLayer { rotationZ = rotation }, localColors.current.faint)
}

@Composable
internal fun LibrarySidePane(visible: Boolean, content: @Composable RowScope.() -> Unit) {
    val reduced = LocalStudioReducedMotion.current
    val enter = if (reduced) 0 else StudioMotion.normal
    val exit = if (reduced) 0 else StudioMotion.fast
    AnimatedVisibility(
        visible, Modifier.fillMaxHeight(),
        enter = expandHorizontally(tween(enter, easing = StudioMotion.standard), expandFrom = Alignment.End) +
            fadeIn(tween(if (reduced) 0 else StudioMotion.quick)),
        exit = shrinkHorizontally(tween(exit, easing = StudioMotion.standard), shrinkTowards = Alignment.End) +
            fadeOut(tween(if (reduced) 0 else StudioMotion.quick)),
    ) {
        Row(Modifier.fillMaxHeight(), content = content)
    }
}

@Composable
private fun LibraryTreeList(modifier: Modifier, content: LazyListScope.() -> Unit) {
    val scroll = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, content = content)
        if (scroll.canScrollForward || scroll.canScrollBackward) {
            VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun LibraryTreeHint(text: String, loading: Boolean = false) {
    Row(Modifier.padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space2), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
        verticalAlignment = Alignment.CenterVertically) {
        if (loading) StudioLoadingIcon(Modifier.size(StudioTokens.compactIconSize))
        Text(text, color = localColors.current.faint)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryPaneResize(width: Dp, default: Dp, ceiling: Dp, resize: (Dp) -> Unit) {
    val density = LocalDensity.current
    val color = localColors.current.accent
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val line = studioColor(if (hovered) color else Color.Transparent)
    var dragWidth by remember { mutableStateOf(width) }
    val drag = rememberDraggableState {
        dragWidth = (dragWidth + with(density) { it.toDp() }).coerceIn(200.dp, ceiling)
        resize(dragWidth)
    }
    // The gutter is a drag hit target and preserves its independent hit geometry.
    Box(Modifier.fillMaxHeight().width(5.dp).hoverable(interaction)
        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
        .combinedClickable(onClick = {}, onDoubleClick = { resize(default) })
        .draggable(drag, Orientation.Horizontal, onDragStarted = { dragWidth = width }),
        contentAlignment = Alignment.Center) {
        Spacer(Modifier.width(StudioTokens.borderWidth).fillMaxHeight().background(line))
    }
}

@Suppress("DEPRECATION")
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ExplorerPreview(tab: ProjectTab, detail: WorkspaceDetail, workspace: Workspace) {
    val m = localMessages.current
    val colors = localColors.current
    val clipboard = LocalClipboardManager.current
    val data = detail.data
    val path = detail.item.id
    val text = data.string("text") ?: data.string("hex")
    val encoding = data.string("encoding")
    val metadata = listOfNotNull(data.string("size")?.toLongOrNull()?.let { libraryFileSize(it, m.language) }, encoding)
        .joinToString(" · ")
    val modified = libraryTime(data.string("modified"), m.language)
    var copyCount by remember(path, text) { mutableIntStateOf(0) }
    var copyProblem by remember(path, text) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(copyCount) {
        if (copyCount != 0) {
            delay(1600)
            copyCount = 0
        }
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Leave room for the title/path and two actions at the larger configured text size.
            val showCopyLabel = maxWidth >= (360 + ((colors.textSize - StudioTokens.textSize.value) * 8).toInt()).dp
            val showInlineMetadata = maxWidth >= (540 + ((colors.textSize - StudioTokens.textSize.value) * 8).toInt()).dp
            Column {
                LibraryHeader(detail.item.title, path, titleSize = colors.textSize.sp) {
                    if (showInlineMetadata) Column(horizontalAlignment = Alignment.End) {
                        if (metadata.isNotBlank()) LibraryCaption(metadata)
                        if (modified.isNotBlank()) LibraryCaption(modified)
                    }
                    if (text != null) {
                        val copied = copyCount != 0
                        val label = m[if (copied) "libraryCopied" else "libraryCopy"]
                        StudioTooltip(label) {
                            WorkAction(label, if (copied) "check" else "copy", iconOnly = !showCopyLabel) {
                                try {
                                    clipboard.setText(AnnotatedString(text))
                                    copyProblem = null
                                    copyCount++
                                } catch (error: Exception) {
                                    copyProblem = error.problem()
                                }
                            }
                        }
                    }
                    StudioTooltip(m["revealFile"]) {
                        WorkAction(m["revealFile"], "folder-open", enabled = !tab.acting, iconOnly = true) {
                            tab.action { post("/explorer/reveal", jsonFields("path" to path)) }
                        }
                    }
                }
                if (!showInlineMetadata && (metadata.isNotBlank() || modified.isNotBlank())) {
                    LibraryCaption(listOf(metadata, modified).filter { it.isNotBlank() }.joinToString("  ·  "),
                        Modifier.padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space1))
                }
            }
        }
        copyProblem?.let { WorkProblemBanner(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                data.obj("previewer") != null || data.string("kind") == "image" ->
                    NativePluginFilePreview(tab, path, data, workspace, Modifier.fillMaxSize())
                text != null -> LibraryCodePreview(text, withNumbers = data.string("kind") == "text", chatTab = tab)
                else -> LibraryEmpty(if (data.string("kind") == "model") "box" else "file-question",
                    data.string("message") ?: m["libraryNoPreview"], data.string("mime"))
            }
        }
        if (data.flag("truncated")) {
            Rule()
            Text(m["previewTruncated"], Modifier.padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space1), color = colors.warning,
                fontSize = StudioTokens.textSmall)
        }
    }
}

@Composable
internal fun LibraryCodePreview(text: String, withNumbers: Boolean, modifier: Modifier = Modifier, chatTab: ProjectTab? = null) {
    val scroll = rememberScrollState()
    val colors = localColors.current
    Box(modifier.fillMaxWidth()) {
        val previewModifier = Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState())
            .padding(horizontal = if (withNumbers) 0.dp else StudioTokens.panePad, vertical = StudioTokens.space4)
        // Both rendered source and its nonselectable gutter use identical monospace metrics.
        val content: @Composable () -> Unit = {
            Row {
                if (withNumbers) DisableSelection {
                    val numbers = remember(text) { (1..text.count { it == '\n' } + 1).joinToString("\n") }
                    Text(numbers, Modifier.background(colors.inset).padding(horizontal = StudioTokens.space3), color = colors.muted,
                    fontSize = StudioTokens.textSmall, fontFamily = localStudioMono.current,
                    lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier, textAlign = TextAlign.End)
                }
                Text(text, Modifier.padding(start = if (withNumbers) StudioTokens.space3 else 0.dp), fontSize = StudioTokens.textSmall,
                    fontFamily = localStudioMono.current, lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
            }
        }
        if (chatTab == null) SelectionContainer(previewModifier, content)
        else ChatReadableSelection(chatTab, chatTab.page, previewModifier, content = content)
        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun LibraryHeader(
    title: String,
    subtitle: String?,
    titleSize: TextUnit = StudioTokens.titleSize,
    monoSubtitle: Boolean = false,
    minHeight: Dp = Dp.Unspecified,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = minHeight).background(localColors.current.surface, RoundedCornerShape(StudioTokens.radiusMd))
        .padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = titleSize, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            subtitle?.let { LibraryCaption(it, mono = monoSubtitle) }
        }
        actions()
    }
}

@Composable
internal fun LibraryCaption(text: String, modifier: Modifier = Modifier, mono: Boolean = false, maxLines: Int = 1) {
    Text(text, modifier, color = localColors.current.muted, fontSize = StudioTokens.textSmall,
        fontFamily = if (mono) localStudioMono.current else null, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun LibrarySectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, fontWeight = FontWeight.Medium)
}

@Composable
internal fun LibraryCard(selected: Boolean = false, padding: Dp = StudioTokens.space3, gap: Dp = StudioTokens.space2,
                         content: @Composable ColumnScope.() -> Unit) {
    val colors = localColors.current
    val background = studioColor(if (selected) colors.selected else colors.cardFill)
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    Column(Modifier.fillMaxWidth().background(background, shape).border(StudioTokens.borderWidth, colors.cardBorder, shape)
        .padding(padding), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
internal fun LibraryButton(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) =
    WorkAction(label, icon, enabled = enabled, onClick = onClick)

@Composable
internal fun LibraryEmpty(icon: String, title: String, hint: String? = null) = WorkEmpty(title, hint, icon)

@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun LibraryIconAction(
    icon: String, label: String, enabled: Boolean = true, bordered: Boolean = false,
    selected: Boolean = false, size: Dp = StudioTokens.controlMinHeight, loading: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (bordered) StudioTokens.radiusMd else StudioTokens.radiusSm)
    StudioTooltip(label) {
        StudioAction(label, icon, Modifier.size(size), enabled, primary = false,
            outlined = bordered, iconOnly = true, danger = false, busy = loading, onClick = onClick,
            shape = shape, tint = localColors.current.muted, selected = selected)
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun LibrarySearchField(value: TextFieldValue, change: (TextFieldValue) -> Unit, label: String,
                                modifier: Modifier = Modifier) {
    StudioField(label, value, change, modifier.fillMaxWidth(), placeholder = label)
}

internal fun libraryTime(value: String?, language: String): String {
    if (value.isNullOrBlank()) return "—"
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }
        .getOrNull() ?: return value
    return DateTimeFormatter.ofPattern(if (language == "ru") "d MMM, HH:mm" else "MMM d, h:mm a",
        Locale.forLanguageTag(language)).format(instant.atZone(ZoneId.systemDefault()))
}

private fun libraryFileSize(size: Long, language: String): String {
    val locale = Locale.forLanguageTag(language)
    val units = if (language == "ru") listOf("Б", "КБ", "МБ", "ГБ") else listOf("B", "KB", "MB", "GB")
    var amount = size.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit++
    }
    return String.format(locale, if (unit == 0) "%.0f %s" else "%.1f %s", amount, units[unit])
}
