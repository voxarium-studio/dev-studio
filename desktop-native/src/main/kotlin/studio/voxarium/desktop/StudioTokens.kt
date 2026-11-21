package studio.voxarium.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// §7: fixed CSS scales in native logical units. Component minima are not fixed heights.
internal object StudioTokens {
    val spaceHair = 2.dp
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 24.dp
    val space6 = 32.dp

    val radiusSm = 4.dp
    val radiusMd = 8.dp
    val radiusLg = 12.dp
    val radiusXl = 16.dp
    val radiusPill = 999.dp

    val titlebarHeight = 44.dp
    val footerHeight = 28.dp
    val rowHeight = 34.dp
    val rowPadX = space3
    val panePad = 22.dp
    val railWidth = 192.dp
    val railIconWidth = 48.dp

    val borderWidth = 1.dp
    val focusWidth = 2.dp
    val controlMinHeight = 30.dp
    val controlPaddingX = space3
    val controlPaddingY = space1
    val iconSize = 16.dp
    val statusDotSize = 6.dp
    val checkboxSize = 15.dp
    val compactIconSize = 12.dp
    val largeIconSize = 32.dp
    val iconButtonSize = 32.dp
    val chatLauncherSize = space5 * 2
    val pillMinHeight = 22.dp
    val menuMinWidth = 160.dp
    val menuMaxWidth = 336.dp
    val menuMaxHeight = 320.dp
    val contextMenuMaxHeight = 496.dp
    val modalMaxWidth = 520.dp
    val emptyStateMaxWidth = 384.dp
    const val tooltipDelayMillis = 550L

    val textSize = 14.sp
    val textSmall = 12.sp
    val textTiny = 10.sp
    val titleSize = 17.sp
    val pageTitleSize = 22.sp
    val metricValueSize = 20.sp
    const val lineHeightMultiplier = 1.5f
    const val disabledOpacity = .5f
    const val draggedOpacity = .45f
    const val statusTintOpacity = .15f

    const val indicatorContrast = 3f
    const val textContrast = 4.5f

    /** WCAG contrast against an opaque adjacent surface. Translucent marks are composited first. */
    fun contrastRatio(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }

    /**
     * Keep an already legible override intact, otherwise blend it toward a readable neutral.
     * Supply the effective, opaque surface, not the color of an alpha layer over it.
     */
    fun ensureContrast(foreground: Color, background: Color, minimum: Float = indicatorContrast): Color {
        if (contrastRatio(foreground, background) >= minimum) return foreground
        val neutral = if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
            Color.Black
        } else {
            Color.White
        }
        // Leave a little room for conversion to the window's 8-bit framebuffer.
        val target = minOf(minimum + .08f, contrastRatio(neutral, background))
        var bad = 0f
        var good = 1f
        repeat(12) {
            val mid = (bad + good) / 2
            if (contrastRatio(lerp(foreground, neutral, mid), background) >= target) good = mid
            else bad = mid
        }
        return lerp(foreground, neutral, good)
    }
}

/** CSS offset and blur, kept separate so native shadow placement can match the surface role. */
internal data class StudioShadow(val offsetY: Dp, val blur: Dp, val color: Color)

// §7: frontend/src/lib/status.ts vocabulary, plus the aliases sent to the native shell.
internal enum class StudioStatusRole(val token: String) {
    Todo("todo"), Pending("pending"), Running("running"), Success("success"), Error("error"),
    Cancelled("cancelled"), Skipped("skipped"), Cached("cached"), Paused("paused"),
    Review("review"), Blocked("blocked");

    companion object {
        fun resolve(status: String): StudioStatusRole = when (status) {
            "todo" -> Todo
            "running", "in_progress" -> Running
            "success", "done", "completed", "loaded" -> Success
            "error", "failed" -> Error
            "cancelled", "canceled" -> Cancelled
            "skipped" -> Skipped
            "cached" -> Cached
            "paused" -> Paused
            "needs_review", "review", "awaiting_approval" -> Review
            "blocked" -> Blocked
            else -> Pending
        }
    }
}
