package studio.voxarium.desktop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text

private const val LOG_BUFFER_LINES = 2000

@Composable
internal fun PipelinePage(tab: ProjectTab, navigate: (Page) -> Unit, workspace: Workspace, modifier: Modifier) {
    val pipelineId = tab.page.selection(Section.PIPELINES)
    if (pipelineId == null) {
        PipelineIndex(tab, navigate, modifier)
    } else {
        key(pipelineId) { PipelineEditor(tab, pipelineId, navigate, workspace, modifier) }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun PipelineIndex(tab: ProjectTab, navigate: (Page) -> Unit, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val items = tab.list?.items.orEmpty()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(TextFieldValue()) }
    var removing by remember { mutableStateOf<WorkspaceItem?>(null) }
    Column(modifier.fillMaxSize()) {
        WorkHeader {
            Text(m["pipelines"], Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = StudioTokens.titleSize)
            PipelineButton("plus", m["pipelineCreate"], enabled = !tab.acting && !creating, primary = true) {
                creating = true
                removing = null
            }
        }
        StudioDisclosure(creating) {
            Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                StudioField(m["referenceName"], name, { name = it }, Modifier.weight(1f),
                    enabled = creating && !tab.acting, placeholder = m["referenceName"])
                PipelineButton("", m["create"], enabled = creating && !tab.acting && name.text.isNotBlank(), primary = true) {
                    val title = name.text.trim()
                    val id = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        .ifEmpty { "pipeline-${System.currentTimeMillis()}" }
                    tab.action {
                        val created = post("/pipelines", jsonFields("id" to id, "name" to title)).jsonObject
                        creating = false
                        name = TextFieldValue()
                        navigate(Page("/pipelines/${segment(created.string("id") ?: id)}"))
                    }
                }
                PipelineButton("", m["cancel"], enabled = !tab.acting) { creating = false }
            }
        }
        removing?.let { item ->
            Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                Text(m["pipelineConfirmRemove"].replace("{name}", item.title))
                if (tab.pipelineGraphs.dirty(item.id)) Muted(m["pipelineConfirmRemoveDraft"])
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    PipelineButton("trash-2", m["pipelineRemove"], enabled = !tab.acting, tint = colors.danger) {
                        tab.action {
                            delete("/pipelines/${segment(item.id)}")
                            tab.pipelineGraphs.discard(item.id)
                            tab.pipelineGraphs.selectedNodes.remove(item.id)
                            removing = null
                        }
                    }
                    PipelineButton("", m["cancel"], enabled = !tab.acting) { removing = null }
                }
            }
        }
        tab.actionError?.let { Box(Modifier.padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2)) { ProblemText(it) } }
        val error = tab.listError
        when {
            error != null -> Box(Modifier.padding(StudioTokens.space4)) { ProblemText(error) }
            tab.list == null -> Box(Modifier.padding(StudioTokens.space4)) { Muted(m["loading"]) }
            items.isEmpty() -> Box(Modifier.fillMaxWidth().studioArrival().padding(top = StudioTokens.space6), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = StudioTokens.emptyStateMaxWidth), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    StudioIcon("workflow", Modifier.size(StudioTokens.largeIconSize), colors.faint)
                    Text(m["pipelineIndexEmpty"], fontWeight = FontWeight.Medium)
                    Text(m["pipelineIndexEmptyHint"], color = colors.muted, fontSize = StudioTokens.textSmall, textAlign = TextAlign.Center)
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().studioArrival()) {
                val columns = ((maxWidth - StudioTokens.panePad * 2 + StudioTokens.space2) /
                    (260.dp + StudioTokens.space2)).toInt().coerceAtLeast(1)
                val rows = remember(items, columns) { items.chunked(columns) }
                LazyColumn(contentPadding = PaddingValues(StudioTokens.panePad),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    items(rows, key = { it.first().id }) { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                            row.forEach { item ->
                                PipelineIndexCard(item, Modifier.weight(1f).fillMaxHeight(), canDelete = !tab.acting, onRemove = {
                                    creating = false
                                    removing = item
                                }) {
                                    navigate(tab.page.select(Section.PIPELINES, item))
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineIndexCard(item: WorkspaceItem, modifier: Modifier, canDelete: Boolean, onRemove: () -> Unit, onOpen: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reduced = LocalStudioReducedMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateDpAsState(if (hovered && !reduced && !pressed) -StudioTokens.spaceHair else 0.dp,
        tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Pipeline card lift")
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val cssLineBox = JewelTheme.defaultTextStyle.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
    val fill = studioColor(when {
        pressed -> colors.active
        hovered -> colors.hover
        else -> colors.cardFill
    })
    Column(
        modifier.graphicsLayer { translationY = lift.toPx() }.studioFocusRing(shape, background = fill)
            .clip(shape).background(fill)
            .border(StudioTokens.borderWidth, studioColor(if (hovered) colors.focusOn(fill) else colors.border), shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "${item.title} · ${item.id}" }.padding(StudioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            StudioIcon("workflow", Modifier.padding(top = StudioTokens.spaceHair).size(StudioTokens.iconSize), colors.accent)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = cssLineBox, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.id, style = cssLineBox, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier,
                    color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PipelineButton("trash-2", m["pipelineRemove"], enabled = canDelete, iconOnly = true, onClick = onRemove)
        }
        item.caption?.takeIf(String::isNotBlank)?.let {
            Text(it, style = cssLineBox, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(pipelineCount(m, item.payload.string("node_count")?.toIntOrNull() ?: 0,
            item.payload.string("edge_count")?.toIntOrNull() ?: 0), style = cssLineBox,
            fontSize = StudioTokens.textSmall, lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier, color = colors.faint)
    }
}

// §6.3: a pipeline has its own editor surface, with the canvas between the schema
// palette and the selected-node inspector. The run dock describes the selected run.
@Composable
private fun PipelineEditor(tab: ProjectTab, pipelineId: String, navigate: (Page) -> Unit,
    workspace: Workspace, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val detail = tab.detail?.takeIf { it.item.id == pipelineId }
    val pipeline = detail?.data?.obj("pipeline")
    val state = tab.pipelineGraphs
    val draft = state.drafts[pipelineId]
    val dirty = state.dirty(pipelineId)
    val preview = pipeline?.let { state.preview(pipelineId, it) }
    val path = "/pipelines/${segment(pipelineId)}"
    val api = tab.api
    var registry by remember(api) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var registryError by remember(api) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(api) {
        if (api != null) {
            try {
                registry = api.get("/node-types").jsonObject.objects("node_types")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                registryError = error.problem()
            }
        }
    }

    val runs = detail?.runs.orEmpty()
    val active = runs.firstOrNull { it.flag("is_running") }
    var selectedRun by remember { mutableStateOf<String?>(null) }
    val lastRun = runs.firstOrNull()?.string("id")
    LaunchedEffect(lastRun) { selectedRun = null }
    val displayed = runs.firstOrNull { it.string("id") == selectedRun } ?: runs.firstOrNull()
    val displayedId = displayed?.string("id")
    val displayedStatus = displayed?.string("status")
    val displayedRunning = displayed?.flag("is_running") == true
    var runNodes by remember(displayedId) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var runError by remember(displayedId) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(api, displayed) {
        if (api != null && displayedId != null) {
            try {
                do {
                    runNodes = api.get("/runs/${segment(displayedId)}").jsonObject.objects("nodes")
                    runError = null
                    if (displayedRunning) delay(2000)
                } while (displayedRunning)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                runError = error.problem()
            }
        }
    }

    var historyOpen by remember { mutableStateOf(false) }
    val canEdit = detail?.data?.string("revision")?.matches(revisionPattern) == true
    val canSave = canEdit && !tab.acting && dirty && draft?.validPosition != false && draft?.validParameters != false
    val canRun = active == null && !tab.acting && preview?.objects("nodes").orEmpty().isNotEmpty() &&
        (if (dirty) canSave else detail?.data?.objects("issues").orEmpty().none { it.string("level") == "error" })
    fun save() {
        tab.action { savePipelineDraft(tab, pipelineId) }
    }
    fun run(startNode: String? = null) {
        val useCache = workspace.preferences.flag("node_cache_enabled")
        tab.action {
            if (dirty) {
                val saved = savePipelineDraft(tab, pipelineId) ?: return@action
                if (saved.objects("issues").any { it.string("level") == "error" }) return@action
            }
            post("$path/run", buildJsonObject {
                put("use_cache", useCache)
                if (startNode != null) put("start_node_id", startNode)
            })
            selectedRun = null
        }
    }

    Column(modifier.fillMaxSize()) {
        // Keep the graph action strip compact enough for the Russian actions at 1024px.
        Row(Modifier.fillMaxWidth().background(colors.headerFill, RoundedCornerShape(StudioTokens.radiusMd))
            .padding(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            PipelineButton("arrow-left", m["pipelineBack"], iconOnly = true) { navigate(Page("/pipelines")) }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                Column(Modifier.widthIn(max = 330.dp).weight(1f, fill = false)) {
                    Text(pipeline?.string("name") ?: pipelineId, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pipelineCount(m, preview?.objects("nodes").orEmpty().size, preview?.objects("edges").orEmpty().size),
                            color = colors.faint, fontSize = StudioTokens.textSmall, maxLines = 1)
                        if (dirty) Text(" · ${m["pipelineUnsaved"]}", color = colors.warning, fontSize = StudioTokens.textSmall)
                    }
                }
                displayedStatus?.let { status ->
                    WorkStatus(status, variant = WorkStatusVariant.Badge, label = m.pipelineStatus(status), live = false)
                }
            }
            Box {
                PipelineButton("history", m["pipelineHistory"]) { historyOpen = !historyOpen }
                if (historyOpen) {
                    val offset = with(LocalDensity.current) { StudioTokens.rowHeight.roundToPx() }
                    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset),
                        onDismissRequest = { historyOpen = false }, properties = PopupProperties(focusable = true)) {
                        RunHistory(runs, displayedId, Modifier.studioArrival(distance = -StudioTokens.space1, duration = StudioMotion.fast)) {
                            selectedRun = it
                            historyOpen = false
                        }
                    }
                }
            }
            if (dirty) PipelineButton("x", m["discardDocumentDraft"], enabled = !tab.acting, iconOnly = true) {
                state.discard(pipelineId)
                tab.refresh(reset = true)
            }
            PipelineButton("save", m["saveDocument"], enabled = canSave, onClick = ::save)
            val activeId = active?.string("id")
            if (activeId == null) {
                PipelineButton("play", m["pipelineRun"], primary = true, enabled = canRun) { run() }
            } else {
                val endpoint = "/runs/${segment(activeId)}"
                if (active.string("status") == "paused") {
                    PipelineButton("skip-forward", m["pipelineContinue"], primary = true, enabled = !tab.acting) {
                        tab.action { post("$endpoint/resume") }
                    }
                }
                PipelineButton("ban", m["cancel"], enabled = !tab.acting, tint = colors.danger) {
                    tab.action { post("$endpoint/cancel") }
                }
            }
        }
        listOfNotNull(tab.actionError, tab.detailError, runError, state.problems[pipelineId]).forEach {
            Box(Modifier.fillMaxWidth().background(colors.dangerSurface).padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space1)) {
                ProblemText(it)
            }
        }
        displayed?.string("error")?.takeIf(String::isNotBlank)?.let { PipelineBanner(it, warning = false) }
        if (!dirty) detail?.data?.objects("issues").orEmpty().forEach { issue ->
            PipelineBanner(listOfNotNull(issue.string("node_id"), issue.string("message")).joinToString(" · "), warning = true)
        }
        if (pipeline == null) {
            if (tab.detailError == null) Centered {
                StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                Muted(m["loading"])
            }
        } else {
            PipelineGraph(tab, workspace, pipeline, registry, registryError, runNodes, displayedId, canEdit,
                Modifier.weight(1f).studioArrival(pipelineId, duration = StudioMotion.fast), canRun) { run(it) }
            PipelineLogDock(api, pipeline, registry, displayedId, runNodes, displayedRunning)
        }
    }
}

@Composable
private fun PipelineBanner(message: String, warning: Boolean) {
    val colors = localColors.current
    val fill = if (warning) colors.warningSurface else colors.dangerSurface
    val tone = if (warning) colors.warning else colors.danger
    Text(message, Modifier.fillMaxWidth().background(fill)
        .padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space1),
        color = StudioTokens.ensureContrast(tone, fill, StudioTokens.textContrast))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PipelineButton(icon: String, label: String, enabled: Boolean = true,
    primary: Boolean = false, iconOnly: Boolean = false, tint: Color = Color.Unspecified,
    compact: Boolean = false, onClick: () -> Unit) {
    if (iconOnly) {
        StudioTooltip(label) {
            StudioIconButton(label, icon, onClick, enabled = enabled, tint = tint, iconSize = StudioTokens.iconSize)
        }
    } else {
        StudioAction(label, icon.ifEmpty { null }, Modifier, enabled, primary, outlined = !compact,
            iconOnly = false, danger = false, busy = false, onClick = onClick, tint = tint)
    }
}

@Composable
private fun RunHistory(runs: List<JsonObject>, selectedRun: String?, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val scroll = rememberScrollState()
    Column(modifier.width(StudioTokens.menuMaxWidth)
        .heightIn(max = StudioTokens.menuMaxHeight).studioShadow(shape, colors.shadowMd)
        .clip(shape).background(colors.sheetFill).border(StudioTokens.borderWidth, colors.border, shape)
        .verticalScroll(scroll)) {
        if (runs.isEmpty()) Text(m["pipelineHistoryEmpty"], Modifier.padding(StudioTokens.space3), color = colors.faint)
        runs.forEachIndexed { index, run ->
            val id = run.string("id") ?: return@forEachIndexed
            val status = run.string("status").orEmpty()
            StudioSelectionRow(id == selectedRun, { onSelect(id) }, Modifier.fillMaxWidth()) {
                PipelineStatus(status, dotOnly = true)
                Column {
                    Text(runTime(run.string("created_at"), m.language), maxLines = 1)
                    val duration = runDuration(run.string("started_at"), run.string("finished_at"))
                    val source = run.string("start_node_id")?.let { m["pipelineHistoryFrom"].replace("{node}", it) }
                    Text(listOfNotNull(status.takeIf(String::isNotBlank)?.let { m.pipelineStatus(it) }, duration, source)
                        .joinToString(" · "), color = colors.faint, fontSize = StudioTokens.textSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (index != runs.lastIndex) Rule()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PipelineLogDock(api: ProjectApi?, pipeline: JsonObject, registry: List<JsonObject>, runId: String?,
    nodes: List<JsonObject>, running: Boolean) {
    val m = localMessages.current
    val colors = localColors.current
    var collapsed by remember { mutableStateOf(false) }
    var pinned by remember(runId) { mutableStateOf<String?>(null) }
    val logged = nodes.filter { it.string("status") != "pending" }
    val selected = pinned?.let { id -> logged.find { it.string("node_id") == id } }
        ?: logged.find { it.string("status") == "running" } ?: logged.firstOrNull()
    val nodeId = selected?.string("node_id")
    val nodeStatus = selected?.string("status")
    var log by remember(runId, nodeId) { mutableStateOf("") }
    var problem by remember(runId, nodeId) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(api, runId, nodeId, nodeStatus, running, collapsed) {
        if (api != null && runId != null && nodeId != null && !collapsed) {
            try {
                do {
                    val text = api.get("/runs/${segment(runId)}/nodes/${segment(nodeId)}/log").jsonObject.string("text").orEmpty()
                    log = text.split('\n').filter(String::isNotEmpty).takeLast(LOG_BUFFER_LINES).joinToString("\n")
                    problem = null
                    if (running && nodeStatus == "running") delay(2000)
                } while (running && nodeStatus == "running")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                problem = error.problem()
            }
        }
    }
    val identities = remember(pipeline, registry) {
        val typeNames = registry.associate { it.string("id") to it.string("name") }
        pipeline.objects("nodes").mapNotNull { node ->
            val id = node.string("id") ?: return@mapNotNull null
            val type = node.string("type")
            val title = node.string("label")?.takeUnless(String::isBlank)
                ?: typeNames[type]?.takeUnless(String::isBlank) ?: type ?: id
            id to (title to type)
        }.toMap()
    }
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Rule()
        Row(Modifier.fillMaxWidth().heightIn(min = StudioTokens.rowHeight).padding(horizontal = StudioTokens.space2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            Text(m["pipelineLogs"], Modifier.padding(end = StudioTokens.space2), fontWeight = FontWeight.Medium)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                logged.forEach { node ->
                    val id = node.string("node_id") ?: return@forEach
                    val (title, type) = identities[id] ?: (id to null)
                    val status = node.string("status").orEmpty()
                    val label = listOfNotNull(title, id, type).distinct().joinToString(" · ")
                    val active = id == nodeId
                    val fill = if (active) colors.selected else colors.surface
                    val state = m.pipelineStatus(status.ifBlank { "unavailable" })
                    StudioTooltip("$label · $state") {
                        StudioSelectionRow(active, { pinned = id },
                            Modifier.widthIn(max = 260.dp).semantics { contentDescription = "$label · $state" },
                            role = Role.Tab) {
                            Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state, color = colors.statusTextColor(status, fill), fontSize = StudioTokens.textSmall, maxLines = 1)
                        }
                    }
                }
            }
            PipelineButton(if (collapsed) "chevron-up" else "chevron-down",
                m[if (collapsed) "pipelineLogsExpand" else "pipelineLogsCollapse"], iconOnly = true) { collapsed = !collapsed }
        }
        StudioDisclosure(!collapsed) {
            Column(Modifier.fillMaxWidth().height(187.dp)) {
                Rule()
                val scroll = rememberScrollState()
                val tailMargin = with(LocalDensity.current) { 40.dp.roundToPx() }
                val followTail = scroll.maxValue == Int.MAX_VALUE || scroll.maxValue - scroll.value < tailMargin
                LaunchedEffect(log) {
                    if (followTail) {
                        withFrameNanos { }
                        scroll.scrollTo(scroll.maxValue)
                    }
                }
                Box(Modifier.fillMaxSize().background(colors.inset)) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(StudioTokens.space2)) {
                        problem?.let { ProblemText(it) }
                        if (log.isBlank()) {
                            Text(m[if (nodeId == null) "pipelineLogsEmpty" else "pipelineLogsNoOutput"],
                                color = colors.faint, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall)
                        } else {
                            SelectionContainer {
                                Text(log, color = colors.muted, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
internal fun pipelineStatusColor(status: String): Color = localColors.current.statusColor(status)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PipelineStatus(status: String, dotOnly: Boolean = false, scale: Float = 1f) {
    val m = localMessages.current
    val label = m.pipelineStatus(status.ifBlank { "unavailable" })
    if (dotOnly) {
        StudioTooltip(label) {
            WorkStatus(status, variant = WorkStatusVariant.Dot, label = label, live = false)
        }
        return
    }
    val colors = localColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1 * scale)) {
        Box(Modifier.size(StudioTokens.statusDotSize * scale).background(colors.statusColor(status, colors.surface), CircleShape))
        Text(label, color = colors.statusTextColor(status, colors.surface), fontSize = StudioTokens.textTiny * scale, maxLines = 1)
    }
}

internal fun Messages.pipelineStatus(status: String): String =
    if (status == "success") this["pipelineSuccess"] else this.status(status)

private fun pipelineCount(m: Messages, nodes: Int, edges: Int): String {
    fun form(count: Int) = when {
        m.language != "ru" -> if (count == 1) "one" else "other"
        count % 100 in 11..14 -> "many"
        count % 10 == 1 -> "one"
        count % 10 in 2..4 -> "few"
        else -> "many"
    }
    return "$nodes ${m["pipelineCountNodes." + form(nodes)]} · $edges ${m["pipelineCountEdges." + form(edges)]}"
}

private fun parseRunTime(value: String?): Instant? {
    if (value == null) return null
    return runCatching { Instant.parse(value) }.getOrElse {
        runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}

private fun runTime(value: String?, locale: String): String = parseRunTime(value)?.let {
    DateTimeFormatter.ofPattern(if (locale == "ru") "d MMM, HH:mm" else "MMM d, h:mm a", Locale.forLanguageTag(locale))
        .withZone(ZoneId.systemDefault()).format(it)
} ?: value ?: "—"

private fun runDuration(from: String?, to: String?): String {
    val start = parseRunTime(from) ?: return "—"
    val seconds = Duration.between(start, parseRunTime(to) ?: Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
