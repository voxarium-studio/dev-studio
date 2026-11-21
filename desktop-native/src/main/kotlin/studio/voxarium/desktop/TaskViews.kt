package studio.voxarium.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

private val boardColumns = listOf(
    listOf("todo"), listOf("pending", "needs_review", "blocked"),
    listOf("in_progress"), listOf("done"), listOf("cancelled"),
)
internal val taskStatuses = boardColumns.flatten()

@Composable
internal fun TasksPage(tab: ProjectTab, navigate: (Page) -> Unit, modifier: Modifier) {
    val m = localMessages.current
    val data = tab.list
    val selectedId = tab.page.selection(Section.TASKS)
    val requestedTeam = data?.items?.find { it.id == selectedId }?.payload?.string("team_id")
    var teamId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedTeam) { if (requestedTeam != null) teamId = requestedTeam }
    val teams = data?.teams.orEmpty()
    val team = teams.find { it.string("id") == (requestedTeam ?: teamId) } ?: teams.firstOrNull()
    val teamKey = team?.string("id")
    val people = team?.objects("members").orEmpty()
    val members = people.associateBy { it.string("id") }
    val tasks = data?.items.orEmpty().filter { it.payload.string("team_id") == teamKey }
    val active = tasks.filterNot { it.payload.flag("archived") }
    val archive = tasks.filter { it.payload.flag("archived") }
    val totalDoneCost = tasks.filter { it.status == "done" }.sumOf { it.payload.string("total_cost_usd")?.toDoubleOrNull() ?: 0.0 }
    var showArchive by remember(teamKey) { mutableStateOf(false) }
    LaunchedEffect(archive.isEmpty()) { if (archive.isEmpty()) showArchive = false }
    var composing by remember(teamKey) { mutableStateOf(false) }
    var showContext by remember(selectedId) { mutableStateOf(false) }
    val newTaskFocus = remember { FocusRequester() }
    var opener by remember(teamKey) { mutableStateOf<Pair<String, FocusRequester>?>(null) }
    val reduced = LocalStudioReducedMotion.current
    val motionScope = rememberCoroutineScope()
    val settling = remember(teamKey) { mutableStateMapOf<String, Long>() }
    var previous by remember(teamKey) { mutableStateOf<Map<String, String?>?>(null) }
    val statusesById = remember(active) { active.associate { it.id to it.status } }
    LaunchedEffect(teamKey, statusesById, reduced) {
        val prior = previous
        previous = statusesById
        if (prior == null || reduced) return@LaunchedEffect
        for ((id, status) in statusesById) {
            if (status != "done" || !prior.containsKey(id) || prior[id] == status) continue
            val arrival = System.nanoTime()
            settling[id] = arrival
            motionScope.launch {
                delay(StudioMotion.slow.toLong())
                if (settling[id] == arrival) settling.remove(id)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TaskBoardHeader(teams, team, { teamId = it }) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.status("done"), color = localColors.current.muted, fontSize = StudioTokens.textSmall)
                    val doneUsage = tasks.filter { it.status == "done" }.map { taskTokens(it.payload) }
                    UsageChip(List(3) { index -> doneUsage.sumOf { it[index] ?: 0L } }, totalDoneCost)
                }
                if (archive.isNotEmpty()) {
                    StudioChip("${m["work.taskArchived"]} ${archive.size}", showArchive, { showArchive = it }, icon = "archive")
                }
                WorkCheckbox(m["work.taskAutostart"], team?.flag("autostart_tasks") == true, teamKey != null && !tab.acting) { checked ->
                    if (teamKey != null) tab.action {
                        patch("/teams/${segment(teamKey)}", buildJsonObject { put("autostart_tasks", checked) })
                    }
                }
                val startable = active.any {
                    it.status in setOf("todo", "pending") && !it.payload.flag("is_running") &&
                        members[it.payload.string("assignee_id")]?.string("kind") == "agent"
                }
                WorkAction(m["work.taskStartAll"], "play", enabled = teamKey != null && startable && !tab.acting) {
                    if (teamKey != null) tab.action {
                        post("/teams/${segment(teamKey)}/tasks/start-all", jsonFields("summary_locale" to m.language))
                    }
                }
                WorkAction(m["newTask"], "plus", Modifier.focusRequester(newTaskFocus),
                    enabled = people.isNotEmpty(), primary = true) { composing = true }
            }
            WorkErrors(tab)
            when {
                data == null -> Centered { Muted(m["loading"]) }
                tasks.isEmpty() -> WorkEmpty(m["work.taskEmpty"], m["work.taskEmptyHint"], "users")
                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val drag = remember(teamKey) { TaskBoardDrag() }
                    val columns = boardColumns.filter { it.first() != "cancelled" || active.any { item -> item.status == "cancelled" } }
                    val archiveVisible = showArchive && archive.isNotEmpty()
                    val count = columns.size + if (archiveVisible) 1 else 0
                    val columnWidth by animateDpAsState(
                        ((maxWidth - StudioTokens.panePad * 2 - StudioTokens.space3 * (count - 1)) / count).coerceIn(224.dp, 384.dp),
                        if (reduced) snap() else tween(StudioMotion.normal, easing = StudioMotion.inOut),
                    )
                    val scroll = rememberScrollState()
                    fun drop(id: String, statuses: List<String>) {
                        if (tab.acting || !(statuses in columns || statuses.isEmpty() && archiveVisible)) return
                        val task = tab.list?.items?.firstOrNull { it.id == id && it.payload.string("team_id") == teamKey }?.payload ?: return
                        val endpoint = taskEndpoint(task) ?: return
                        val body = taskColumnPatch(task, statuses, m.language) ?: return
                        tab.action { patch(endpoint, body) }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onGloballyPositioned { drag.viewport = it }) {
                            Row(
                                Modifier.fillMaxSize().horizontalScroll(scroll).padding(StudioTokens.panePad),
                                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3),
                            ) {
                                for ((index, statuses) in boardColumns.withIndex()) key(teamKey, statuses.first()) {
                                    TaskColumnVisibility(statuses in columns) {
                                        TaskColumn(statuses, active.filter { it.status in statuses }, tab, selectedId, members,
                                            settling, Modifier.width(columnWidth).studioArrival(delay = index.coerceAtMost(3) * StudioMotion.stagger),
                                            drag, ::drop) { item, focus ->
                                            opener = item.id to focus
                                            navigate(tab.page.select(Section.TASKS, item))
                                        }
                                    }
                                }
                                TaskColumnVisibility(archiveVisible) {
                                    TaskColumn(emptyList(), archive, tab, selectedId, members, emptyMap(),
                                        Modifier.width(columnWidth).studioArrival(delay = columns.size.coerceAtMost(3) * StudioMotion.stagger),
                                        drag, ::drop) { item, focus ->
                                        opener = item.id to focus
                                        navigate(tab.page.select(Section.TASKS, item))
                                    }
                                }
                            }
                            TaskDragOverlay(drag, scroll)
                        }
                        if (scroll.viewportSize > 0 && scroll.maxValue > 0) TaskBoardScrollbar(scroll)
                    }
                }
            }
        }

        if (selectedId != null) {
            val close = {
                opener = null
                navigate(Page("/tasks"))
            }
            val detail = tab.detail?.takeIf { it.item.id == selectedId }
            val card = tasks.find { it.id == selectedId }
            val dialogLabel = detail?.item?.title ?: card?.title ?: m["tasks"]
            WorkDialog(dialogLabel, close, Modifier.widthIn(max = 1200.dp).fillMaxWidth(.97f).fillMaxHeight(.95f),
                returnFocus = opener?.takeIf { it.first == selectedId }?.second) { dismiss ->
                when {
                    detail != null -> key(selectedId) {
                        TaskConversationView(tab, detail.data, close = dismiss, openContext = { showContext = true },
                            memberFallback = members[detail.data.string("assignee_id")])
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth().padding(StudioTokens.space3), horizontalArrangement = Arrangement.End) {
                            WorkAction(m["close"], "x", outlined = false, iconOnly = true, onClick = dismiss)
                        }
                        Centered {
                            val error = tab.detailError
                            if (error != null) ProblemText(error)
                            else Muted(m[if (data == null || tasks.any { it.id == selectedId }) "loading" else "pickItem"])
                        }
                    }
                }
            }
        }
        if (showContext && selectedId != null) {
            val task = tab.detail?.takeIf { it.item.id == selectedId }?.data
            task?.string("team_id")?.let { contextTeam ->
                NativeContextViewer(tab, NativeContextTarget.Task(contextTeam, selectedId), close = { showContext = false },
                    revision = task.string("updated_at"))
            }
        }
        if (composing && teamKey != null) TaskComposer(tab, teamKey, people, { composing = false }, newTaskFocus) { id ->
            composing = false
            opener = id to newTaskFocus
            navigate(Page("/tasks", Selectors(task = id)))
        }
    }
}

