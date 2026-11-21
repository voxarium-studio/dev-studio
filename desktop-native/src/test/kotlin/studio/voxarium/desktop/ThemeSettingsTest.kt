package studio.voxarium.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ThemeSettingsTest {
    @Test
    fun oldThemeFilesPatchOnlyApplicationAppearance() {
        val imported = parseNativeTheme("""{
            "mode":"light", "accent":"#a394e8", "text_size":null, "node_tint":32.0,
            "font":"removed", "project_name":"Managed project", "custom_tokens":{"--vxs-bg-surface":" #ffffff "}
        }""")
        assertEquals(Json.parseToJsonElement("""{
            "mode":"light", "text_size":null, "node_tint":32, "custom_tokens":{"--vxs-bg-surface":"#ffffff"}
        }""").jsonObject, imported)
        assertEquals(imported, parseNativeTheme(exportNativeTheme(imported)))
    }

    @Test
    fun rejectsValuesTheWebThemeParserWouldRefuse() {
        listOf("[]", """{"accent":"#a394e8"}""", """{"mode":"sepia"}""", """{"text_size":"14"}""",
            """{"text_size":14.5}""", """{"node_tint":61}""", """{"custom_tokens":{"color":"red"}}""",
            """{"custom_tokens":{"--vxs-bg-surface":"red; color: blue"}}""").forEach { text ->
            assertFails(text) { parseNativeTheme(text) }
        }
    }
}
