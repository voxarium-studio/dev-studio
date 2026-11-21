package studio.voxarium.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun WorkHeader(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(localColors.current.headerFill, RoundedCornerShape(StudioTokens.radiusMd))
            .padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun WorkAction(
    label: String,
    icon: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    outlined: Boolean = true,
    iconOnly: Boolean = false,
    danger: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit,
) = StudioAction(label, icon, modifier, enabled, primary, outlined, iconOnly, danger, busy, onClick)

@Composable
internal fun WorkEmpty(title: String, hint: String? = null, icon: String? = null, modifier: Modifier = Modifier) {
    val colors = localColors.current
    Box(modifier.fillMaxSize().padding(StudioTokens.space5), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = StudioTokens.emptyStateMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space2),
        ) {
            icon?.let { StudioIcon(it, Modifier.size(StudioTokens.largeIconSize), colors.faint) }
            Text(title, color = colors.text, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            hint?.let { Text(it, color = colors.muted, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
internal fun WorkExplanation(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = localColors.current.muted, fontSize = StudioTokens.textSmall,
        lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
}

@Composable
internal fun WorkErrors(tab: ProjectTab, includeDetail: Boolean = false) {
    for (error in listOfNotNull(tab.actionError, tab.listError, tab.detailError.takeIf { includeDetail }).distinct()) {
        WorkProblemBanner(error)
    }
}

@Composable
internal fun WorkProblemBanner(problem: Problem, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(localColors.current.dangerSurface)
        .padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space2)) { ProblemText(problem) }
}

@Composable
internal fun WorkCheckbox(label: String, checked: Boolean, enabled: Boolean = true, change: ((Boolean) -> Unit)? = null) =
    WorkCheckbox(label, checked, Modifier, enabled, change)

/** §7: The whole label is one target; checkbox keyboard activation is reserved for Space. */
@Composable
internal fun WorkCheckbox(label: String, checked: Boolean, modifier: Modifier, enabled: Boolean = true, change: ((Boolean) -> Unit)? = null) {
    val theme = localColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val actionable = change != null && enabled
    val reduce = LocalStudioReducedMotion.current
    val edge = studioColor(when {
        checked -> StudioTokens.ensureContrast(theme.accent, theme.surface)
        !enabled -> theme.border
        hovered && actionable -> theme.focusOn(theme.inset)
        else -> theme.controlBorder
    })
    val fill = studioColor(if (checked) theme.accent else theme.inset)
    val scale by animateFloatAsState(if (pressed && actionable && !reduce) .9f else 1f,
        if (reduce) snap() else tween(if (pressed) StudioMotion.press else StudioMotion.fast, easing = StudioMotion.standard))
    val shape = RoundedCornerShape(StudioTokens.radiusSm)
    Row(
        modifier.then(if (change == null) Modifier.semantics(mergeDescendants = true) {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        } else Modifier.studioFocusRing(shape, enabled).hoverable(interaction, enabled).studioCheckboxKeys()
            .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Checkbox) { if (enabled) change(it) }
            .padding(horizontal = StudioTokens.spaceHair, vertical = StudioTokens.space1)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
    ) {
        Box(Modifier.size(StudioTokens.checkboxSize).graphicsLayer {
            alpha = if (enabled) 1f else StudioTokens.disabledOpacity
            scaleX = scale; scaleY = scale
        }.background(fill, shape).border(StudioTokens.borderWidth, edge, shape), contentAlignment = Alignment.Center) {
            WorkCheckmark(checked, StudioTokens.ensureContrast(theme.onAccent, fill))
        }
        Text(label, fontSize = StudioTokens.textSmall, color = if (enabled) theme.muted else theme.faint)
    }
}

@Composable
private fun WorkCheckmark(checked: Boolean, tint: Color) {
    val reduce = LocalStudioReducedMotion.current
    AnimatedVisibility(checked,
        enter = if (reduce) EnterTransition.None else scaleIn(tween(StudioMotion.normal, easing = StudioMotion.spring), initialScale = .6f) +
            fadeIn(tween(StudioMotion.fast)),
        exit = if (reduce) ExitTransition.None else fadeOut(tween(StudioMotion.quick))) {
        StudioIcon("check", Modifier.size(StudioTokens.compactIconSize), tint)
    }
}

@Composable
internal fun WorkDot(color: Color, diameter: Int = StudioTokens.statusDotSize.value.toInt(), live: Boolean = false) {
    val opacity = if (live) workPulse(.45f) else rememberUpdatedState(1f)
    Spacer(Modifier.size(diameter.dp).graphicsLayer { alpha = opacity.value }.background(color, CircleShape))
}

@Composable
internal fun WorkStatus(status: String, quiet: Boolean = false, live: Boolean = status == "running") =
    WorkStatus(status, if (quiet) WorkStatusVariant.Chip else WorkStatusVariant.Badge, quiet = quiet, live = live)

/** Dot suits an adjacent visible status label or a labelled tooltip, not a color-only legend. */
internal enum class WorkStatusVariant { Badge, Chip, Dot }

@Composable
internal fun WorkStatus(
    status: String,
    variant: WorkStatusVariant,
    label: String? = null,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    live: Boolean = status == "running",
    background: Color = Color.Unspecified,
) {
    val colors = localColors.current
    val title = label ?: localMessages.current.status(status)
    val animate = live && !quiet
    val adjacent = if (variant == WorkStatusVariant.Badge) colors.inset
        else if (background != Color.Unspecified) background else colors.surface
    val dot = colors.statusColor(status, adjacent)
    var previous by remember { mutableStateOf(status) }
    var settled by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(status) {
        if (status != previous) {
            previous = status
            settled = if (status in setOf("success", "done")) System.nanoTime() else null
        }
    }
    when (variant) {
        WorkStatusVariant.Dot -> Box(modifier.semantics { contentDescription = title }) { WorkDot(dot, live = false) }
        WorkStatusVariant.Badge, WorkStatusVariant.Chip -> StudioPill(title, listOf(dot), variant == WorkStatusVariant.Chip,
            icon = null, live = animate, settled = if (quiet) null else settled, modifier = modifier)
    }
}

@Composable
internal fun WorkPill(label: String, dots: List<Color> = emptyList(), quiet: Boolean = true, icon: String? = null,
                      live: Boolean = false, settled: Long? = null) =
    StudioPill(label, dots, quiet, icon, live = live && !quiet, settled = if (quiet) null else settled)

@Composable
private fun StudioPill(label: String, dots: List<Color>, quiet: Boolean, icon: String?, live: Boolean, settled: Long?,
    modifier: Modifier = Modifier) {
    val colors = localColors.current
    Row(
        modifier.background(if (quiet) Color.Transparent else colors.inset, CircleShape)
            .border(StudioTokens.borderWidth, colors.border, CircleShape).workStatusHalo(live, settled, StudioTokens.radiusPill)
            .heightIn(min = StudioTokens.pillMinHeight).padding(horizontal = StudioTokens.space2, vertical = StudioTokens.spaceHair),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { StudioIcon(it, Modifier.size(StudioTokens.compactIconSize), colors.muted) }
        if (dots.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
            // The halo supplies motion; the identifying dot stays at full contrast.
            dots.forEach { WorkDot(it) }
        }
        Text(label, color = if (quiet) colors.muted else colors.text, fontSize = StudioTokens.textSmall,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun workStatusColor(status: String): Color = localColors.current.statusColor(status)

@Composable
internal fun MemberAvatar(member: JsonObject?, badge: Boolean = false) {
    if (member == null) return
    val colors = localColors.current
    val tint = member.memberColor(colors.accent)
    val icon = member.string("avatar_icon")?.takeIf { it.matches(Regex("[a-z0-9-]+")) }
    val glyph = remember(icon) { icon?.takeIf { Messages::class.java.getResource("/studio-icons/$it.svg") != null } }
    val initials = member.string("name").orEmpty().split(Regex("\\s+"))
        .filter(String::isNotBlank).take(2).joinToString("") { it.take(1).uppercase() }
    if (badge) {
        val shape = if (member.string("kind") == "human") CircleShape else RoundedCornerShape(StudioTokens.radiusMd)
        val ink = StudioTokens.ensureContrast(colors.text, tint, StudioTokens.textContrast)
        Box(Modifier.size(StudioTokens.controlMinHeight).background(tint, shape), contentAlignment = Alignment.Center) {
            if (glyph != null) StudioIcon(glyph, Modifier.size(StudioTokens.iconSize), ink)
            else Text(initials, color = ink, fontSize = StudioTokens.textSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        Box(Modifier.size(StudioTokens.iconSize), contentAlignment = Alignment.Center) {
            if (glyph != null) StudioIcon(glyph, Modifier.size(StudioTokens.iconSize), tint) else WorkDot(tint)
        }
    }
}

internal fun JsonObject?.memberColor(default: Color): Color {
    val value = this?.string("avatar_color")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: return default
    return Color(0xff000000L or value.substring(1).toLong(16))
}

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

internal fun activityTime(value: String?, withDate: Boolean = false): String = value?.let {
    runCatching {
        val timestamp = if (it.endsWith('Z') || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(it)) it else "${it}Z"
        val format = if (withDate) DateTimeFormatter.ofPattern("MMM d, hh:mm a") else DateTimeFormatter.ofPattern("HH:mm")
        OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()).format(format)
    }.getOrDefault("")
}.orEmpty()

@Composable
internal fun BoxScope.WorkOverlay(
    label: String,
    close: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WorkDialog(label, close, modifier) { content() }
}

@Composable
internal fun BoxScope.WorkDialog(
    label: String,
    close: () -> Unit,
    modifier: Modifier,
    canDismiss: Boolean = true,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) = WorkDialog(label, close, modifier, canDismiss, Alignment.Center, content = content)

/** §7: In-app modal; independent native windows/tray remain owned by the shell.
 * The dialog uses a focusable layer over the caller's Box. Only the top layer receives keys;
 * closing it reveals the same underlying focus target, including when the parent is a dialog.
 */
@Composable
internal fun BoxScope.WorkDialog(
    label: String,
    close: () -> Unit,
    modifier: Modifier,
    canDismiss: Boolean = true,
    alignment: Alignment = Alignment.Center,
    returnFocus: FocusRequester? = null,
    scrim: Color = localColors.current.scrim,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = localColors.current
    val focus = remember { FocusRequester() }
    val latestClose by rememberUpdatedState(close)
    val dismissEnabled by rememberUpdatedState(canDismiss)
    val reduced = LocalStudioReducedMotion.current
    val density = LocalDensity.current
    var dismissing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (dismissEnabled && !dismissing) dismissing = true }
    val currentDismiss by rememberUpdatedState(dismiss)
    BoxWithConstraints(Modifier.matchParentSize()) {
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        Popup(popupPositionProvider = WorkDialogPosition,
            properties = PopupProperties(focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false),
            onPreviewKeyEvent = { event ->
                when {
                    dismissing -> true
                    event.key == Key.Escape -> {
                        if (event.type == KeyEventType.KeyUp) dismiss()
                        true
                    }
                    else -> false
                }
            },
        ) {
            val visible = remember { MutableTransitionState(false).apply { targetState = true } }
            LaunchedEffect(dismissing) { visible.targetState = !dismissing }
            LaunchedEffect(dismissing, visible.targetState, visible.isIdle) {
                if (dismissing && !visible.targetState && visible.isIdle) {
                    // Restore within the underlying layer while the explicit trigger still exists.
                    // If the modal is removed externally, the layer simply reveals its retained focus.
                    returnFocus?.requestFocus(FocusDirection.Enter)
                    latestClose()
                    // Some legacy WorkOverlay owners veto closure in their callback (e.g. a save started).
                    // If the owner stays mounted, keep the live modal rather than a blank, blocking layer.
                    dismissing = false
                }
            }
            Box(Modifier.size(paneWidth, paneHeight), contentAlignment = alignment) {
                AnimatedVisibility(visible, Modifier.matchParentSize(), enter = EnterTransition.None, exit = ExitTransition.None) {
                    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
                        val scrimEnter = if (reduced) EnterTransition.None else fadeIn(tween(StudioMotion.quick, easing = LinearEasing))
                        val scrimExit = if (reduced) ExitTransition.None else fadeOut(tween(StudioMotion.fast, easing = StudioMotion.standard))
                        Box(Modifier.matchParentSize().animateEnterExit(scrimEnter, scrimExit).background(scrim)
                            .pointerInput(Unit) { detectTapGestures { currentDismiss() } })
                        val enter = if (reduced) EnterTransition.None else
                            fadeIn(tween(StudioMotion.normal, easing = StudioMotion.standard)) +
                                scaleIn(tween(StudioMotion.normal, easing = StudioMotion.standard), initialScale = .98f) +
                                slideInVertically(tween(StudioMotion.normal, easing = StudioMotion.standard)) {
                                    with(density) { StudioTokens.space2.roundToPx() }
                                }
                        val exit = if (reduced) ExitTransition.None else
                            fadeOut(tween(StudioMotion.fast, easing = StudioMotion.standard)) +
                                scaleOut(tween(StudioMotion.fast, easing = StudioMotion.standard), targetScale = .985f) +
                                slideOutVertically(tween(StudioMotion.fast, easing = StudioMotion.standard)) {
                                    with(density) { StudioTokens.space1.roundToPx() }
                                }
                        val shape = RoundedCornerShape(StudioTokens.radiusLg)
                        var panelHasFocus by remember { mutableStateOf(false) }
                        Column(
                            modifier.animateEnterExit(enter, exit).studioShadow(shape, colors.shadowLg)
                                .studioFocusRing(shape, background = colors.sheetFill)
                                .clip(shape).background(colors.sheetFill)
                                .border(StudioTokens.borderWidth, colors.cardBorder, shape)
                                .focusRequester(focus).onFocusChanged { panelHasFocus = it.hasFocus }.focusable()
                                .semantics { dialog(); contentDescription = label }
                                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                        ) { content(dismiss) }
                        LaunchedEffect(focus) {
                            // Let an explicitly focused field in the content win; the frame is the fallback.
                            if (!panelHasFocus && initialFocus?.requestFocus(FocusDirection.Enter) != true) {
                                focus.requestFocus(FocusDirection.Enter)
                            }
                        }
                        if (dismissing) {
                            Box(Modifier.matchParentSize().pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                            })
                        }
                    }
                }
            }
        }
    }
}

private object WorkDialogPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset = anchorBounds.topLeft
}
