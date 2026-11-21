package studio.voxarium.desktop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.SliderColors
import org.jetbrains.jewel.ui.component.styling.SliderMetrics
import org.jetbrains.jewel.ui.component.styling.SliderStyle
import androidx.compose.ui.unit.DpSize

// SettingsPage.vue: max-w-2xl, gap-10, w-44 and @4xl on the pane's inner container.
private val settingsCardsMax = 672.dp
private val contentsGap = 40.dp
private val contentsWidth = 176.dp
private val settingsWideContent = 896.dp

@Composable
internal fun Settings(workspace: Workspace, catalogProject: ProjectApi?, anchor: String?, openWeb: (String) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val theme = workspace.preferences.obj("theme")
    val enabled = !workspace.preferenceBusy
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val arrival = rememberSettingsArrival()
    val reducedMotion = LocalStudioReducedMotion.current
    val sections = listOf("startup.title", "language", "appearance", "explorer", "agentSettings.backends", "agentSettings.orchestrator",
        "execution", "nativeRemote.profilesTitle", "nativeRemote.ai.devicesTitle", "updates")
    LaunchedEffect(anchor) {
        val index = sections.indexOf(anchor)
        if (index >= 0) state.scrollToItem(index + 1)
    }
    val fontChoices = listOf(
        "cascadia" to "Cascadia Code", "jetbrains" to "JetBrains Mono", "fira" to "Fira Code", "system" to m["systemMono"],
    )
    fun setting(key: String, value: String) {
        workspace.applyPreference(buildJsonObject { putJsonObject("theme") { put(key, value) } })
    }
    fun number(key: String, value: Int?) {
        workspace.applyPreference(buildJsonObject {
            putJsonObject("theme") { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
        })
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showContents = maxWidth - StudioTokens.panePad * 2 >= settingsWideContent
        val tailPadding = if (showContents) contentsGap + contentsWidth else 0.dp
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(StudioTokens.radiusMd)).background(colors.surface)
                    .padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2)) {
                    Text(m["settings"], fontSize = StudioTokens.titleSize, lineHeight = StudioTokens.titleSize * StudioTokens.lineHeightMultiplier, fontWeight = FontWeight.Medium)
                    Text(m["settingsScope"], color = colors.faint, fontSize = StudioTokens.textSmall, lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
                }
            }
            item("startup") { Box(Modifier.settingsArrival(arrival, 0)) { StartupSettings(m, tailPadding) } }
            item("language") {
                SettingsSection("language", tailPadding, Modifier.settingsArrival(arrival, 1)) {
                    SettingsRow(m["interfaceLanguage"]) {
                        SettingChoices(
                            workspace.preferences.string("language") ?: m.language,
                            listOf("en" to "English", "ru" to "Русский"),
                            enabled,
                        ) { workspace.applyPreference(jsonFields("language" to it)) }
                        SettingsHelp(m["languageHint"])
                    }
                    SettingsRow(m["respondLanguage"]) {
                        val saved = workspace.preferences.string("respond_language") ?: "English"
                        var draft by remember(saved) { mutableStateOf(TextFieldValue(saved)) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            StudioField(m["respondLanguage"], draft, { draft = it }, enabled = enabled,
                                modifier = Modifier.width(216.dp))
                            WorkAction(m["saveDocument"], null, primary = true, enabled =
                                enabled && draft.text.isNotBlank() && draft.text.trim() != saved) {
                                workspace.applyPreference(jsonFields("respond_language" to draft.text.trim()))
                            }
                        }
                        SettingsHelp(m["respondLanguageHint"])
                    }
                }
            }
            item("appearance") {
                SettingsSection("appearance", tailPadding, Modifier.settingsArrival(arrival, 2)) {
                    SettingsRow(m["appearanceMode"]) {
                        SettingChoices(theme?.string("mode") ?: "dark",
                            listOf("dark", "light", "system").map { it to m[it] }, enabled) { setting("mode", it) }
                    }
                    SettingsRow(m["appearanceTextSize"]) {
                        val saved = theme?.string("text_size")?.toIntOrNull()
                        SettingSlider(m["appearanceTextSize"], saved ?: 14, 11..18, " px", enabled) { number("text_size", it) }
                        SettingsHelp(m[if (saved == null) "textSizeDefaultHint" else "textSizePickedHint"])
                        if (saved != null) WorkAction(m["appearanceReset"], null, enabled = enabled, outlined = false) {
                            number("text_size", null)
                        }
                    }
                    SettingsRow(m["appearanceFonts"]) {
                        SettingsHelp(m["appearanceCodeFont"])
                        val font = theme?.string("mono_font") ?: "jetbrains"
                        SettingsFontSelect(font, fontChoices.toMap(), enabled) {
                            setting("mono_font", it)
                        }
                    }
                    SettingsRow(m["appearanceSidebar"]) {
                        SettingChoices(theme?.string("sidebar") ?: "icons", listOf(
                            "labels" to m["sidebarLabels"], "icons" to m["sidebarIcons"], "auto" to m["sidebarAuto"],
                        ), enabled) { setting("sidebar", it) }
                        SettingsHelp(m["sidebarHint"])
                    }
                    SettingsRow(m["appearanceCanvas"]) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                                SettingsHelp(m["appearanceGrid"])
                                SettingChoices(theme?.string("canvas_grid") ?: "dots", listOf(
                                    "dots" to m["gridDots"], "lines" to m["gridLines"], "none" to m["gridNone"],
                                ), enabled) { setting("canvas_grid", it) }
                            }
                            Column(Modifier.width(185.dp), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                                SettingsHelp(m["appearanceNodeTint"])
                                SettingSlider(m["appearanceNodeTint"], theme?.string("node_tint")?.toIntOrNull() ?: 16,
                                    0..60, "%", enabled) {
                                    number("node_tint", it)
                                }
                            }
                        }
                    }
                    SettingsRow(m["appearanceStatus"]) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            SettingChoices(theme?.string("status_palette") ?: "default", listOf(
                                "default" to m["paletteDefault"], "accessible" to m["paletteAccessible"],
                            ), enabled) { setting("status_palette", it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                                listOf("todo", "pending", "in_progress", "needs_review", "done", "blocked", "cancelled").forEach { status ->
                                    WorkStatus(status, WorkStatusVariant.Dot, background = colors.surface)
                                }
                            }
                        }
                        SettingsHelp(m["appearanceStatusHint"])
                    }
                    ThemeSettings(workspace, theme, enabled)
                }
            }
            item("explorer") {
                SettingsSection("explorer", tailPadding, Modifier.settingsArrival(arrival, 3)) {
                    WorkCheckbox(m["showDataDirectory"], workspace.preferences.flag("show_data_dir"), enabled) {
                        workspace.applyPreference(buildJsonObject { put("show_data_dir", it) })
                    }
                    SettingsHelp(m["showDataDirectoryHint"], Modifier.padding(start = StudioTokens.checkboxSize + StudioTokens.space2))
                }
            }
            item("api-backends") { Box(Modifier.settingsArrival(arrival, 4)) { ApiBackendsSettings(workspace, tailPadding) } }
            item("orchestrator") {
                Box(Modifier.settingsArrival(arrival, 5)) { OrchestratorSettings(workspace, tailPadding, catalogProject) }
            }
            item("execution") {
                SettingsSection("execution", tailPadding, Modifier.settingsArrival(arrival, 6)) {
                    WorkCheckbox(m["reuseNodeCache"], workspace.preferences.flag("node_cache_enabled"), enabled) {
                        workspace.applyPreference(buildJsonObject { put("node_cache_enabled", it) })
                    }
                    SettingsHelp(m["reuseCacheHint"], Modifier.padding(start = StudioTokens.checkboxSize + StudioTokens.space2))
                }
            }
            item("remote-servers") {
                Box(Modifier.settingsArrival(arrival, 7)) {
                    RemoteProfilesSettings(workspace.api, m, tailPadding) { openWeb("/settings#settings-remote-servers") }
                }
            }
            item("remote-ai") {
                Box(Modifier.settingsArrival(arrival, 8)) {
                    RemoteAiDeviceSettings(workspace.api, m, tailPadding) { openWeb("/settings#settings-remote-ai") }
                }
            }
            item("updates") { NativeUpdateSettings(workspace, tailPadding) }
            item {
                Row(Modifier.padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space5)) {
                    LibraryButton("external-link", m["openWeb"]) { openWeb("/settings") }
                }
            }
        }
        if (showContents) {
            val current = (state.firstVisibleItemIndex - 1).coerceIn(sections.indices)
            val active = if (!state.canScrollForward && state.canScrollBackward) sections.lastIndex else current
            val cardsWidth = (maxWidth - StudioTokens.panePad * 2 - tailPadding).coerceAtMost(settingsCardsMax)
            val visibleHeaderHeight by remember(state) {
                derivedStateOf {
                    state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                        ?.let { (it.offset + it.size).coerceAtLeast(0) } ?: 0
                }
            }
            val stickyTop = StudioTokens.panePad + with(LocalDensity.current) { visibleHeaderHeight.toDp() }
            SettingsContents(sections, active, Modifier.align(Alignment.TopStart)
                .padding(start = StudioTokens.panePad + contentsGap + cardsWidth, top = stickyTop).width(contentsWidth)) { index ->
                scope.launch {
                    if (reducedMotion) state.scrollToItem(index + 1)
                    else state.animateScrollToItem(index + 1)
                }
            }
        }
    }
}

