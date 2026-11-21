package studio.voxarium.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskBoardDragTest {
    private fun task(status: String, archived: Boolean = false, running: Boolean = false) = buildJsonObject {
        put("status", status)
        put("archived", archived)
        put("is_running", running)
    }

    @Test
    fun waitingDefaultsToPendingAndKeepsTasksAlreadyInThatColumn() {
        val waiting = listOf("pending", "needs_review", "blocked")
        assertEquals(jsonFields("status" to "pending", "summary_locale" to "ru"),
            taskColumnPatch(task("todo"), waiting, "ru"))
        assertNull(taskColumnPatch(task("blocked"), waiting, "ru"))
        assertNull(taskColumnPatch(task("needs_review"), waiting, "ru"))
    }

    @Test
    fun archiveOnlyAcceptsFinishedTasksAndClearsOnReturnToBoard() {
        assertNull(taskColumnPatch(task("todo"), emptyList(), "en"))
        assertNull(taskColumnPatch(task("done", archived = true), emptyList(), "en"))
        assertEquals(buildJsonObject { put("archived", true); put("summary_locale", "en") },
            taskColumnPatch(task("done"), emptyList(), "en"))
        assertEquals(buildJsonObject { put("archived", false); put("summary_locale", "en") },
            taskColumnPatch(task("done", archived = true), listOf("done"), "en"))
        assertEquals(buildJsonObject { put("archived", false); put("status", "todo"); put("summary_locale", "en") },
            taskColumnPatch(task("done", archived = true), listOf("todo"), "en"))
    }

    @Test
    fun runningTasksCannotMove() {
        assertNull(taskColumnPatch(task("in_progress", running = true), listOf("done"), "en"))
    }
}