@Composable
private fun TaskBoardScrollbar(scroll: ScrollState) {
    val colors = localColors.current
    // Paint the actual track surface so custom themes and canvas patterns cannot dilute the thumb contrast.
    val style = LocalScrollbarStyle.current.copy(
        thickness = StudioTokens.space2,
        unhoverColor = colors.scrollbarThumb,
        hoverColor = colors.scrollbarThumbHover,
        hoverDurationMillis = 0, // Avoid intermediate colors falling below 3:1 in custom themes.
    )
    HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth().background(colors.scrollbarTrack), style = style)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskBoardHeader(
    teams: List<JsonObject>,
    team: JsonObject?,
    select: (String?) -> Unit,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    val colors = localColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()
        .background(colors.headerFill, RoundedCornerShape(StudioTokens.radiusMd))
        .padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2)) {
        if (maxWidth >= 860.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                Box(Modifier.weight(1f)) { TaskBoardTeam(teams, team, select) }
                FlowRow(Modifier.weight(3f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space1), itemVerticalAlignment = Alignment.CenterVertically,
                    content = actions)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                TaskBoardTeam(teams, team, select)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space1), itemVerticalAlignment = Alignment.CenterVertically,
                    content = actions)
            }
        }
    }
}

@Composable
private fun TaskBoardTeam(teams: List<JsonObject>, team: JsonObject?, select: (String?) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val anchor = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            if (teams.size <= 1) Modifier else Modifier.focusRequester(anchor)
                .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = colors.headerFill)
                .clickable(role = Role.Button) { expanded = true },
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(m["tasks"], fontWeight = FontWeight.Medium, fontSize = StudioTokens.titleSize, maxLines = 1)
            team?.string("name")?.let {
                Text("— $it", Modifier.weight(1f, fill = false), color = colors.muted, fontSize = StudioTokens.textSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (teams.size > 1) StudioIcon("chevron-down", Modifier.size(StudioTokens.compactIconSize), colors.faint)
        }
        StudioMenu(expanded, { expanded = false }, teams.map { entry ->
            StudioMenuItem(entry.string("name").orEmpty(), checked = entry == team) { select(entry.string("id")) }
        }, initialItem = teams.indexOfFirst { it == team }, returnFocus = anchor)
    }
}