@Composable
internal fun SettingsContents(sections: List<String>, active: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    Column(modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
        Text(m["settingsContents"].uppercase(), color = colors.faint, fontSize = StudioTokens.textSmall,
            modifier = Modifier.padding(bottom = StudioTokens.space2))
        sections.forEachIndexed { index, section ->
            val selected = active == index
            StudioSelectionRow(selected, { onSelect(index) }, Modifier.fillMaxWidth(), role = Role.Button) {
                Text(m[section], color = if (selected) colors.text else colors.muted)
            }
        }
    }
}

@Composable
internal fun SettingsHelp(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = localColors.current.faint, fontSize = StudioTokens.textSmall,
        lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
}

@Composable
internal fun SettingsFontSelect(font: String, choices: Map<String, String>, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = localColors.current
    val measure = rememberTextMeasurer()
    val textStyle = StudioTypography.style(colors)
    val widest = choices.values.maxOfOrNull { measure.measure(it, textStyle, maxLines = 1).size.width } ?: 0
    // StudioSelect uses two side insets, one gap and one chevron; reserve measured text width at 11–18 sp.
    val selectWidth = with(LocalDensity.current) { widest.toDp() } + StudioTokens.iconSize + StudioTokens.space2 * 3 +
        StudioTokens.borderWidth * 2
    StudioSelect(localMessages.current["appearanceCodeFont"], font, choices, Modifier.width(selectWidth), enabled,
        onSelect = onChange)
}

