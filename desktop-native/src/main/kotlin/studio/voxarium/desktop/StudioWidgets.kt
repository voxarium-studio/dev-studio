package studio.voxarium.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import kotlin.math.roundToInt

/** §7: Draw the ring inside the outline so scroll containers and clips don't cut it off.
 * Install before an existing focus target (clickable/toggleable/focusable), not in place of one.
 */
@Composable
internal fun Modifier.studioFocusRing(
    shape: Shape,
    enabled: Boolean = true,
    background: Color = Color.Unspecified,
): Modifier {
    val colors = localColors.current
    var focused by remember { mutableStateOf(false) }
    val focus = if (background == Color.Unspecified) colors.focus else colors.focusOn(background)
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused && enabled) Modifier.border(StudioTokens.focusWidth, focus, shape) else Modifier)
}

internal fun Modifier.studioShadow(shape: Shape, style: StudioShadow): Modifier =
    dropShadow(shape, Shadow(radius = style.blur / 2, color = style.color, offset = DpOffset(0.dp, style.offsetY)))

/** Passive overlay. The full action name remains on the trigger's own semantics node. */
@Composable
internal fun StudioTooltip(label: String, modifier: Modifier = Modifier, side: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = localColors.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val interested = hovered || focused
    LaunchedEffect(interested) {
        ready = false
        dismissed = false
        if (interested) {
            delay(StudioTokens.tooltipDelayMillis)
            ready = true
        }
    }
    Box(modifier.hoverable(interactions).onFocusChanged { focused = it.hasFocus }.onPreviewKeyEvent {
        if (it.key == Key.Escape && focused && ready && !dismissed) {
            if (it.type == KeyEventType.KeyUp) dismissed = true
            true
        } else false
    }.focusGroup()) {
        content()
        if (interested && ready && !dismissed) {
            val shape = RoundedCornerShape(StudioTokens.radiusSm)
            val gap = with(LocalDensity.current) { StudioTokens.space2.roundToPx() }
            val position = remember(side, gap) {
                if (side) SideTooltipPosition(gap) else StudioMenuPosition(Alignment.CenterHorizontally, gap, null)
            }
            Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false,
                dismissOnBackPress = false, dismissOnClickOutside = false)) {
                Text(label, Modifier.widthIn(max = StudioTokens.menuMaxWidth)
                    .studioShadow(shape, colors.shadowSm).background(colors.sheetFill, shape)
                    .border(StudioTokens.borderWidth, colors.cardBorder, shape)
                    .padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1)
                    .semantics { hideFromAccessibility() },
                    color = colors.text, fontSize = StudioTokens.textSmall)
            }
        }
    }
}

private class SideTooltipPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = anchorBounds.right + gap
        val left = anchorBounds.left - gap - popupContentSize.width
        val x = if (right + popupContentSize.width <= windowSize.width) right else left
        val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

// Foundation clickable activates on Enter as well as Space; a checkbox reserves activation for Space.
internal fun Modifier.studioCheckboxKeys(): Modifier = onPreviewKeyEvent { it.key == Key.Enter || it.key == Key.NumPadEnter }

@Composable
internal fun StudioIconButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(StudioTokens.radiusMd),
    iconSize: Dp = StudioTokens.iconSize,
    tint: Color = Color.Unspecified,
    hoverFill: Color = Color.Unspecified,
    danger: Boolean = false,
) = StudioAction(label, icon, modifier, enabled, primary = false, outlined = false, iconOnly = true,
    danger = danger, busy = false, onClick = onClick, shape = shape, iconSize = iconSize, tint = tint, hoverFill = hoverFill)