@Composable
private fun RowScope.TaskColumnVisibility(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalStudioReducedMotion.current
    AnimatedVisibility(visible,
        enter = if (reduced) fadeIn(tween(StudioMotion.quick)) else
            fadeIn(tween(StudioMotion.normal, easing = StudioMotion.standard)) +
                expandHorizontally(tween(StudioMotion.normal, easing = StudioMotion.standard), expandFrom = Alignment.Start),
        exit = if (reduced) fadeOut(tween(StudioMotion.quick)) else
            fadeOut(tween(StudioMotion.quick)) +
                shrinkHorizontally(tween(StudioMotion.normal, easing = StudioMotion.inOut), shrinkTowards = Alignment.Start),
    ) { content() }
}

private class TaskColumnHistory {
    var shownIds: Set<String>? = null
}

@Composable
private fun TaskColumn(
    statuses: List<String>,
    tasks: List<WorkspaceItem>,
    tab: ProjectTab,
    selection: String?,
    members: Map<String?, JsonObject>,
    settling: Map<String, Long>,
    modifier: Modifier,
    drag: TaskBoardDrag,
    drop: (String, List<String>) -> Unit,
    open: (WorkspaceItem, FocusRequester) -> Unit,
) {
    val colors = localColors.current
    val m = localMessages.current
    val reduced = LocalStudioReducedMotion.current
    val shownIds = remember(tasks) { tasks.mapTo(mutableSetOf(), WorkspaceItem::id) }
    val history = remember { TaskColumnHistory() }
    val arrivals = remember(shownIds) {
        history.shownIds?.let { previous ->
            val started = System.nanoTime()
            (shownIds - previous).associateWith { started }
        }.orEmpty()
    }
    SideEffect { history.shownIds = shownIds }
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    val outline = studioColor(if (drag.target == statuses) colors.accent else Color.Transparent)
    Column(modifier.fillMaxHeight().onGloballyPositioned { drag.column(statuses, it) }
        .background(colors.elevated, shape).border(StudioTokens.borderWidth, outline, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(start = StudioTokens.space3, end = StudioTokens.space3,
                top = StudioTokens.space3, bottom = StudioTokens.space2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (statuses.size) {
                0 -> WorkPill(m["work.taskArchived"], icon = "archive")
                1 -> WorkStatus(statuses.single(), quiet = true)
                else -> WorkPill(m["work.taskWaiting"], statuses.map { workStatusColor(it) })
            }
            Text(tasks.size.toString(), color = colors.muted, fontSize = StudioTokens.textSmall)
        }
        ScrollList(Modifier.weight(1f).fillMaxWidth().padding(horizontal = StudioTokens.space2)) {
            if (tasks.isEmpty()) item { Muted(m["work.taskNothingHere"], modifier = Modifier.padding(StudioTokens.space1)) }
            items(tasks, key = { it.id }) { item ->
                val arrival = remember(item.id, reduced) {
                    !reduced && arrivals[item.id]?.let { System.nanoTime() - it < StudioMotion.normal * 1_000_000L } == true
                }
                val itemMotion = Modifier.animateItem(
                    fadeInSpec = if (reduced) tween(StudioMotion.quick, easing = LinearEasing) else null,
                    placementSpec = if (reduced) null else tween(StudioMotion.normal, easing = StudioMotion.inOut),
                    fadeOutSpec = tween(StudioMotion.quick, easing = LinearEasing),
                )
                TaskCard(tab, item, members[item.payload.string("assignee_id")], selection == item.id, statuses.size > 1,
                    settling[item.id],
                    if (arrival) itemMotion.studioArrival(distance = StudioTokens.space2, duration = StudioMotion.normal) else itemMotion,
                    drag, { drop(item.id, it) },
                ) {
                    open(item, it)
                }
            }
        }
    }
}

@Composable
private fun TaskCard(tab: ProjectTab, item: WorkspaceItem, member: JsonObject?, selected: Boolean, mixed: Boolean,
                     settled: Long?, modifier: Modifier, drag: TaskBoardDrag, drop: (List<String>) -> Unit,
                     open: (FocusRequester) -> Unit) {
    val colors = localColors.current
    val m = localMessages.current
    val data = item.payload
    val radius = StudioTokens.radiusLg
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = studioColor(when {
        selected -> colors.selected
        hovered -> colors.hover
        else -> colors.surface
    })
    val running = data.flag("is_running")
    val activity = tab.workActivity.task(item.id)
    val reduced = LocalStudioReducedMotion.current
    val dragging = drag.item?.id == item.id
    val cardFocus = remember { FocusRequester() }
    var cardFocused by remember { mutableStateOf(false) }
    var rename by remember(item.id) { mutableStateOf<TextFieldValue?>(null) }
    val endpoint = taskEndpoint(data)
    val canRename = endpoint != null && !tab.acting
    fun startRename() {
        if (canRename) rename = TextFieldValue(item.title, TextRange(0, item.title.length))
    }
    fun saveTitle() {
        val edited = rename ?: return
        val title = edited.text.trim()
        if (title.isEmpty() || title == item.title) {
            rename = null
        } else if (endpoint != null && !tab.acting) {
            tab.action {
                patch(endpoint, jsonFields("title" to title))
                if (rename == edited) rename = null
            }
        }
    }
    val lift by animateFloatAsState(if (hovered && !dragging && !reduced) -StudioTokens.spaceHair.value else 0f,
        if (reduced) snap() else tween(StudioMotion.fast, easing = StudioMotion.standard))
    val opacity by animateFloatAsState(if (dragging) StudioTokens.draggedOpacity else 1f,
        if (reduced) snap() else tween(StudioMotion.fast, easing = StudioMotion.standard))
    Column(
        modifier.fillMaxWidth().padding(bottom = StudioTokens.space2).graphicsLayer {
            translationY = lift.dp.toPx()
            alpha = opacity
        }
            .studioFocusRing(shape, enabled = rename == null, background = background)
            .background(background, shape).workStatusHalo(running, settled, radius).clip(shape)
            .then(if (reduced) Modifier else Modifier.animateContentSize(tween(StudioMotion.normal, easing = StudioMotion.standard)))
            .taskDragSource(item, drag, enabled = !running && !tab.acting && rename == null, drop)
            .hoverable(interaction).focusRequester(cardFocus).onFocusChanged { cardFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // Pointer double-click renames; keyboard activation opens once without waiting for a second press.
                if (cardFocused && rename == null && event.key in setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                    if (event.type == KeyEventType.KeyUp && !dragging) open(cardFocus)
                    true
                } else false
            }
            .combinedClickable(enabled = rename == null, role = Role.Button,
                onClick = { if (!dragging) open(cardFocus) }, onDoubleClick = if (canRename) ::startRename else null)
            .padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space2),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space1),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                verticalAlignment = Alignment.CenterVertically) {
                Text(data.string("display_id").orEmpty(), color = colors.faint, fontFamily = localStudioMono.current,
                    fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mixed && item.status != null) WorkStatus(item.status, WorkStatusVariant.Chip, live = running, background = background)
            }
            TaskCardMenu(tab, data, editTitle = ::startRename)
        }
        val titleDraft = rename
        if (titleDraft == null) {
            Text(item.title, modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            val focus = remember { FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            StudioField(m["taskTitle"], titleDraft, { rename = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { state ->
                    if (state.isFocused) hadFocus = true else if (hadFocus) saveTitle()
                }.onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter -> { saveTitle(); true }
                        event.key == Key.Escape -> { rename = null; true }
                        else -> false
                    }
                })
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(member)
            if (member?.string("kind") == "human") StudioIcon("user", Modifier.size(StudioTokens.compactIconSize), colors.faint)
            Text(member?.string("name") ?: data.string("assignee_id").orEmpty(), Modifier.weight(1f, fill = false),
                color = colors.muted, fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (data.stringList("depends_on").isNotEmpty()) StudioIcon("link-2", Modifier.size(StudioTokens.compactIconSize), colors.faint)
        }
        TaskUsage(data)
        if (running && activity != null) {
            if (activity.delegated.isNotEmpty()) {
                WorkPill(activity.delegated.size.toString(), listOf(colors.statusColor("running", colors.inset)),
                    quiet = false, icon = "bot", live = true)
            }
            activity.message?.let { latest ->
                WorkActivityLine(latest.text, latest.sequence, pulseOnMount = System.nanoTime() - latest.sequence < 900_000_000L)
            }
        }
        data.string("error")?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.danger, fontSize = StudioTokens.textSmall, maxLines = 2) }
        val archived = data.flag("archived")
        if (item.status == "done" || archived) {
            WorkAction(m[if (archived) "work.taskUnarchive" else "work.taskArchive"], if (archived) "archive-restore" else "archive",
                modifier = Modifier.fillMaxWidth(), enabled = !tab.acting) {
                taskEndpoint(data)?.let { endpoint ->
                    tab.action { patch(endpoint, buildJsonObject { put("archived", !archived) }) }
                }
            }
        }
    }
}