@Composable
internal fun SettingsSection(label: String, tailPadding: Dp, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusLg)
    Column(modifier.fillMaxWidth().padding(start = StudioTokens.panePad, end = StudioTokens.panePad + tailPadding,
        top = StudioTokens.panePad)) {
        Text(localMessages.current[label], fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = StudioTokens.space2))
        Column(Modifier.widthIn(max = settingsCardsMax).fillMaxWidth().studioShadow(shape, colors.shadowSm)
            .background(colors.surface, shape).border(StudioTokens.borderWidth, colors.subtleBorder, shape)
            .padding(StudioTokens.space4), verticalArrangement = Arrangement.spacedBy(StudioTokens.space4), content = content)
    }
}

@Composable
internal fun SettingsRow(label: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = localColors.current
    // Reflow without remounting controls (and losing an unsaved draft) as the pane resizes.
    Layout({
        Text(label, color = colors.muted)
        Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2), content = content)
    }, Modifier.fillMaxWidth()) { children, constraints ->
        val width = constraints.maxWidth
        // Start with Vue's label column, but allow the longest word at larger text sizes.
        val labelFloor = 112.dp.roundToPx()
        val labelLimit = 176.dp.roundToPx()
        val preferredLabel = children[0].minIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
            .coerceIn(labelFloor, labelLimit)
        val stacked = width < 400.dp.roundToPx() + (preferredLabel - labelFloor)
        val labelWidth = if (stacked) width else preferredLabel
        val labelTop = if (stacked) 0 else StudioTokens.space1.roundToPx()
        val heading = children[0].measure(constraints.copy(minWidth = 0, maxWidth = labelWidth, minHeight = 0))
        val fieldLeft = if (stacked) 0 else labelWidth + StudioTokens.space3.roundToPx()
        val fieldTop = if (stacked) heading.height + StudioTokens.space1.roundToPx() else 0
        val pane = children[1].measure(constraints.copy(minWidth = 0, maxWidth = width - fieldLeft, minHeight = 0))
        val height = maxOf(heading.height + labelTop, pane.height + fieldTop)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            heading.placeRelative(0, labelTop)
            pane.placeRelative(fieldLeft, fieldTop)
        }
    }
}

