package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.*

private const val CONNECTIONS_ENDPOINT = "/api/settings/api-backends"
private const val API_BACKEND_PREFIX = "openai_api:"
private val protocolModes = listOf("auto", "responses", "chat_completions")
private val tierKeys = listOf("1", "2", "3")
private val modelId = Regex("[A-Za-z0-9._:/+\\-]+")
private val connectionFields = setOf("id", "name", "base_url", "model", "models", "has_api_key",
    "api_mode", "use_limits", "tpm", "context_window", "icon")

// §10.6: the settings catalog is shared by windows and only carries public connection metadata.
internal class AgentRuntimeSettingsState(private val api: StudioApi, private val scope: CoroutineScope) {
    var connections by mutableStateOf<List<JsonObject>>(emptyList())
        private set
    var connectionDraft by mutableStateOf<ApiConnectionDraft?>(null)
        private set
    var confirmDelete by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set

    val orchestratorDraft = OrchestratorDraft()

    fun refresh() = connectionAction("agentSettings.catalogError") {
        connections = api.request(CONNECTIONS_ENDPOINT).objects().map(::publicConnection)
        loaded = true
    }

    fun edit(connection: JsonObject? = null) {
        if (busy) return
        connectionDraft?.apiKey = TextFieldValue()
        connectionDraft = ApiConnectionDraft(connection)
        confirmDelete = null
        problem = null
    }

    fun closeEditor() {
        if (busy) return
        connectionDraft?.apiKey = TextFieldValue()
        connectionDraft = null
        problem = null
    }

    fun requestDeletion(id: String?) {
        if (!busy) {
            confirmDelete = id
            problem = null
        }
    }

    fun save() {
        val draft = connectionDraft ?: return
        if (!draft.valid) return
        connectionAction("agentSettings.saveError") {
            val result = publicConnection(api.request("$CONNECTIONS_ENDPOINT/${segment(draft.id)}", "PUT", draft.payload()).jsonObject)
            val index = connections.indexOfFirst { it.string("id") == draft.id }
            connections = if (index == -1) connections + result else connections.toMutableList().apply { set(index, result) }
            draft.apiKey = TextFieldValue()
            connectionDraft = null
        }
    }

    fun delete() {
        val id = confirmDelete ?: return
        connectionAction("agentSettings.deleteError") {
            api.request("$CONNECTIONS_ENDPOINT/${segment(id)}", "DELETE")
            connections = connections.filterNot { it.string("id") == id }
            orchestratorDraft.removeBackend("$API_BACKEND_PREFIX$id")
            if (connectionDraft?.id == id) {
                connectionDraft?.apiKey = TextFieldValue()
                connectionDraft = null
            }
            confirmDelete = null
        }
    }

    private fun connectionAction(failure: String, action: suspend () -> Unit) {
        if (busy) return
        // Mark busy before starting work because two separate windows can edit this catalog.
        busy = true
        problem = null
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Operation errors must not render an HTTP response that might echo credentials.
                problem = Problem(failure)
            } finally {
                busy = false
            }
        }
    }
}

private fun publicConnection(value: JsonObject) = JsonObject(value.filterKeys(connectionFields::contains))

internal class ApiConnectionDraft(saved: JsonObject?) {
    val id = saved?.string("id") ?: UUID.randomUUID().toString()
    val hasKey = saved?.flag("has_api_key") == true
    var name by mutableStateOf(TextFieldValue(saved?.string("name").orEmpty()))
    var url by mutableStateOf(TextFieldValue(saved?.string("base_url") ?: "https://api.openai.com/v1"))
    var model by mutableStateOf(TextFieldValue(saved?.string("model").orEmpty()))
    var models by mutableStateOf(TextFieldValue(saved?.stringList("models").orEmpty().joinToString("\n")))
    var mode by mutableStateOf(saved?.string("api_mode") ?: "auto")
    var context by mutableStateOf(TextFieldValue(saved?.string("context_window").orEmpty()))
    var tpm by mutableStateOf(TextFieldValue(saved?.string("tpm") ?: "1000000"))
    var useLimits by mutableStateOf(saved?.flag("use_limits") == true)
    var icon by mutableStateOf(saved?.string("icon"))
    var apiKey by mutableStateOf(TextFieldValue())
    var clearKey by mutableStateOf(false)

