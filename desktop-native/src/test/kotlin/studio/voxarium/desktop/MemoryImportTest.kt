package studio.voxarium.desktop

import kotlinx.serialization.json.*
import kotlin.test.*

class MemoryImportTest {
    private val binding = ProjectBinding("C:/project", "Project", "project", "test")

    @Test
    fun defaultImportDoesNotReplaceOrCopyUnrelatedGlobalMemory() {
        val state = MemoryImportState(binding)
        state.accept(sources(
            candidate("project", "new"), candidate("related", "new", "global", true),
            candidate("personal", "new", "global"), candidate("replacement", "differs"), candidate("same", "same"),
        ), preselect = true)
        assertEquals(listOf("project", "related"), state.payload().objects("items").map { it.string("id") })
        assertTrue(state.payload().objects("items").none { it.flag("replace") })
        state.change("replacement", true)
        assertTrue(state.payload().objects("items").last().flag("replace"))
    }

    @Test
    fun aNewConflictMustBeSelectedExplicitlyAfterRefreshing() {
        val state = MemoryImportState(binding)
        state.accept(sources(candidate("entry", "new")), preselect = true)
        assertEquals(1, state.choices.size)
        state.accept(sources(candidate("entry", "differs")), preselect = false)
        assertTrue(state.choices.isEmpty())
    }

    private fun sources(vararg candidates: JsonObject) = buildJsonObject {
        putJsonArray("sources") { addJsonObject { put("candidates", JsonArray(candidates.toList())) } }
    }

    private fun candidate(id: String, state: String, scope: String = "project", related: Boolean = false) = buildJsonObject {
        put("id", id)
        put("state", state)
        put("scope", scope)
        put("related", related)
    }
}
