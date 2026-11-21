package studio.voxarium.desktop

import java.net.URI
import java.util.Locale
import java.util.ResourceBundle
import kotlin.test.*
import kotlinx.serialization.json.*

class NativePluginPagesTest {
    @Test
    fun onlyRegisteredBuiltInPageMatches() {
        val descriptor = jsonFields("id" to "graphify-settings", "plugin_id" to "graphify", "route" to "/plugins/graphify/settings")
        assertEquals(NativePluginPage.GRAPHIFY, nativePluginPage(descriptor))
        assertNull(nativePluginPage(JsonObject(descriptor + ("plugin_id" to JsonPrimitive("another-plugin")))))
        assertNull(nativePluginPage(JsonObject(descriptor + ("route" to JsonPrimitive("/plugins/custom/settings")))))
    }

    @Test
    fun settingsKeepUnknownDataAndSerializeKnownTypesAndLegacyValues() {
        val previous = buildJsonObject {
            put("hunyuan_face_count", 9000)
            put("hunyuan_remove_background", false)
            put("transport_override", buildJsonObject { put("custom", 5) })
        }
        val payload = pluginSettingsPayload(previous, hunyuanFields, mapOf("hunyuan_poll_interval" to "2,5"), keepBlank = true)
        assertEquals(previous["transport_override"], payload["transport_override"])
        assertEquals(JsonPrimitive(9000), payload["default_face_count"])
        assertEquals(JsonPrimitive(false), payload["default_remove_background"])
        assertEquals(JsonPrimitive(2.5), payload["hunyuan_poll_interval"])
        assertEquals(JsonPrimitive("512"), payload["default_texture_resolution"])
        assertFailsWith<IllegalArgumentException> {
            pluginSettingsPayload(previous, hunyuanFields, mapOf("default_steps" to "-1"), keepBlank = true)
        }
        val cleared = pluginSettingsPayload(jsonFields("path" to "docs", "future" to "preserved"), graphifyFields,
            mapOf("path" to "  "), keepBlank = false)
        assertFalse("path" in cleared)
        assertEquals("preserved", cleared.string("future"))
    }

    @Test
    fun healthAppendsToPinnedAddressAndRejectsCredentialsAndNonHttp() {
        assertEquals(URI("https://studio.example/hunyuan/health"), hunyuanHealthUri(" https://studio.example/hunyuan/ "))
        assertFailsWith<IllegalArgumentException> { hunyuanHealthUri("file:///p/secret") }
        assertFailsWith<IllegalArgumentException> { hunyuanHealthUri("https://user:password@example.com") }
        assertFailsWith<IllegalArgumentException> { hunyuanHealthUri("https://example.com/?foo=bar") }
    }

    @Test
    fun pluginMessagesExistInBothLocales() {
        val base = ResourceBundle.getBundle("native_plugins", Locale.ROOT)
        val russian = ResourceBundle.getBundle("native_plugins", Locale.forLanguageTag("ru"))
        assertEquals(base.keySet(), russian.keySet())
        for (key in base.keySet()) assertTrue(russian.getString(key).isNotBlank(), key)
        assertNotEquals(base.getString("preview.nextPage"), russian.getString("preview.nextPage"))
    }
}
