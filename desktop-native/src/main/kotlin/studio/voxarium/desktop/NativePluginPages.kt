package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.ResourceBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

// Dev spec §9.3: dispatch only registered, built-in page identities. Other pages retain the ES-module contract.
internal enum class NativePluginPage(val id: String, val route: String) {
    UNREAL("unreal", "/plugins/unreal/settings"),
    GRAPHIFY("graphify", "/plugins/graphify/settings"),
    HUNYUAN3D("hunyuan3d", "/plugins/hunyuan3d/settings"),
}

internal fun nativePluginPage(descriptor: JsonObject) = NativePluginPage.entries.firstOrNull {
    descriptor.string("id") == "${it.id}-settings" && descriptor.string("plugin_id") == it.id &&
        descriptor.string("route") == it.route
}

internal class PluginWords(language: String, private val namespace: String) {
    private val bundle = ResourceBundle.getBundle("native_plugins", Locale.forLanguageTag(language),
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))

    operator fun get(key: String): String = bundle.getString("$namespace.$key")

    fun text(key: String, vararg values: Pair<String, Any?>): String =
        values.fold(this[key]) { message, (name, value) -> message.replace("{$name}", value?.toString().orEmpty()) }
}

internal data class PluginField(
    val key: String,
    val message: String,
    val initial: String = "",
    val kind: Kind = Kind.TEXT,
    val choices: List<String> = emptyList(),
    val placeholder: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val legacyKey: String? = null,
    val labelledChoices: Boolean = false,
    val wrapSelectedValue: Boolean = false,
) {
    enum class Kind { TEXT, INTEGER, NUMBER, SWITCH, CHOICE }

    fun read(settings: JsonObject): String {
        val stored = (settings[key] ?: legacyKey?.let(settings::get)) as? JsonPrimitive ?: return initial
        val value = stored.contentOrNull ?: return initial
        return value.takeIf { this.value(it) != null } ?: initial
    }

    fun value(text: String): JsonPrimitive? = when (kind) {
        Kind.TEXT -> JsonPrimitive(text.trim())
        Kind.SWITCH -> text.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        Kind.CHOICE -> text.takeIf(choices::contains)?.let(::JsonPrimitive)
        Kind.INTEGER -> text.trim().toIntOrNull()?.takeIf { withinRange(it.toDouble()) }?.let(::JsonPrimitive)
        Kind.NUMBER -> text.trim().replace(',', '.').toDoubleOrNull()?.takeIf(::withinRange)?.let(::JsonPrimitive)
    }

    private fun withinRange(value: Double): Boolean = value.isFinite() &&
        (min == null || value >= min) && (max == null || value <= max)
}

internal fun pluginSettingsPayload(
    stored: JsonObject,
    fields: List<PluginField>,
    values: Map<String, String>,
    keepBlank: Boolean,
): JsonObject = buildJsonObject {
    val visibleKeys = fields.mapTo(mutableSetOf(), PluginField::key)
    stored.filterKeys { it !in visibleKeys }.forEach(::put)
    fields.forEach { field ->
        val raw = values[field.key] ?: field.read(stored)
        if (keepBlank || raw.isNotBlank() || field.kind != PluginField.Kind.TEXT) {
            put(field.key, requireNotNull(field.value(raw)) { field.key })
        }
    }
}

private val unrealFields = listOf(
    PluginField("project", "fields.project", placeholder = "D:/Projects/MyGame/MyGame.uproject"),
    PluginField("engine_root", "fields.engineRoot", placeholder = "C:/Program Files/Epic Games/UE_5.7"),
    PluginField("editor_cmd", "fields.editorCmd", placeholder = "UnrealEditor-Cmd.exe"),
    PluginField("command_host", "fields.commandHost", placeholder = "127.0.0.1"),
    choice("transport", "connection.transport", "auto", "remote", "commandlet", labelled = true).copy(wrapSelectedValue = true),
)

