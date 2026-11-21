package studio.voxarium.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.jewel.ui.component.Text

private val themeChoices = mapOf(
    "mode" to setOf("dark", "light", "system"),
    "mono_font" to setOf("cascadia", "jetbrains", "fira", "system"),
    "sidebar" to setOf("labels", "icons", "auto"),
    "canvas_grid" to setOf("dots", "lines", "none"),
    "status_palette" to setOf("default", "accessible"),
)
private val themeSizes = mapOf("text_size" to 11..18, "node_tint" to 0..60)
private val tokenName = Regex("--[a-zA-Z0-9_-]+")
private val tokenPunctuation = Regex("[;{}]")
private val legacyAccent = Regex("#[0-9a-fA-F]{6}")
private val themeJson = Json { prettyPrint = true }

private fun validTokenValue(value: String) = value.length <= 200 && !tokenPunctuation.containsMatchIn(value)
private fun JsonElement.stringValue() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// §5.2 and §7: match the Vue appearance file and write only a patch to application settings.
internal fun exportNativeTheme(theme: JsonObject): String = themeJson.encodeToString(JsonObject(
    theme.filterKeys { it in themeChoices || it in themeSizes || it == "custom_tokens" },
)) + "\n"

internal fun parseNativeTheme(text: String): JsonObject {
    val source = Json.parseToJsonElement(text) as? JsonObject ?: error("theme object required")
    val patch = buildJsonObject {
        for ((name, value) in source) {
            val choices = themeChoices[name]
            val range = themeSizes[name]
            when {
                choices != null -> {
                    require(value.stringValue() in choices)
                    put(name, value)
                }
                name == "text_size" && value == JsonNull -> put(name, value)
                range != null -> {
                    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                    require(number != null && number.toInt() in range && number == number.toInt().toDouble())
                    put(name, number.toInt())
                }
                name == "custom_tokens" -> {
                    require(value is JsonObject)
                    putJsonObject(name) {
                        for ((key, raw) in value) {
                            val property = raw.stringValue()
                            require(tokenName.matches(key) && property != null && validTokenValue(property))
                            put(key, property.trim())
                        }
                    }
                }
                name == "accent" -> require(value.stringValue()?.let(legacyAccent::matches) == true)
            }
        }
    }
    require(patch.isNotEmpty())
    return patch
}

private data class TokenDraft(val id: Int, val name: TextFieldValue, val value: TextFieldValue) {
    val valid: Boolean get() = tokenName.matches(name.text.trim()) && validTokenValue(value.text)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ThemeSettings(workspace: Workspace, theme: JsonObject?, enabled: Boolean) {
    val m = localMessages.current
    val colors = localColors.current
    val scope = rememberCoroutineScope()
    val owner = LocalAwtWindow.current
    val saved = theme?.obj("custom_tokens") ?: JsonObject(emptyMap())
    val rows = remember(saved) {
        mutableStateListOf<TokenDraft>().apply {
            saved.entries.forEachIndexed { index, (name, value) ->
                add(TokenDraft(index, TextFieldValue(name), TextFieldValue(value.stringValue().orEmpty())))
            }
        }
    }
    var nextId by remember(saved) { mutableIntStateOf(rows.size) }
    var fileBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = enabled && !fileBusy && theme != null
    val draft = buildJsonObject { rows.forEach { put(it.name.text.trim(), it.value.text.trim()) } }
    val valid = rows.all(TokenDraft::valid) && draft.size == rows.size

    fun update(patch: JsonObject) = workspace.applyPreference(buildJsonObject { put("theme", patch) })

    fun choose(save: Boolean): File? {
        val dialog = JFileChooser().apply {
            dialogTitle = m[if (save) "themeSettings.export" else "themeSettings.import"]
            fileFilter = FileNameExtensionFilter("JSON", "json")
            if (save) selectedFile = File("voxarium-studio-theme.json")
        }
        val result = if (save) dialog.showSaveDialog(owner) else dialog.showOpenDialog(owner)
        if (result != JFileChooser.APPROVE_OPTION) return null
        val selected = dialog.selectedFile
        if (!save) return selected
        val file = if (selected.extension.isEmpty()) File(selected.parentFile, selected.name + ".json") else selected
        if (file.exists() && JOptionPane.showConfirmDialog(owner, m["themeSettings.overwrite"], dialog.dialogTitle,
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null
        return file
    }

    fun transfer(save: Boolean) {
        val file = choose(save) ?: return
        fileBusy = true
        error = null
        scope.launch {
            try {
                if (save) withContext(Dispatchers.IO) { file.writeText(exportNativeTheme(requireNotNull(theme))) }
                else {
                    val patch = withContext(Dispatchers.IO) { parseNativeTheme(file.readText()) }
                    update(patch)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = if (save) "themeSettings.exportFailed" else "themeSettings.importFailed"
            } finally {
                fileBusy = false
            }
        }
    }

    SettingsRow(m["themeSettings.tokens"]) {
        rows.forEachIndexed { index, row ->
            key(row.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                    StudioField(m["themeSettings.name"], row.name, { rows[index] = row.copy(name = it) },
                        Modifier.weight(1f), enabled = ready, placeholder = "--vxs-bg-surface")
                    StudioField(m["themeSettings.value"], row.value, { rows[index] = row.copy(value = it) },
                        Modifier.weight(1f), enabled = ready, placeholder = "#101114")
                    StudioIconButton(m["themeSettings.remove"], "x", { rows.removeAt(index) }, enabled = ready)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            WorkAction(m["themeSettings.add"], "plus", enabled = ready) {
                rows.add(TokenDraft(nextId++, TextFieldValue("--vxs-"), TextFieldValue()))
            }
            WorkAction(m["saveDocument"], null, enabled = ready && valid && saved != draft) {
                update(buildJsonObject { put("custom_tokens", draft) })
            }
            WorkAction(m["themeSettings.export"], null, enabled = ready) { transfer(true) }
            WorkAction(m["themeSettings.import"], null, enabled = ready) { transfer(false) }
        }
        SettingsHelp(m["themeSettings.hint"])
        if (!valid) Text(m["themeSettings.invalid"], color = colors.warning)
        error?.let { Text(m[it], color = colors.danger) }
    }
}
