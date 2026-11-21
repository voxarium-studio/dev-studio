@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package studio.voxarium.desktop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.isTraySupported
import java.awt.Desktop
import java.awt.Window
import java.io.File
import java.net.URI
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.ui.component.*

// Native borderless-window hit geometry: these targets share the window-control strip.
private val windowButtonWidth = 46.dp

@Composable
internal fun FrameWindowScope.StudioShell(
    model: WorkspaceWindow,
    workspace: Workspace,
    state: WindowState,
    icon: Painter,
    onClose: () -> Unit,
    openSearch: (() -> Unit)? = null,
) {
    val m = localMessages.current
    val colors = localColors.current
    val tab = model.active
    val pickerVisible = model.picking && workspace.ready && !workspace.quitting
    val titlePickerTrigger = remember { FocusRequester() }
    val welcomePickerTrigger = remember { FocusRequester() }
    var pickerReturn by remember { mutableStateOf<FocusRequester?>(null) }
    fun openPicker(trigger: FocusRequester? = null) {
        if (model.picking) return
        pickerReturn = trigger
        model.picking = true
        workspace.refreshRecent()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().drawBehind { drawRect(colors.shellBrush(size)) }) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(StudioTokens.titlebarHeight)) {
                val windowControlsWidth = windowButtonWidth * (if (isTraySupported) 4 else 3)
                val pickerWidth = StudioTokens.rowHeight + StudioTokens.space2
                val tabWidth = (maxWidth - windowControlsWidth - StudioTokens.railIconWidth -
                    pickerWidth - StudioTokens.rowHeight).coerceAtLeast(0.dp)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    WindowDraggableArea(Modifier.width(StudioTokens.railIconWidth).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(icon, null, Modifier.size(StudioTokens.iconSize + StudioTokens.space1))
                        }
                    }
                    Row(
                        Modifier.widthIn(max = tabWidth).fillMaxHeight().padding(vertical = StudioTokens.space1)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    ) {
                        model.tabs.forEach { project ->
                            key(project.root) { ProjectTabButton(project, model, workspace) }
                        }
                    }
                    ShellIconButton("plus", m["openProject"], Modifier.padding(start = StudioTokens.space2, top = StudioTokens.space1, bottom = StudioTokens.space1)
                        .size(StudioTokens.rowHeight).focusRequester(titlePickerTrigger), iconSize = StudioTokens.iconSize + StudioTokens.space1) { openPicker(titlePickerTrigger) }
                    WindowDraggableArea(Modifier.weight(1f).fillMaxHeight().projectTabDrop(workspace, model)) {}
                    if (isTraySupported) {
                        WindowControl("panel-bottom-close", m["hideToTray"]) { model.visible = false }
                    }
                    WindowControl("minus", m["minimizeWindow"]) { state.isMinimized = true }
                    val maximized = state.placement == WindowPlacement.Maximized
                    WindowControl(if (maximized) "copy" else "square", m[if (maximized) "restoreWindow" else "maximizeWindow"]) {
                        state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                    }
                    WindowControl("x", m["closeWindow"], colors.danger, onClose)
                }
            }
            // §5.3.4: keep the project composition alive while the shell owns the picker,
            // but show only shell chrome behind the modal (as in Electron).
            Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = if (pickerVisible) 0f else 1f }
                .then(if (pickerVisible) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                when {
                    workspace.quitting -> Centered { Text(m["closing"]) }
                    !workspace.ready -> Centered { Text(m["starting"]) }
                    tab == null && !model.settings && !model.remoteAi -> {
                        Column(Modifier.align(Alignment.TopCenter).padding(top = StudioTokens.space6),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                            Muted(m["noProject"], StudioTokens.textSize.value.toInt())
                            WorkAction(m["openProject"], null, Modifier.focusRequester(welcomePickerTrigger), primary = true) {
                                openPicker(welcomePickerTrigger)
                            }
                            LibraryButton("globe", m["remote"]) { model.remoteAi = true }
                        }
                        ShellIconButton("settings", m["settings"], Modifier.align(Alignment.TopEnd).padding(StudioTokens.space2).size(StudioTokens.iconButtonSize)) {
                            model.settings = true
                        }
                    }
                    else -> ProjectWorkspace(tab, model, workspace, { openPicker() }, openSearch)
                }
                if (tab != null && workspace.ready && !workspace.quitting) {
                    key(tab.root) {
                        AutomationToastStack(tab.automationToasts, Modifier.align(Alignment.TopEnd).padding(StudioTokens.space4)) { page ->
                            tab.navigate(page)
                            model.settings = false
                            workspace.scheduleSave()
                        }
                    }
                }
            }
            workspace.problem?.takeIf { workspace.ready && !pickerVisible }?.let { problem ->
                Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space1),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ProblemText(problem) }
                    ShellIconButton("x", m["dismiss"], Modifier.size(StudioTokens.controlMinHeight)) { workspace.problem = null }
                }
            }
            if (tab != null || model.settings) Box(Modifier.graphicsLayer { alpha = if (pickerVisible) 0f else 1f }
                .then(if (pickerVisible) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                StudioStatusBar(model, workspace)
            }
        }
        if (pickerVisible) ProjectPicker(workspace, model, window, pickerReturn)
    }
}

