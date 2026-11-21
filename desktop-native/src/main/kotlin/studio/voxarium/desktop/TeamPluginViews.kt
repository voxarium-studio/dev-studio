package studio.voxarium.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginsPage(tab: ProjectTab, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val inventory = tab.list
    val plugins = inventory?.items.orEmpty()
    var filter by remember { mutableStateOf("all") }
    val enabled = plugins.count { it.payload.flag("enabled") }
    val filters = mapOf("all" to plugins.size, "enabled" to enabled, "disabled" to plugins.size - enabled)
    val visible = plugins.filter { filter == "all" || it.payload.flag("enabled") == (filter == "enabled") }
    Column(modifier.fillMaxSize()) {
        WorkHeader {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                Text(m["plugins"], fontSize = StudioTokens.titleSize, fontWeight = FontWeight.Medium)
                Text(m["work.pluginsWarning"], fontSize = StudioTokens.textSmall, color = colors.faint)
            }
        }
        WorkErrors(tab)
        when {
            inventory == null -> Centered {
                StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                Muted(m["loading"])
            }
            plugins.isEmpty() -> WorkEmpty(m["work.pluginsEmpty"], icon = "blocks")
            else -> Column(Modifier.weight(1f).fillMaxWidth().studioArrival().padding(start = StudioTokens.panePad, end = StudioTokens.panePad, top = StudioTokens.panePad)) {
                FlowRow(Modifier.fillMaxWidth().selectableGroup().padding(bottom = StudioTokens.space3),
                    horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    for ((key, count) in filters) {
                        StudioSelectionRow(key == filter, onClick = { filter = key }) {
                            val ink = if (key == filter) colors.onAccentContainer else colors.text
                            Text(m["work.pluginFilter.$key"], color = ink, fontSize = StudioTokens.textSmall)
                            Text(count.toString(), color = ink, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall)
                        }
                    }
                }
                if (visible.isEmpty()) WorkEmpty(m["work.pluginFilterEmpty"], modifier = Modifier.weight(1f))
                else ScrollList(Modifier.weight(1f).fillMaxWidth()) {
                    items(visible, key = { it.id }) { plugin -> PluginCard(plugin, tab) }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(item: WorkspaceItem, tab: ProjectTab) {
    val m = localMessages.current
    val colors = localColors.current
    val data = item.payload
    val enabled = data.flag("enabled")
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    val scope = data.string("scope")
    val origin = when (scope) {
        "builtin", "global", "project" -> m["work.pluginScope.$scope"]
        else -> scope
    }
    Row(Modifier.widthIn(max = 896.dp).fillMaxWidth().padding(bottom = StudioTokens.space2)
        .background(if (enabled) colors.cardFill else colors.surface, shape).padding(StudioTokens.space3),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3), verticalAlignment = Alignment.Top) {
        StudioIcon("boxes", Modifier.padding(top = StudioTokens.spaceHair).size(StudioTokens.iconSize), colors.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = if (enabled) colors.text else colors.muted, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(item.caption.orEmpty(), color = colors.faint, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall)
                if (item.status == "error") WorkPill(m["work.pluginError"], listOf(colors.danger), quiet = false)
            }
            Muted(listOfNotNull(item.id, origin, data.string("author")?.takeIf(String::isNotBlank)).joinToString(" · "))
            data.string("description")?.trim()?.takeIf(String::isNotBlank)?.let { PluginDescription(it) }
            data.string("error")?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.danger, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall) }
            val env = data.obj("env")
            val envKey = when (val state = env?.string("state")) {
                "pending" -> if (item.status == "loaded") "queued" else if (enabled) "notBuilt" else "onEnable"
                "ready", "building", "failed" -> state
                else -> null
            }
            val providers = data.stringList("search_providers")
            val counts = listOfNotNull(
                pluginCount(m, "nodes", data.stringList("node_types").size),
                pluginCount(m, "pages", data.stringList("pages").size),
                data.stringList("previewers").size.takeIf { it > 0 }?.let { pluginCount(m, "previewers", it) },
                providers.takeIf { it.isNotEmpty() }?.let { m["work.pluginSearch"].replace("{providers}", it.joinToString(", ")) },
                envKey?.let { m["work.pluginEnv.$it"] },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                if (envKey == "building") StudioLoadingIcon(Modifier.size(StudioTokens.compactIconSize))
                Muted(counts.joinToString(" · "))
            }
            if (envKey == "failed") {
                env?.string("error")?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = colors.danger, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, maxLines = 8)
                }
                Muted(m["work.pluginEnvRetry"])
            }
        }
        WorkAction(m[if (enabled) "work.disable" else "work.enable"], "power", enabled = !tab.acting) {
            tab.action { post("/plugins/${segment(item.id)}/${if (enabled) "disable" else "enable"}") }
        }
    }
}