@Composable
internal fun StudioAction(
    label: String,
    icon: String?,
    modifier: Modifier,
    enabled: Boolean,
    primary: Boolean,
    outlined: Boolean,
    iconOnly: Boolean,
    danger: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(StudioTokens.radiusMd),
    iconSize: Dp = StudioTokens.iconSize,
    tint: Color = Color.Unspecified,
    hoverFill: Color = Color.Unspecified,
    selected: Boolean = false,
) {
    val colors = localColors.current
    // Busy swaps the glyph only; the caller decides whether this operation may be retriggered.
    val available = enabled
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalStudioReducedMotion.current
    val face = studioColor(when {
        available && pressed -> if (primary) colors.primaryPressed else colors.active
        available && hovered && hoverFill != Color.Unspecified -> hoverFill
        available && hovered -> if (primary) colors.primaryHover else if (selected) colors.active else colors.hover
        selected -> colors.selected
        danger && !primary -> colors.dangerSoft
        else -> Color.Transparent
    })
    val restingFace = if (primary) (colors.primaryFill as? SolidColor)?.value ?: colors.primaryPressed else colors.surface
    val faceBackground = face.compositeOver(restingFace)
    val ringBackground = if (primary || face.alpha == 1f) faceBackground else Color.Unspecified
    val intent = when {
        tint != Color.Unspecified -> tint
        danger -> colors.danger
        primary -> colors.onPrimary
        else -> colors.text
    }
    val ink = StudioTokens.ensureContrast(intent, faceBackground,
        if (iconOnly) StudioTokens.indicatorContrast else StudioTokens.textContrast)
    val opacity by animateFloatAsState(if (available) 1f else StudioTokens.disabledOpacity,
        if (reduce) snap() else tween(StudioMotion.quick, easing = LinearEasing))
    val press by animateFloatAsState(if (pressed && available && !reduce) .97f else 1f,
        if (reduce) snap() else tween(if (pressed) StudioMotion.press else StudioMotion.fast, easing = StudioMotion.standard))
    val size = if (iconOnly) Modifier.size(StudioTokens.iconButtonSize) else Modifier.heightIn(min = StudioTokens.controlMinHeight)
        .padding(horizontal = StudioTokens.controlPaddingX, vertical = StudioTokens.controlPaddingY)
    Row(
        modifier.graphicsLayer { alpha = opacity; scaleX = press; scaleY = press }
            .studioFocusRing(shape, available, ringBackground)
            .clip(shape).background(if (primary) colors.primaryFill else SolidColor(Color.Transparent))
            .background(face)
            .then(if (outlined || primary) Modifier.border(StudioTokens.borderWidth, when {
                !available -> colors.subtleBorder
                danger -> StudioTokens.ensureContrast(colors.danger, faceBackground)
                else -> StudioTokens.ensureContrast(colors.controlBorder, faceBackground)
            }, shape) else Modifier)
            .hoverable(interaction, available)
            .then(if (iconOnly || selected) Modifier.semantics {
                if (iconOnly) contentDescription = label
                if (selected) this.selected = true
            } else Modifier)
            .clickable(interaction, indication = null, enabled = available, role = Role.Button) { if (available) onClick() }
            .then(size),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1, Alignment.CenterHorizontally),
    ) {
        if (busy) StudioLoadingIcon(Modifier.size(iconSize), ink)
        else icon?.let { StudioIcon(it, Modifier.size(iconSize), ink) }
        if (!iconOnly) Text(label, color = ink, fontSize = (colors.textSize - 1f).sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A single selection target for composed content (e.g. a metric caption and value).
 * Use a selectableGroup on the owning set; the slot supplies content, not another click target.
 */
@Composable
internal fun StudioSelectionRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val face = studioColor(when {
        enabled && pressed -> colors.active
        selected -> colors.selected
        enabled && hovered -> colors.hover
        else -> colors.cardFill
    })
    Row(modifier.graphicsLayer { alpha = if (enabled) 1f else StudioTokens.disabledOpacity }
        .studioFocusRing(shape, enabled, face)
        .clip(shape).background(face).hoverable(interaction, enabled)
        .selectable(selected, interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
        .heightIn(min = StudioTokens.controlMinHeight)
        .padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space2),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically,
        content = content)
}

/** An interactive filter is a toggle; status and telemetry pills are not. */
@Composable
internal fun StudioChip(
    label: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: String? = null,
) {
    val colors = localColors.current
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = studioColor(when {
        enabled && pressed -> colors.active
        selected -> if (enabled && hover) colors.active else colors.selected
        enabled && hover -> colors.hover
        else -> colors.inset
    })
    val ink = if (enabled) (if (selected) colors.onAccentContainer else colors.text) else colors.faint
    Row(modifier.studioFocusRing(CircleShape, enabled, fill).clip(CircleShape).background(fill)
        .border(StudioTokens.borderWidth, if (enabled) colors.controlBorder else colors.subtleBorder, CircleShape)
        .hoverable(interaction, enabled)
        .studioCheckboxKeys()
        .toggleable(selected, interaction, indication = null, enabled = enabled, role = Role.Checkbox) {
            if (enabled) onSelectedChange(it)
        }.heightIn(min = StudioTokens.controlMinHeight).padding(horizontal = StudioTokens.space2),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
        if (selected) StudioIcon("check", Modifier.size(StudioTokens.iconSize), ink)
        else icon?.let { StudioIcon(it, Modifier.size(StudioTokens.iconSize), ink) }
        Text(label, color = ink, fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A Jewel field using the Studio theme, with a stable accessible label independent of its value. */
@Composable
internal fun StudioField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    error: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: String? = null,
    errorMessage: String? = null,
) {
    val colors = localColors.current
    val placeholderColor = StudioTokens.ensureContrast(colors.faint, colors.inset, StudioTokens.textContrast)
    TextField(value = value, onValueChange = { if (enabled && !readOnly) onValueChange(it) },
        modifier = modifier.semantics {
            contentDescription = label
            if (error && errorMessage != null) error(errorMessage)
        },
        enabled = enabled, readOnly = readOnly, outline = if (error) Outline.Error else Outline.None,
        visualTransformation = visualTransformation,
        placeholder = placeholder?.let { text -> { Text(text, color = placeholderColor) } })
}

internal data class StudioMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val icon: String? = null,
    val checked: Boolean? = null,
    val dividerBefore: Boolean = false,
    val shortcut: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** Context actions belong to the row which was invoked, not the previous primary selection.
 * Install the supplied modifier on that row's single existing focus target.
 */
@Composable
internal fun StudioContextTarget(
    title: String,
    items: List<StudioMenuItem>,
    modifier: Modifier = Modifier,
    minimumWidth: Dp = StudioTokens.menuMinWidth,
    onOpen: () -> Unit = {},
    onTargetKey: (KeyEvent) -> Boolean = { false },
    content: @Composable (Modifier) -> Unit,
) {
    val focus = remember { FocusRequester() }
    var anchor by remember { mutableStateOf<IntOffset?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val enabled = items.isNotEmpty()
    fun show(position: IntOffset?) {
        focus.requestFocus()
        onOpen()
        anchor = position
        menuOpen = true
    }
    val currentShow by rememberUpdatedState<(IntOffset?) -> Unit>(::show)
    LaunchedEffect(enabled) { if (!enabled) menuOpen = false }
    Box(modifier.then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!event.buttons.isSecondaryPressed) continue
                val press = event.changes.firstOrNull { it.changedToDown() } ?: continue
                press.consume()
                currentShow(IntOffset(press.position.x.roundToInt(), press.position.y.roundToInt()))
            }
        }
    })) {
        content(Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            if (enabled && (event.key == Key.Menu || event.isShiftPressed && event.key == Key.F10)) {
                if (event.type == KeyEventType.KeyUp) show(null)
                true
            } else onTargetKey(event)
        })
        StudioMenu(enabled && menuOpen, { menuOpen = false }, items, minimumWidth = minimumWidth,
            returnFocus = focus, title = title, anchorOffset = anchor, maximumHeight = StudioTokens.contextMenuMaxHeight)
    }
}

