package studio.voxarium.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path

// §2.7.1, §7: match the Graphite durations and curves in frontend/src/styles/tokens.css.
internal object StudioMotion {
    const val quick = 90
    const val fast = 130
    const val normal = 220
    const val slow = 320
    const val press = 60
    const val page = 170
    const val travel = 280
    const val stagger = 45
    const val spinnerCycle = 900
    const val splashSweep = 1400
    const val statusPulse = 800
    const val typingBeat = 320
    const val typingStagger = 110
    const val progressIndeterminateCycle = 1600
    const val progressSheenCycle = 1100
    val standard = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val spring = CubicBezierEasing(.34f, 1.45f, .64f, 1f)
    val inOut = CubicBezierEasing(.77f, 0f, .175f, 1f)
}

internal val LocalStudioReducedMotion = staticCompositionLocalOf { false }

private object WindowsMotionPreference {
    private const val GET_CLIENT_AREA_ANIMATION = 0x1042

    private val systemParameters by lazy {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@lazy null
        runCatching {
            val user32 = Path.of(System.getenv("SystemRoot"), "System32", "user32.dll")
            val symbol = SymbolLookup.libraryLookup(user32, Arena.global()).findOrThrow("SystemParametersInfoW")
            Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
        }.getOrNull()
    }

    fun reduced(): Boolean {
        val read = systemParameters ?: return false
        return Arena.ofConfined().use { arena ->
            val enabled = arena.allocate(JAVA_INT)
            read.invoke(GET_CLIENT_AREA_ANIMATION, 0, enabled, 0) as Int != 0 && enabled.get(JAVA_INT, 0) == 0
        }
    }
}

internal fun systemReducedMotion(): Boolean = WindowsMotionPreference.reduced()

@Composable
internal fun studioColor(target: Color): Color {
    val animated by animateColorAsState(target, tween(
        if (LocalStudioReducedMotion.current) 0 else StudioMotion.quick,
        easing = LinearEasing,
    ), label = "Graphite color")
    return animated
}

@Composable
internal fun Modifier.studioArrival(
    identity: Any? = Unit,
    distance: Dp = StudioTokens.space2,
    duration: Int = StudioMotion.page,
    delay: Int = 0,
    startScale: Float = 1f,
): Modifier {
    if (LocalStudioReducedMotion.current) return this
    val progress = remember(identity) { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(duration, delay, StudioMotion.standard))
    }
    return graphicsLayer {
        val fraction = progress.value
        alpha = fraction
        translationY = distance.toPx() * (1f - fraction)
        scaleX = startScale + (1f - startScale) * fraction
        scaleY = scaleX
    }
}

@Composable
internal fun StudioDisclosure(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduced = LocalStudioReducedMotion.current
    val enter = if (reduced) 0 else StudioMotion.normal
    val exit = if (reduced) 0 else StudioMotion.fast
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(tween(enter, easing = StudioMotion.standard)) +
            expandVertically(tween(enter, easing = StudioMotion.standard), expandFrom = Alignment.Top),
        exit = fadeOut(tween(exit, easing = StudioMotion.standard)) +
            shrinkVertically(tween(exit, easing = StudioMotion.standard), shrinkTowards = Alignment.Top),
        content = content,
    )
}

@Composable
internal fun StudioLoadingIcon(modifier: Modifier = Modifier, tint: Color = localColors.current.muted) {
    val rotation = if (LocalStudioReducedMotion.current) null else {
        val transition = rememberInfiniteTransition(label = "Loading")
        transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(StudioMotion.spinnerCycle, easing = LinearEasing)), label = "Loading rotation")
    }
    StudioIcon("refresh-cw", modifier.graphicsLayer { rotationZ = rotation?.value ?: 0f }, tint)
}
