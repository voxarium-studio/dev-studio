package studio.voxarium.desktop

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineGraphTest {
    @Test
    fun nodeEditKeepsFreshUnrelatedFieldsAndRejectsConflictingEdits() {
        val original = studioJson.decodeFromString<JsonObject>(
            """{"id":"start","type":"fixture","label":"Start","params":{"path":"old"}}""",
        )
        val fresh = studioJson.decodeFromString<JsonObject>(
            """{"id":"flow","nodes":[{"id":"start","type":"fixture","label":"Start","params":{"path":"new"},"pins":{"path":true}},{"id":"next","type":"fixture"}],
                "edges":[{"from":{"node":"start","port":"out"},"to":{"node":"next","port":"in"}}],"viewport":{"zoom":0.75}}""",
        )
        val updates = mapOf("label" to JsonPrimitive("Renamed"))
        val merged = assertNotNull(mergePipelineNode(fresh, original, updates))
        val node = merged.objects("nodes").first()
        assertEquals(JsonPrimitive("Renamed"), node["label"])
        assertEquals(fresh.objects("nodes").first()["params"], node["params"])
        assertEquals(fresh.objects("nodes").first()["pins"], node["pins"])
        assertEquals(fresh.objects("nodes").last(), merged.objects("nodes").last())
        assertEquals(fresh["edges"], merged["edges"])
        assertEquals(fresh["viewport"], merged["viewport"])
        assertNull(mergePipelineNode(merged, original, updates))
    }

    @Test
    fun draftsAreTrackedAndRejectInvalidCoordinates() {
        val state = PipelineGraphState()
        val node = studioJson.decodeFromString<JsonObject>(
            """{"id":"start","type":"fixture","label":"Start","position":{"x":-12.0,"y":85.0}}""",
        )
        val draft = PipelineNodeDraft(node)
        state.drafts["flow"] = draft
        assertFalse(state.hasUnsavedChanges())
        draft.label = TextFieldValue("Renamed")
        assertTrue(state.hasUnsavedChanges())
        assertEquals(mapOf("label" to JsonPrimitive("Renamed")), draft.changes())
        draft.x = TextFieldValue("NaN")
        assertNull(draft.changes())
        draft.x = TextFieldValue("12,5")
        assertEquals(JsonPrimitive(12.5), (draft.changes()?.get("position") as JsonObject)["x"])
    }

    @Test
    fun editingAnotherFieldKeepsTheSchemaLabelImplicit() {
        val node = studioJson.decodeFromString<JsonObject>(
            """{"id":"start","type":"fixture","label":null}""",
        )
        val draft = PipelineNodeDraft(node, defaultLabel = "Fixture")
        assertEquals("Fixture", draft.label.text)
        draft.disabled = true
        assertEquals(mapOf("disabled" to JsonPrimitive(true)), draft.changes())
    }

    @Test
    fun graphOperationsStageAndPreserveUnrelatedDefinitionFields() {
        val graph = definition()
        val state = PipelineGraphState()
        val input = PipelinePort("n3", "input")
        assertTrue(state.stage("flow", graph) { it.connectPipelinePorts(PipelinePort("n2", "out"), input) })
        assertTrue(state.hasUnsavedChanges())
        val staged = state.preview("flow", graph)
        assertEquals(listOf(GraphEdge("n2", "out", "n3", "input")), staged.objects("edges").map { it.pipelineEdge() })
        assertTrue(state.stage("flow", graph) { it.removePipelineNode("n2") })
        assertTrue(state.preview("flow", graph).objects("edges").isEmpty())
        assertTrue(state.stage("flow", graph) { it.addPipelineNode("plugin.new", 120.0, -15.0, state.reservedNodeIds("flow")).first })

        val fresh = JsonObject(graph + ("name" to JsonPrimitive("Renamed elsewhere")))
        val payload = assertNotNull(state.payload("flow", fresh))
        assertEquals(listOf("n1", "n3", "n4"), payload.objects("nodes").map { it.string("id") })
        assertEquals("Renamed elsewhere", payload.string("name"))
        assertEquals(fresh["viewport"], payload["viewport"])
        assertEquals(JsonPrimitive(-15.0), payload.objects("nodes").last().obj("position")?.get("y"))
        assertTrue(payload.objects("edges").isEmpty())
        state.discard("flow")
        assertFalse(state.hasUnsavedChanges())
    }

    @Test
    fun connectionAndPinChangesDetectConcurrentRewires() {
        val graph = definition()
        val input = PipelinePort("n3", "input")
        val connected = graph.connectPipelinePorts(PipelinePort("n2", "out"), input)
        val alternative = graph.connectPipelinePorts(PipelinePort("n1", "other"), input)
        assertNull(mergePipelineDefinition(alternative, graph, connected))
        assertEquals(1, connected.objects("edges").size)
        val hidden = connected.setPipelinePin("n3", "input", checked = false, schemaDefault = true)
        assertTrue(hidden.objects("edges").isEmpty())
        assertEquals(JsonPrimitive(false), hidden.objects("nodes").last().obj("pins")?.get("input"))
        val unconnected = graph.disconnectPipelineEdge(GraphEdge("n1", "out", "n3", "input"))
        assertNull(mergePipelineDefinition(connected, unconnected,
            unconnected.setPipelinePin("n3", "input", checked = false, schemaDefault = true)))
        val restored = hidden.setPipelinePin("n3", "input", checked = true, schemaDefault = true)
        assertTrue(restored.objects("nodes").last().obj("pins").orEmpty().isEmpty())
    }

    @Test
    fun typedParametersPreserveTheKindAndRebaseDistinctKeys() {
        val graph = definition()
        val node = graph.objects("nodes").last()
        val draft = PipelineNodeDraft(node)
        val numeric = jsonFields("type" to "integer", "minimum" to "0", "maximum" to "100")
        draft.editParameter("size", numeric, TextFieldValue("not a number"))
        assertFalse(draft.validParameters)
        assertNull(draft.changes())
        draft.editParameter("size", numeric, TextFieldValue("36"))
        draft.editParameter("options", jsonFields("type" to "json"), TextFieldValue("""{"fast":true,"colors":[1,2]}"""))
        draft.editParameter("expired", null, null)
        assertTrue(draft.validParameters)
        val payload = assertNotNull(draft.changes())
        val current = assertNotNull(mergePipelineNode(graph, node, mapOf("params" to studioJson.parseToJsonElement(
            """{"size":12,"keep":"updated elsewhere","expired":false}""",
        ))))
        val changed = assertNotNull(mergePipelineNode(current, node, payload)).objects("nodes").last().obj("params")
        assertEquals("36", changed?.get("size").toString())
        assertEquals("updated elsewhere", changed?.string("keep"))
        assertEquals(true, changed?.obj("options")?.flag("fast"))
        assertNull(changed?.get("expired"))
        assertEquals(JsonNull, parsePipelineParameter("", numeric))
        assertNull(parsePipelineParameter("36.1", numeric))
        assertNull(parsePipelineParameter("101", numeric))
    }

    @Test
    fun aNodeDragConvertsPixelsToGraphCoordinatesAndStagesOneGuardedPosition() {
        val graph = definition()
        val state = PipelineGraphState()
        val node = graph.objects("nodes").last()
        val staged = PipelineNodeDraft(node).apply { label = TextFieldValue("New label") }
        state.drafts["flow"] = staged
        val position = pipelineNodeDragPosition(Offset(10f, 20f), Offset(80f, -12f), 2f)
        assertEquals(Offset(50f, 14f), position)
        assertEquals(Offset(1_000_000_000f, -1_000_000_000f),
            pipelineNodeDragPosition(Offset.Zero, Offset(2_000_000_000f, -2_000_000_000f), 1f))
        assertTrue(state.stageNodePosition("flow", graph, node, position))
        assertNull(state.drafts["flow"])
        val actual = state.preview("flow", graph).objects("nodes").last()
        assertEquals("New label", actual.string("label"))
        assertEquals("50.0", actual.obj("position")?.string("x"))
        assertEquals("14.0", actual.obj("position")?.string("y"))
        assertEquals(graph["edges"], state.preview("flow", graph)["edges"])

        val fresh = assertNotNull(mergePipelineNode(graph, node, mapOf("position" to jsonFields("x" to "200"))))
        assertNull(state.payload("flow", fresh))
        val newState = PipelineGraphState()
        assertFalse(newState.stageNodePosition("flow", fresh, node, position))
        assertFalse(newState.hasUnsavedChanges())
    }

    private fun definition() = studioJson.decodeFromString<JsonObject>(
        """{"id":"flow","name":"Original","nodes":[{"id":"n1","type":"plugin.source"},{"id":"n2","type":"plugin.source"},
           {"id":"n3","type":"plugin.sink","params":{"size":12,"keep":"original","expired":false}}],
           "edges":[{"from":{"node":"n1","port":"out"},"to":{"node":"n3","port":"input"}}],"viewport":{"zoom":1.2}}""",
    )
}
