package studio.voxarium.desktop

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.*
import kotlin.test.*

class McpPageTest {
    @Test
    fun editingACommandPreservesExistingArgsAndEnvironment() {
        val server = studioJson.parseToJsonElement("""
            {"name":"docs","type":"stdio","command":"agent","args":["", " some value "],"env":{"OPTIONS":"first\nsecond"}}
        """.trimIndent()).jsonObject
        val payload = McpForm(server).copy(command = "agent-next").payload(server)
        assertEquals("agent-next", payload.string("command"))
        assertEquals(server["args"], payload["args"])
        assertEquals(server["env"], payload["env"])
    }

    @Test
    fun editsKeepSpacesInArgumentsAndEqualsSignsInValues() {
        val payload = McpForm(command = "agent", args = "--path\nC:/Game Project", env = "FLAGS=a=b").payload()
        assertEquals(JsonArray(listOf(JsonPrimitive("--path"), JsonPrimitive("C:/Game Project"))), payload["args"])
        assertEquals("a=b", payload.obj("env")?.string("FLAGS"))
        assertFailsWith<IllegalArgumentException> { McpForm(env = "FLAGS").payload() }
    }

    @Test
    fun aRefreshKeepsAnMcpDraftAndItsOriginalVersion() {
        val state = McpPageState()
        state.accept(jsonFields("text" to "old"))
        state.openRaw("old")
        state.rawDraft = TextFieldValue("draft")
        state.accept(jsonFields("text" to "changed externally"))
        assertTrue(state.hasUnsavedChanges)
        assertEquals("old", state.rawBase)
        assertEquals("draft", state.rawDraft.text)
        state.openRaw(state.overview?.string("text").orEmpty())
        assertFalse(state.hasUnsavedChanges)
        assertEquals("changed externally", state.rawDraft.text)
    }
}
