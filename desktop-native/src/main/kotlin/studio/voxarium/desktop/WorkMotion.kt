package studio.voxarium.desktop

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.Text

private val breatheEasing = CubicBezierEasing(.42f, 0f, .58f, 1f)

@Composable
internal fun workPulse(low: Float, duration: Int = StudioMotion.statusPulse): State<Float> {
    if (LocalStudioReducedMotion.current) return rememberUpdatedState(1f)
    val transition = rememberInfiniteTransition(label = "work status")
    return transition.animateFloat(1f, low, infiniteRepeatable(tween(duration, easing = breatheEasing), RepeatMode.Reverse),
        label = "pulse")
}

@Composable
internal fun Modifier.workStatusHalo(running: Boolean, settled: Long?, radius: Dp): Modifier {
    val colors = localColors.current
    val reduced = LocalStudioReducedMotion.current
    val breath = if (running && !reduced) workPulse(0f) else rememberUpdatedState(0f)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(settled, running, reduced) {
        if (settled != null && !running && !reduced) {
            flash.snapTo(1f)
            val hold = StudioMotion.slow * 3 / 10
            delay(hold.toLong())
            flash.animateTo(0f, tween(StudioMotion.slow - hold, easing = StudioMotion.standard))
        } else {
            flash.snapTo(0f)
        }
    }
    return drawBehind {
        val amount = if (running) breath.value else flash.value
        if (!running && amount == 0f) return@drawBehind
        val hue = if (running) colors.warning else lerp(colors.border, colors.good, amount)
        val reach = (StudioTokens.space1 * amount).toPx()
        val line = StudioTokens.borderWidth.toPx()
        val corner = radius.toPx()
        if (reach > 0f) {
            drawRoundRect(hue.copy(alpha = (if (running) .16f else .22f) * amount),
                topLeft = Offset(-reach / 2, -reach / 2),
                size = Size(size.width + reach, size.height + reach),
                cornerRadius = CornerRadius(corner + reach / 2), style = Stroke(reach))
        }
        drawRoundRect(hue, topLeft = Offset(line / 2, line / 2),
            size = Size(size.width - line, size.height - line),
            cornerRadius = CornerRadius(corner), style = Stroke(line))
    }
}

@Composable
internal fun WorkTyping(color: Color, label: String) {
    val reduce = LocalStudioReducedMotion.current
    val transition = if (reduce) null else rememberInfiniteTransition(label = "typing")
    Row(Modifier.height(StudioTokens.compactIconSize).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0..2) {
            val progress = transition?.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(StudioMotion.typingBeat, easing = breatheEasing), RepeatMode.Reverse,
                    StartOffset(index * StudioMotion.typingStagger)),
                label = "typing dot",
            )
            Spacer(Modifier.size(StudioTokens.statusDotSize).graphicsLayer {
                val amount = progress?.value ?: .54f
                alpha = .35f + .65f * amount
                translationY = if (reduce) 0f else -amount.dp.toPx()
            }.background(color, CircleShape))
        }
    }
}

internal data class WorkActivity(val sequence: Long, val text: String)

internal class WorkActivityState {
    private val tasks = mutableStateMapOf<String, TaskLiveActivity>()
    private val order = LinkedHashSet<String>()

    fun task(id: String): TaskLiveActivity? = tasks[id]

    // §10.4: a board tile can report live activity before its transcript is open.
    fun onEvent(event: StudioEvent) {
        val payload = event.payload ?: return
        val taskId = payload.string("task_id") ?: return
        when (event.type) {
            "team.member.message" -> {
                val previous = tasks[taskId] ?: TaskLiveActivity()
                val message = workActivityText(payload)?.let { WorkActivity(System.nanoTime(), it) }
                var running = previous.delegated
                val data = payload.obj("data")
                val delegatedId = data?.string("task_id")
                val kind = payload.string("kind")
                val subtype = payload.string("text")
                if (kind == "system" && delegatedId != null) {
                    if (subtype == "task_started" && running.size < 128) running = running + delegatedId
                    if (delegatedFinished(subtype, data)) running = running - delegatedId
                }
                if (kind in setOf("cancelled", "error") || kind == "result" && data?.flag("continued") != true) running = emptySet()
                if (message != null || running != previous.delegated) {
                    tasks[taskId] = TaskLiveActivity(message ?: previous.message, running)
                    order.remove(taskId)
                    order.add(taskId)
                    if (order.size > 256) {
                        val oldest = order.first()
                        order.remove(oldest)
                        tasks.remove(oldest)
                    }
                }
            }
            "team.task.deleted" -> {
                tasks.remove(taskId)
                order.remove(taskId)
            }
        }
    }
}

internal data class TaskLiveActivity(val message: WorkActivity? = null, val delegated: Set<String> = emptySet())

