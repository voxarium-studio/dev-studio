package studio.voxarium.desktop

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class AgentRuntimeSettingsTest {
    @Test
    fun keysAreEmptyInAnEditAndOnlyAnExplicitUpdateSendsAKey() {
        val draft = ApiConnectionDraft(connection())
        assertTrue(draft.hasKey)
        assertTrue(draft.apiKey.text.isEmpty())
        assertFalse("api_key" in draft.payload())
        draft.apiKey = TextFieldValue("replacement")
        assertEquals("replacement", draft.payload().string("api_key"))
        draft.clearKey = true
        assertEquals("", draft.payload().string("api_key"))
        assertFalse("has_api_key" in draft.payload())
    }

    @Test
    fun catalogNormalizesIdsAndAnInvalidDisabledLimitDoesNotBlockSaving() {
        val draft = ApiConnectionDraft(connection())
        draft.models = TextFieldValue("model-b\n\nmodel-c\r\n model-b \n")
        draft.context = TextFieldValue("")
        draft.useLimits = false
        draft.tpm = TextFieldValue("invalid-inactive-draft")
        assertTrue(draft.valid)
        val body = draft.payload()
        assertEquals(listOf("model-b", "model-c"), body.stringList("models"))
        assertEquals(JsonNull, body["context_window"])
        assertFalse("tpm" in body)
        draft.useLimits = true
        assertFalse(draft.valid)
        draft.tpm = TextFieldValue("5000")
        assertEquals(5000, (draft.payload()["tpm"] as JsonPrimitive).int)
        draft.url = TextFieldValue("https://user:password@example.invalid/v1")
        assertFalse(draft.valid)
    }

    @Test
    fun orchestratorWritesOnlyChangedColumnsAndRequiresConcreteValues() {
        val stored = studioJson.parseToJsonElement("""
            {"claude_code":{"1":"fast","2":"standard","3":"strong"},"codex":{"1":"fast","2":"standard","3":"strong"}}
        """.trimIndent()).jsonObject
        val draft = OrchestratorDraft()
        val defaults = mapOf("apiA" to "default-model-a", "apiB" to "default-model-b")
        val columns = stored.keys + defaults.keys
        draft.edit("apiA", "2", "default-model-a", TextFieldValue("AUTO"))
        assertNull(draft.payload(stored, defaults, columns))
        draft.edit("apiA", "2", "default-model-a", TextFieldValue(" provider/new-model:+1 "))
        val ready = assertNotNull(draft.payload(stored, defaults, columns)?.obj("orchestrator_models"))
        assertEquals(stored["claude_code"], ready["claude_code"])
        assertEquals(stored["codex"], ready["codex"])
        assertNull(ready["apiB"])
        val apiColumn = assertNotNull(ready.obj("apiA"))
        assertEquals("default-model-a", apiColumn.string("1"))
        assertEquals("provider/new-model:+1", apiColumn.string("2"))
        assertEquals("default-model-a", apiColumn.string("3"))
        draft.clearSaved(ready)
        assertFalse(draft.dirty)
    }

    private fun connection() = studioJson.parseToJsonElement("""
        {"id":"9fed2ac2-a7eb-43c1-b2b0-65bd22100ca0","name":"Example","base_url":"https://example.invalid/v1",
        "model":"model-a","has_api_key":true,"api_key":"unexpected-field","api_mode":"responses"}
    """.trimIndent()).jsonObject
}