@Composable
private fun TaskCardMenu(tab: ProjectTab, task: JsonObject, editTitle: () -> Unit) {
    val endpoint = taskEndpoint(task) ?: return
    val m = localMessages.current
    val enabled = !tab.acting
    val focus = remember { FocusRequester() }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    Box {
        StudioIconButton(m["taskActions.menu"], "chevron-down", { open = true },
            Modifier.size(StudioTokens.controlMinHeight).focusRequester(focus), enabled = enabled,
            iconSize = StudioTokens.compactIconSize)
        val items = buildList {
            add(StudioMenuItem(m["taskActions.rename"], icon = "pencil", onClick = editTitle))
            if (!task.flag("is_running")) {
                taskStatuses.forEachIndexed { index, status ->
                    add(StudioMenuItem(m.status(status), checked = status == task.string("status"), dividerBefore = index == 0) {
                        selectTaskStatus(tab, task, endpoint, m, status)
                    })
                }
            }
        }
        StudioMenu(open && enabled, { open = false }, items, alignment = Alignment.End, returnFocus = focus,
            title = task.string("display_id"))
    }
}

@Composable
private fun BoxScope.TaskComposer(
    tab: ProjectTab,
    team: String,
    people: List<JsonObject>,
    close: () -> Unit,
    opener: FocusRequester,
    created: (String) -> Unit,
) {
    val m = localMessages.current
    val colors = localColors.current
    var assignee by remember { mutableStateOf(people.firstOrNull()?.string("id")) }
    val member = people.find { it.string("id") == assignee }
    var title by remember { mutableStateOf(TextFieldValue()) }
    var brief by remember { mutableStateOf(TextFieldValue()) }
    var autoStart by remember { mutableStateOf(false) }
    var attachments by remember(team) { mutableStateOf<List<TaskAttachment>>(emptyList()) }
    val isAgent = member?.string("kind") == "agent"
    WorkDialog(m["newTask"], close, Modifier.widthIn(max = 672.dp).fillMaxWidth(.93f).heightIn(max = 640.dp),
        canDismiss = !tab.acting, returnFocus = opener) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Heading(m["newTask"], Modifier.weight(1f), StudioTokens.titleSize.value.toInt())
                WorkAction(m["close"], "x", outlined = false, iconOnly = true, enabled = !tab.acting, onClick = dismiss)
            }
            Muted(m["work.taskComposerIntro"])
        }
        Rule()
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(StudioTokens.space4),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    Muted(m["work.taskTitleLabel"])
                    StudioField(m["work.taskTitleLabel"], title, { title = it }, Modifier.fillMaxWidth(), placeholder = m["taskTitle"])
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    Muted(m["work.taskAssignTo"])
                    val choices = people.mapNotNull { person ->
                        person.string("id")?.let { it to person.string("name").orEmpty() }
                    }.toMap()
                    StudioSelect(m["work.taskAssignTo"], assignee.orEmpty(), choices, Modifier.fillMaxWidth(), !tab.acting) { id ->
                        assignee = id
                        if (people.find { it.string("id") == id }?.string("kind") != "agent") autoStart = false
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                Muted(m["work.taskBriefLabel"])
                TaskAttachmentPicker(tab, attachments, { attachments = it }) { paste ->
                    TextArea(brief, onValueChange = { brief = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 230.dp).then(paste)
                            .semantics { contentDescription = m["work.taskBriefLabel"] },
                        placeholder = { Muted(m["work.taskBriefPlaceholder"]) })
                }
            }
            tab.actionError?.let { ProblemText(it) }
        }
        Rule()
        Row(Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { WorkCheckbox(m["work.taskAutoStart"], autoStart, isAgent) { autoStart = it } }
            WorkAction(m["cancel"], null, enabled = !tab.acting, onClick = dismiss)
            val memberId = assignee
            WorkAction(m["work.taskCreate"], "plus", primary = true, enabled = !tab.acting && memberId != null && title.text.isNotBlank()) {
                if (memberId != null) tab.action {
                    val response = post("/teams/${segment(team)}/tasks", buildJsonObject {
                        put("assignee_id", memberId)
                        put("title", title.text.trim())
                        put("brief", brief.text)
                        put("attachments", attachments.attachmentReferences())
                        put("auto_start", isAgent && autoStart)
                        put("summary_locale", m.language)
                    }).jsonObject
                    if (tab.page.path == "/tasks") created(requireNotNull(response.string("id")))
                }
            }
        }
    }
}