@Composable
private fun PluginDescription(description: String) {
    val m = localMessages.current
    val colors = localColors.current
    var expanded by remember(description) { mutableStateOf(false) }
    var overflow by remember(description) { mutableStateOf(false) }
    val reduced = LocalStudioReducedMotion.current
    Column(if (reduced) Modifier else Modifier.animateContentSize(tween(StudioMotion.normal, easing = StudioMotion.standard)),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Text(description, color = colors.muted, maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (overflow || expanded) {
            WorkAction(m[if (expanded) "work.overflowCollapse" else "work.overflowExpand"],
                if (expanded) "chevron-up" else "chevron-down", outlined = false) { expanded = !expanded }
        }
    }
}

private fun pluginCount(m: Messages, key: String, count: Int): String {
    val form = if (m.language != "ru") {
        if (count == 1) "one" else "other"
    } else when {
        count % 100 in 11..14 -> "many"
        count % 10 == 1 -> "one"
        count % 10 in 2..4 -> "few"
        else -> "many"
    }
    return m["work.pluginCount.$key.$form"].replace("{count}", count.toString())
}

@Composable
internal fun TeamsPage(tab: ProjectTab, navigate: (Page) -> Unit, workspace: Workspace, modifier: Modifier) {
    val m = localMessages.current
    val colors = localColors.current
    val list = tab.list
    val teams = list?.teams.orEmpty()
    val selection = tab.page.selection(Section.TEAMS)
    val team = teams.find { it.string("id") == selection } ?: teams.firstOrNull()
    val teamId = team?.string("id")
    val members = team?.objects("members").orEmpty()
    val tasks = tab.detail?.takeIf { it.item.id == teamId }?.data?.objects("tasks").orEmpty()
    var adding by remember(teamId) { mutableStateOf(false) }
    var editingId by remember(teamId) { mutableStateOf<String?>(null) }
    var teamMenu by remember { mutableStateOf(false) }
    val teamMenuFocus = remember { FocusRequester() }
    val editing = members.find { it.string("id") == editingId }
    LaunchedEffect(teamId, selection) {
        if (selection == null && teamId != null) navigate(Page("/teams", Selectors(path = teamId)))
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
      val settingsWidth = (maxWidth * .38f).coerceAtMost(352.dp)
      val editorWidth = (maxWidth * .38f).coerceAtMost(384.dp)
      Row(Modifier.fillMaxSize()) {
        TeamSettingsRail(tab, Modifier.width(settingsWidth).fillMaxHeight())
        Rule(vertical = true)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            WorkHeader {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    Box {
                        Row(if (teams.size > 1) Modifier.focusRequester(teamMenuFocus)
                            .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = colors.headerFill)
                            .clickable(role = Role.Button) { teamMenu = true } else Modifier,
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                            Text(team?.string("name") ?: m["work.teamFallback"], fontSize = StudioTokens.titleSize,
                                fontWeight = FontWeight.Medium)
                            if (teams.size > 1) StudioIcon("chevron-down", Modifier.size(StudioTokens.compactIconSize), colors.faint)
                        }
                        StudioMenu(teamMenu, { teamMenu = false }, teams.map { entry ->
                            StudioMenuItem(entry.string("name").orEmpty(), checked = entry == team) {
                                teamMenu = false
                                navigate(Page("/teams", Selectors(path = entry.string("id"))))
                            }
                        }, initialItem = teams.indexOfFirst { it == team }, returnFocus = teamMenuFocus)
                    }
                    TeamHelp(m["work.teamAutoSave"])
                }
                WorkAction(m["work.teamAddMember"], "user-plus", enabled = teamId != null && !tab.acting) { adding = true }
            }
            WorkErrors(tab, includeDetail = true)
            if (adding && teamId != null) {
                CreateMember(teamId, tab, members, { adding = false }) { id ->
                    adding = false
                    if (id != null) editingId = id
                }
            }
            when {
                list == null -> Centered { Muted(m["loading"]) }
                members.isEmpty() -> WorkEmpty(m["work.teamEmpty"], m["work.teamEmptyHint"], "users", Modifier.weight(1f))
                else -> OrgCanvas(
                    teamId, members, tasks, editingId, Modifier.weight(1f).fillMaxWidth().studioArrival(teamId),
                    workspace.preferences.obj("theme")?.string("canvas_grid") ?: "dots", canTidy = !tab.acting,
                    tidy = teamId?.let { id -> { done ->
                        tab.action {
                            patch("/teams/${segment(id)}/layout", buildJsonObject {
                                putJsonObject("positions") { members.mapNotNull { it.string("id") }.forEach { put(it, JsonNull) } }
                            })
                            done()
                        }
                    } },
                ) { editingId = it }
            }
        }
        if (editing != null && teamId != null) {
            Rule(vertical = true)
            Box(Modifier.width(editorWidth).fillMaxHeight().background(colors.surface)) {
                MemberEditor(tab, teamId, editing, members) { editingId = null }
            }
        }
      }
    }
}