@Composable
private fun WindowControl(icon: String, label: String, hover: Color = localColors.current.hover, onClick: () -> Unit) {
    ShellIconButton(icon, label, Modifier.width(windowButtonWidth).fillMaxHeight(), iconSize = StudioTokens.compactIconSize,
        shape = androidx.compose.ui.graphics.RectangleShape, hover = hover, onClick = onClick)
}

@Composable
private fun ProjectTabButton(tab: ProjectTab, model: WorkspaceWindow, workspace: Workspace) {
    val m = localMessages.current
    val colors = localColors.current
    val selected = tab == model.active
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    Row(Modifier.fillMaxHeight().clip(shape).background(studioColor(if (selected) colors.selected else colors.elevated))
        .projectTabDrop(workspace, model, before = tab),
        verticalAlignment = Alignment.CenterVertically) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Row(Modifier.fillMaxHeight().widthIn(min = 120.dp, max = 260.dp)
            .background(studioColor(if (hovered && !selected) colors.hover else Color.Transparent))
            .hoverable(interaction)
            .studioFocusRing(shape, background = if (selected) colors.selected else if (hovered) colors.hover else colors.elevated)
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab) { workspace.select(model, tab) }
            .projectTabDrag(workspace, model, tab)
            .padding(horizontal = StudioTokens.rowPadX),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
            Text(tab.binding?.name ?: tab.root.substringAfterLast('\\').substringAfterLast('/'),
                modifier = Modifier.weight(1f, fill = false), color = studioColor(if (selected) colors.text else colors.muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (workspace.producerAttention.any { it.matches(tab.binding) }) {
                Box(Modifier.size(StudioTokens.statusDotSize).background(colors.warning, CircleShape)
                    .semantics { contentDescription = m["producerAttention.waiting"] })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                for ((status, icon) in listOf("in_progress" to "play", "needs_review" to "check")) {
                    val count = tab.chromeCounts?.string(status)?.toIntOrNull()
                    val label = "${m.status(status)}: ${count ?: "—"}"
                    val background = if (selected) colors.selected else if (hovered) colors.hover else colors.elevated
                    StudioTooltip(label) {
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.semantics { contentDescription = label }) {
                            Text(count?.toString() ?: "—", color = if (selected) colors.text else colors.muted, fontSize = StudioTokens.textSmall)
                            StudioIcon(icon, Modifier.size(StudioTokens.compactIconSize),
                                if ((count ?: 0) > 0) colors.statusColor(status, background) else colors.faint)
                        }
                    }
                }
            }
        }
        var moving by remember { mutableStateOf(false) }
        Box {
            ShellIconButton("external-link", m["moveTab"], Modifier.width(StudioTokens.controlMinHeight).fillMaxHeight(), StudioTokens.compactIconSize) { moving = true }
            StudioMenu(moving, { moving = false }, buildList {
                add(StudioMenuItem(m["newWindow"], icon = "plus") { workspace.move(model, workspace.newWindow(), tab) })
                for (target in workspace.windows.filter { it != model }) {
                    add(StudioMenuItem(target.active?.binding?.name ?: m["emptyWindow"]) {
                        workspace.move(model, target, tab)
                    })
                }
            })
        }
        ShellIconButton("x", m["closeTab"], Modifier.width(StudioTokens.controlMinHeight).fillMaxHeight(), StudioTokens.compactIconSize) { workspace.closeTab(model, tab) }
    }
}