/** §7: The menu grows from the anchor, flips above it, then clamps to the window.
 * A focusable Compose layer keeps keyboard input within the top popup, without taking focus away
 * from the anchor in its own layer. Put the menu next to the trigger in the same Box.
 */
@Composable
internal fun StudioMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<StudioMenuItem>,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
    minimumWidth: Dp = StudioTokens.menuMinWidth,
    initialItem: Int? = null,
    returnFocus: FocusRequester? = null,
    title: String? = null,
    anchorOffset: IntOffset? = null,
    maximumHeight: Dp = StudioTokens.menuMaxHeight,
) {
    if (!expanded || items.isEmpty()) return
    val colors = localColors.current
    val parentFocus = LocalFocusManager.current
    val enabledItems = items.indices.filter { items[it].enabled }
    val requests = remember(items.size) { List(items.size) { FocusRequester() } }
    val panelFocus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var closed by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closed) {
            closed = true
            onDismissRequest()
            returnFocus?.requestFocus(FocusDirection.Enter)
        }
    }
    val gap = with(LocalDensity.current) { StudioTokens.space1.roundToPx() }
    val position = remember(alignment, gap, anchorOffset) { StudioMenuPosition(alignment, gap, anchorOffset) }
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    Popup(popupPositionProvider = position, onDismissRequest = close,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        onPreviewKeyEvent = { event ->
            when (event.key) {
                Key.Escape -> { if (event.type == KeyEventType.KeyUp) close(); true }
                Key.Tab -> {
                    if (event.type == KeyEventType.KeyUp && !closed) {
                        close()
                        parentFocus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    }
                    true
                }
                Key.DirectionUp, Key.DirectionDown, Key.MoveHome, Key.MoveEnd -> {
                    if (event.type == KeyEventType.KeyDown && enabledItems.isNotEmpty()) {
                        val positionInMenu = enabledItems.indexOf(focusedIndex)
                        val index = when (event.key) {
                            Key.MoveHome -> enabledItems.first()
                            Key.MoveEnd -> enabledItems.last()
                            Key.DirectionUp -> enabledItems[if (positionInMenu <= 0) enabledItems.lastIndex else positionInMenu - 1]
                            else -> enabledItems[(positionInMenu + 1) % enabledItems.size]
                        }
                        requests[index].requestFocus(FocusDirection.Enter)
                    }
                    true
                }
                else -> closed
            }
        },
    ) {
        Column(modifier.widthIn(min = minimumWidth, max = maxOf(minimumWidth, StudioTokens.menuMaxWidth)).width(IntrinsicSize.Max)
            .heightIn(max = maximumHeight).studioShadow(shape, colors.shadowMd)
            .clip(shape).background(colors.sheetFill).border(StudioTokens.borderWidth, colors.cardBorder, shape)
            .then(if (enabledItems.isEmpty()) Modifier.focusRequester(panelFocus).focusable() else Modifier)
            .verticalScroll(rememberScrollState()).padding(StudioTokens.space1)) {
            if (title != null) {
                Text(title, Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1)
                    .semantics { contentDescription = title },
                    color = colors.muted, fontSize = StudioTokens.textSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.fillMaxWidth().padding(bottom = StudioTokens.space1)
                    .height(StudioTokens.borderWidth).background(colors.subtleBorder))
            }
            val showMarks = items.any { it.checked != null || it.icon != null }
            items.forEachIndexed { index, item ->
                if (item.dividerBefore && index > 0) {
                    Spacer(Modifier.fillMaxWidth().padding(vertical = StudioTokens.space1)
                        .height(StudioTokens.borderWidth).background(colors.subtleBorder))
                }
                StudioMenuRow(item, showMarks, Modifier.focusRequester(requests[index]).onFocusChanged {
                    if (it.isFocused) focusedIndex = index
                }) {
                    if (item.enabled && !closed) {
                        close()
                        item.onClick()
                    }
                }
            }
        }
        LaunchedEffect(requests, enabledItems) {
            if (focusedIndex !in enabledItems) {
                val first = initialItem?.takeIf { it in enabledItems } ?: enabledItems.firstOrNull()
                (first?.let { requests[it] } ?: panelFocus).requestFocus(FocusDirection.Enter)
            }
        }
    }
}

