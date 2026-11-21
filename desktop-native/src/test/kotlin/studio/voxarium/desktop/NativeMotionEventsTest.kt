package studio.voxarium.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeMotionEventsTest {
    @Test
    fun progressIsolatedByRunAndClearedWhenWorkEnds() {
        val state = PipelineProgressState()
        val progress = buildJsonObject { put("percent", 43) }
        for (run in listOf("first", "other")) {
            state.onEvent(StudioEvent("node.progress", buildJsonObject {
                put("run_id", run)
                put("node_id", "mesh")
                put("progress", progress)
            }))
        }
        state.onEvent(StudioEvent("node.status_changed",
            jsonFields("run_id" to "first", "node_id" to "mesh", "status" to "success")))
        assertNull(state.frame("first", "mesh"))
        assertEquals(progress, state.frame("other", "mesh"))
        state.onEvent(StudioEvent("run.cancelled", jsonFields("run_id" to "other")))
        assertNull(state.frame("other", "mesh"))
    }

    @Test
    fun notificationOpensNativeScopedDestinationsWithLiteralSelectors() {
        assertEquals(Page("/tasks", Selectors(task = "abcd")), automationToastRoute("/tasks?task=abcd"))
        val literal = "Models/100% #land+water.vox"
        assertEquals(Page("/explorer", Selectors(path = literal)),
            automationToastRoute("/explorer?path=$literal", literal))
        assertEquals(Page("/explorer"), automationToastRoute("/explorer", literal))
        assertEquals(Page("/pipelines/demo-graph"), automationToastRoute("/pipelines/demo-graph"))
        assertNull(automationToastRoute("//other.example/tasks?task=abcd"))
    }
}