private data class PickerInspection(val path: String, val exists: Boolean, val directory: Boolean, val project: Boolean)

@Composable
internal fun BoxScope.ProjectPicker(workspace: Workspace, model: WorkspaceWindow, owner: Window?, returnFocus: FocusRequester?) {
    val m = localMessages.current
    val shellFocus = LocalFocusManager.current
    val colors = localColors.current
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(shellFocus, returnFocus) {
        // A focusable popup isolates key events but does not clear the shell's visible ring.
        // Explicit openers can be cleared here and restored by WorkDialog on dismissal.
        if (returnFocus != null) shellFocus.clearFocus(force = true)
    }
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(TextFieldValue()) }
    var inspection by remember { mutableStateOf<PickerInspection?>(null) }
    var listing by remember { mutableStateOf<JsonObject?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Problem?>(null) }
    val validDirectory = inspection?.let {
        it.exists && it.directory && path.text.trim() == it.path
    } == true
    val initializes = validDirectory && inspection?.project != true
    fun useInspection(result: PickerInspection) {
        inspection = result
        path = TextFieldValue(result.path, androidx.compose.ui.text.TextRange(result.path.length))
    }
    fun load(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error.problem()
            } finally {
                busy = false
            }
        }
    }
    suspend fun inspect(root: String): PickerInspection {
        val result = workspace.api.request("/api/fs/inspect" + query("path" to root)).jsonObject
        return PickerInspection(result.string("path") ?: root, result.flag("exists"), result.flag("is_dir"), result.flag("is_project"))
    }
    fun checkPath(root: String) {
        if (root.isNotBlank()) load { useInspection(inspect(root)) }
    }
    fun browse(root: String) = load {
        val next = workspace.api.request("/api/fs/browse" + query("path" to root)).jsonObject
        listing = next
        if (root.isNotEmpty()) {
            // A successful read-only listing already describes this exact canonical directory.
            useInspection(PickerInspection(next.string("path") ?: root, true, true, next.flag("is_project")))
        }
    }
    Box(Modifier.matchParentSize().padding(top = StudioTokens.titlebarHeight)) {
        WorkDialog(m["openProject"], { model.picking = false }, Modifier.padding(StudioTokens.space3)
            .widthIn(max = 600.dp).fillMaxWidth(), canDismiss = !busy,
            alignment = Alignment.TopCenter, returnFocus = returnFocus,
            scrim = colors.projectPickerScrim, initialFocus = cancelFocus) { dismiss ->
            Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                Text(m["openProject"], Modifier.weight(1f), fontWeight = FontWeight.Medium)
                WorkAction(m["cancel"], null, Modifier.focusRequester(cancelFocus), enabled = !busy,
                    outlined = false, onClick = dismiss)
            }
            Rule()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(StudioTokens.space4),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                WorkAction(m["browseComputer"], null, enabled = !busy && owner != null, primary = true) {
                    val picker = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        inspection?.path?.takeIf { validDirectory }?.let { currentDirectory = File(it) }
                    }
                    if (picker.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) checkPath(picker.selectedFile.absolutePath)
                }
                Muted(m["recentProjects"])
                if (workspace.recent.isEmpty()) Muted(m["noRecent"])
                else LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    items(workspace.recent, key = { it.string("root").orEmpty() }) { entry ->
                        val root = entry.string("root").orEmpty()
                        val name = entry.string("name") ?: root
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                StudioTooltip(root) {
                                    ListRow(name, null, selected = false, enabled = !busy && root.isNotEmpty(),
                                        status = if (entry.flag("exists")) null else "unavailable") {
                                        load {
                                            val result = inspect(root)
                                            useInspection(result)
                                            // Recent entries never grant permission to initialize.
                                            if (result.exists && result.directory && result.project) workspace.open(model, result.path)
                                        }
                                    }
                                }
                            }
                            WorkAction(m["forgetRecent"], null,
                                Modifier.semantics { contentDescription = "${m["forgetRecent"]}: $name" },
                                enabled = !busy, outlined = false) { workspace.forgetRecent(root) }
                        }
                    }
                }
                WorkAction(m["browse"], if (browsing) "chevron-down" else "chevron-right",
                    enabled = !busy, outlined = false) {
                    browsing = !browsing
                    if (browsing && listing == null) browse("")
                }
                if (browsing) {
                    val parent = listing?.string("parent")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        WorkAction(m["parentFolder"], "arrow-up", enabled = !busy && parent != null, iconOnly = true) {
                            parent?.let(::browse)
                        }
                        LibraryCaption(listing?.string("path")?.ifEmpty { m["browseComputer"] }.orEmpty(),
                            Modifier.weight(1f), mono = true)
                    }
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        val entries = listing?.objects("entries").orEmpty()
                        if (entries.isEmpty() && listing != null) item { Muted(m["libraryFolderEmpty"]) }
                        items(entries, key = { it.string("path").orEmpty() }) { entry ->
                            val root = entry.string("path").orEmpty()
                            ListRow(entry.string("name") ?: root, null, selected = false, enabled = !busy && root.isNotEmpty()) {
                                browse(root)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    Muted(m["projectFolder"])
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        StudioField(m["projectFolder"], path, {
                            if (path.text != it.text) { inspection = null; failure = null }
                            path = it
                        },
                            Modifier.weight(1f).onPreviewKeyEvent {
                                if (it.key != Key.Enter && it.key != Key.NumPadEnter) false
                                else { if (it.type == KeyEventType.KeyUp) checkPath(path.text.trim()); true }
                            }, enabled = !busy, error = inspection != null && !validDirectory,
                            errorMessage = m.status("unavailable"))
                        WorkAction(m["browse"], null, enabled = !busy && path.text.isNotBlank()) { checkPath(path.text.trim()) }
                    }
                    if (inspection != null && !validDirectory) Muted(m.status("unavailable"))
                    if (initializes) Muted(m["initializeProject"])
                }
                failure?.let { ProblemText(it) }
                workspace.problem?.let { problem ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ProblemText(problem) }
                        ShellIconButton("x", m["dismiss"], Modifier.size(StudioTokens.controlMinHeight)) { workspace.problem = null }
                    }
                }
            }
            Rule()
            Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (busy) StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                WorkAction(m[if (initializes) "initialize" else "open"], null, enabled = validDirectory && !busy,
                    primary = true) { workspace.open(model, path.text.trim(), create = initializes) }
            }
        }
    }
}

