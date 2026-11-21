package studio.voxarium.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ChatRuntimeTest {
    @Test
    fun attachmentOnlyMessageCarriesTheUploadReferenceAndProjectPage() {
        val attachment = TaskAttachment("design.png", "upload_${"a".repeat(32)}")
        val draft = ChatDraft("", listOf(attachment))
        assertTrue(draft.hasContent)
        assertFalse(ChatDraft("  ", emptyList()).hasContent)
        val request = draft.request(ChatLocation(Page("/documents", Selectors(path = "design/note.md")), "Notes"))
        assertEquals(JsonArray(listOf(JsonPrimitive(attachment.reference))), request["attachments"])
        assertEquals("/documents", request.obj("context")?.string("route"))
        assertEquals("design/note.md", request.obj("context")?.string("document"))
    }

    @Test
    fun switchingToInheritingClearsBothOverridesButOtherBackendsUseTheirDefault() {
        val choice = ChatRuntimeChoice("codex", "custom")
        val inherit = choice.forBackend("claude_code", "claude_code").request()
        assertEquals(JsonNull, inherit["agent_backend"])
        assertEquals(JsonNull, inherit["model"])
        val selected = choice.forBackend("openai_api:local", "claude_code").request()
        assertEquals("openai_api:local", selected.string("agent_backend"))
        assertEquals("", selected.string("model"))
        val options = buildJsonObject {
            put("suggested_models", JsonArray(listOf(
                jsonFields("id" to "codex-model", "backend" to "codex"),
                jsonFields("id" to "runtime-model", "backend" to "openai_api:local"),
            )))
        }
        assertEquals(listOf("runtime-model"), chatModels(options, "openai_api:local").map { it.string("id") })
    }

    @Test
    fun newerUnknownContextAndCompactionDoNotReuseOldUsage() {
        val known = buildJsonObject {
            put("kind", "system")
            put("text", "usage_update")
            putJsonObject("data") {
                put("context_tokens", 10)
                put("context_window", 100)
            }
        }
        val unknown = buildJsonObject {
            put("kind", "system")
            put("text", "usage_update")
            putJsonObject("data") { put("context_window", 200) }
        }
        assertEquals(ChatContextUsage(10, 100), chatContextUsage(listOf(known)))
        assertEquals(ChatContextUsage(null, null), chatContextUsage(listOf(known, unknown)))
        assertEquals(ChatContextUsage(null, null), chatContextUsage(listOf(known, jsonFields("kind" to "context_summary"))))
    }

    @Test
    fun resizingFitsTheCurrentWindowAndPreservesTheUntouchedAxis() {
        val desired = ChatPanelSize(720, 840)
        val available = ChatPanelSize(630, 590)
        assertEquals(ChatPanelSize(630, 590), desired.fit(available))
        assertEquals(ChatPanelSize(720, 510), desired.resize(ChatPanelEdge.TOP, desired.fit(available), 110f, 80f, available))
        assertEquals(ChatPanelSize(460, 510), desired.resize(ChatPanelEdge.CORNER, desired.fit(available), 170f, 80f, available))
        assertEquals(ChatPanelSize(200, 260), desired.fit(ChatPanelSize(200, 260)))
    }

    @Test
    fun selectedTextIsBoundToItsPageAndIsNotClearedByALateSendOfAnOlderSelection() {
        val first = Page("/documents", Selectors(path = "design/start.md"))
        val second = Page("/documents", Selectors(path = "design/end.md"))
        val selection = ChatContextSelection()
        selection.update(first, "  older text  ")
        assertNull(selection.text(second))
        val sent = ChatDraft("Explain this", emptyList()).request(ChatLocation(first, selection = selection.text(first)))
        assertEquals("older text", sent.obj("context")?.string("selection"))
        selection.update(first, "newer text")
        selection.clear(first, "older text")
        assertEquals("newer text", selection.text(first))
        selection.update(first, "a".repeat(15_999) + "😀 and more")
        assertEquals(15_999, selection.text(first)?.length)
        selection.clear(first)
        assertNull(selection.text(first))
    }

    @Test
    fun onlyRepliesToUnseenConversationsIncrementTheBadge() {
        val state = FloatingChatState()
        fun message(id: String, role: String) = StudioEvent("team.chat.message", jsonFields("chat_id" to id, "role" to role, "kind" to "text"))
        state.onEvent(message("design", "user"))
        assertEquals(0, state.unreadCount)
        state.onEvent(message("design", "assistant"))
        state.onEvent(message("design", "assistant"))
        assertEquals(1, state.unreadCount)
        state.watch("design")
        state.watch("design")
        state.unwatch("design")
        state.onEvent(message("design", "assistant"))
        assertFalse(state.isUnread("design"))
        state.unwatch("design")
        state.onEvent(message("design", "assistant"))
        assertTrue(state.isUnread("design"))
        state.onEvent(StudioEvent("team.chat.deleted", jsonFields("chat_id" to "design")))
        assertEquals(0, state.unreadCount)
    }
}