@Composable
private fun CreateMember(
    team: String,
    tab: ProjectTab,
    members: List<JsonObject>,
    close: () -> Unit,
    created: (String?) -> Unit,
) {
    val m = localMessages.current
    val colors = localColors.current
    var name by remember { mutableStateOf(TextFieldValue()) }
    var role by remember { mutableStateOf(TextFieldValue()) }
    var kind by remember { mutableStateOf("agent") }
    var reportsTo by remember { mutableStateOf(members.firstOrNull { it.string("manager_id") == null }?.string("id")) }
    Column(Modifier.fillMaxWidth().background(colors.elevated).padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            LabeledField(m["work.name"], name, Modifier.weight(1f)) { name = it }
            LabeledField(m["work.role"], role, Modifier.weight(1f)) { role = it }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                val label = m["work.teamKind"]
                TeamHelp(label)
                StudioSelect(label, kind, mapOf("agent" to m["work.teamAgent"], "human" to m["work.teamHuman"]),
                    Modifier.fillMaxWidth()) { kind = it }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.Bottom) {
            ManagerPicker(reportsTo, members, Modifier.weight(1f)) { reportsTo = it }
            WorkAction(m["cancel"], null, onClick = close)
            WorkAction(m["work.add"], "plus", primary = true, enabled = name.text.isNotBlank() && !tab.acting) {
                tab.action {
                    val response = post("/teams/${segment(team)}/members", buildJsonObject {
                        put("name", name.text.trim())
                        put("role_title", role.text.trim())
                        put("kind", kind)
                        put("manager_id", reportsTo)
                    }).jsonObject
                    if (tab.page.path == "/teams") created(response.string("id"))
                }
            }
        }
    }
    Rule()
}

@Composable
private fun LabeledField(label: String, value: TextFieldValue, modifier: Modifier, change: (TextFieldValue) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        TeamHelp(label)
        StudioField(label, value, change, Modifier.fillMaxWidth(), placeholder = label)
    }
}

@Composable
private fun ManagerPicker(selection: String?, members: List<JsonObject>, modifier: Modifier, choose: (String?) -> Unit) {
    val m = localMessages.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        val label = m["work.teamReportsTo"]
        TeamHelp(label)
        StudioSelect(label, selection.orEmpty(), buildMap {
            put("", m["work.teamNoManager"])
            members.forEach { member -> member.string("id")?.let { put(it, member.string("name").orEmpty()) } }
        }, Modifier.fillMaxWidth()) { choose(it.ifEmpty { null }) }
    }
}

@Composable
private fun TeamSettingValue(label: String, value: String) {
    val colors = localColors.current
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        TeamHelp(label)
        Text(value, Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(StudioTokens.radiusMd))
            .border(StudioTokens.borderWidth, colors.border, RoundedCornerShape(StudioTokens.radiusMd)).padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1))
    }
}