internal fun choice(key: String, message: String, initial: String, vararg choices: String, labelled: Boolean = false) =
    PluginField(key, message, initial, PluginField.Kind.CHOICE, listOf(initial, *choices), labelledChoices = labelled)

internal fun toggle(key: String, message: String, initial: Boolean, legacyKey: String? = null) =
    PluginField(key, message, initial.toString(), PluginField.Kind.SWITCH, legacyKey = legacyKey)

internal fun number(key: String, message: String, initial: Number, min: Number, max: Number, legacyKey: String? = null) =
    PluginField(key, message, initial.toString(), if (initial is Int) PluginField.Kind.INTEGER else PluginField.Kind.NUMBER,
        min = min.toDouble(), max = max.toDouble(), legacyKey = legacyKey)

internal class PluginSettingsState(val fields: List<PluginField>) {
    var stored by mutableStateOf<JsonObject?>(null)
        private set
    var failure by mutableStateOf<Problem?>(null)
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    private val edits = mutableStateMapOf<String, TextFieldValue>()

    fun value(field: PluginField) = edits[field.key] ?: TextFieldValue(field.read(stored ?: JsonObject(emptyMap())))
    fun select(field: PluginField, value: String) = edit(field, TextFieldValue(value))
    fun edit(field: PluginField, value: TextFieldValue) {
        edits[field.key] = value
        saved = false
    }

    val valid: Boolean get() = fields.all { it.value(value(it).text) != null }

    suspend fun load(api: ProjectApi, endpoint: String) {
        failure = null
        try {
            stored = api.get(endpoint).jsonObject.obj("settings") ?: JsonObject(emptyMap())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = error.problem()
        }
    }

    suspend fun save(api: ProjectApi, endpoint: String, keepBlank: Boolean, onSaved: () -> Unit) {
        val previous = stored ?: return
        if (!valid || saving) return
        failure = null
        saving = true
        try {
            val settings = pluginSettingsPayload(previous, fields, edits.mapValues { it.value.text }, keepBlank)
            api.put(endpoint, buildJsonObject { put("settings", settings) })
            stored = settings
            saved = true
            onSaved()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = error.problem()
        } finally {
            saving = false
        }
    }
}

@Composable
internal fun NativePluginSettingsPage(page: NativePluginPage, api: ProjectApi, title: String) {
    val m = localMessages.current
    val words = remember(m.language, page) { PluginWords(m.language, page.id) }
    val state = remember(api, page) {
        PluginSettingsState(when (page) {
            NativePluginPage.UNREAL -> unrealFields
            NativePluginPage.GRAPHIFY -> graphifyFields
            NativePluginPage.HUNYUAN3D -> hunyuanFields
        })
    }
    val scope = rememberCoroutineScope()
    val endpoint = "/plugins/${page.id}/settings"
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(state, retry) { state.load(api, endpoint) }
    val save: (() -> Unit) -> Unit = { saved ->
        scope.launch { state.save(api, endpoint, keepBlank = page == NativePluginPage.HUNYUAN3D, onSaved = saved) }
    }
    when (page) {
        NativePluginPage.UNREAL -> UnrealSettingsPage(api, title, state, words, { retry++ }) { save {} }
        NativePluginPage.GRAPHIFY -> GraphifySettingsPage(api, title, state, words, { retry++ }, save)
        NativePluginPage.HUNYUAN3D -> HunyuanSettingsPage(api, title, state, words, { retry++ }) { save {} }
    }
}