private data class NavEntry(val section: Section, val icon: String, val tint: String = "")

private val navigation = listOf(
    NavEntry(Section.EXPLORER, "folder-tree", "explorer"),
    NavEntry(Section.DOCUMENTS, "file-text", "documents"),
    NavEntry(Section.DOCUMENT_GRAPH, "waypoints", "documents"),
    NavEntry(Section.CHATS, "message-circle", "teams"),
    NavEntry(Section.PIPELINES, "workflow", "pipelines"),
    NavEntry(Section.AUTOMATIONS, "zap", "automations"),
    NavEntry(Section.TASKS, "square-kanban", "tasks"),
    NavEntry(Section.PRODUCER, "clapperboard", "tasks"),
    NavEntry(Section.TEAMS, "users", "teams"),
    NavEntry(Section.STATISTICS, "chart-line", "statistics"),
    NavEntry(Section.VCS, "git-branch", "vcs"),
    NavEntry(Section.MEMORY, "brain", "memory"),
    NavEntry(Section.SKILLS, "graduation-cap", "skills"),
    NavEntry(Section.MCP, "plug", "mcp"),
    NavEntry(Section.PLUGINS, "blocks", "plugins"),
    NavEntry(Section.PROJECT_SETTINGS, "folder-cog"),
    NavEntry(Section.REMOTE_AI, "globe", "teams"),
)