private class StudioMenuPosition(private val alignment: Alignment.Horizontal, private val gap: Int,
    private val anchorOffset: IntOffset?,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + (anchorOffset?.x ?: alignment.align(popupContentSize.width, anchorBounds.width, layoutDirection))
        val bottom = anchorOffset?.let { anchorBounds.top + it.y } ?: anchorBounds.bottom
        val top = anchorOffset?.let { bottom } ?: anchorBounds.top
        val below = windowSize.height - bottom
        val y = if (popupContentSize.height + gap <= below || below >= top) bottom + gap
            else top - popupContentSize.height - gap
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable
private fun StudioMenuRow(item: StudioMenuItem, showMarks: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = localColors.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val fill = studioColor(when {
        item.enabled && pressed -> colors.active
        item.enabled && hovered -> colors.hover
        item.checked == true -> colors.selected
        else -> colors.sheetFill
    })
    val ink = when {
        !item.enabled -> colors.faint
        item.danger -> StudioTokens.ensureContrast(colors.danger, fill, StudioTokens.textContrast)
        else -> colors.text
    }
    val shape = RoundedCornerShape(StudioTokens.radiusSm)
    Row(modifier.fillMaxWidth().studioFocusRing(shape, item.enabled, fill).clip(shape).background(fill)
        .hoverable(interactions, item.enabled).semantics { item.checked?.let { selected = it } }
        .clickable(interactions, indication = null, enabled = item.enabled, role = if (item.checked == null) Role.Button else Role.RadioButton,
            onClick = onClick)
        .heightIn(min = StudioTokens.controlMinHeight).padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
        if (showMarks) Box(Modifier.size(StudioTokens.iconSize), contentAlignment = Alignment.Center) {
            val glyph = if (item.checked == true) "check" else item.icon
            glyph?.let { StudioIcon(it, Modifier.fillMaxSize(), ink) }
        }
        Text(item.label, Modifier.weight(1f, fill = item.shortcut != null), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.shortcut?.let {
            Text(it, color = if (item.enabled) colors.muted else colors.faint,
                fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall, maxLines = 1, softWrap = false)
        }
    }
}

/** The public select offers stable keys; rendered menu labels are already localized by the caller. */
@Composable
internal fun StudioSelect(
    label: String,
    selected: String,
    items: Map<String, String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledItems: Set<String> = emptySet(),
    wrapSelectedValue: Boolean = false,
    onSelect: (String) -> Unit,
) = StudioChoice(label, selected, items, selected, modifier, enabled, disabledItems,
    wrapSelectedValue = wrapSelectedValue, onSelect = onSelect)

/** Typed adapter for enums, IDs and inherit/default sentinels; no string conversion of values. */
@Composable
internal fun <T> StudioChoice(
    label: String,
    selected: T,
    items: Map<T, String>,
    fallback: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledItems: Set<T> = emptySet(),
    visibleLabel: String? = null,
    icons: Map<T, String> = emptyMap(),
    wrapSelectedValue: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val colors = localColors.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val available = enabled && items.keys.any { it !in disabledItems }
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(available) { if (!available) expanded = false }
    val fill = studioColor(when {
        available && (pressed || expanded) -> colors.selected
        available && hovered -> colors.hover
        else -> colors.inset
    })
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val shownValue = items[selected] ?: fallback
    val ink = if (available) colors.text else colors.faint
    Box(modifier) {
        Row(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }
            .studioFocusRing(shape, available, fill).clip(shape).background(fill)
            .border(StudioTokens.borderWidth, if (available) colors.controlBorder else colors.subtleBorder, shape)
            .hoverable(interactions, available).semantics {
                contentDescription = label
                stateDescription = shownValue
                if (available) {
                    if (expanded) collapse { expanded = false; true }
                    else expand { expanded = true; true }
                }
            }
            .onPreviewKeyEvent { event ->
                if (available && (event.key == Key.DirectionDown || event.key == Key.DirectionUp)) {
                    if (event.type == KeyEventType.KeyUp) expanded = true
                    true
                } else false
            }
            .clickable(interactions, indication = null, enabled = available, role = Role.DropdownList) {
                if (available) expanded = !expanded
            }.heightIn(min = StudioTokens.controlMinHeight)
            .padding(horizontal = StudioTokens.space2, vertical = StudioTokens.controlPaddingY),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
            Text(visibleLabel ?: shownValue, Modifier.weight(1f), color = ink,
                maxLines = if (wrapSelectedValue) Int.MAX_VALUE else 1,
                overflow = if (wrapSelectedValue) TextOverflow.Clip else TextOverflow.Ellipsis)
            StudioIcon(if (expanded) "chevron-up" else "chevron-down", Modifier.size(StudioTokens.iconSize), ink)
        }
        StudioMenu(expanded && available, { expanded = false }, items.map { (value, title) ->
            StudioMenuItem(title, enabled = value !in disabledItems, checked = value == selected, icon = icons[value]) { onSelect(value) }
        }, minimumWidth = with(LocalDensity.current) { anchorWidth.toDp() }, initialItem = items.keys.indexOf(selected))
    }
}