@Composable
private fun MemberEditor(tab: ProjectTab, team: String, member: JsonObject, members: List<JsonObject>, close: () -> Unit) {
    val m = localMessages.current
    val id = member.string("id") ?: return
    val storedName = member.string("name").orEmpty()
    val storedTitle = member.string("role_title").orEmpty()
    val storedResponsibility = member.string("responsibility").orEmpty()
    val storedManager = member.string("manager_id")
    val producer = member.string("builtin") == "producer"
    var name by remember(id, storedName) { mutableStateOf(TextFieldValue(storedName)) }
    var title by remember(id, storedTitle) { mutableStateOf(TextFieldValue(storedTitle)) }
    var responsibility by remember(id, storedResponsibility) { mutableStateOf(TextFieldValue(storedResponsibility)) }
    var manager by remember(id, storedManager) { mutableStateOf(storedManager) }
    Column(Modifier.fillMaxSize()) {
        WorkHeader {
            MemberAvatar(member, badge = true)
            Text(member.string("name").orEmpty(), Modifier.weight(1f), fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            WorkAction(m["close"], "x", outlined = false, iconOnly = true, onClick = close)
        }
        Rule()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(StudioTokens.space4),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            LabeledField(m["work.name"], name, Modifier.fillMaxWidth()) { name = it }
            LabeledField(m["work.role"], title, Modifier.fillMaxWidth()) { title = it }
            ManagerPicker(manager, members.filter { it.string("id") != id }, Modifier.fillMaxWidth()) { manager = it }
            val responsibilityLabel = m["work.teamResponsibility"]
            TeamHelp(responsibilityLabel)
            TextArea(responsibility, onValueChange = { responsibility = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 170.dp)
                    .semantics { contentDescription = responsibilityLabel }, readOnly = producer)
            if (producer) TeamHelp(m["nativeTeam.producerResponsibility"])
            val model = listOfNotNull(member.string("agent_backend"), member.string("model")).joinToString(" · ")
            if (model.isNotBlank() && member.string("kind") == "agent") TeamSettingValue(m["status.model"], model)
            tab.actionError?.let { ProblemText(it) }
            val changed = name.text != storedName || title.text != storedTitle || manager != storedManager ||
                !producer && responsibility.text != storedResponsibility
            WorkAction(m["work.save"], null, enabled = changed && name.text.isNotBlank() && !tab.acting, primary = true) {
                tab.action {
                    patch("/teams/${segment(team)}/members/${segment(id)}", buildJsonObject {
                        put("name", name.text.trim())
                        put("role_title", title.text.trim())
                        if (!producer) put("responsibility", responsibility.text)
                        if (manager == null) put("manager_id", JsonNull) else put("manager_id", manager)
                    })
                }
            }
        }
    }
}

private data class OrgMember(val member: JsonObject, val position: Offset) {
    val id get() = member.string("id").orEmpty()
}

private fun orgMembers(members: List<JsonObject>): List<OrgMember> {
    val ids = members.mapNotNull { it.string("id") }.toSet()
    val descendants = members.groupBy { it.string("manager_id")?.takeIf(ids::contains) }
    val placed = mutableSetOf<String>()
    val result = mutableListOf<OrgMember>()
    var column = 0
    fun place(member: JsonObject, depth: Int): Float? {
        val id = member.string("id") ?: return null
        if (!placed.add(id)) return null
        val childPositions = descendants[id].orEmpty().mapNotNull { place(it, depth + 1) }
        val x = if (childPositions.isEmpty()) (column++ * 264).toFloat() else (childPositions.first() + childPositions.last()) / 2
        val fallback = Offset(x, (depth * 140).toFloat())
        val savedX = member.string("pos_x")?.toFloatOrNull()?.takeIf(Float::isFinite)
        val savedY = member.string("pos_y")?.toFloatOrNull()?.takeIf(Float::isFinite)
        result += OrgMember(member, if (savedX != null && savedY != null) Offset(savedX, savedY) else fallback)
        return x
    }
    for (root in descendants[null].orEmpty()) {
        place(root, 0)
        column++
    }
    for (member in members) if (member.string("id") !in placed) {
        place(member, 0)
        column++
    }
    return result
}

/** §10.4: fit the measured cards in the graph viewport, not the entire pane. */
internal data class OrgViewport(val scale: Float, val origin: Offset) {
    fun map(point: Offset): Offset = origin + point * scale
}

