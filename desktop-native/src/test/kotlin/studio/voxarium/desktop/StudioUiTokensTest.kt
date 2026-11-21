package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.jewel.ui.component.Text

class StudioUiTokensTest {
    @Test
    fun actionUsesRealPointerAndKeyboardInput() {
        var performed = 0
        var blocked = 0
        var busy = 0
        ControlsScene {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkAction("Run", "plus", Modifier.width(180.dp), onClick = { performed++ })
                WorkAction("Unavailable", null, enabled = false, onClick = { blocked++ })
                WorkAction("Syncing", null, busy = true, onClick = { busy++ })
            }
        }.use { ui ->
            val button = ui.node("Run", Role.Button)
            val sample = Offset(button.boundsInWindow.left + 6f, button.boundsInWindow.center.y)
            val resting = ui.pixel(sample)
            ui.move(button.boundsInWindow.center)
            val hovered = ui.pixel(sample)
            assertNotEquals(resting, hovered, "pointer hover should visibly change the real button")
            ui.press(button.boundsInWindow.center)
            assertEquals(0, performed, "press alone hasn't completed a click")
            assertNotEquals(hovered, ui.pixel(sample), "press should visibly differ from hover")
            ui.release(button.boundsInWindow.center)
            assertEquals(1, performed)

            val disabled = ui.node("Unavailable", Role.Button)
            assertTrue(SemanticsProperties.Disabled in disabled.config)
            ui.click(disabled)
            assertEquals(0, blocked)
            if (!ui.isFocused("Run")) ui.key(Key.Tab)
            ui.assertFocused("Run")
            ui.key(Key.Enter)
            assertEquals(2, performed, "Enter on the focused button must activate exactly once")
            ui.key(Key.Tab)
            ui.assertFocused("Syncing")
            ui.key(Key.Enter)
            assertEquals(1, busy, "the busy glyph does not override the caller's enabled state")
        }
    }

    @Test
    fun checkboxTogglesOnLabelClickAndSpaceButNotWhenUnavailable() {
        var checked by mutableStateOf(false)
        var toggles = 0
        var blocked = 0
        ControlsScene {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkCheckbox("Show generated files", checked) { next -> checked = next; toggles++ }
                WorkCheckbox("Disabled option", false, enabled = false) { blocked++ }
                WorkCheckbox("Read-only option", true)
                WorkAction("Next action", null, onClick = {})
            }
        }.use { ui ->
            fun selected() = ui.node("Show generated files", Role.Checkbox)
                .config.getOrNull(SemanticsProperties.ToggleableState)
            assertEquals(ToggleableState.Off, selected())
            ui.key(Key.Tab)
            ui.assertFocused("Show generated files")
            ui.key(Key.Enter)
            assertEquals(0, toggles, "a focused checkbox reserves activation for Space")
            ui.key(Key.Spacebar)
            assertEquals(1, toggles, "Space key down/up must toggle exactly once")
            assertEquals(ToggleableState.On, selected())
            // Click well to the right of the checkbox glyph, in the label.
            val bounds = ui.node("Show generated files", Role.Checkbox).boundsInWindow
            ui.click(Offset(bounds.right - 3f, bounds.center.y))
            assertEquals(2, toggles)
            assertEquals(ToggleableState.Off, selected())

            ui.key(Key.Tab)
            ui.assertFocused("Next action")
            ui.key(Key.Tab, shift = true)
            ui.assertFocused("Show generated files")
            val disabled = ui.node("Disabled option", Role.Checkbox)
            assertTrue(SemanticsProperties.Disabled in disabled.config)
            ui.click(disabled)
            val readOnly = ui.node("Read-only option", Role.Checkbox)
            assertFalse(SemanticsActions.OnClick in readOnly.config)
            assertEquals(ToggleableState.On, readOnly.config.getOrNull(SemanticsProperties.ToggleableState))
            ui.click(readOnly)
            assertEquals(0, blocked)
            assertEquals(2, toggles)
        }
    }

    @Test
    fun selectSkipsDisabledOptionsAndRestoresFocusAfterSelectionOrEscape() {
        var selected by mutableStateOf("first")
        val changes = mutableListOf<String>()
        val choices = linkedMapOf("first" to "First", "locked" to "Unavailable option", "third" to "Third")
        ControlsScene {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioSelect("Mode", selected, choices, Modifier.width(190.dp).testTag("select"),
                    disabledItems = setOf("locked")) { next -> selected = next; changes += next }
                StudioSelect("Unavailable select", "first", choices, Modifier.testTag("disabled-select"),
                    enabled = false) { changes += "unexpected: $it" }
                WorkAction("Next action", null, onClick = {})
            }
        }.use { ui ->
            ui.key(Key.Tab)
            ui.assertFocusedTag("select")
            ui.key(Key.Enter)
            assertTrue(ui.popupVisible())
            val locked = ui.node("Unavailable option")
            assertTrue(SemanticsProperties.Disabled in locked.config)
            ui.click(locked)
            assertEquals(emptyList(), changes)
            assertTrue(ui.popupVisible(), "disabled option does not dismiss the popup")

            ui.key(Key.DirectionDown)
            ui.key(Key.Enter)
            assertEquals(listOf("third"), changes, "arrow skips the disabled row; Enter commits once")
            assertFalse(ui.popupVisible())
            ui.assertFocusedTag("select")

            ui.key(Key.Spacebar)
            assertTrue(ui.popupVisible())
            ui.key(Key.DirectionUp)
            ui.key(Key.Spacebar)
            assertEquals(listOf("third", "first"), changes, "Space commits once")
            assertFalse(ui.popupVisible())
            ui.assertFocusedTag("select")

            ui.key(Key.Enter)
            ui.key(Key.Escape)
            assertFalse(ui.popupVisible())
            ui.assertFocusedTag("select")
            ui.key(Key.Tab)
            ui.assertFocused("Next action")
            ui.click(ui.tagged("disabled-select"))
            assertFalse(ui.popupVisible())
            assertEquals(listOf("third", "first"), changes)
        }
    }

    @Test
    fun genericMenuFitsAtAnEdgeAndSupportsPointerAndTabDismissal() {
        var open by mutableStateOf(false)
        var closes = 0
        var selections = 0
        val title = "Открыть выбранные документы в новой вкладке"
        ControlsScene(width = 340, height = 220) {
            val anchor = remember { FocusRequester() }
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkAction("Previous", null, onClick = {})
                    Box {
                        WorkAction("Actions", null, Modifier.focusRequester(anchor), onClick = { open = true })
                        StudioMenu(open, { open = false; closes++ }, listOf(
                            StudioMenuItem("Disabled command", enabled = false) { error("Disabled menu command ran") },
                            StudioMenuItem(title, dividerBefore = true) { selections++ },
                        ), returnFocus = anchor)
                    }
                    WorkAction("Next", null, onClick = {})
                }
            }
        }.use { ui ->
            val trigger = ui.node("Actions", Role.Button)
            ui.click(trigger)
            assertTrue(ui.popupVisible())
            val item = ui.node(title, Role.Button)
            val bounds = item.boundsInWindow
            assertTrue(bounds.bottom < trigger.boundsInWindow.top, "the menu must flip above a bottom-edge anchor")
            assertTrue(bounds.left >= 0 && bounds.right <= 340 && bounds.width > 160,
                "the long localized row must fit inside the viewport")
            ui.click(item)
            assertEquals(1, selections)
            assertEquals(1, closes)
            ui.assertFocused("Actions")

            ui.key(Key.Enter)
            ui.key(Key.Tab)
            assertFalse(ui.popupVisible())
            assertEquals(2, closes)
            ui.assertFocused("Next")

            ui.key(Key.Tab, shift = true)
            ui.key(Key.Enter)
            ui.key(Key.Tab, shift = true)
            assertFalse(ui.popupVisible())
            assertEquals(3, closes)
            assertEquals(1, selections)
            ui.assertFocused("Previous")
        }
    }

    @Test
    fun dialogIsModalAndDismissesExactlyOnceWithFocusReturn() {
        var visible by mutableStateOf(false)
        var dismissible by mutableStateOf(true)
        var closes = 0
        var confirmed = 0
        var backgroundClicks = 0
        ControlsScene(reducedMotion = false) {
            val opener = remember { FocusRequester() }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkAction("Open dialog", null, Modifier.focusRequester(opener), onClick = { visible = true })
                    WorkAction("Background action", null, onClick = { backgroundClicks++ })
                }
                if (visible) WorkDialog("Confirmation", { visible = false; closes++ }, Modifier.width(310.dp),
                    canDismiss = dismissible, returnFocus = opener) { dismiss ->
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Please confirm")
                        WorkAction("Confirm", null, onClick = { confirmed++; dismiss() })
                        WorkAction("Cancel", null, onClick = dismiss)
                    }
                }
            }
        }.use { ui ->
            val background = ui.node("Background action").boundsInWindow.center
            ui.key(Key.Tab)
            ui.assertFocused("Open dialog")
            ui.key(Key.Enter)
            ui.settle(24)
            assertTrue(ui.named("Confirmation").isNotEmpty())
            repeat(3) {
                ui.key(Key.Tab)
                ui.assertFocusInside("Confirmation")
            }
            ui.key(Key.Escape)
            ui.key(Key.Escape)
            ui.settle(24)
            assertEquals(1, closes, "repeat Escape during the exit animation must not close twice")
            assertTrue(ui.named("Confirmation").isEmpty())
            ui.assertFocused("Open dialog")

            dismissible = false
            ui.click(ui.node("Open dialog"))
            ui.settle(24)
            ui.key(Key.Escape)
            ui.click(background)
            ui.settle(24)
            assertEquals(1, closes, "blocked dismissal must not close the modal")
            assertEquals(0, backgroundClicks, "the backdrop must not pass the pointer to the page")

            dismissible = true
            ui.settle()
            ui.click(background)
            ui.settle(24)
            assertEquals(2, closes)
            assertEquals(0, backgroundClicks)
            ui.assertFocused("Open dialog")

            ui.key(Key.Enter)
            ui.settle(24)
            ui.click(ui.node("Confirm", Role.Button))
            ui.settle(24)
            assertEquals(1, confirmed)
            assertEquals(3, closes)
            ui.assertFocused("Open dialog")
        }
    }

    @Test
    fun fieldAndChipKeepTheirAccessibleRolesAndUseRealInput() {
        var value by mutableStateOf(TextFieldValue("abc", selection = TextRange(3)))
        var selected by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        ControlsScene {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioField("Project path", value, { value = it }, Modifier.width(200.dp).testTag("field"),
                    error = true, errorMessage = "Path is not a directory")
                StudioChip("Running only", selected, { selected = it; changes += it }, Modifier.testTag("chip"))
                WorkStatus("blocked", variant = WorkStatusVariant.Dot)
            }
        }.use { ui ->
            val field = ui.node("Project path")
            assertTrue(SemanticsActions.SetText in field.config, "field exposes an editable text action")
            assertEquals("Path is not a directory", field.config.getOrNull(SemanticsProperties.Error))
            ui.key(Key.Tab)
            ui.assertFocusedTag("field")
            ui.key(Key.Backspace)
            assertEquals("ab", value.text)
            ui.key(Key.Tab)
            ui.assertFocusedTag("chip")
            ui.key(Key.Spacebar)
            assertEquals(listOf(true), changes)
            ui.click(ui.tagged("chip"))
            assertEquals(listOf(true, false), changes)
            assertTrue(ui.named(Messages("ru").status("blocked")).isNotEmpty(), "dot is not color-only")
        }
    }

    @Test
    fun graphitesFocusAndOnAccentStayLegibleForBothThemesAndOverrides() {
        val custom = buildJsonObject {
            put("accent", "#e8e8e8")
            put("text_size", 18)
            put("status_palette", "accessible")
            put("custom_tokens", buildJsonObject {
                put("--vxs-bg-canvas", "#333333")
                put("--vxs-status-review", "#333333")
            })
        }
        for (dark in listOf(false, true)) for (theme in listOf(null, custom)) {
            val palette = StudioColors(dark, theme)
            assertTrue(StudioTokens.contrastRatio(palette.onAccent, palette.accent) >= 4.5f)
            assertTrue(StudioTokens.contrastRatio(palette.focusOn(palette.canvas), palette.canvas) >= 3f)
            val status = palette.statusColor("needs_review", palette.canvas)
            assertEquals(status, palette.statusColor("awaiting_approval", palette.canvas))
            assertTrue(StudioTokens.contrastRatio(status, palette.canvas) >= 3f)
            ControlsScene(dark = dark, theme = theme) {
                Box(Modifier.padding(20.dp)) {
                    WorkAction("Длинная подпись кнопки для проверки фокуса", "search", Modifier.width(260.dp),
                        onClick = {})
                }
            }.use { ui ->
                val bounds = ui.node("Длинная подпись кнопки для проверки фокуса", Role.Button).boundsInWindow
                // Straight section away from corners, text, and antialiasing of the rounded shape.
                val samples = (-3..5).map { Offset(bounds.center.x, bounds.top + it) }
                val before = ui.pixels(samples)
                ui.key(Key.Tab)
                ui.assertFocused("Длинная подпись кнопки для проверки фокуса")
                val after = ui.pixels(samples)
                assertTrue(before.zip(after).any { (old, focused) ->
                    old != focused && StudioTokens.contrastRatio(focused, palette.canvas) >= 3f
                }, "focus stroke must be drawn with readable contrast (dark=$dark custom=${theme != null})")
            }
        }
    }

    @Test
    fun renderRealControlsInLightAndDarkWhenRequested() {
        val directory = System.getenv("VOXARIUM_UI_EVIDENCE_DIR")?.takeIf(String::isNotBlank)?.let(::File)
        if (directory != null) {
            require(directory.isAbsolute) { "VOXARIUM_UI_EVIDENCE_DIR must be an absolute task-temp path" }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        }
        for (dark in listOf(false, true)) {
            val filePrefix = if (dark) "native-ru-dark" else "native-ru-light"
            ControlsScene(width = 880, height = 566, dark = dark) { ControlShowcase() }.use { ui ->
                val search = ui.node("Поиск по проекту", Role.Button).boundsInWindow.center
                ui.key(Key.Tab)
                ui.assertFocused("Обновить список документов")
                ui.move(search)
                directory?.let { ui.save(it.resolve("$filePrefix-controls.png")) }
                ui.press(search)
                directory?.let { ui.save(it.resolve("$filePrefix-pressed.png")) }
                ui.release(search)

                ui.click(ui.tagged("showcase-select"))
                assertTrue(ui.popupVisible())
                ui.key(Key.DirectionDown)
                directory?.let { ui.save(it.resolve("$filePrefix-menu.png")) }
                ui.key(Key.Escape)
                ui.click(ui.node("Показать диалог", Role.Button))
                ui.settle(24)
                assertTrue(ui.named("Подтвердите действие").isNotEmpty())
                ui.key(Key.Tab)
                ui.assertFocusInside("Подтвердите действие")
                directory?.let { ui.save(it.resolve("$filePrefix-dialog.png")) }
            }
        }
    }
}