    private val catalog get() = models.text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    private val contextNumber get() = context.text.trim().toIntOrNull()
    private val rate get() = tpm.text.trim().toBigIntegerOrNull()?.takeIf { it.signum() > 0 }
    val validUrl: Boolean get() = runCatching {
        val uri = URI(url.text.trim())
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank() &&
            '@' !in uri.rawAuthority && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
    val validContext get() = context.text.isBlank() || contextNumber?.let { it in 1_024..100_000_000 } == true
    val validRate get() = !useLimits || rate != null
    val validModels get() = catalog.size <= 200 && catalog.all { it.length <= 200 }
    val valid get() = name.text.trim().length in 1..120 && model.text.trim().length in 1..200 && mode in protocolModes &&
        validUrl && validContext && validRate && validModels

    fun payload() = buildJsonObject {
        put("name", name.text.trim())
        put("base_url", url.text.trim())
        put("model", model.text.trim())
        put("models", JsonArray(catalog.map(::JsonPrimitive)))
        put("api_mode", mode)
        put("use_limits", useLimits)
        rate?.let { put("tpm", JsonPrimitive(it)) }
        put("context_window", contextNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("icon", icon?.let(::JsonPrimitive) ?: JsonNull)
        if (hasKey && clearKey) put("api_key", "")
        else if (apiKey.text.isNotEmpty()) put("api_key", apiKey.text)
    }
}

internal class OrchestratorDraft {
    private val edits = mutableStateMapOf<Pair<String, String>, TextFieldValue>()
    val dirty get() = edits.isNotEmpty()

    fun field(backend: String, tier: String, stored: String) = edits[backend to tier] ?: TextFieldValue(stored)

    fun edit(backend: String, tier: String, stored: String, value: TextFieldValue) {
        val key = backend to tier
        if (value.text.trim() == stored) edits.remove(key) else edits[key] = value
    }

    fun clearSaved(saved: JsonObject) {
        edits.entries.toList().forEach { (key, field) ->
            if (saved.obj(key.first)?.string(key.second) == field.text.trim()) edits.remove(key)
        }
    }

    fun removeBackend(backend: String) {
        edits.keys.filter { it.first == backend }.forEach(edits::remove)
    }

    fun payload(saved: JsonObject, defaults: Map<String, String>, allowed: Set<String>): JsonObject? {
        val changed = edits.entries.filter { it.key.first in allowed }.groupBy { it.key.first }
        if (changed.isEmpty()) return null
        val columns = saved.toMutableMap()
        for ((backend, values) in changed) {
            val base: MutableMap<String, JsonElement> = saved.obj(backend)?.toMutableMap() ?: tierKeys.associateWithTo(linkedMapOf()) {
                JsonPrimitive(defaults[backend].orEmpty())
            }
            values.forEach { (key, field) -> base[key.second] = JsonPrimitive(field.text.trim()) }
            for (tier in tierKeys) if (!validOrchestratorModel((base[tier] as? JsonPrimitive)?.content.orEmpty())) return null
            columns[backend] = JsonObject(base)
        }
        return buildJsonObject { put("orchestrator_models", JsonObject(columns)) }
    }
}

private fun validOrchestratorModel(value: String): Boolean = value.matches(modelId) &&
    !value.equals("auto", ignoreCase = true) && !value.equals("default", ignoreCase = true)

@Composable
internal fun ApiBackendsSettings(workspace: Workspace, tailPadding: Dp) {
    val m = localMessages.current
    val state = workspace.agentRuntimeSettings
    LaunchedEffect(state) { state.refresh() }
    SettingsSection("agentSettings.backends", tailPadding) {
        Muted(m["agentSettings.backendsHint"])
        if (!state.loaded && state.busy) Muted(m["loading"])
        state.connections.forEach { connection ->
            val id = connection.string("id") ?: return@forEach
            LibraryCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    StudioIcon(agentConnectionIcon(connection.string("icon")), Modifier.size(StudioTokens.iconSize))
                    Column(Modifier.weight(1f)) {
                        Text(connection.string("name").orEmpty(), fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        LibraryCaption(listOfNotNull(connection.string("model"), connection.string("base_url")).joinToString(" · "),
                            mono = true, maxLines = 2)
                    }
                    LibraryIconAction("pencil", m["agentSettings.edit"], enabled = !state.busy && state.connectionDraft == null) {
                        state.edit(connection)
                    }
                    LibraryIconAction("trash-2", m["work.delete"], enabled = !state.busy && state.connectionDraft == null) {
                        state.requestDeletion(id)
                    }
                }
                if (connection.flag("has_api_key")) Muted(m["agentSettings.keyConfigured"], StudioTokens.textTiny.value.toInt())
                if (state.confirmDelete == id) {
                    Muted(m["agentSettings.deleteHint"])
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                        LibraryButton("trash-2", m["work.delete"], enabled = !state.busy, onClick = state::delete)
                        WorkAction(m["cancel"], null, enabled = !state.busy, onClick = { state.requestDeletion(null) })
                    }
                }
            }
        }
        state.connectionDraft?.let { ApiBackendForm(it, state) }
        state.problem?.let { ProblemText(it) }
        if (state.connectionDraft == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            LibraryButton("plus", m["agentSettings.add"], enabled = state.loaded && !state.busy) { state.edit() }
            LibraryButton("refresh-cw", m["refresh"], enabled = !state.busy, onClick = state::refresh)
        }
    }
}