@Composable
internal fun PluginSettingsLayout(title: String, state: PluginSettingsState, words: PluginWords, retry: () -> Unit,
                                  content: @Composable () -> Unit) {
    val m = localMessages.current
    ScrollList(Modifier.fillMaxSize().padding(horizontal = StudioTokens.panePad, vertical = StudioTokens.space4)) {
        item {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                Heading(title, size = StudioTokens.titleSize.value.toInt())
                SettingsHelp(words["intro"])
                state.failure?.let { ProblemText(it) }
                if (state.stored == null) {
                    if (state.failure == null) Muted(m["loading"])
                    else WorkAction(m["refresh"], "refresh-cw", onClick = retry)
                } else {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun PluginFields(fields: List<PluginField>, state: PluginSettingsState, words: PluginWords) {
    fields.forEach { field ->
        key(field.key) { PluginSettingField(field, state, words) }
    }
}

@Composable
internal fun PluginChoice(label: String, values: List<Pair<String, String>>, selected: String, enabled: Boolean,
                          wrapSelectedValue: Boolean = false, pick: (String) -> Unit) {
    StudioSelect(label, selected, values.toMap(), enabled = enabled, wrapSelectedValue = wrapSelectedValue, onSelect = pick)
}

@Composable
private fun PluginSettingField(field: PluginField, state: PluginSettingsState, words: PluginWords) {
    val label = words["${field.message}.label"]
    val value = state.value(field)
    val enabled = !state.saving
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        val hint = words["${field.message}.hint"]
        when (field.kind) {
            PluginField.Kind.SWITCH -> WorkCheckbox(label, checked = value.text == "true", enabled = enabled) { checked ->
                state.select(field, checked.toString())
            }
            PluginField.Kind.CHOICE -> {
                Text(label)
                PluginChoice(label, field.choices.map { it to if (field.labelledChoices) words["${field.message}.$it"] else it },
                    value.text, enabled, wrapSelectedValue = field.wrapSelectedValue) { state.select(field, it) }
            }
            else -> {
                Text(label)
                StudioField(label, value, { state.edit(field, it) }, Modifier.fillMaxWidth(),
                    enabled = enabled, placeholder = field.placeholder)
            }
        }
        SettingsHelp(hint)
        if (field.value(value.text) == null) {
            Text(words.text("range", "min" to field.min?.toString()?.removeSuffix(".0"),
                "max" to field.max?.toString()?.removeSuffix(".0")), color = localColors.current.danger)
        }
    }
}

@Composable
internal fun PluginSaveRow(state: PluginSettingsState, save: () -> Unit) {
    val language = localMessages.current.language
    val words = remember(language) { PluginWords(language, "shared") }
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3), verticalAlignment = Alignment.CenterVertically) {
        WorkAction(words[if (state.saving) "saving" else "save"], "save",
            enabled = !state.saving && state.valid, primary = true, busy = state.saving, onClick = save)
        if (state.saved) Muted(words["saved"])
    }
}

@Composable
private fun UnrealSettingsPage(api: ProjectApi, title: String, state: PluginSettingsState, words: PluginWords,
                               retry: () -> Unit, save: () -> Unit) {
    var nodes by remember(api) { mutableStateOf(emptyList<JsonObject>()) }
    LaunchedEffect(api, retry) {
        try {
            nodes = api.get("/node-types").jsonObject.objects("node_types").filter { it.string("plugin_id") == "unreal" }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }
    PluginSettingsLayout(title, state, words, retry) {
        LibraryCard {
            LibrarySectionTitle(words["connection.title"])
            PluginFields(unrealFields, state, words)
            PluginSaveRow(state, save)
            if (state.value(unrealFields.first()).text.isBlank()) {
                Text(words["connection.noProject"], color = localColors.current.warning)
            }
        }
        LibraryCard {
            LibrarySectionTitle(words["firstRun.title"])
            Text(words.text("firstRun.enablePlugin", "plugin" to "Python Editor Script Plugin"))
            Text(words.text("firstRun.remoteExecution", "transport" to "remote", "setting" to "Enable Remote Execution"))
            Text(words.text("firstRun.formats", "vox" to ".vox", "stl" to ".stl"))
        }
        if (nodes.isNotEmpty()) LibraryCard {
            LibrarySectionTitle(words["nodes"])
            nodes.forEach { node ->
                node.string("id")?.let { Text(it, fontFamily = localStudioMono.current) }
                node.string("description")?.takeIf(String::isNotBlank)?.let { Muted(it) }
            }
        }
    }
}