@Composable
internal fun TaskUsage(data: JsonObject) {
    val tokens = data.string("total_tokens")?.toLongOrNull() ?: 0
    val cost = data.string("total_cost_usd")?.toDoubleOrNull() ?: 0.0
    if (tokens > 0 || cost > 0) UsageChip(taskTokens(data), cost)
}

private fun taskTokens(data: JsonObject): List<Long?> {
    val total = data.string("total_tokens")?.toLongOrNull() ?: 0
    if (total == 0L) return listOf(0L, 0L, 0L)
    val split = listOf("total_input_tokens", "total_output_tokens", "total_cached_tokens")
        .map { data.string(it)?.toLongOrNull()?.takeIf { count -> count >= 0 } }
    if (split.none { it == null } && split.sumOf { it ?: 0L } == total) return split
    val reported = data.obj("usage")
    if (reported != null) {
        val fields = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")
            .map { reported.string(it)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L }
        if (fields.sum() == total) return listOf(fields[0] + fields[1], fields[3], fields[2])
    }
    return listOf(null, null, split[2]?.takeIf { it > 0 && it <= total })
}

@Composable
internal fun UsageChip(tokens: List<Long?>, cost: Double?) {
    val colors = localColors.current
    val m = localMessages.current
    val locale = Locale.forLanguageTag(m.language)
    val shape = RoundedCornerShape(StudioTokens.radiusPill)
    Row(Modifier.background(colors.inset, shape).border(StudioTokens.borderWidth, colors.subtleBorder, shape)
        .heightIn(min = StudioTokens.pillMinHeight).padding(horizontal = StudioTokens.space1, vertical = StudioTokens.spaceHair),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
        val values = tokens.map { count -> count?.let { formatTokenCount(it, locale) } ?: "—" } +
            (cost?.let { String.format(locale, "%.1f", it) } ?: "—")
        listOf("arrow-up", "arrow-down", "cpu", "dollar-sign").zip(values).forEach { (icon, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair), verticalAlignment = Alignment.CenterVertically) {
                StudioIcon(icon, Modifier.size(StudioTokens.compactIconSize), colors.faint)
                Text(value, color = colors.muted, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, maxLines = 1)
            }
        }
    }
}

