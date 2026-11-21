package studio.voxarium.desktop

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkMotionTest {
    private fun json(text: String) = studioJson.parseToJsonElement(text).jsonObject

    @Test
    fun `task activity belongs to its own tile and ignores accounting updates`() {
        val activity = WorkActivityState()
        activity.onEvent(StudioEvent("team.member.message",
            json("""{"task_id":"one","kind":"tool_use","data":{"name":"Read","input":{"path":"a.md"}}}""")))
        activity.onEvent(StudioEvent("team.member.message", json("""{"task_id":"two","kind":"thinking","text":"Reading"}""")))
        activity.onEvent(StudioEvent("team.member.message", json("""{"task_id":"one","kind":"system","text":"usage_update"}""")))
        assertEquals("Read · a.md", activity.task("one")?.message?.text)
        assertEquals("Reading", activity.task("two")?.message?.text)
        activity.onEvent(StudioEvent("team.task.deleted", json("""{"task_id":"one"}""")))
        assertNull(activity.task("one"))
    }

    @Test
    fun `continued turns preserve running workers and terminal frames stop the pulse`() {
        val start = json("""{"kind":"system","text":"task_started","data":{"task_id":"worker","description":"Read tests"}}""")
        val turn = json("""{"kind":"result","data":{"continued":true}}""")
        val end = json("""{"kind":"system","text":"task_updated","data":{"task_id":"worker","patch":{"status":"killed"}}}""")
        assertTrue(delegatedWork(listOf(start, turn), emptyList()).single().running)
        assertFalse(delegatedWork(listOf(start, turn, end), emptyList()).single().running)
    }

    @Test
    fun `older production frames cannot overwrite a newer durable worker snapshot`() {
        val finished = json("""{"id":"worker","description":"Read tests","status":"completed","updated_at":"2026-11-20T16:00:01Z"}""")
        val stale = json("""{"kind":"system","text":"task_started","created_at":"2026-11-20T16:00:00Z","data":{"task_id":"worker"}}""")
        val worker = delegatedWork(listOf(stale), listOf(finished)).single()
        assertEquals("Read tests", worker.description)
        assertFalse(worker.running)
    }
}
