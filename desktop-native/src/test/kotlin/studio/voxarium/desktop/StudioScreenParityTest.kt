@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.ui.component.Text

class StudioScreenParityTest {
    @Test
    fun compoundMetricChoiceHasSingleFocusAndSkipsDisabledOption() {
        val picks = mutableListOf<String>()
        ScreenScene(350, 230, dark = true, textSize = 18) {
            var selected by remember { mutableStateOf("Ввод") }
            Column {
                for ((label, count) in listOf("Ввод" to "22", "Недоступно" to "0", "Кэш" to "13")) {
                    StudioSelectionRow(selected == label, {
                        picks += label
                        selected = label
                    }, enabled = label != "Недоступно") {
                        Column {
                            Text(label)
                            Text(count)
                        }
                    }
                }
            }
        }.use { scene ->
            scene.key(Key.Tab)
            assertTrue(scene.focusedWithin("Ввод"))
            scene.key(Key.Tab)
            assertTrue(scene.focusedWithin("Кэш"))
            scene.key(Key.Spacebar)
            assertEquals(listOf("Кэш"), picks)
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true &&
                it.boundsInWindow.contains(scene.node("Кэш").boundsInWindow.center) })
            scene.click(scene.node("Недоступно").boundsInWindow.center)
            assertEquals(listOf("Кэш"), picks)
        }
    }

    @Test
    fun floatingChatResizeRetainsKeyboardTargetAndGeometry() {
        val m = Messages("ru")
        val initial = ChatPanelSize()
        val committed = mutableListOf<ChatPanelSize>()
        ScreenScene(600, 700, dark = false, textSize = 18) {
            ChatPanelResizeHandle(ChatPanelEdge.LEFT, initial, ChatPanelSize(560, 660), Modifier.size(36.dp),
                preview = {}, commit = { committed += it })
        }.use { scene ->
            scene.key(Key.Tab)
            assertTrue(scene.focusedWithin(m["floatingChat.resizeWidth"]))
            scene.key(Key.DirectionLeft)
            assertEquals(listOf(initial.copy(width = initial.width + 8)), committed)
        }
    }

    @Test
    fun compactRailSearchHasOneFocusableNameAndKeepsItsAction() {
        var openings = 0
        val caption = "Поиск по проекту"
        ScreenScene(340, 240, dark = false, textSize = 18) {
            Box(Modifier.width(StudioTokens.railIconWidth)) {
                RailRow("search", caption, 0f, StudioTokens.rowHeight, false, localColors.current.muted,
                    showMarker = false, search = true) { openings++ }
            }
        }.use { scene ->
            scene.key(Key.Tab)
            assertTrue(scene.focusedWithin("$caption (Ctrl+K)"))
            scene.key(Key.Enter)
            assertEquals(1, openings)
        }
    }

    @Test
    fun explorerContextUsesTheInvokedFileAndReturnsFocusFromAnEdgePopup() {
        val m = Messages("ru")
        val chosen = mutableListOf<String>()
        val actions = mutableListOf<ExplorerAction>()
        val target = "Очень длинное название выбранного файла — без потери назначения команды.txt"
        ScreenScene(440, 380, dark = true, textSize = 18) {
            Box(Modifier.fillMaxSize()) {
                ExplorerContextTarget(ExplorerTarget("original.txt", false), "original.txt",
                    true, false, {}, { chosen += "original" }, { actions += it }) { keys ->
                    WorkAction("original.txt", null, keys) {}
                }
                Box(Modifier.align(Alignment.BottomEnd).width(190.dp)) {
                    ExplorerContextTarget(ExplorerTarget(target, false), target,
                        true, false, {}, { chosen += target }, { actions += it }) { keys ->
                        WorkAction(target, null, keys.fillMaxWidth()) {}
                    }
                }
            }
        }.use { scene ->
            scene.click(scene.node(target).boundsInWindow.bottomRight - Offset(4f, 4f), secondary = true)
            assertEquals(listOf(target), chosen)
            assertTrue(scene.focusedWithin(m[ExplorerAction.NEW_FILE.label]))
            val popup = scene.nodes().single { SemanticsProperties.IsPopup in it.config }.boundsInWindow
            assertTrue(popup.left >= 0 && popup.top >= 0 && popup.right <= 440 && popup.bottom <= 380, popup.toString())
            scene.key(Key.MoveEnd)
            assertTrue(scene.focusedWithin(m[ExplorerAction.DELETE.label]))
            scene.key(Key.Escape)
            assertTrue(scene.focusedWithin(target))
            assertTrue(actions.isEmpty(), "Escape must not run the destructive action")
            scene.key(Key.F10, shift = true)
            assertTrue(scene.focusedWithin(m[ExplorerAction.NEW_FILE.label]))
            scene.key(Key.Escape)
            scene.key(Key.F2)
            assertEquals(listOf(ExplorerAction.RENAME), actions)
            assertEquals(listOf(target, target), chosen)
        }
    }

    @Test
    fun teamsFitUsesActualCardsAndLeavesSpaceForControlsAt1024() {
        val members = (0..5).map { index ->
            buildJsonObject {
                put("id", "member$index")
                put("name", "Участник $index")
                put("kind", "agent")
                put("pos_x", index * 544 - 240)
                put("pos_y", (index % 2) * 180)
                if (index > 0) put("manager_id", "member0")
            }
        }
        val selected = mutableListOf<String>()
        ScreenScene(1024, 700, dark = false, textSize = 18) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(StudioTokens.railWidth))
                OrgCanvas("main", members, emptyList(), null, Modifier.fillMaxSize(), "dots", select = { selected += it })
            }
        }.use { scene ->
            val viewport = scene.tag("teams.orgViewport").boundsInWindow
            val before = members.indices.map { scene.tag("teams.member.member$it").boundsInWindow }
            before.forEach { card ->
                assertTrue(card.width > 35 && card.height > 12, card.toString())
                assertTrue(viewport.contains(card.topLeft) && viewport.contains(card.bottomRight), "$card is outside $viewport")
            }
            assertTrue(before.first().left < before.last().left)
            assertTrue(before.first().width < 220f * .3f, "fixture must exercise fit below the old zoom floor")
            val emptyGround = Offset(viewport.center.x, viewport.top + 40f)
            scene.drag(emptyGround, Offset(72f, 18f))
            assertTrue(scene.tag("teams.member.member0").boundsInWindow.left > before.first().left + 20f)
            scene.click(scene.node(Messages("ru")["work.zoomIn"]).boundsInWindow.center)
            scene.click(scene.node(Messages("ru")["work.fitView"]).boundsInWindow.center)
            assertEquals(before, members.indices.map { scene.tag("teams.member.member$it").boundsInWindow })
            scene.click(before.last().center)
            assertEquals(listOf("member5"), selected)
        }
    }

    @Test
    fun longRussianSettingsLabelCanWrapAndStayKeyboardReachable() {
        val long = "nativeRemote.profilesTitle"
        val labels = listOf("appearance", long, "updates")
        val ru = Messages("ru")
        val picked = mutableListOf<Int>()
        for (textSize in listOf(11, 14, 18)) for (dark in listOf(false, true)) {
            ScreenScene(300, 250, dark, textSize) {
                SettingsContents(labels, 0, Modifier.width(176.dp)) { picked += it }
            }.use { scene ->
                val label = scene.node(ru[long]).boundsInWindow
                assertTrue(label.height >= textSize, "setting caption clipped at $textSize sp")
                if (textSize == 18) assertTrue(label.height > 36f, "long label should occupy a full second line")
                scene.key(Key.Tab)
                scene.key(Key.Tab)
                assertTrue(scene.focusedWithin(ru[long]))
                scene.key(Key.Enter)
                assertEquals(1, picked.last())
                scene.key(Key.Tab, shift = true)
                assertTrue(scene.focusedWithin(ru["appearance"]))
            }
        }
        assertEquals(List(6) { 1 }, picked)
    }

    @Test
    fun typedChoiceDoesNotConfuseDefaultWithAnUnsetValue() {
        val picked = mutableListOf<Int?>()
        ScreenScene(400, 280, dark = false, textSize = 18) {
            StudioChoice("Исполнитель", null, linkedMapOf(null to "Унаследовать", 0 to "По умолчанию"),
                fallback = "Нет значения", modifier = Modifier.width(210.dp), onSelect = { picked += it })
        }.use { scene ->
            scene.key(Key.Tab)
            scene.key(Key.DirectionDown)
            assertTrue(scene.focusedWithin("Унаследовать"))
            scene.key(Key.DirectionDown)
            scene.key(Key.Enter)
            assertEquals(listOf<Int?>(0), picked)
            assertTrue(scene.focusedWithin("Исполнитель"))
        }
    }
}