private fun formatTokenCount(count: Long, locale: Locale): String = when {
    count >= 1_000_000 -> String.format(locale, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(locale, "%.1fK", count / 1_000.0)
    else -> count.toString()
}

internal fun taskEndpoint(task: JsonObject): String? {
    val team = task.string("team_id") ?: return null
    val id = task.string("id") ?: return null
    return "/teams/${segment(team)}/tasks/${segment(id)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskConversationView(
    tab: ProjectTab,
    data: JsonObject,
    close: () -> Unit,
    inline: Boolean = false,
    memberFallback: JsonObject? = null,
    openContext: (() -> Unit)? = null,
) {
    val m = localMessages.current
    val colors = localColors.current
    val endpoint = taskEndpoint(data)
    val assignee = data.obj("assignee") ?: memberFallback
    val messages = data.objects("messages")
    val taskId = data.string("id").orEmpty()
    val editor = tab.taskEdits[taskId].takeUnless { inline }
    var diffPath by remember(taskId) { mutableStateOf<String?>(null) }
    var trayOpen by remember(taskId) { mutableStateOf(false) }
    val transcriptState = key(taskId) { rememberLazyListState() }
    val team = tab.list?.teams?.find { it.string("id") == data.string("team_id") }
    val people = team?.objects("members").orEmpty()
    val assigneeId = data.string("assignee_id")
    fun otherMemberName(id: String?): String? = id?.takeUnless { it == assigneeId }?.let { otherId ->
        people.find { it.string("id") == otherId }?.string("name")
    }
    val creator = otherMemberName(data.string("created_by"))
    // §10.4: name the assignee's manager as reviewer without changing who sends review actions.
    val reviewer = otherMemberName(assignee?.string("manager_id")
        ?: people.find { it.string("id") == assigneeId }?.string("manager_id"))
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val hasStatusRail = !inline && endpoint != null
      val split = hasStatusRail && maxWidth >= 740.dp
      val railWidth = (maxWidth * .25f).coerceIn(220.dp, 300.dp)
      val showNarrowRail = hasStatusRail && !split && trayOpen
      val headerPadding = if (maxWidth < 550.dp || inline) StudioTokens.space3 else StudioTokens.panePad
      Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = headerPadding, vertical = StudioTokens.space2),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                        Text(data.string("display_id").orEmpty(), color = colors.faint, fontFamily = localStudioMono.current,
                            fontSize = StudioTokens.textSmall)
                        Text(data.string("title").orEmpty(), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3),
                            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1), itemVerticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                                MemberAvatar(assignee)
                                Text(assignee?.string("name") ?: assigneeId.orEmpty(), color = colors.muted,
                                    fontSize = StudioTokens.textSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            creator?.let {
                                Text(m["work.taskCreatedBy"].replace("{name}", it), color = colors.muted, fontSize = StudioTokens.textSmall)
                            }
                            reviewer?.let {
                                Text(m["work.taskReviewedBy"].replace("{name}", it), color = colors.muted, fontSize = StudioTokens.textSmall)
                            }
                        }
                    }
                    if (endpoint != null && assignee?.string("kind") == "agent") TaskRemoteControl(tab, data, assignee, endpoint)
                    if (hasStatusRail && !split) {
                        WorkAction(m["work.sessionStatus"], "files", outlined = false, iconOnly = true,
                            modifier = Modifier.semantics { selected = showNarrowRail }) {
                            trayOpen = !trayOpen
                        }
                    }
                    if (!inline && endpoint != null && editor == null) {
                        WorkAction(m["taskActions.edit"], "pencil", outlined = false, iconOnly = true, enabled = !tab.acting) {
                            tab.taskEdits[taskId] = TaskEditDraft(data)
                        }
                    }
                    WorkAction(m[if (inline) "work.producerOpenTask" else "close"], if (inline) "external-link" else "x",
                        outlined = false, iconOnly = true, onClick = close)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space1), itemVerticalAlignment = Alignment.CenterVertically) {
                    val status = data.string("status")
                    if (status != null) TaskDetailStatus(status, data.flag("is_running"), !inline && endpoint != null && !tab.acting) { next ->
                        if (endpoint != null) selectTaskStatus(tab, data, endpoint, m, next)
                    }
                    if (data.flag("archived")) WorkPill(m["work.taskArchived"], icon = "archive")
                    data.string("result_type")?.takeIf { it in nativeTaskResults }?.let {
                        val resultStatus = when (it) { "done" -> "done"; "error" -> "error"; else -> "needs_review" }
                        WorkPill(m["taskActions.result.$it"], dots = listOf(colors.statusColor(resultStatus, colors.sheetFill)))
                    }
                    val started = data.conversationTimestamp("started_at")
                    val finished = data.conversationTimestamp("finished_at")
                    if (started != null && finished != null) {
                        val seconds = Duration.between(started, finished).seconds.coerceAtLeast(0)
                        WorkPill("%d:%02d".format(seconds / 60, seconds % 60), icon = "timer")
                    }
                    if (assignee?.string("kind") != "human") {
                        TaskModelTelemetry(data, assignee, messages, openContext)
                    } else if (openContext != null) {
                        WorkAction(m["contextViewer.title"], "file-search", outlined = false, iconOnly = true, onClick = openContext)
                    }
                    TaskUsage(data)
                    TaskLimitIndicator(data)
                }
            }
            Rule()
            Column(Modifier.weight(1f)) {
                val file = diffPath
                if (showNarrowRail && endpoint != null) {
                    TaskFileRail(tab.api, endpoint, data, Modifier.fillMaxSize()) {
                        diffPath = it
                        trayOpen = false
                    }
                } else if (file != null && endpoint != null) {
                    TaskDiffPanel(tab.api, endpoint, file) { diffPath = null }
                } else if (editor != null && endpoint != null) {
                    TaskEditForm(tab, data, editor, endpoint, taskId)
                } else {
                    val brief = data.string("brief")?.takeIf(String::isNotBlank)
                    val attachments = data.taskAttachments(brief = true)
                    ConversationTranscript(messages, taskId, assignee?.string("name"),
                        if (brief == null && attachments.isEmpty()) m["work.producerNoMessages"] else "", Modifier.fillMaxSize(),
                        running = data.flag("is_running"), chatSelectionTab = tab, scrollState = transcriptState, before = {
                            if (brief != null || attachments.isNotEmpty()) {
                                item("brief") {
                                    val shape = RoundedCornerShape(StudioTokens.radiusMd)
                                    Column(Modifier.fillMaxWidth().background(colors.inset, shape).padding(StudioTokens.space2),
                                        verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                                        Muted(m["taskBrief"])
                                        if (brief != null) ConversationMarkdown(brief, selectable = false)
                                        TaskAttachmentLinks(tab.api, attachments)
                                    }
                                }
                            }
                        }, afterMessage = { message ->
                            if (message.string("kind") == "review") {
                                TaskAttachmentLinks(tab.api, message.obj("data")?.taskAttachments(brief = false).orEmpty(), endpoint)
                            }
                        })
                }
            }
            if (!showNarrowRail && endpoint != null && editor == null) {
                Rule()
                TaskLifecycleActions(tab, data, assignee, endpoint, inline, close)
            }
        }
        if (split && endpoint != null) {
            Rule(vertical = true)
            TaskFileRail(tab.api, endpoint, data, Modifier.width(railWidth).fillMaxHeight()) { diffPath = it }
        }
      }
    }
}