internal fun fitOrgViewport(viewport: Size, cards: List<Rect>, inset: Float): OrgViewport? {
    if (cards.isEmpty() || !viewport.width.isFinite() || !viewport.height.isFinite() ||
        viewport.width <= 0f || viewport.height <= 0f) return null
    val left = cards.minOf { it.left }
    val top = cards.minOf { it.top }
    val width = cards.maxOf { it.right } - left
    val height = cards.maxOf { it.bottom } - top
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    val margin = inset.coerceIn(0f, minOf(viewport.width, viewport.height) / 4f)
    // Fit may have to go below the manual minimum; adding that clamp here clips wide teams.
    val scale = minOf(.9f, (viewport.width - 2 * margin) / width, (viewport.height - 2 * margin) / height)
    return OrgViewport(scale, Offset((viewport.width - width * scale) / 2 - left * scale,
        (viewport.height - height * scale) / 2 - top * scale))
}

internal fun zoomOrgScale(scale: Float, fitScale: Float, zoomIn: Boolean): Float =
    if (zoomIn) (scale * 1.2f).coerceAtMost(1.6f)
    else (scale / 1.2f).coerceAtLeast(minOf(.3f, fitScale, scale))

private class OrgCanvasTransform {
    var pan by mutableStateOf(Offset.Zero)
    var manualZoom by mutableStateOf<Float?>(null)
    // Written in measure without scheduling another frame; event handlers use the latest fit.
    var fitScale: Float? = null

    fun step(zoomIn: Boolean) {
        val fit = fitScale ?: return
        manualZoom = zoomOrgScale(manualZoom ?: fit, fit, zoomIn)
    }

    fun reset() {
        pan = Offset.Zero
        manualZoom = null
    }
}