@Composable
private fun ProjectWorkspace(tab: ProjectTab?, model: WorkspaceWindow, workspace: Workspace, openPicker: () -> Unit,
                             openSearch: (() -> Unit)?) {
    val m = localMessages.current
    val colors = localColors.current
    val section = tab?.let { Section.from(it.page) }
    val sidebar = localStudioTheme.current?.string("sidebar") ?: "icons"
    val reduced = LocalStudioReducedMotion.current
    val railWidth by animateDpAsState(if (sidebar == "labels") StudioTokens.railWidth else StudioTokens.railIconWidth,
        tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Reserved navigation width")
    fun navigate(page: Page) {
        tab?.navigate(page)
        if (tab != null) model.settings = false
        workspace.scheduleSave()
    }
    fun openWeb(route: String) {
        val target = tab?.api?.web(route) ?: URI.create("$ORIGIN$route")
        openBrowser(target, workspace)
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().padding(start = railWidth, top = StudioTokens.space2, end = StudioTokens.space2)
            .clip(RoundedCornerShape(StudioTokens.radiusXl)).background(colors.canvas).background(colors.pageFill)
            .padding(StudioTokens.space2)) {
            when {
                model.settings -> Settings(workspace, tab?.api, model.settingsAnchor, ::openWeb)
                tab == null && model.remoteAi -> RemoteAiPage(null, workspace.api, m) { openWeb("/remote-ai") }
                tab == null -> Unit
                tab.binding == null -> {
                    val scope = rememberCoroutineScope()
                    Centered {
                        if (tab.connectionProblem == null || tab.connecting) {
                            StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                            Text(m["openingProject"])
                        } else {
                            tab.connectionProblem?.let { ProblemText(it) }
                            WorkAction(m["retry"], "refresh-cw") { scope.launch { tab.connect() } }
                        }
                    }
                }
                else -> key(tab.root) {
                    if (section == Section.CHATS) {
                        ProjectSection(tab, workspace, ::navigate, ::openWeb, openPicker)
                    } else {
                        FloatingChatWorkspace(tab, workspace.chatPanelPreference, ::navigate,
                            reserveLauncherSpace = section == Section.DOCUMENT_GRAPH || section == Section.STATISTICS) {
                            ProjectSection(tab, workspace, ::navigate, ::openWeb, openPicker)
                        }
                    }
                }
            }
        }
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val expanded = sidebar == "labels" || sidebar == "auto" && hovered
        val width by animateDpAsState(if (expanded) StudioTokens.railWidth else StudioTokens.railIconWidth,
            tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Navigation rail width")
        val rowHeight by animateDpAsState(if (expanded) maxOf(StudioTokens.rowHeight,
            (colors.textSize * StudioTokens.lineHeightMultiplier).dp + StudioTokens.space3) else StudioTokens.controlMinHeight,
            tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Navigation row height")
        val expansion by animateFloatAsState(if (expanded) 1f else 0f,
            tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Navigation labels")
        val railFill = studioColor(if (expanded && sidebar == "auto" && colors.railFill == Color.Transparent) colors.strip else colors.railFill)
        var info by remember { mutableStateOf<String?>(null) }
        Column(Modifier.width(width).fillMaxHeight()
            .background(railFill).hoverable(interaction).clipToBounds().padding(top = StudioTokens.space2)) {
            if (tab != null && openSearch != null) {
                Box(Modifier.padding(start = StudioTokens.space2, end = StudioTokens.space2, bottom = StudioTokens.space2)) {
                    RailRow("search", m["librarySearch"], expansion, rowHeight, false, colors.muted,
                        showMarker = false, search = true, onClick = openSearch)
                }
            }
            val railScroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(railScroll)
                    .padding(start = StudioTokens.space2, end = StudioTokens.space2 + StudioTokens.space2 * expansion),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    if (tab != null) {
                        val activeIndex = navigation.indexOfFirst { it.section == section }
                        val indicator by animateFloatAsState(activeIndex.coerceAtLeast(0).toFloat(),
                            tween(if (reduced) 0 else StudioMotion.travel, easing = StudioMotion.spring),
                            label = "Navigation selection")
                        val marker = studioColor(if (activeIndex >= 0 && !model.settings) colors.secondary else Color.Transparent)
                        Box(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                                navigation.forEach { entry ->
                                    val selected = !model.settings && entry.section == section
                                    val waiting = if (entry.section == Section.PRODUCER) {
                                        workspace.producerAttention.firstOrNull { it.matches(tab.binding) }
                                    } else null
                                    RailRow(entry.icon, m[entry.section.label], expansion, rowHeight,
                                        selected, colors.navigationTint(entry.tint), showMarker = false, attention = waiting != null) {
                                        navigate(waiting?.page ?: Page(entry.section.route))
                                    }
                                }
                            }
                            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(
                                0, ((rowHeight + StudioTokens.space2) * indicator + (rowHeight - StudioTokens.iconSize) / 2f).roundToPx(),
                            ) }.size(StudioTokens.focusWidth, StudioTokens.iconSize).background(marker, CircleShape))
                        }
                        tab.chromePluginPages.takeIf { it.isNotEmpty() }?.let { pages ->
                            Spacer(Modifier.height(StudioTokens.spaceHair))
                            if (expanded) Muted(m["tools"], modifier = Modifier.padding(start = StudioTokens.space2))
                            pages.forEach { page ->
                                val route = page.string("route")
                                    ?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return@forEach
                                RailRow("blocks", page.string("title") ?: route, expansion, rowHeight,
                                    !model.settings && tab.page.path == route, colors.navigationTint("plugin-page")) {
                                    navigate(Page(route))
                                }
                            }
                        }
                    } else {
                        RailRow("globe", m["remote"], expansion, rowHeight, model.remoteAi && !model.settings, colors.muted) {
                            model.settings = false
                            model.remoteAi = true
                        }
                    }
                }
                if (railScroll.maxValue > 0) {
                    VerticalScrollbar(rememberScrollbarAdapter(railScroll),
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            Column(Modifier.fillMaxWidth().padding(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                RailRow("circle-arrow-up", m["updates"], expansion, rowHeight, false, colors.navigationTint("update"), lifted = true) {
                    model.settingsAnchor = "updates"
                    model.settings = true
                }
                RailRow("scroll-text", m["changelog"], expansion, rowHeight, false, colors.muted) { info = "changelog" }
                RailRow("settings", m["settings"], expansion, rowHeight, model.settings, colors.muted) {
                    model.settingsAnchor = null
                    model.settings = true
                }
                RailRow("info", m["about"], expansion, rowHeight, false, colors.muted) { info = "about" }
            }
        }
        info?.let { StudioInfo(it, workspace) { info = null } }
    }
}

@Composable
private fun ProjectSection(tab: ProjectTab, workspace: Workspace, navigate: (Page) -> Unit,
                           openWeb: (String) -> Unit, openPicker: () -> Unit) {
    val m = localMessages.current
    val section = Section.from(tab.page)
    val body = Modifier.fillMaxSize()
    when (section) {
        Section.DOCUMENT_GRAPH -> DocumentGraphPage(tab, navigate, workspace, body)
        Section.STATISTICS -> StatisticsPage(tab, body)
        Section.AUTOMATIONS -> tab.api?.let { AutomationsPage(tab, it, m, navigate) }
        Section.MCP -> McpPage(tab, body)
        Section.REMOTE_AI -> RemoteAiPage(tab, workspace.api, m) { openWeb("/remote-ai") }
        Section.PROJECT_SETTINGS -> tab.api?.let { api ->
            ProjectSettingsPage(tab, api, workspace.api, m,
                openRemote = { openWeb("/project-settings#settings-sync") },
                openRemoteAi = { openWeb("/project-settings#project-remote-ai") },
                openProjectPicker = openPicker)
        }
        Section.VCS -> VersionControlPage(tab, body)
        Section.MEMORY -> ReferencePage(tab, skills = false, modifier = body)
        Section.SKILLS -> ReferencePage(tab, skills = true, modifier = body)
        Section.DOCUMENTS, Section.EXPLORER -> LibraryWorkspaceSection(section, tab, navigate, workspace, body)
        else -> WorkspaceSection(section, tab, navigate, workspace, body)
    }
}

@Composable
internal fun RailRow(icon: String, label: String, expansion: Float, rowHeight: Dp, selected: Boolean, tint: Color,
                    lifted: Boolean = false, showMarker: Boolean = true, attention: Boolean = false,
                    search: Boolean = false, onClick: () -> Unit) {
    val colors = localColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val reduced = LocalStudioReducedMotion.current
    val nudge by animateDpAsState(if (hovered && !reduced) StudioTokens.spaceHair else 0.dp,
        tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Navigation icon hover")
    val fill = studioColor(when {
        selected -> colors.selected
        hovered || lifted -> colors.hover
        search -> colors.inset
        else -> Color.Transparent
    })
    val text = studioColor(if (selected || hovered) colors.text else colors.muted)
    val marker = studioColor(if (selected) colors.secondary else Color.Transparent)
    val shortcut = "Ctrl+K"
    val accessible = if (search) "$label ($shortcut)" else label
    StudioTooltip(accessible, side = true) {
        Box(Modifier.fillMaxWidth().height(rowHeight).clip(shape)
            .background(fill).hoverable(interaction).studioFocusRing(shape,
                background = if (fill.alpha == 1f) fill else colors.strip)
            .then(if (search) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                else Modifier.selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick))
            .semantics { contentDescription = accessible }) {
            Row(Modifier.fillMaxSize().padding(start = StudioTokens.space2 + StudioTokens.space1 * expansion,
                end = StudioTokens.space2 * expansion),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                verticalAlignment = Alignment.CenterVertically) {
                StudioIcon(icon, Modifier.size(StudioTokens.iconSize).graphicsLayer { translationX = nudge.toPx() }, tint)
                if (expansion > 0f) {
                    Text(if (search) "$label…" else label, Modifier.weight(1f).graphicsLayer { alpha = expansion },
                        color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (search && expansion > .85f) {
                        Text(shortcut, color = colors.muted, fontFamily = localStudioMono.current, fontSize = StudioTokens.textTiny, maxLines = 1)
                    }
                }
            }
            if (showMarker) Box(Modifier.align(Alignment.CenterStart).size(StudioTokens.focusWidth, StudioTokens.iconSize).background(marker, CircleShape))
            if (attention) {
                val m = localMessages.current
                Box(Modifier.align(Alignment.TopEnd).padding(end = StudioTokens.spaceHair, top = StudioTokens.spaceHair).size(StudioTokens.statusDotSize)
                    .background(colors.warning, CircleShape).semantics { contentDescription = m["producerAttention.waiting"] })
            }
        }
    }
}

@Composable
internal fun ShellIconButton(
    icon: String,
    label: String,
    modifier: Modifier,
    iconSize: Dp = StudioTokens.iconSize,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(StudioTokens.radiusMd),
    hover: Color = localColors.current.hover,
    onClick: () -> Unit,
) {
    StudioTooltip(label) {
        StudioIconButton(label, icon, onClick, modifier, shape = shape, iconSize = iconSize, hoverFill = hover)
    }
}

@Composable
private fun ShellTextButton(label: String, onClick: () -> Unit) {
    WorkAction(label, null, outlined = false, onClick = onClick)
}

@Composable
private fun StudioStatusBar(model: WorkspaceWindow, workspace: Workspace) {
    val m = localMessages.current
    val colors = localColors.current
    val tab = model.active
    val info = tab?.chromeStatus?.obj("agent_backends")
    val vcs = tab?.chromeProject?.obj("vcs")
    val version = tab?.chromeProject?.obj("version")
    Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.footerHeight).padding(horizontal = StudioTokens.rowPadX),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
            if (tab != null) {
                val engines = listOf("claude_code" to m["engineClaude"], "codex" to m["engineCodex"], "qwen" to m["engineQwen"],
                    "antigravity" to m["engineAntigravity"])
                engines.forEach { (id, label) ->
                    AgentStatus(label, "agent-${id.replace('_', '-')}", info?.obj(id))
                }
                workspace.chromeConnections.forEach { connection ->
                    val id = "openai_api:${connection.string("id")}"
                    AgentStatus(connection.string("name") ?: id, "agent-codex", info?.obj(id))
                }
                val tint = if (vcs == null) colors.faint else if (vcs.flag("available")) colors.good else colors.danger
                val label = m["statusBarVcs"].replace("{name}", vcs?.string("name") ?: m["vcsNone"])
                Row(Modifier.studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = colors.strip)
                    .clickable(role = Role.Button) {
                        tab.navigate(Page(Section.VCS.route))
                        model.settings = false
                        workspace.scheduleSave()
                    }, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalAlignment = Alignment.CenterVertically) {
                    WorkDot(tint)
                    Text(label, color = colors.secondary, fontSize = StudioTokens.textSmall, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            WorkDot(if (tab?.eventsConnected != false) colors.good else colors.warning)
            Text(m[if (tab?.eventsConnected != false) "connectionLive" else "connectionWaiting"], color = colors.secondary, fontSize = StudioTokens.textSmall)
            val build = version?.string("display") ?: workspace.build.display
            Text(if (version?.flag("dirty") == true) "$build*" else build,
                color = colors.faint, fontSize = StudioTokens.textTiny, fontFamily = localStudioMono.current, maxLines = 1)
            if (tab != null && !model.settings) {
                val compactAction = Modifier.size(StudioTokens.footerHeight - StudioTokens.spaceHair * 2)
                ShellIconButton("refresh-cw", m["refresh"], compactAction, StudioTokens.compactIconSize) { tab.refresh() }
                ShellIconButton("external-link", m["openWeb"], compactAction, StudioTokens.compactIconSize) {
                    val (route, search) = tab.page.browserTarget()
                    tab.api?.web(route, search)?.let { openBrowser(it, workspace) }
                }
            }
            if (!isTraySupported) ShellTextButton(m["quit"]) { workspace.requestQuit() }
        }
    }
}

@Composable
private fun AgentStatus(label: String, icon: String, backend: JsonObject?) {
    val m = localMessages.current
    val colors = localColors.current
    val tint = when {
        backend == null -> colors.warning
        backend.flag("available") -> colors.good
        else -> colors.danger
    }
    val now = Instant.now().epochSecond
    val limits = backend?.objects("usage_limits").orEmpty().mapNotNull { limit ->
        val expires = limit.string("resets_at")?.toLongOrNull()
        if (expires != null && expires in 0..now) return@mapNotNull null
        limit.string("used_percent")?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
            ?.let { "${kotlin.math.round(100 - it).toInt()}%" }
    }
    val speed = backend?.obj("token_speed")?.takeIf { (it.string("expires_at")?.toDoubleOrNull() ?: 0.0) > now }
        ?.string("tokens_per_second")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val reason = backend?.string("reason")?.takeIf(String::isNotBlank) ?: label
    StudioTooltip(reason) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            StudioIcon(icon, Modifier.size(StudioTokens.compactIconSize), tint)
            Text(label, color = colors.secondary, fontSize = StudioTokens.textSmall, maxLines = 1)
            Text(limits.joinToString(" / ").ifEmpty { "—" }, color = colors.faint, fontSize = StudioTokens.textSmall, maxLines = 1)
            if (speed != null) {
                val locale = Locale.forLanguageTag(m.language)
                val rate = NumberFormat.getCompactNumberInstance(locale, NumberFormat.Style.SHORT).apply { maximumFractionDigits = 2 }.format(speed)
                Text(m["statusTokenSpeed"].replace("{rate}", rate), color = colors.faint, fontSize = StudioTokens.textSmall, maxLines = 1)
            }
        }
    }
}

