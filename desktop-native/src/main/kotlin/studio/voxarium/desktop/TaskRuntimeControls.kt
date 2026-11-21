package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.awt.Desktop
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TaskLimitCountdown(val label: String, val seconds: Long?)

// §10.3: show the provider's reset first, then the Studio's scheduled restart after the grace period.
internal fun taskLimitCountdown(reset: Instant?, resume: Instant?, now: Instant): TaskLimitCountdown? = when {
    reset != null && reset > now -> TaskLimitCountdown("taskRuntime.resetsIn", Duration.between(now, reset).seconds.coerceAtLeast(0))
    resume != null -> TaskLimitCountdown("taskRuntime.resumesIn", Duration.between(now, resume).seconds.coerceAtLeast(0))
    reset != null -> TaskLimitCountdown("taskRuntime.resetPassed", null)
    else -> null
}

@Composable
internal fun TaskLimitIndicator(task: JsonObject) {
    val reset = task.workTimestamp("limit_reset_at")
    val resume = task.workTimestamp("limit_resume_at")
    if (reset == null && resume == null) return
    val m = localMessages.current
    var now by remember(reset, resume) { mutableStateOf(Instant.now()) }
    LaunchedEffect(reset, resume) {
        while (listOfNotNull(reset, resume).any { it > now }) {
            delay(1000)
            now = Instant.now()
        }
    }
    val timer = taskLimitCountdown(reset, resume, now) ?: return
    val seconds = timer.seconds
    val label = if (seconds == null) m[timer.label] else {
        val time = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
        m[timer.label].replace("{time}", time)
    }
    WorkPill(label, icon = "timer")
}

@Composable
internal fun TaskResumeOnLimit(tab: ProjectTab, task: JsonObject, endpoint: String) {
    val m = localMessages.current
    val checked = task.string("resume_on_limit")?.toBooleanStrictOrNull() ?: true
    WorkCheckbox(m["taskRuntime.resumeOnLimit"], checked, enabled = !tab.acting) { enabled ->
        tab.action { patch(endpoint, buildJsonObject { put("resume_on_limit", enabled) }) }
    }
}

internal fun taskRemoteControlLink(value: String?): URI? {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    return uri.takeIf { it.scheme.equals("https", ignoreCase = true) && it.host.equals("claude.ai", ignoreCase = true) &&
        it.userInfo == null && (it.port == -1 || it.port == 443) && it.path.startsWith("/code/") }
}

// §10.8: the provider creates or removes the handoff only on an explicit click in this menu.
@Suppress("DEPRECATION")
@Composable
internal fun TaskRemoteControl(tab: ProjectTab, task: JsonObject, member: JsonObject?, endpoint: String) {
    val m = localMessages.current
    val colors = localColors.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val connected = !task.string("remote_control_url").isNullOrBlank()
    val link = remember(task.string("remote_control_url")) { taskRemoteControlLink(task.string("remote_control_url")) }
    val backend = task.string("agent_backend") ?: member?.string("agent_backend")
    val compressing = task.flag("is_compacting") || task.flag("is_compressing_context")
    val hint = when {
        connected -> "taskRuntime.remoteReady"
        compressing -> "taskRuntime.remoteCompressing"
        !task.flag("is_running") -> "taskRuntime.remoteIdle"
        backend != null && backend != "claude_code" -> "taskRuntime.remoteBackend"
        else -> null
    }
    var menu by remember(task.string("id")) { mutableStateOf(false) }
    var problem by remember(link) { mutableStateOf<Problem?>(null) }
    val triggerFocus = remember { FocusRequester() }
    fun dismiss() {
        menu = false
        triggerFocus.requestFocus()
    }
    Box {
        WorkAction(m["taskRuntime.remote"], "plug", Modifier.focusRequester(triggerFocus),
            outlined = false, iconOnly = true, primary = connected) { menu = !menu }
        if (menu) {
            val offset = with(LocalDensity.current) { (StudioTokens.iconButtonSize + StudioTokens.space1).roundToPx() }
            // This is rich popup content, not a menu: the URL can be selected and problems are reported in place.
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset), onDismissRequest = ::dismiss,
                properties = PopupProperties(focusable = true)) {
                val shape = RoundedCornerShape(StudioTokens.radiusMd)
                Column(Modifier.width(StudioTokens.menuMaxWidth).heightIn(max = StudioTokens.menuMaxHeight)
                    .studioShadow(shape, colors.shadowMd).clip(shape).background(colors.sheetFill)
                    .border(StudioTokens.borderWidth, colors.cardBorder, shape).verticalScroll(rememberScrollState())
                    .padding(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    WorkAction(m[if (connected) "taskRuntime.remoteDisconnect" else "taskRuntime.remoteConnect"], "plug",
                        enabled = !tab.acting && (connected || hint == null)) {
                        tab.action {
                            if (connected) delete("$endpoint/remote-control") else post("$endpoint/remote-control")
                        }
                    }
                    hint?.let { Muted(m[it]) }
                    if (link != null) {
                        SelectionContainer { LibraryCaption(link.toString(), maxLines = 2) }
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                            WorkAction(m["taskRuntime.remoteCopy"], "copy") { clipboard.setText(AnnotatedString(link.toString())) }
                            WorkAction(m["open"], "external-link") {
                                scope.launch {
                                    problem = withContext(Dispatchers.IO) {
                                        try {
                                            Desktop.getDesktop().browse(link)
                                            null
                                        } catch (cause: Exception) {
                                            cause.problem("taskRuntime.remoteOpenFailed")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (connected && link == null) Muted(m["taskRuntime.remoteInvalidLink"])
                    (problem ?: tab.actionError)?.let { ProblemText(it) }
                }
            }
        }
    }
}