@Composable
internal fun OrgCanvas(
    team: String?,
    members: List<JsonObject>,
    tasks: List<JsonObject>,
    selected: String?,
    modifier: Modifier,
    grid: String,
    canTidy: Boolean = true,
    tidy: ((onDone: () -> Unit) -> Unit)? = null,
    select: (String) -> Unit,
) {
    val m = localMessages.current
    val colors = localColors.current
    val nodes = remember(members) { orgMembers(members) }
    val reportsColor = remember(colors) { StudioTokens.ensureContrast(colors.strongBorder, colors.canvas) }
    val assignsColor = remember(colors) { StudioTokens.ensureContrast(colors.accent, colors.canvas) }
    val taskCounts = remember(tasks) {
        tasks.filterNot { it.flag("archived") }.groupBy { it.string("assignee_id") }
            .mapValues { (_, records) -> records.groupingBy { it.string("status") }.eachCount() }
    }
    val transform = remember(team) { OrgCanvasTransform() }
    SubcomposeLayout(modifier.background(colors.canvas).clipToBounds().testTag("teams.orgChart")) { limits ->
        val w = limits.maxWidth
        val h = limits.maxHeight
        val top = subcompose("top") {
            Row(Modifier.fillMaxWidth().testTag("teams.orgTopControls").background(colors.canvas).padding(StudioTokens.space3),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3),
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    OrgLegend(m["work.teamLegendReports"], reportsColor, dashed = false)
                    OrgLegend(m["work.teamLegendAssign"], assignsColor, dashed = true)
                }
                if (tidy != null) {
                    Box(Modifier.background(colors.surface, RoundedCornerShape(StudioTokens.radiusMd))) {
                        WorkAction(m["work.teamTidy"], "layout-grid", enabled = canTidy) { tidy(transform::reset) }
                    }
                }
            }
        }.single().measure(Constraints.fixedWidth(w).copy(maxHeight = h))
        val bottom = subcompose("bottom") {
            Box(Modifier.fillMaxWidth().testTag("teams.orgBottomControls").background(colors.canvas).padding(StudioTokens.space3)) {
                val shape = RoundedCornerShape(StudioTokens.radiusMd)
                Column(Modifier.studioShadow(shape, colors.shadowSm).background(colors.surface, shape)
                    .border(StudioTokens.borderWidth, colors.border, shape)) {
                    WorkAction(m["work.zoomIn"], "plus", iconOnly = true, outlined = false) { transform.step(true) }
                    WorkAction(m["work.zoomOut"], "minus", iconOnly = true, outlined = false) { transform.step(false) }
                    WorkAction(m["work.fitView"], "maximize", iconOnly = true, outlined = false) { transform.reset() }
                }
            }
        }.single().measure(Constraints.fixedWidth(w).copy(maxHeight = h - top.height))
        val viewportHeight = h - top.height - bottom.height
        val viewport = Size(w.toFloat(), viewportHeight.toFloat())
        val cardWidth = 220.dp.roundToPx() // MemberNode.vue and persisted chart coordinates.
        val cards = nodes.map { node ->
            val placeable = subcompose(node.id to "card") {
                OrgCard(node.member, taskCounts[node.id].orEmpty(), selected == node.id,
                    Modifier.testTag("teams.member.${node.id}")) { select(node.id) }
            }.single().measure(Constraints.fixedWidth(cardWidth))
            val point = node.position * density
            Triple(node, placeable, Rect(point, Size(placeable.width.toFloat(), placeable.height.toFloat())))
        }
        val fit = fitOrgViewport(viewport, cards.map { it.third }, StudioTokens.space4.toPx())
        transform.fitScale = fit?.scale
        val scale = transform.manualZoom ?: fit?.scale ?: 1f
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val origin = if (fit == null) Offset.Zero else center + (fit.origin - center) * (scale / fit.scale) + transform.pan
        val projection = OrgViewport(scale, origin)
        val chart = if (fit != null) subcompose("chart") {
            val boundsById = cards.associate { (node, _, bounds) -> node.id to bounds }
            Canvas(Modifier.testTag("teams.orgViewport").pointerInput(transform) {
                detectDragGestures { change, amount ->
                    change.consume()
                    transform.pan += amount
                }
            }) {
                drawOrgGrid(grid, colors.grid, projection)
                nodes.forEach { node ->
                    val bounds = boundsById.getValue(node.id)
                    boundsById[node.member.string("manager_id")]?.let { manager ->
                        drawOrgEdge(projection.map(manager.bottomCenter), projection.map(bounds.topCenter),
                            reportsColor, sideways = false, scale = scale)
                    }
                    for (target in node.member.stringList("can_assign_to").mapNotNull(boundsById::get)) {
                        drawOrgEdge(projection.map(bounds.centerRight), projection.map(target.centerLeft),
                            assignsColor, sideways = true, scale = scale)
                    }
                }
            }
        }.single().measure(Constraints.fixed(w, viewportHeight)) else null
        layout(w, h) {
            chart?.place(0, top.height)
            if (fit != null) {
                cards.forEach { (_, card, bounds) ->
                    val position = projection.map(bounds.topLeft)
                    card.placeWithLayer(position.x.roundToInt(), top.height + position.y.roundToInt()) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
            // Foreground chrome stays usable when the user pans past the fitted boundary.
            top.place(0, 0, zIndex = 1f)
            bottom.place(0, h - bottom.height, zIndex = 1f)
        }
    }
}

@Composable
private fun OrgLegend(label: String, tint: Color, dashed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(StudioTokens.space5, StudioTokens.focusWidth)) {
            drawLine(tint, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
        }
        TeamHelp(label)
    }
}

