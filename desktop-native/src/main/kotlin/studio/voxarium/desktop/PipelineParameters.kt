package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import java.util.Locale
import javax.swing.JColorChooser
import javax.swing.JFileChooser
import javax.swing.filechooser.FileFilter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField

internal class PipelineParameterEdit(val text: TextFieldValue?, port: JsonObject?) {
    val value = text?.let { parsePipelineParameter(it.text, port) }
    val valid = text == null || value != null
}

internal fun parsePipelineParameter(raw: String, port: JsonObject?): JsonElement? {
    val type = port?.string("type") ?: "json"
    val number = raw.trim().replace(',', '.')
    return when (type) {
        "integer", "number" -> {
            if (number.isEmpty()) return JsonNull
            val value = if (type == "integer") number.toBigIntegerOrNull()?.toBigDecimal() else number.toBigDecimalOrNull()
            if (value == null) return null
            val minimum = port?.string("minimum")?.toBigDecimalOrNull()
            val maximum = port?.string("maximum")?.toBigDecimalOrNull()
            if (minimum != null && value < minimum || maximum != null && value > maximum) return null
            if (!value.toDouble().isFinite() && type == "number") return null
            JsonPrimitive(value)
        }
        "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        "enum" -> if (raw.isEmpty()) JsonNull else JsonPrimitive(raw).takeIf { it in (port?.get("options") as? JsonArray).orEmpty() }
        "json" -> if (raw.isBlank()) JsonNull else runCatching { studioJson.parseToJsonElement(raw) }.getOrNull()
        else -> JsonPrimitive(raw)
    }
}

internal fun pipelineParameterText(value: JsonElement?, port: JsonObject?): String = when {
    value == null || value == JsonNull -> ""
    port == null || port.string("type") == "json" -> value.toString()
    value is JsonPrimitive -> value.content
    else -> value.toString()
}

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun PipelineParamEditor(
    label: String,
    explicit: JsonElement?,
    port: JsonObject?,
    edit: PipelineParameterEdit?,
    enabled: Boolean,
    root: String,
    controls: @Composable RowScope.() -> Unit = {},
    update: (TextFieldValue?) -> Unit,
) {
    val m = localMessages.current
    val colors = localColors.current
    val type = port?.string("type") ?: "json"
    val inherited = port?.get("default")
    val current = pipelineParameterText(if (edit != null) edit.value ?: inherited else explicit ?: inherited, port)
    var field by remember(label, current) { mutableStateOf(TextFieldValue(current)) }
    var browseProblem by remember(label, root) { mutableStateOf<Problem?>(null) }
    val shown = edit?.text ?: field
    val editable = Modifier.semantics { contentDescription = label }
    fun change(next: TextFieldValue) {
        if (next.text == shown.text && edit?.text == null) field = next else update(next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            Text(if (port?.flag("required") == true) "$label *" else label, Modifier.weight(1f))
            Muted(type, StudioTokens.textTiny.value.toInt())
            controls()
        }
        when (type) {
            "boolean" -> {
                val checked = (if (edit == null) explicit ?: inherited else edit.value ?: inherited)
                    .let { it as? JsonPrimitive }?.booleanOrNull == true
                WorkCheckbox(m[if (checked) "pipelineFieldEnabled" else "pipelineFieldDisabled"], checked, editable, enabled) {
                    update(TextFieldValue(it.toString()))
                }
            }
            "enum" -> StudioSelect(label, shown.text, buildMap {
                (port?.get("options") as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().forEach { put(it.content, it.content) }
                put("", "—")
            }, enabled = enabled) { update(TextFieldValue(it)) }
            "json", "text" -> TextArea(shown, ::change, editable.fillMaxWidth().heightIn(min = 78.dp, max = 160.dp), enabled = enabled)
            else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                StudioField(label, shown, ::change, Modifier.weight(1f), enabled = enabled)
                if (type in setOf("file", "directory", "path")) {
                    PipelineButton("folder-open", m["browse"], enabled, iconOnly = true) {
                        browseProblem = null
                        runCatching { browsePipelinePath(root, type, shown.text, port) }
                            .onSuccess { chosen -> chosen?.let { update(TextFieldValue(it)) } }
                            .onFailure { browseProblem = Problem("pipelineFieldOutsideProject") }
                    }
                }
                if (type == "color") {
                    val color = shown.text.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
                    if (color != null) Spacer(Modifier.size(StudioTokens.iconSize).background(Color(0xff000000 or color.toLong()),
                        RoundedCornerShape(StudioTokens.radiusSm)))
                    PipelineButton("palette", m["pipelineFieldPickColor"], enabled, iconOnly = true) {
                        choosePipelineColor(shown.text, m["pipelineFieldPickColor"])?.let { update(TextFieldValue(it)) }
                    }
                }
            }
        }
        browseProblem?.let { ProblemText(it) }
        if (edit?.valid == false) Text(m["pipelineFieldInvalid"], color = colors.danger, fontSize = StudioTokens.textSmall)
        port?.string("description")?.takeIf(String::isNotBlank)?.let { Muted(it) }
        if (enabled && (edit?.text != null || edit == null && explicit != null)) {
            PipelineButton("", m["pipelineFieldReset"], compact = true) { update(null) }
        }
    }
}

private fun browsePipelinePath(project: String, type: String, current: String, port: JsonObject?): String? {
    val root = Path.of(project).toRealPath()
    val existing = runCatching { root.resolve(current).toRealPath() }.getOrNull()?.takeIf { it.startsWith(root) }
    val chooser = JFileChooser(root.toFile()).apply {
        fileSelectionMode = when (type) {
            "file" -> JFileChooser.FILES_ONLY
            "directory" -> JFileChooser.DIRECTORIES_ONLY
            else -> JFileChooser.FILES_AND_DIRECTORIES
        }
        val extensions = (port?.get("accept") as? JsonArray).orEmpty()
            .filterIsInstance<JsonPrimitive>().map { it.content.lowercase(Locale.ROOT) }.filter(String::isNotBlank)
        if (extensions.isNotEmpty() && type != "directory") {
            fileFilter = object : FileFilter() {
                override fun getDescription(): String = extensions.joinToString(", ")
                override fun accept(file: java.io.File): Boolean = file.isDirectory ||
                    extensions.any { file.name.lowercase(Locale.ROOT).endsWith(it) }
            }
        }
        existing?.let { selectedFile = it.toFile() }
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val path = chooser.selectedFile.toPath().toRealPath()
    require(path.startsWith(root))
    return root.relativize(path).toString().replace('\\', '/').ifEmpty { "." }
}
internal fun choosePipelineColor(initial: String, label: String): String? {
    val rgb = initial.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0xffffff
    val selected = JColorChooser.showDialog(null, label, java.awt.Color(rgb)) ?: return null
    return String.format(Locale.ROOT, "#%06x", selected.rgb and 0xffffff)
}

