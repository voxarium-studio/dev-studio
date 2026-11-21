package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private val chatBackendIcons = mapOf(
    "claude_code" to "agent-claude-code", "codex" to "agent-codex", "qwen" to "agent-qwen",
    "antigravity" to "agent-antigravity",
)
private val chatModelId = Regex("[A-Za-z0-9._-]+")

internal data class ChatRuntimeChoice(val backend: String?, val model: String?) {
    fun forBackend(next: String, inherited: String) =
        if (next == inherited) ChatRuntimeChoice(null, null) else ChatRuntimeChoice(next, "")

    fun request() = buildJsonObject {
        put("agent_backend", backend)
        put("model", model)
    }
}

internal fun chatModels(options: JsonObject?, backend: String) = options?.objects("suggested_models").orEmpty()
    .filter { it.string("backend") in listOf(null, backend) && !it.string("id").isNullOrBlank() }
    .distinctBy { it.string("id") }

internal data class ChatContextUsage(val tokens: Long?, val capacity: Long?)

// §10.9: the latest request owns its context rating, even when the runtime reported no size.
internal fun chatContextUsage(messages: List<JsonObject>): ChatContextUsage {
    var resultWindow: Long? = null
    for (message in messages.asReversed()) {
        val kind = message.string("kind")
        if (kind in setOf("compressed summary", "context_summary", "producer_compacted")) break
        val data = message.obj("data") ?: continue
        val window = data.string("context_window")?.toLongOrNull()?.takeIf { it > 0 }
        if (kind == "usage_update" || kind == "system" && message.string("text") == "usage_update") {
            val tokens = data.string("context_tokens")?.toLongOrNull()?.takeIf { it >= 0 }
            return ChatContextUsage(tokens, window.takeIf { tokens != null })
        }
        if (data.string("parent_tool_use_id") != null) continue
        if (kind == "result") {
            resultWindow = resultWindow ?: window
            continue
        }
        if (message.string("role") != "assistant") continue
        val usage = data.obj("usage") ?: continue
        val input = usage.string("input_tokens")?.toLongOrNull()?.takeIf { it >= 0 }
        val cache = usage.string("cache_read_input_tokens")?.toLongOrNull()?.takeIf { it >= 0 }
        if (input == null && cache == null) continue
        val creation = usage.string("cache_creation_input_tokens")?.toLongOrNull()?.takeIf { it >= 0 }
        return ChatContextUsage((input ?: 0) + (cache ?: 0) + (creation ?: 0), window ?: resultWindow)
    }
    return ChatContextUsage(null, null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatModelChips(tab: ProjectTab, chat: JsonObject, member: JsonObject, openContext: () -> Unit) {
    val m = localMessages.current
    val inheritedBackend = member.string("agent_backend") ?: "claude_code"
    val id = chat.string("id") ?: return
    val choice = ChatRuntimeChoice(chat.string("agent_backend"), chat.string("model"))
    val backend = choice.backend ?: inheritedBackend
    var options by remember(tab.api) { mutableStateOf<JsonObject?>(null) }
    var error by remember(tab.api) { mutableStateOf<Problem?>(null) }
    LaunchedEffect(tab.api) {
        try {
            options = tab.api?.get("/teams/member-options")?.jsonObject
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            error = cause.problem("agentSettings.modelCatalogError")
        }
    }
    val backends = options?.obj("agent_backends")?.keys.orEmpty().ifEmpty { chatBackendIcons.keys } + backend
    val models = chatModels(options, backend)
    val enabled = !tab.acting && !chat.flag("is_running") && !chat.flag("is_compacting")
    var editing by remember(id, choice) { mutableStateOf(false) }
    var custom by remember(id, choice) { mutableStateOf(TextFieldValue(choice.model.orEmpty())) }
    fun choose(next: ChatRuntimeChoice) {
        if (next != choice) tab.action { patch("/chats/${segment(id)}/model", next.request()) }
        else editing = false
    }

    Column(Modifier.fillMaxWidth().padding(start = StudioTokens.space3, end = StudioTokens.space3, bottom = StudioTokens.space2),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            for (candidate in backends) key(candidate) {
                val label = options?.obj("agent_backends")?.obj(candidate)?.string("label") ?: candidate
                StudioAction(label, chatBackendIcons[candidate] ?: "bot", Modifier,
                    enabled = enabled && candidate != backend, primary = false, outlined = true,
                    iconOnly = true, danger = false, busy = false, selected = candidate == backend,
                    onClick = { choose(choice.forBackend(candidate, inheritedBackend)) })
            }
            if (editing) {
                val model = custom.text.trim()
                val valid = (model.isEmpty() || model.matches(chatModelId)) && model != "auto"
                val apply = { if (enabled && valid) choose(choice.copy(model = model)) }
                StudioField(m["chatModel.id"], custom, { custom = it }, enabled = enabled,
                    modifier = Modifier.widthIn(min = 100.dp, max = 160.dp)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Enter -> { apply(); true }
                                event.key == Key.Escape -> { editing = false; true }
                                else -> false
                            }
                        }, placeholder = m["chatModel.id"])
                WorkAction(m["work.save"], "check", enabled = enabled && valid, iconOnly = true, onClick = apply)
                WorkAction(m["cancel"], "x", iconOnly = true) { editing = false }
            } else {
                val inheritedModel = member.string("model")?.takeIf(String::isNotBlank) ?: m["chatModel.default"]
                val modelLabel = when (choice.model) {
                    null -> if (backend == inheritedBackend) m["chatModel.inherit"].replace("{model}", inheritedModel) else m["chatModel.default"]
                    "" -> m["chatModel.default"]
                    else -> models.find { it.string("id") == choice.model }?.string("label") ?: choice.model
                }
                var opened by remember { mutableStateOf(false) }
                val modelMenuFocus = remember { FocusRequester() }
                Box(Modifier.widthIn(max = 228.dp)) {
                    WorkAction(modelLabel, "chevron-down", Modifier.focusRequester(modelMenuFocus), enabled = enabled) { opened = true }
                    val items = buildList {
                        if (backend == inheritedBackend) add(StudioMenuItem(
                            m["chatModel.inherit"].replace("{model}", inheritedModel), checked = choice.model == null,
                        ) { choose(ChatRuntimeChoice(null, null)) })
                        add(StudioMenuItem(m["chatModel.default"], checked = choice.model == "") {
                            choose(choice.copy(model = ""))
                        })
                        for (entry in models) {
                            val model = entry.string("id") ?: continue
                            add(StudioMenuItem(entry.string("label") ?: model, checked = choice.model == model) {
                                choose(choice.copy(model = model))
                            })
                        }
                        add(StudioMenuItem(m["chatModel.custom"], dividerBefore = true) {
                            custom = TextFieldValue(choice.model.orEmpty()); editing = true
                        })
                    }
                    StudioMenu(opened && enabled, { opened = false }, items,
                        initialItem = items.indexOfFirst { it.checked == true }, returnFocus = modelMenuFocus)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            member.string("effort")?.takeIf(String::isNotBlank)?.let { WorkPill(it) }
            val usage = remember(chat) { chatContextUsage(chat.objects("messages")) }
            val number = remember(m.language) { NumberFormat.getIntegerInstance(Locale.forLanguageTag(m.language)) }
            val tokens = usage.tokens?.let(number::format) ?: "—"
            val capacity = usage.capacity?.let(number::format) ?: "—"
            WorkAction(m["chatModel.context"].replace("{used}", tokens).replace("{total}", capacity), "file-search",
                outlined = false, onClick = openContext)
            TaskUsage(chat)
        }
        error?.let { ProblemText(it) }
    }
}