private class ScreenScene(width: Int, height: Int, dark: Boolean, textSize: Int, content: @Composable () -> Unit) :
    AutoCloseable {
    private var time = 1_000L
    private val scene = ImageComposeScene(width, height, density = Density(1f)) {
        StudioTheme(dark, buildJsonObject { put("text_size", textSize) }) {
            CompositionLocalProvider(localMessages provides Messages("ru"), LocalStudioReducedMotion provides true) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    init { settle() }
    private fun settle() = repeat(6) { scene.render((time + 16).also { time = it } * 1_000_000L).close() }

    fun key(key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
        settle()
    }

    fun click(at: Offset, secondary: Boolean = false) {
        val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
        scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ++time)
        scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ++time,
            buttons = PointerButtons(isPrimaryPressed = !secondary, isSecondaryPressed = secondary), button = button)
        scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ++time, buttons = PointerButtons(), button = button)
        settle()
    }

    fun wheel(at: Offset, amount: Float) {
        scene.sendPointerEvent(PointerEventType.Scroll, at, timeMillis = ++time, scrollDelta = Offset(0f, amount))
        settle()
    }

    fun drag(from: Offset, by: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = ++time,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        repeat(4) { step ->
            scene.sendPointerEvent(PointerEventType.Move, from + by * ((step + 1) / 4f), timeMillis = ++time,
                buttons = PointerButtons(isPrimaryPressed = true))
            settle()
        }
        scene.sendPointerEvent(PointerEventType.Release, from + by, timeMillis = ++time, button = PointerButton.Primary)
        settle()
    }

    fun nodes() = scene.semanticsOwners.flatMap { it.rootSemanticsNode.flatten() }
    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
    private fun nodes(name: String) = nodes().filter {
        name in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == name }
    }
    fun node(name: String) = nodes(name).firstOrNull() ?: error("No semantics for «$name»")
    fun tag(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    fun focusedWithin(name: String): Boolean {
        val matches = nodes(name).map(SemanticsNode::boundsInWindow)
        return nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }.any { target ->
            val focus = target.boundsInWindow
            matches.any { focus.contains(it.center) }
        }
    }
    override fun close() = scene.close()
}