internal fun openBrowser(uri: URI, workspace: Workspace) {
    try {
        Desktop.getDesktop().browse(uri)
    } catch (error: Exception) {
        workspace.problem = error.problem("browserFailed")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChromeButton(label: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val m = localMessages.current
    StudioTooltip(m[description]) {
        WorkAction(label, null, Modifier.semantics { contentDescription = m[description] },
            enabled = enabled, outlined = false, onClick = onClick)
    }
}

@Composable
internal fun ListRow(title: String, caption: String?, selected: Boolean, enabled: Boolean = true, status: String? = null, onClick: () -> Unit) {
    val colors = localColors.current
    StudioSelectionRow(selected, onClick, Modifier.fillMaxWidth(), enabled = enabled, role = Role.Button) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) Color.Unspecified else colors.muted)
            caption?.takeIf(String::isNotBlank)?.let { Muted(it) }
        }
        status?.takeIf(String::isNotBlank)?.let { Status(it) }
    }
}

@Composable
internal fun ScrollList(modifier: Modifier = Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = StudioTokens.space2), state = scroll, content = content)
        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier, size: Int = StudioTokens.pageTitleSize.value.toInt()) {
    Text(text, modifier, fontSize = size.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun Status(status: String) = WorkStatus(status, quiet = true, live = false)

@Composable
internal fun Muted(text: String, fontSize: Int = StudioTokens.textSmall.value.toInt(), modifier: Modifier = Modifier) {
    Text(text, modifier, color = localColors.current.muted, fontSize = fontSize.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(StudioTokens.panePad), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space3), content = content)
    }
}

@Composable
internal fun Rule(vertical: Boolean = false) {
    val base = if (vertical) Modifier.fillMaxHeight().width(StudioTokens.borderWidth) else Modifier.fillMaxWidth().height(StudioTokens.borderWidth)
    Box(base.background(localColors.current.border))
}

@Composable
internal fun ProblemText(problem: Problem) {
    val m = localMessages.current
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Text(m[problem.key], color = localColors.current.danger)
        problem.details?.takeIf(String::isNotBlank)?.let { Muted(it) }
    }
}