@Composable
private fun ControlShowcase() {
    val colors = localColors.current
    var option by remember { mutableStateOf("system") }
    var checkbox by remember { mutableStateOf(true) }
    var archived by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(TextFieldValue("")) }
    var dialog by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    val choices = linkedMapOf(
        "system" to "Использовать настройки системы",
        "locked" to "Недоступный профиль резервного копирования",
        "alternate" to "Выбрать пользовательский профиль оформления",
    )
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Общие элементы интерфейса", color = colors.text, fontSize = StudioTokens.titleSize)
            Column(
                Modifier.fillMaxWidth().background(colors.cardFill, shape)
                    .border(StudioTokens.borderWidth, colors.cardBorder, shape).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkAction("Обновить список документов", "check", primary = true, onClick = {})
                    WorkAction("Удалить", "trash-2", danger = true, onClick = {})
                    WorkAction("Синхронизация", null, busy = true, onClick = {})
                    WorkAction("Импорт недоступен", null, enabled = false, onClick = {})
                    StudioIconButton("Поиск по проекту", "search", onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    WorkCheckbox("Показывать внутренние файлы управляемого проекта", checkbox) { checkbox = it }
                    WorkCheckbox("Исключить архивные записи", archived) { archived = it }
                }
                WorkCheckbox("Изменение запрещено", true, enabled = false) {}
                StudioChip("Только активные пайплайны", chip, { chip = it }, icon = "check")
            }
            Column(
                Modifier.fillMaxWidth().background(colors.cardFill, shape)
                    .border(StudioTokens.borderWidth, colors.cardBorder, shape).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StudioField("Имя профиля", title, { title = it }, Modifier.width(290.dp), error = true,
                        placeholder = "Укажите название профиля")
                    StudioSelect("Режим оформления", option, choices, Modifier.width(260.dp).testTag("showcase-select"),
                        disabledItems = setOf("locked")) { option = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkStatus("running", variant = WorkStatusVariant.Badge)
                    WorkStatus("needs_review", variant = WorkStatusVariant.Chip)
                    WorkStatus("blocked", variant = WorkStatusVariant.Chip)
                    WorkStatus("success", variant = WorkStatusVariant.Dot)
                    Text(localMessages.current.status("success"), color = colors.muted, fontSize = StudioTokens.textSmall)
                }
                WorkAction("Показать диалог", null, Modifier.focusRequester(opener), onClick = { dialog = true })
            }
        }
        if (dialog) WorkDialog("Подтвердите действие", { dialog = false }, Modifier.width(432.dp),
            returnFocus = opener) { dismiss ->
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Подтвердите перемещение документов", fontSize = StudioTokens.titleSize)
                Text("Все выбранные документы будут перемещены в другую папку управляемого проекта.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkAction("Отмена", null, onClick = dismiss)
                    WorkAction("Переместить", "check", primary = true, onClick = dismiss)
                }
            }
        }
    }
}