class StudioWorkScreenParityTest {
    // Seed the view model without binding a transport; the tests below use no backend.
    @Suppress("UNCHECKED_CAST")
    private fun <T> ProjectTab.prime(name: String, value: T) {
        val field = ProjectTab::class.java.getDeclaredField("$name\$delegate")
        field.isAccessible = true
        (field.get(this) as MutableState<T>).value = value
    }

    @Test
    fun boardKeyboardRevealsDoneAndEscapeRestoresOpener() {
        val taskTitle = "BT example"
        val task = buildJsonObject {
            put("id", "abc"); put("team_id", "sample")
            put("display_id", "BT-23"); put("title", taskTitle); put("status", "done")
        }
        val item = WorkspaceItem("abc", taskTitle, status = "done", payload = task)
        val team = buildJsonObject { put("id", "sample"); put("name", "Sample") }
        lateinit var tab: ProjectTab
        ScreenScene(1024, 700, dark = false, textSize = 18) {
            val scope = rememberCoroutineScope()
            tab = remember { ProjectTab(scope, StudioApi(null), "fixture", Page("/tasks")).apply {
                prime("list", WorkspaceList(listOf(item), teams = listOf(team)))
            } }
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(StudioTokens.railWidth))
                TasksPage(tab, { next ->
                    tab.page = next
                    tab.prime("detail", if (next.query.task != null) WorkspaceDetail(item, task) else null)
                }, Modifier.fillMaxSize())
            }
        }.use { scene ->
            fun cardFocused(): Boolean {
                val bounds = scene.node(taskTitle).boundsInWindow
                return scene.nodes().any { node ->
                    node.config.getOrNull(SemanticsProperties.Focused) == true &&
                        node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button &&
                        node.boundsInWindow.contains(bounds.center)
                }
            }
            repeat(12) { if (!cardFocused()) scene.key(Key.Tab) }
            assertTrue(cardFocused(), scene.nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }.toString())
            val title = scene.node(taskTitle).boundsInWindow
            assertTrue(title.width > 20f && title.left >= 192f && title.right <= 1024f, title.toString())
            scene.key(Key.Enter)
            assertEquals("abc", tab.page.query.task)
            assertTrue(scene.nodes().any { SemanticsProperties.IsDialog in it.config })
            scene.key(Key.Escape)
            assertNull(tab.page.query.task)
            assertTrue(cardFocused())
        }
    }

    @Test
    fun chatTimestampUsesUtcForSqliteAndChatContextMenuRetainsTheRow() {
        val snippet = "Ровно этот чат — не предыдущий"
        val payload = buildJsonObject {
            put("id", "abc"); put("title", snippet)
            put("last_message_at", "2026-11-18 10:48:54.695783")
        }
        val expectedInstant = java.time.Instant.parse("2026-11-18T10:48:54.695783Z")
        assertEquals(expectedInstant, payload.conversationTimestamp("last_message_at"))
        val expectedTime = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.forLanguageTag("ru"))
            .format(expectedInstant.atZone(java.time.ZoneId.systemDefault()))
        lateinit var tab: ProjectTab
        val selected = mutableListOf<Page>()
        val m = Messages("ru")
        ScreenScene(320, 360, dark = false, textSize = 18) {
            val scope = rememberCoroutineScope()
            tab = remember { ProjectTab(scope, StudioApi(null), "fixture", Page("/chats")) }
            val popup = FloatingChatPresentation(WorkspaceList(listOf(WorkspaceItem("abc", snippet, payload = payload))),
                null, null, null, {}, {})
            ChatsPage(tab, { selected += it }, Modifier.fillMaxSize(), popup)
        }.use { scene ->
            assertTrue(scene.node(expectedTime).boundsInWindow.width > 30f)
            scene.click(scene.node(snippet).boundsInWindow.center, secondary = true)
            assertTrue(scene.focusedWithin(m["work.delete"]))
            assertTrue(selected.isEmpty())
            scene.key(Key.Escape)
            assertTrue(scene.focusedWithin(snippet))
            scene.key(Key.F10, shift = true)
            assertTrue(scene.focusedWithin(m["work.delete"]))
            scene.key(Key.Escape)
            scene.key(Key.Enter)
            assertEquals(listOf(Page("/chats", Selectors(chat = "abc"))), selected)
        }
    }

    @Test
    fun transcriptAndStatusRailHaveIndependentScroll() {
        val m = Messages("ru")
        val fixture = buildJsonObject {
            put("id", "abc"); put("team_id", "sample"); put("title", "BT-23"); put("status", "done")
            put("write_paths", kotlinx.serialization.json.JsonArray(List(100) { kotlinx.serialization.json.JsonPrimitive("path$it.kt") }))
            put("messages", kotlinx.serialization.json.JsonArray(List(80) {
                buildJsonObject { put("id", "message$it"); put("kind", "status"); put("text", "Line $it") }
            }))
        }
        ScreenScene(1000, 600, dark = true, textSize = 14) {
            val scope = rememberCoroutineScope()
            val tab = remember { ProjectTab(scope, StudioApi(null), "fixture", Page("/tasks")) }
            TaskConversationView(tab, fixture, close = {})
        }.use { scene ->
            scene.click(scene.node(m["work.sessionWritePaths"]).boundsInWindow.center)
            fun scrollAt(x: Float): Pair<Rect, Float> {
                val target = scene.nodes().first { node ->
                    val bounds = node.boundsInWindow
                    x in bounds.left..bounds.right &&
                        (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
                }
                return target.boundsInWindow to (target.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: -1f)
            }
            val beforeTranscript = scrollAt(450f)
            val beforeRail = scrollAt(850f)
            assertNotEquals(beforeTranscript.first, beforeRail.first)
            scene.wheel(beforeRail.first.center, 50f)
            assertNotEquals(beforeRail.second, scrollAt(850f).second)
            assertEquals(beforeTranscript.second, scrollAt(450f).second)
        }
    }
}