@Composable
internal fun SettingChoices(value: String, choices: List<Pair<String, String>>, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val reducedMotion = LocalStudioReducedMotion.current
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    Box(Modifier.clip(shape).background(colors.inset).border(StudioTokens.borderWidth,
        if (enabled) colors.controlBorder else colors.subtleBorder, shape)) {
        val current = bounds[value]
        if (current != null) {
            val indicator = animateRectAsState(current,
                if (reducedMotion) snap() else tween(StudioMotion.normal, easing = StudioMotion.spring))
            Canvas(Modifier.matchParentSize()) {
                val rect = indicator.value
                drawRoundRect(colors.selected, rect.topLeft, rect.size, CornerRadius(StudioTokens.radiusMd.toPx()))
            }
        }
        FlowRow(Modifier.padding(StudioTokens.spaceHair).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
            choices.forEach { (key, label) ->
                val selected = value == key
                val interaction = remember(key) { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                // Keep the moving indicator separate from the choice hit targets.
                val face = studioColor(when {
                    enabled && pressed -> colors.active
                    enabled && hovered && !selected -> colors.hover
                    selected && current == null -> colors.selected
                    else -> Color.Transparent
                })
                Box(Modifier.onGloballyPositioned { bounds[key] = Rect(it.positionInParent(), it.size.toSize()) }
                        .studioFocusRing(shape, enabled, if (selected) colors.selected else colors.inset)
                        .clip(shape).background(face).hoverable(interaction, enabled)
                        .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.RadioButton,
                            onClick = { if (!selected) onChange(key) })
                        .heightIn(min = StudioTokens.controlMinHeight)
                        .padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) colors.text else colors.muted)
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Int, range: IntRange, units: String, enabled: Boolean, onChange: (Int) -> Unit) {
    val colors = localColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = LocalStudioReducedMotion.current
    val thumb by animateDpAsState(when {
        reducedMotion || !enabled -> StudioTokens.iconSize
        pressed -> StudioTokens.iconSize + StudioTokens.spaceHair
        hovered || focused -> StudioTokens.iconSize + StudioTokens.space1
        else -> StudioTokens.iconSize
    }, if (reducedMotion) snap() else tween(StudioMotion.normal, easing = StudioMotion.spring), label = "Slider thumb")
    val fill = StudioTokens.ensureContrast(colors.accent, colors.surface)
    val style = remember(colors, thumb, fill) {
        SliderStyle(
            SliderColors(
                track = colors.controlBorder, trackFilled = fill, trackDisabled = colors.border,
                trackFilledDisabled = colors.faint, stepMarker = Color.Transparent,
                thumbFill = fill, thumbFillDisabled = colors.faint, thumbFillFocused = fill,
                thumbFillPressed = fill, thumbFillHovered = fill,
                thumbBorder = Color.Transparent, thumbBorderFocused = Color.Transparent, thumbBorderDisabled = Color.Transparent,
                thumbBorderPressed = Color.Transparent, thumbBorderHovered = Color.Transparent,
            ),
            SliderMetrics(StudioTokens.space1, DpSize(thumb, thumb), Dp.Hairline, Dp.Hairline, Dp.Hairline, Dp.Hairline),
            CircleShape,
        )
    }
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(Modifier.heightIn(min = StudioTokens.controlMinHeight), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3),
        verticalAlignment = Alignment.CenterVertically) {
        Slider(draft, { draft = it }, Modifier.widthIn(max = 224.dp).weight(1f, fill = false)
            .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), enabled, colors.surface)
            .semantics { contentDescription = label }, enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.count() - 2,
            style = style, interactionSource = interaction,
            onValueChangeFinished = {
                val changed = kotlin.math.round(draft).toInt()
                if (changed != value) onChange(changed)
            })
        Text("${kotlin.math.round(draft).toInt()}$units", fontSize = StudioTokens.textSmall)
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun StudioInfo(key: String, workspace: Workspace, onClose: () -> Unit) {
    val m = localMessages.current
    val colors = localColors.current
    val title = m[key]
    val changelog = key == "changelog"
    val state = rememberDialogState(width = if (changelog) 720.dp else 416.dp,
        height = if (changelog) 580.dp else 330.dp, position = WindowPosition.Aligned(Alignment.Center))
    DialogWindow(onCloseRequest = onClose, title = title, state = state, undecorated = true, resizable = changelog) {
        Column(Modifier.fillMaxSize().background(colors.elevated).border(StudioTokens.borderWidth, colors.border)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                StudioIcon(if (changelog) "scroll-text" else "info", Modifier.size(StudioTokens.iconSize), colors.accent)
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                ShellIconButton("x", m["close"], Modifier, onClick = onClose)
            }
            Rule()
            if (changelog) {
                var markdown by remember { mutableStateOf<String?>(null) }
                var problem by remember { mutableStateOf<Problem?>(null) }
                LaunchedEffect(workspace) {
                    try {
                        markdown = workspace.changelog()
                    } catch (error: Exception) {
                        problem = error.problem()
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioTokens.space5)) {
                    val contents = markdown
                    val failure = problem
                    when {
                        contents != null -> ProvideMarkdownStyling {
                            Markdown(contents, selectable = true, onUrlClick = { link ->
                                if (link.startsWith("https://") || link.startsWith("http://")) openBrowser(URI.create(link), workspace)
                            })
                        }
                        failure != null -> ProblemText(failure)
                        else -> Muted(m["loading"])
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(StudioTokens.space5), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Link("Voxarium Studio", onClick = { openBrowser(URI.create("https://voxarium.studio/studio/"), workspace) })
                        Muted("Open Source | MIT Licence | Free For All")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Muted("AUTHORS")
                        Link("Voxarium", onClick = { openBrowser(URI.create("https://voxarium.studio/"), workspace) })
                        Text("CEO | Arseniy Zvezda", color = colors.secondary)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Muted("CONTRIBUTORS")
                        Text("devilast | Igor Nazarov", color = colors.secondary)
                    }
                }
            }
        }
    }
}
