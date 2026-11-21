package studio.voxarium.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.ui.component.Text
import java.net.URLDecoder

// §6.5: notify actions appear above any page in the project that emitted the event.
internal class AutomationToast(val notice: JsonObject) {
    var visible by mutableStateOf(true)
        private set
    fun leave() { visible = false }
}

internal class AutomationToastState(private val scope: CoroutineScope) {
    private val pending = mutableStateListOf<AutomationToast>()
    private val timers = mutableMapOf<AutomationToast, Job>()
    val items: List<AutomationToast> get() = pending

    fun onEvent(event: StudioEvent) {
        if (event.type != "automation.notification") return
        val notice = event.payload ?: return
        if (pending.count { it.visible } >= 4) dismiss(pending.first { it.visible })
        if (pending.size >= 8) {
            val oldest = pending.first()
            hold(oldest)
            pending.remove(oldest)
        }
        val toast = AutomationToast(notice)
        pending.add(toast)
        release(toast)
    }

    fun hold(toast: AutomationToast) {
        if (toast.visible) timers.remove(toast)?.cancel()
    }

    fun release(toast: AutomationToast) {
        if (!toast.visible || toast !in pending) return
        hold(toast)
        timers[toast] = scope.launch {
            delay(8000)
            dismiss(toast)
        }
    }

    fun dismiss(toast: AutomationToast) {
        if (!toast.visible || toast !in pending) return
        hold(toast)
        toast.leave()
        scope.launch {
            delay(StudioMotion.normal.toLong()) // leave transition and its final layout frame
            pending.remove(toast)
        }
    }
}

@Composable
internal fun AutomationToastStack(state: AutomationToastState, modifier: Modifier = Modifier, open: (Page) -> Unit) {
    Column(modifier.width(320.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        state.items.forEach { toast -> key(toast) { AutomationToastItem(toast, state, open) } }
    }
}

@Composable
private fun AutomationToastItem(toast: AutomationToast, state: AutomationToastState, open: (Page) -> Unit) {
    val reduced = LocalStudioReducedMotion.current
    val enter = if (reduced) 0 else StudioMotion.normal
    val exit = if (reduced) 0 else StudioMotion.fast
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = toast.visible
    AnimatedVisibility(transition,
        enter = fadeIn(tween(enter, easing = StudioMotion.standard)) +
            slideInHorizontally(tween(enter, easing = StudioMotion.standard)) { it / 10 },
        exit = fadeOut(tween(exit, easing = StudioMotion.standard)) +
            slideOutHorizontally(tween(exit, easing = StudioMotion.standard)) { it / 10 } +
            shrinkVertically(tween(exit, easing = StudioMotion.inOut))) {
        val m = localMessages.current
        val colors = localColors.current
        val (title, message) = automationToastText(m, toast.notice)
        val target = remember(toast) {
            automationToastRoute(toast.notice.string("route"), toast.notice.obj("values")?.string("path"))
        }
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        LaunchedEffect(toast) {
            var held = false
            try {
                snapshotFlow { hovered }.collect { pointerOverToast ->
                    if (pointerOverToast) state.hold(toast) else if (held) state.release(toast)
                    held = pointerOverToast
                }
            } finally {
                if (held) state.release(toast)
            }
        }
        val shape = RoundedCornerShape(StudioTokens.radiusMd)
        Column {
            Row(Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(StudioTokens.borderWidth, colors.border, shape)
                .hoverable(interaction).padding(StudioTokens.space2)) {
                // This notification body is one focusable action with multiline content; its sibling
                // dismiss button stays independent, and hovering either target holds the toast timer.
                val action = if (target != null) Modifier
                    .studioFocusRing(shape, enabled = toast.visible, background = colors.surface)
                    .background(studioColor(if (hovered) colors.hover else colors.surface), shape)
                    .clickable(enabled = toast.visible, role = Role.Button) {
                        state.dismiss(toast)
                        open(target)
                    } else Modifier
                Row(Modifier.weight(1f).then(action).padding(StudioTokens.space2), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    StudioIcon("zap", Modifier.size(StudioTokens.iconSize), colors.navigationTint("automations"))
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                        Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (message.isNotBlank()) Text(message, color = colors.muted, fontSize = StudioTokens.textSmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        toast.notice.string("automation_name")?.takeIf(String::isNotBlank)?.let { Muted(it, StudioTokens.textTiny.value.toInt()) }
                    }
                }
                ShellIconButton("x", m["dismiss"], Modifier) { state.dismiss(toast) }
            }
            Spacer(Modifier.height(StudioTokens.space2))
        }
    }
}

internal fun automationToastRoute(route: String?, changedPath: String? = null): Page? {
    val value = route ?: return null
    val path = value.substringBefore('?')
    val search = value.substringAfter('?', "")
    fun selector(name: String): String? = search.removePrefix("$name=").takeIf { search.startsWith("$name=") }
        ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull() }
    return when {
        path == "/tasks" -> Page(path, Selectors(task = selector("task")))
        path == "/explorer" -> Page(path, Selectors(path = when {
            search.isEmpty() -> null
            changedPath != null -> changedPath
            else -> selector("path")
        }))
        path.startsWith("/pipelines/") && path.removePrefix("/pipelines/").matches(Regex("[A-Za-z0-9_-]+")) -> Page(path)
        else -> null
    }
}

private fun automationToastText(m: Messages, payload: JsonObject): Pair<String, String> {
    val values = payload.obj("values")
    fun field(name: String): String = values?.string(name).orEmpty()
    fun label(key: String) = m["automations.notification.$key"]
    val task = field("task")
    val pipeline = field("pipeline")
    val trigger = payload.string("trigger")
    val (title, message) = when (trigger) {
        "task.status_changed" -> label("statusTitle").replace("{task}", task).replace("{status}", m.status(field("status"))) to field("title")
        "task.created" -> label("createdTitle").replace("{task}", task) to field("title")
        "path.changed" -> {
            val count = field("count").toIntOrNull()?.coerceAtLeast(1) ?: 1
            val form = when {
                m.language != "ru" -> "other"
                count % 100 in 11..14 -> "many"
                count % 10 == 1 -> "one"
                count % 10 in 2..4 -> "few"
                else -> "many"
            }
            label("pathTitle").replace("{folder}", field("folder").ifBlank { "./" }) to
                if (count == 1) field("path") else label("pathCount.$form").replace("{count}", count.toString())
        }
        "run.finished" -> label(when (field("outcome")) {
            "success" -> "runSucceeded"
            "failed" -> "runFailed"
            else -> "runCancelled"
        }).replace("{pipeline}", pipeline) to field("error")
        else -> payload.string("automation_name").orEmpty().ifBlank { label("testTitle") } to label("testMessage")
    }
    return payload.string("title").orEmpty().ifBlank { title } to payload.string("message").orEmpty().ifBlank { message }
}