@Composable
private fun ApiBackendForm(draft: ApiConnectionDraft, state: AgentRuntimeSettingsState) {
    val m = localMessages.current
    val enabled = !state.busy
    val protocols = protocolModes.associateWith { m["agentSettings.protocol.$it"] }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        RuntimeField(m["referenceName"], draft.name, enabled) { if (it.text.length <= 120) draft.name = it }
        RuntimeField(m["agentSettings.url"], draft.url, enabled) { draft.url = it }
        if (!draft.validUrl && draft.url.text.isNotBlank()) RuntimeIssue(m["agentSettings.invalidUrl"])
        Muted(m["agentSettings.protocol"])
        RuntimeDropdown(protocols.getValue(draft.mode), protocols, draft.mode, enabled) { draft.mode = it }
        Muted(m["agentSettings.protocolHint"], StudioTokens.textTiny.value.toInt())
        RuntimeField(m["agentSettings.key"], draft.apiKey, enabled && !draft.clearKey, secret = true) { draft.apiKey = it }
        Muted(m[if (draft.hasKey) "agentSettings.keepKey" else "agentSettings.keyOptional"], StudioTokens.textTiny.value.toInt())
        if (draft.hasKey) WorkCheckbox(m["agentSettings.clearKey"], draft.clearKey, enabled) {
            draft.clearKey = it
            if (it) draft.apiKey = TextFieldValue()
        }
        RuntimeField(m["agentSettings.defaultModel"], draft.model, enabled) { if (it.text.length <= 200) draft.model = it }
        RuntimeField(m["agentSettings.models"], draft.models, enabled, multiline = true) { draft.models = it }
        Muted(m["agentSettings.modelsHint"], StudioTokens.textTiny.value.toInt())
        if (!draft.validModels) RuntimeIssue(m["agentSettings.invalidModels"])
        RuntimeField(m["agentSettings.context"], draft.context, enabled) { draft.context = it }
        Muted(m["agentSettings.contextHint"], StudioTokens.textTiny.value.toInt())
        if (!draft.validContext) RuntimeIssue(m["agentSettings.invalidContext"])
        WorkCheckbox(m["agentSettings.enableLimits"], draft.useLimits, enabled) { draft.useLimits = it }
        if (draft.useLimits) {
            RuntimeField(m["agentSettings.tpm"], draft.tpm, enabled) { draft.tpm = it }
            if (!draft.validRate) RuntimeIssue(m["agentSettings.invalidTpm"])
        }
        val icons = listOf("bot", "cpu", "brain", "terminal", "code", "message-circle", "sparkles", "zap", "globe", "cloud")
            .filter { agentConnectionIcon(it) == it }
        val labels = icons.associateWith { m["agentSettings.icon.$it"] } + ("" to m["agentSettings.none"])
        val icon = draft.icon.orEmpty()
        Muted(m["agentSettings.icon"])
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            StudioIcon(agentConnectionIcon(draft.icon), Modifier.size(StudioTokens.iconSize))
            RuntimeDropdown(labels[icon] ?: icon, labels, icon, enabled, leadingGlyph = true) {
                draft.icon = it.takeIf(String::isNotEmpty)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            WorkAction(m["saveDocument"], null, primary = true, enabled = enabled && draft.valid, onClick = state::save)
            WorkAction(m["cancel"], null, enabled = enabled, onClick = state::closeEditor)
        }
    }
}

private fun agentConnectionIcon(value: String?) = value?.takeIf {
    it.matches(Regex("[a-z0-9-]+")) && Messages::class.java.getResource("/studio-icons/$it.svg") != null
} ?: "bot"