@Composable
private fun TaskDetailStatus(status: String, running: Boolean, editable: Boolean, select: (String) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val enabled = editable && !running
    val focus = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        if (enabled) {
            Row(Modifier.focusRequester(focus).studioFocusRing(RoundedCornerShape(StudioTokens.radiusPill), background = colors.sheetFill)
                .semantics { contentDescription = m["taskActions.status"]; stateDescription = m.status(status) }
                .clickable(role = Role.DropdownList) { expanded = true }
                .heightIn(min = StudioTokens.controlMinHeight),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                verticalAlignment = Alignment.CenterVertically) {
                WorkStatus(status, WorkStatusVariant.Chip, background = colors.sheetFill)
                StudioIcon("chevron-down", Modifier.size(StudioTokens.compactIconSize), colors.faint)
            }
        } else WorkStatus(status, WorkStatusVariant.Chip, live = running, background = colors.sheetFill)
        StudioMenu(expanded && enabled, { expanded = false }, taskStatuses.map { value ->
            StudioMenuItem(m.status(value), checked = value == status) { select(value) }
        }, initialItem = taskStatuses.indexOf(status), returnFocus = focus)
    }
}

@Composable
private fun TaskModelTelemetry(data: JsonObject, member: JsonObject?, messages: List<JsonObject>, openContext: (() -> Unit)?) {
    val m = localMessages.current
    val colors = localColors.current
    val backend = data.string("agent_backend") ?: member?.string("agent_backend")
    val model = data.string("model") ?: (if (backend == member?.string("agent_backend")) member?.string("model") else m["chatModel.default"])
    val effort = data.string("effort") ?: member?.string("effort") ?: "auto".takeIf { !model.isNullOrBlank() || backend != null }
    val backendIcon = when (backend) {
        "claude_code" -> "agent-claude-code"
        "codex" -> "agent-codex"
        "qwen" -> "agent-qwen"
        else -> "bot"
    }
    model?.takeIf(String::isNotBlank)?.let { WorkPill(it, icon = backendIcon) }
    effort?.takeIf(String::isNotBlank)?.let { WorkPill(if (it == "auto") m["work.producerEffort.auto"] else it, icon = "sliders-horizontal") }
    val context = remember(messages) { chatContextUsage(messages) }
    val locale = Locale.forLanguageTag(m.language)
    val used = context.tokens?.let { formatTokenCount(it, locale) }
    val capacity = context.capacity?.let { formatTokenCount(it, locale) }
    if (used != null) {
        val label = m["chatModel.context"].replace("{used}", used).replace("{total}", capacity ?: "—")
        if (openContext == null) WorkPill(label, icon = "file-search")
        else Box(Modifier.studioFocusRing(RoundedCornerShape(StudioTokens.radiusPill), background = colors.sheetFill)
            .clickable(role = Role.Button, onClickLabel = m["contextViewer.title"], onClick = openContext)
            .heightIn(min = StudioTokens.controlMinHeight), contentAlignment = Alignment.Center) {
            WorkPill(label, icon = "file-search", quiet = false)
        }
    } else if (openContext != null) {
        WorkAction(m["contextViewer.title"], "file-search", outlined = false, iconOnly = true, onClick = openContext)
    }
}