/** Renders/advances the actual scene, so Compose handles gestures, focus, semantics and popups. */
@OptIn(ExperimentalComposeUiApi::class)
private class ControlsScene(
    width: Int = 640,
    height: Int = 440,
    dark: Boolean = false,
    theme: JsonObject? = null,
    reducedMotion: Boolean = true,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private var time = 1_000L
    private val scene = ImageComposeScene(width, height, density = Density(1f)) {
        StudioTheme(dark, theme) {
            CompositionLocalProvider(localMessages provides Messages("ru"), LocalStudioReducedMotion provides reducedMotion) {
                Box(Modifier.fillMaxSize().background(localColors.current.canvas)) { content() }
            }
        }
    }

    init {
        settle()
    }

    fun settle(frames: Int = 4) {
        repeat(frames) { render().close() }
    }

    private fun render(): Image = scene.render((time + 16).also { time = it } * 1_000_000L)

    // The pinned Desktop Compose API has no stable constructor for synthetic key events.
    @OptIn(InternalComposeUiApi::class)
    fun key(key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
        settle()
    }

    fun move(at: Offset) = send(PointerEventType.Move, at)
    fun press(at: Offset) = send(PointerEventType.Press, at, pressed = true, changed = PointerButton.Primary)
    fun release(at: Offset) = send(PointerEventType.Release, at, changed = PointerButton.Primary)

    fun click(at: Offset) {
        move(at)
        press(at)
        release(at)
    }

    fun click(node: SemanticsNode) = click(node.boundsInWindow.center)

    private fun send(type: PointerEventType, at: Offset, pressed: Boolean = false, changed: PointerButton? = null) {
        scene.sendPointerEvent(
            eventType = type, position = at, timeMillis = ++time,
            buttons = PointerButtons(isPrimaryPressed = pressed), button = changed,
        )
        settle()
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
    private fun nodes() = scene.semanticsOwners.flatMap { it.rootSemanticsNode.flatten() }
    private fun SemanticsNode.hasName(name: String): Boolean =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(name) ||
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name }

    fun named(name: String): List<SemanticsNode> = nodes().filter { it.hasName(name) }

    fun tagged(tag: String): SemanticsNode = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    fun popupVisible(): Boolean = nodes().any { SemanticsProperties.IsPopup in it.config }

    fun node(name: String, role: Role? = null): SemanticsNode = named(name).firstOrNull {
        role == null || it.config.getOrNull(SemanticsProperties.Role) == role
    } ?: error("No semantics node for '$name' ($role). Actual names: ${nodes().map { it.config }}")

    fun assertFocused(name: String) {
        assertTrue(isFocused(name), "Expected focus on '$name', actual: ${focused().map { it.config }}")
    }

    fun isFocused(name: String): Boolean = focused().any { it.hasName(name) }

    fun assertFocusedTag(tag: String) {
        val owner = tagged(tag)
        assertTrue(focused().any { owner.boundsInWindow.contains(it.boundsInWindow.center) }, "Expected focus in '$tag'")
    }

    fun assertFocusInside(name: String) {
        val owner = node(name)
        assertTrue(focused().any { owner.boundsInWindow.contains(it.boundsInWindow.center) }, "Focus escaped '$name'")
    }

    private fun focused() = nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }

    fun pixel(at: Offset): Color = pixels(listOf(at)).single()

    fun pixels(positions: List<Offset>): List<Color> = render().use { image ->
        Bitmap.makeFromImage(image).use { bitmap ->
            positions.map { Color(bitmap.getColor(it.x.toInt(), it.y.toInt())) }
        }
    }

    fun save(destination: File) {
        render().use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { destination.writeBytes(it.bytes) }
        }
    }

    override fun close() {
        scene.close()
    }
}
