package studio.voxarium.desktop

import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeProducerAttentionTest {
    private val rootA = Path.of("").toAbsolutePath().resolve("project-a").toString()
    private val rootB = Path.of("").toAbsolutePath().resolve("project-b").toString()

    private fun attention(id: String, root: String, chat: String? = "same-chat", target: String? = chat?.let { "/producer?chat=$it" }) =
        buildJsonObject {
            putJsonObject("project") {
                put("project_id", id)
                put("name", id)
                put("root", root)
                put("epoch", "epoch")
            }
            put("source_seq", 1)
            put("target", "/producer")
            putJsonArray("waiting") {
                if (chat != null) add(buildJsonObject { put("chat_id", chat); put("target", target) })
            }
        }

    private fun frame(type: String, seq: Int?, data: JsonObject, epoch: String = "epoch") = buildJsonObject {
        put("type", type)
        put("epoch", epoch)
        if (seq != null) put("seq", seq)
        put("data", data)
    }

    private fun hello(vararg items: JsonObject) = frame("system.hello", 2, buildJsonObject {
        put("epoch", "epoch"); put("resumed_from", JsonNull); put("attention", JsonArray(items.toList()))
    })

    @Test
    fun identicalChatIdsInTwoProjectsKeepTheirOwnTabAndOnlyClearTheirProject() {
        val stream = NativeAttentionStream()
        val snapshot = assertNotNull(stream.receive(hello(attention("a", rootA), attention("b", rootB))))
        val matching = snapshot.first { it.id == "b" }
        assertTrue(matching.matches(ProjectBinding(rootB, "b", "b", "epoch")))
        assertFalse(matching.matches(ProjectBinding(rootA, "a", "b", "epoch")))
        assertFalse(matching.matches(ProjectBinding(rootB, "b", "b", "old-epoch")))
        assertEquals(Page("/producer", Selectors(chat = "same-chat")), matching.page)
        assertEquals(listOf(matching), stream.receive(frame("desktop.producer_attention", 3, attention("a", rootA, null))))
        assertNull(stream.receive(frame("desktop.producer_attention", 3, attention("a", rootA))))
    }

    @Test
    fun gapsReplaceAttentionAndExcludeForeignOrInvalidTargets() {
        val stream = NativeAttentionStream()
        assertNull(stream.receive(frame("desktop.producer_attention", 1, attention("a", rootA))))
        assertNull(stream.receive(hello(attention("a", rootA, target = "/producer?chat=same-chat&root=elsewhere"))))
        assertNotNull(stream.receive(hello(attention("a", rootA))))
        assertNull(stream.receive(frame("desktop.producer_attention", 3, attention("b", rootB), epoch = "other")))
        val gap = frame("system.dropped", null, buildJsonObject {
            put("resync", true); put("reason", "overflow"); put("count", 2); put("snapshot_seq", 8)
            put("attention", JsonArray(listOf(attention("b", rootB))))
        })
        assertEquals(listOf("b"), stream.receive(gap)?.map { it.id })
        assertNull(stream.receive(frame("desktop.producer_attention", 7, attention("a", rootA))))
        assertNull(stream.receive(hello(attention("a", rootA))))
    }
}