@Composable
private fun TaskFileRail(api: ProjectApi?, endpoint: String, task: JsonObject, modifier: Modifier, openDiff: (String) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    var changes by remember(endpoint) { mutableStateOf<JsonObject?>(null) }
    var error by remember(endpoint) { mutableStateOf<Problem?>(null) }
    val messages = task.objects("messages")
    LaunchedEffect(api, endpoint, messages.lastOrNull()?.string("id")) {
        if (api == null) return@LaunchedEffect
        try {
            changes = api.get("$endpoint/changes").jsonObject
            error = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.problem()
        }
    }
    WorkSessionStatus(messages, modifier, task) {
        val files = changes?.objects("files").orEmpty()
        SessionFold(m["work.taskFiles"], "files", files.size) {
            error?.let { ProblemText(it) }
            changes?.string("error")?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.danger, fontSize = StudioTokens.textSmall) }
            if (files.isEmpty() && error == null && changes?.string("error") == null) {
                Muted(m[if (changes == null) "loading" else "work.taskNoFiles"], modifier = Modifier.padding(start = StudioTokens.space4))
            }
            files.forEach { file ->
                val path = file.string("path") ?: return@forEach
                Row(Modifier.fillMaxWidth().studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = colors.canvas)
                    .clickable(onClickLabel = m["taskActions.diff"], role = Role.Button) { openDiff(path) }
                    .padding(vertical = StudioTokens.space1), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalAlignment = Alignment.CenterVertically) {
                    StudioIcon("file-text", Modifier.size(StudioTokens.iconSize), colors.faint)
                    Text(path, Modifier.weight(1f), fontSize = StudioTokens.textSmall,
                        fontFamily = localStudioMono.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    file.string("insertions")?.let { Text("+$it", color = colors.good, fontSize = StudioTokens.textSmall) }
                    file.string("deletions")?.let { Text("−$it", color = colors.danger, fontSize = StudioTokens.textSmall) }
                }
            }
        }
    }
}