private fun DrawScope.drawOrgGrid(grid: String, tint: Color, view: OrgViewport) {
    if (grid == "none") return
    val gap = (18.dp.toPx() * view.scale).coerceAtLeast(6.dp.toPx()) // OrgChart.vue background gap.
    val radius = (.75.dp.toPx() * view.scale).coerceAtLeast(.45.dp.toPx())
    val startY = view.origin.y % gap
    var x = view.origin.x % gap
    while (x < size.width) {
        if (grid == "lines") drawLine(tint, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        else {
            var y = startY
            while (y < size.height) {
                drawCircle(tint, radius, Offset(x, y))
                y += gap
            }
        }
        x += gap
    }
    if (grid == "lines") {
        var y = startY
        while (y < size.height) {
            drawLine(tint, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            y += gap
        }
    }
}

/** Passive visual connections: no editable handles are offered by the native chart. */
private fun DrawScope.drawOrgEdge(a: Offset, b: Offset, tint: Color, sideways: Boolean, scale: Float) {
    val extension = (12.dp.toPx() * scale).coerceAtLeast(3.dp.toPx())
    val step = if (sideways) Offset(extension, 0f) else Offset(0f, extension)
    val start = a + step
    val end = b - step
    val mid = if (sideways) (start.y + end.y) / 2 else (start.x + end.x) / 2
    val points = if (sideways && start.x > end.x) listOf(a, start, Offset(start.x, mid), Offset(end.x, mid), end, b)
    else if (!sideways && start.y > end.y) listOf(a, start, Offset(mid, start.y), Offset(mid, end.y), end, b)
    else if (sideways) {
        val x = (a.x + b.x) / 2
        listOf(a, Offset(x, a.y), Offset(x, b.y), b)
    } else {
        val y = (a.y + b.y) / 2
        listOf(a, Offset(a.x, y), Offset(b.x, y), b)
    }
    val corners = points.fold(mutableListOf<Offset>()) { path, point ->
        if (path.lastOrNull() != point) path += point
        path
    }
    val path = Path().apply {
        moveTo(a.x, a.y)
        for (i in 1 until corners.lastIndex) {
            val corner = corners[i]
            fun toward(other: Offset): Offset {
                val delta = other - corner
                val length = delta.getDistance()
                return corner + delta * (minOf(5.dp.toPx() * scale, length / 2f) / length)
            }
            val before = toward(corners[i - 1])
            val after = toward(corners[i + 1])
            lineTo(before.x, before.y)
            quadraticTo(corner.x, corner.y, after.x, after.y)
        }
        lineTo(b.x, b.y)
    }
    drawPath(path, tint, style = Stroke(width = 1.dp.toPx(),
        pathEffect = if (sideways) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
    val tip = (5.dp.toPx() * scale).coerceAtLeast(2.dp.toPx())
    val back = b - if (sideways) Offset(tip, 0f) else Offset(0f, tip)
    val wing = if (sideways) Offset(0f, tip / 2) else Offset(tip / 2, 0f)
    drawPath(Path().apply {
        moveTo(b.x, b.y)
        lineTo(back.x + wing.x, back.y + wing.y)
        lineTo(back.x - wing.x, back.y - wing.y)
        close()
    }, tint)
}

@Composable
private fun OrgCard(member: JsonObject, counts: Map<String?, Int>, selected: Boolean, modifier: Modifier, open: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    val accent = member.memberColor(colors.accent)
    val person = member.string("kind") == "human"
    val skills = member.stringList("skills")
    val face = colors.cardFill
    Column(modifier.studioFocusRing(shape, background = face).clip(shape).background(face)
        .border(if (selected) StudioTokens.focusWidth else StudioTokens.borderWidth,
            if (selected) colors.focusOn(face) else colors.cardBorder, shape)
        .clickable(role = Role.Button, onClick = open)) {
        // Min height also bounds the member's swatch in chart coordinates; fit measures the whole card.
        val nodeTitleHeight = 42.dp
        Row(Modifier.fillMaxWidth().padding(start = StudioTokens.spaceHair).heightIn(min = nodeTitleHeight),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            Spacer(Modifier.height(nodeTitleHeight).width(StudioTokens.space1).background(accent))
            MemberAvatar(member, badge = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                Text(member.string("name").orEmpty(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(member.string("role_title")?.takeIf(String::isNotBlank) ?: m[if (person) "work.teamHuman" else "work.teamAgent"])
            }
            if (selected) StudioIcon("sliders-horizontal", Modifier.padding(end = StudioTokens.space1).size(StudioTokens.iconSize), colors.faint)
        }
        if (!person) {
            Rule()
            Row(Modifier.padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                StudioIcon("bot", Modifier.size(StudioTokens.compactIconSize), accent)
                Text(member.string("model").orEmpty(), fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (skills.isNotEmpty() && !person) {
            Rule()
            Row(Modifier.padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                StudioIcon("graduation-cap", Modifier.size(StudioTokens.compactIconSize), colors.pink)
                Text(skills.take(3).joinToString(" · ") + if (skills.size > 3) " +${skills.size - 3}" else "",
                    fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val active = listOf("pending", "in_progress", "needs_review").filter { (counts[it] ?: 0) > 0 }
        if (active.isNotEmpty()) {
            Rule()
            Row(Modifier.padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                active.forEach { WorkPill(counts[it].toString(), listOf(workStatusColor(it))) }
            }
        }
    }
}
