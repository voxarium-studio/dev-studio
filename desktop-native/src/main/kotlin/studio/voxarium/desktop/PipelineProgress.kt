package studio.voxarium.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.Text
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

// §11.2: progress is transient and scoped to a node in a run.
internal class PipelineProgressState {
    private data class Node(val run: String, val id: String)
    private val frames = mutableStateMapOf<Node, JsonObject>()
    private val order = LinkedHashSet<Node>()

    fun frame(run: String?, node: String): JsonObject? = run?.let { frames[Node(it, node)] }

    fun onEvent(event: StudioEvent) {
        if (event.type == "system.connected" || event.type == "system.dropped") {
            order.clear()
            frames.clear()
            return
        }
        val payload = event.payload ?: return
        val run = payload.string("run_id") ?: return
        when (event.type) {
            "node.progress" -> {
                val node = Node(run, payload.string("node_id") ?: return)
                val frame = payload.obj("progress") ?: return
                order.remove(node)
                order.add(node)
                frames[node] = frame
                if (order.size > 256) remove(order.first())
            }
            "node.status_changed" -> if (payload.string("status") != "running") {
                payload.string("node_id")?.let { remove(Node(run, it)) }
            }
            "run.completed", "run.failed", "run.cancelled" -> order.filter { it.run == run }.forEach(::remove)
        }
    }

    private fun remove(node: Node) {
        order.remove(node)
        frames.remove(node)
    }
}

@Composable
internal fun PipelineNodeProgress(frame: JsonObject, color: Color, scale: Float) {
    val colors = localColors.current
    val reduced = LocalStudioReducedMotion.current
    val percent = frame.string("percent")?.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 100f)
    val fraction by animateFloatAsState(percent?.div(100f) ?: .35f,
        tween(if (reduced) 0 else StudioMotion.slow, easing = StudioMotion.standard), label = "Node progress")
    // Keep continuous progress sweep cadence independent of the UI transition scale.
    val sweep = if (reduced) null else {
        val transition = rememberInfiniteTransition(label = "Progress sweep")
        transition.animateFloat(0f, 1f, infiniteRepeatable(
            if (percent == null) tween(StudioMotion.progressIndeterminateCycle, easing = FastOutSlowInEasing)
            else tween(StudioMotion.progressSheenCycle, easing = LinearEasing),
        ), label = "Progress sweep phase")
    }
    val m = localMessages.current
    val locale = Locale.forLanguageTag(m.language)
    val percentage = remember(percent, locale) {
        percent?.let { NumberFormat.getPercentInstance(locale).format(it / 100f) }
    }
    val steps = frame.string("steps")?.toIntOrNull()?.takeIf { it > 0 }
    val step = frame.string("step")?.toIntOrNull() ?: 0
    val detail = frame.string("detail") ?: steps?.let { "$step/$it" }
    val stage = listOfNotNull(frame.string("label"), detail).joinToString(" · ")
    val estimate = frame.string("eta_s")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        ?.coerceAtMost(86400f)?.roundToInt()?.let { seconds ->
            val time = when {
                seconds < 60 -> m["pipelineProgress.seconds"].replace("{s}", seconds.toString())
                seconds < 3600 -> m["pipelineProgress.minutesSeconds"]
                    .replace("{m}", (seconds / 60).toString()).replace("{s}", (seconds % 60).toString())
                else -> m["pipelineProgress.hoursMinutes"]
                    .replace("{h}", (seconds / 3600).toString()).replace("{m}", (seconds / 60 % 60).toString())
            }
            "~$time"
        }
    val amount = listOfNotNull(percentage, estimate).joinToString(" · ")
    val gleamColor = remember(colors.canvas, color) {
        StudioTokens.ensureContrast(colors.canvas, color).copy(alpha = StudioTokens.statusTintOpacity)
    }
    Column(Modifier.fillMaxWidth().height((26 * scale).dp).padding(horizontal = StudioTokens.space2 * scale),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair * scale)) {
        Canvas(Modifier.fillMaxWidth().height((3 * scale).dp).clip(CircleShape).semantics {
            progressBarRangeInfo = if (percent == null) ProgressBarRangeInfo.Indeterminate
                else ProgressBarRangeInfo(percent, 0f..100f)
        }) {
            val width = size.width * fraction
            val phase = sweep?.value
            drawRect(colors.controlBorder)
            if (percent == null) {
                val x = if (phase == null) (size.width - width) / 2f else (size.width + width) * phase - width
                drawRect(color, Offset(x, 0f), Size(width, size.height))
            } else if (width > 0f) {
                drawRect(color, size = Size(width, size.height))
                if (phase != null) clipRect(right = width) {
                    val gleam = width * .34f
                    val x = (width + gleam) * phase - gleam
                    drawRect(Brush.horizontalGradient(
                        listOf(Color.Transparent, gleamColor, Color.Transparent), x, x + gleam,
                    ), Offset(x, 0f), Size(gleam, size.height))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2 * scale)) {
            Text(stage, Modifier.weight(1f), fontSize = StudioTokens.textTiny * scale, color = colors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (amount.isNotEmpty()) Text(amount, fontSize = StudioTokens.textTiny * scale, color = colors.faint, maxLines = 1)
        }
    }
}