internal fun delegatedFinished(subtype: String?, data: JsonObject?): Boolean =
    subtype == "task_notification" || subtype == "task_updated" &&
        data?.obj("patch")?.string("status") in setOf("completed", "failed", "stopped", "killed")

internal data class DelegatedWork(val description: String, val running: Boolean, val time: Instant?)

// §10.3: transcript lifecycle frames and durable Studio workers report the same visible delegation.
internal fun delegatedWork(messages: List<JsonObject>, workers: List<JsonObject>): List<DelegatedWork> {
    val entries = linkedMapOf<String, DelegatedWork>()
    for (worker in workers) {
        val id = worker.string("id") ?: continue
        entries[id] = DelegatedWork(worker.string("description")?.takeIf(String::isNotBlank) ?: id,
            worker.string("status") in setOf("queued", "running"), worker.workTimestamp("updated_at"))
    }
    for (message in messages) {
        val kind = message.string("kind")
        val time = message.workTimestamp("created_at")
        val data = message.obj("data")
        if (kind in setOf("cancelled", "error") || kind == "result" && data?.flag("continued") != true) {
            entries.replaceAll { _, work ->
                if (work.running && (work.time == null || time != null && time > work.time)) work.copy(running = false, time = time)
                else work
            }
        }
        if (kind != "system" || data == null) continue
        val id = data.string("task_id") ?: continue
        val previous = entries[id]
        if (time != null && previous?.time != null && time <= previous.time) continue
        val subtype = message.string("text")
        if (subtype == "task_started") {
            entries[id] = DelegatedWork(data.string("description")?.takeIf(String::isNotBlank) ?: previous?.description ?: id,
                running = true, time)
        } else if (previous != null && delegatedFinished(subtype, data)) {
            entries[id] = previous.copy(running = false, time = time)
        }
    }
    return entries.values.toList()
}

internal fun JsonObject.workTimestamp(field: String): Instant? = string(field)?.let { raw ->
    runCatching { Instant.parse(raw) }.getOrElse { runCatching { Instant.parse("${raw}Z") }.getOrNull() }
}

@Composable
internal fun WorkFoldChevron(expanded: Boolean, icon: String, openAngle: Float) {
    val reduced = LocalStudioReducedMotion.current
    val angle by animateFloatAsState(if (expanded) openAngle else 0f,
        if (reduced) snap() else tween(StudioMotion.fast, easing = StudioMotion.standard))
    StudioIcon(icon, Modifier.size(StudioTokens.compactIconSize).graphicsLayer { rotationZ = angle }, localColors.current.faint)
}

internal fun workActivityText(message: JsonObject): String? {
    val kind = message.string("kind")
    val text = message.string("text").orEmpty()
    val data = message.obj("data")
    if (kind in setOf("usage_update", "file_observation_status") ||
        kind == "system" && text in setOf("usage_update", "thinking_tokens")) return null
    val line = when {
        kind == "tool_use" -> {
            val tool = (data?.string("name") ?: text).removePrefix("mcp__").replace("__", " · ")
            val input = data?.obj("input")
            val subject = sequenceOf("file_path", "path", "command", "pattern", "url", "query", "title", "description")
                .mapNotNull { input?.string(it)?.takeIf(String::isNotBlank) }.firstOrNull()
            listOfNotNull(tool.takeIf(String::isNotBlank), subject).joinToString(" · ")
        }
        kind == "system" && text in setOf("task_started", "task_progress", "task_notification", "task_updated") ->
            data?.string("description") ?: data?.string("summary") ?: data?.string("task_type").orEmpty()
        else -> text
    }.replace(Regex("\\s+"), " ").trim()
    return line.takeIf(String::isNotBlank)?.let { if (it.length > 120) it.take(120) + "…" else it }
}

@Composable
internal fun WorkActivityLine(text: String, pulseKey: Any?, modifier: Modifier = Modifier, pulseOnMount: Boolean = false) {
    val colors = localColors.current
    val reduced = LocalStudioReducedMotion.current
    val progress = remember { Animatable(1f) }
    var observed by remember { mutableStateOf(if (pulseOnMount) null else pulseKey) }
    LaunchedEffect(pulseKey, reduced) {
        if (!reduced && pulseKey != null && pulseKey != observed) {
            observed = pulseKey
            progress.snapTo(0f)
            progress.animateTo(1f, tween(StudioMotion.statusPulse, easing = StudioMotion.standard))
        } else {
            observed = pulseKey
            progress.snapTo(1f)
        }
    }
    val fraction = progress.value
    Text(text, modifier, color = lerp(colors.accent, colors.faint, ((fraction - .2f) / .8f).coerceIn(0f, 1f))
        .copy(alpha = (.35f + fraction * 3.25f).coerceAtMost(1f)), fontFamily = localStudioMono.current,
        fontSize = StudioTokens.textSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