@Composable
internal fun OrchestratorSettings(workspace: Workspace, tailPadding: Dp, catalogProject: ProjectApi? = null) {
    val m = localMessages.current
    val state = workspace.agentRuntimeSettings
    val draft = state.orchestratorDraft
    val saved = workspace.preferences.obj("orchestrator_models") ?: JsonObject(emptyMap())
    var options by remember(catalogProject) { mutableStateOf<JsonObject?>(null) }
    var catalogProblem by remember(catalogProject) { mutableStateOf<Problem?>(null) }
    var reload by remember(catalogProject) { mutableIntStateOf(0) }
    LaunchedEffect(state) { if (!state.loaded) state.refresh() }
    LaunchedEffect(catalogProject, reload) {
        if (catalogProject == null) return@LaunchedEffect
        try {
            options = catalogProject.get("/teams/member-options").jsonObject
            catalogProblem = null
        } catch (error: Exception) {
            catalogProblem = error.problem("agentSettings.modelCatalogError")
        }
    }
    LaunchedEffect(saved) { draft.clearSaved(saved) }

    // §10.11: use scoped backend suggestions, but save the strength table globally.
    val connections = state.connections.mapNotNull { entry -> entry.string("id")?.let { "$API_BACKEND_PREFIX$it" to entry } }.toMap()
    val defaults = connections.mapValues { it.value.string("model").orEmpty() }
    val available = options?.obj("agent_backends").orEmpty()
    val backends = (available.keys + saved.keys).filterNot { it.startsWith(API_BACKEND_PREFIX) } + connections.keys
    val labels = backends.associateWith { backend ->
        connections[backend]?.string("name") ?: (available[backend] as? JsonObject)?.string("label") ?: backend
    }
    var preferred by remember { mutableStateOf<String?>(null) }
    val chosen = preferred?.takeIf { it in backends } ?: backends.firstOrNull()
    val enabled = !workspace.preferenceBusy
    val payload = draft.payload(saved, defaults, backends.toSet())

    SettingsSection("agentSettings.orchestrator", tailPadding) {
        Muted(m["agentSettings.orchestratorHint"])
        if (chosen == null) {
            Muted(m[if (workspace.booting) "loading" else "agentSettings.noBackends"])
        } else {
            RuntimeDropdown(labels.getValue(chosen), labels, chosen, enabled) { preferred = it }
            val catalog = connections[chosen]?.let { entry ->
                (listOfNotNull(entry.string("model")) + entry.stringList("models")).distinct().associateWith { it }
            } ?: options?.objects("suggested_models").orEmpty().mapNotNull { entry ->
                val backend = entry.string("backend")
                val value = entry.string("id") ?: return@mapNotNull null
                if (backend == null || backend == chosen) value to (entry.string("label") ?: value) else null
            }.toMap()
            val suggestions = catalog.filterKeys(::validOrchestratorModel)
            tierKeys.forEach { tier ->
                val stored = saved.obj(chosen)?.string(tier) ?: defaults[chosen].orEmpty()
                val label = m["agentSettings.strength"].replace("{tier}", tier)
                val field = draft.field(chosen, tier, stored)
                RuntimeField(label, field, enabled) { draft.edit(chosen, tier, stored, it) }
                if (suggestions.isNotEmpty()) RuntimeDropdown(m["agentSettings.chooseModel"], suggestions, field.text, enabled) { id ->
                    draft.edit(chosen, tier, stored, TextFieldValue(id))
                }
                if (!validOrchestratorModel(field.text.trim())) RuntimeIssue(m["agentSettings.concreteModel"])
            }
        }
        catalogProblem?.let {
            ProblemText(it)
            LibraryButton("refresh-cw", m["retry"]) { reload++ }
        }
        if (draft.dirty && payload == null) RuntimeIssue(m["agentSettings.invalidMapping"])
        val body = payload
        WorkAction(m["saveDocument"], null, enabled = enabled && body != null, primary = true,
            onClick = { body?.let(workspace::applyPreference) })
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RuntimeField(label: String, value: TextFieldValue, enabled: Boolean, secret: Boolean = false,
    multiline: Boolean = false, update: (TextFieldValue) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Muted(label)
        val field = Modifier.fillMaxWidth().semantics { contentDescription = label }
        if (multiline) TextArea(value, update, field.heightIn(min = 72.dp, max = 130.dp), enabled = enabled)
        else StudioField(label, value, update, Modifier.fillMaxWidth(), enabled = enabled,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun RuntimeDropdown(label: String, items: Map<String, String>, selected: String, enabled: Boolean, leadingGlyph: Boolean = false,
    choose: (String) -> Unit) {
    StudioChoice(label, selected, items, label, Modifier.widthIn(max = 380.dp), enabled,
        visibleLabel = label,
        icons = if (leadingGlyph) items.keys.filter(String::isNotEmpty).associateWith(::agentConnectionIcon) else emptyMap(),
        onSelect = choose)
}

@Composable
private fun RuntimeIssue(message: String) = Text(message, color = localColors.current.warning)
