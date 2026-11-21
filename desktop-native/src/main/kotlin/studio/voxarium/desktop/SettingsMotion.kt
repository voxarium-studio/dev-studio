package studio.voxarium.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// §7: a single page clock keeps lazy sections from repeating their entrance on scroll.
@Composable
internal fun rememberSettingsArrival(): State<Float> {
    val reduced = LocalStudioReducedMotion.current
    val duration = StudioMotion.page + 3 * StudioMotion.stagger
    val clock = remember { Animatable(if (reduced) duration.toFloat() else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) clock.snapTo(duration.toFloat())
        else clock.animateTo(duration.toFloat(), tween(duration, easing = LinearEasing))
    }
    return clock.asState()
}

internal fun Modifier.settingsArrival(clock: State<Float>, index: Int): Modifier = graphicsLayer {
    val delay = index.coerceAtMost(3) * StudioMotion.stagger
    val progress = ((clock.value - delay) / StudioMotion.page).coerceIn(0f, 1f)
    alpha = StudioMotion.standard.transform(progress)
    translationY = StudioTokens.space2.toPx() * (1f - alpha)
}

@Composable
internal fun SettingsRefreshButton(label: String, working: Boolean, enabled: Boolean = !working, refresh: () -> Unit) {
    WorkAction(label, "refresh-cw", enabled = enabled, busy = working, onClick = refresh)
}

// §7: fast local reads skip the indicator, and the steady bars still describe a slow read in reduced motion.
@Composable
internal fun SettingsLoadingBars(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(StudioMotion.normal.toLong())
        shown = true
    }
    if (!shown) return

    val colors = localColors.current
    val reducedMotion = LocalStudioReducedMotion.current
    val label = localMessages.current["loading"]
    val shimmer = if (reducedMotion) null else rememberInfiniteTransition(label = "Settings loading")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(StudioMotion.splashSweep, easing = LinearEasing)), label = "Skeleton shimmer")
    val arrival = Modifier.studioArrival(distance = 0.dp, duration = StudioMotion.quick)
    Column(modifier.widthIn(max = 448.dp).fillMaxWidth().then(arrival).semantics {
        contentDescription = label
        liveRegion = LiveRegionMode.Polite
    }, verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        listOf(1f, .7f, .45f).forEachIndexed { index, width ->
            Canvas(Modifier.fillMaxWidth(width).height(StudioTokens.space2)) {
                val radius = CornerRadius(size.height / 2)
                if (shimmer == null) {
                    drawRoundRect(colors.surface, cornerRadius = radius)
                } else {
                    val phase = (shimmer.value - index * (StudioMotion.fast.toFloat() / StudioMotion.splashSweep) + 1f) % 1f
                    val start = (phase * 1.4f - .6f) * size.width
                    drawRoundRect(Brush.horizontalGradient(listOf(colors.surface, colors.hover, colors.surface),
                        start, start + size.width, TileMode.Repeated), cornerRadius = radius)
                }
            }
        }
    }
}
