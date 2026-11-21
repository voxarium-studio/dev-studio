package studio.voxarium.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.*
import kotlinx.serialization.json.*

class PipelineCanvasPreviewTest {
    @Test
    fun connectsPreviewToObservedArtifactInsteadOfAnOldLiteral() {
        val params = jsonFields("file" to "old.glb")
        assertNull(pipelinePreviewSources(PipelinePreviewKind.MESH, params, null, mapOf("file" to null), emptyMap()).result)
        assertEquals("incoming.glb", pipelinePreviewSources(PipelinePreviewKind.MESH, params, null,
            mapOf("file" to "incoming.glb"), emptyMap()).result)
        assertEquals("executed.glb", pipelinePreviewSources(PipelinePreviewKind.MESH, params,
            jsonFields("file" to "executed.glb"), mapOf("file" to "incoming.glb"), emptyMap()).result)
    }

    @Test
    fun meshInputViewsKeepSchemaOrderAndResultSwitchesToModel() {
        val sources = pipelinePreviewSources(PipelinePreviewKind.GENERATE_MESH,
            jsonFields("right" to "r.png", "front" to "f.png", "back" to "unused.png"),
            jsonFields("mesh_file" to "output.glb"), mapOf("left" to "l.png", "back" to null), mapOf("front" to "Front view"))
        assertEquals(listOf("front", "left", "right"), sources.inputs.map(PipelinePreviewInput::id))
        assertEquals("Front view", sources.inputs.first().label)
        assertEquals("output.glb", sources.result)
        assertTrue(sources.resultIsModel)
    }

    @Test
    fun graphFitsIndependentPreviewCardsAndKeepsTheirPortsOnTheEdges() {
        val type = "preview3d.view"
        val port = buildJsonObject { put("id", "file"); put("connectable", true) }
        val schema = buildJsonObject {
            put("id", type)
            put("inputs", JsonArray(listOf(port)))
            put("outputs", JsonArray(listOf(port)))
        }
        val nodes = (1..5).map { index ->
            buildJsonObject {
                put("id", "n$index")
                put("type", type)
                if (index == 1) put("position", buildJsonObject { put("x", 0); put("y", 0) })
            }
        }
        val definition = buildJsonObject { put("nodes", JsonArray(nodes)) }
            .connectPipelinePorts(PipelinePort("n1", "file"), PipelinePort("n2", "file"))
        val run = buildJsonObject {
            put("node_id", "n1")
            put("outputs", jsonFields("file" to "model.glb"))
        }
        val layout = definition.graphLayout(listOf(schema), listOf(run))
        val first = layout.byId.getValue("n1")
        val last = layout.byId.getValue("n5")
        assertEquals(Offset.Zero, first.point)
        assertTrue(first.width >= 250f)
        assertEquals(first.point.x + first.width, first.anchor("file", true).x)
        assertTrue(first.anchor("file", true).y < first.height - first.preview!!.height)
        assertEquals("model.glb", layout.connectedArtifacts["n2"]?.get("file"))
        assertTrue(last.point.y >= first.point.y + first.height)
        assertTrue(layout.width >= first.width && layout.height >= last.point.y + last.height)
    }

    @Test
    fun staticSvgPreviewKeepsAspectAndPaintsPixels() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50" viewBox="0 0 100 50"><rect width="100" height="50" fill="red"/></svg>"""
        val image = decodePreviewImage(svg.encodeToByteArray(), "preview.svg", maxPixels = 10_000)
        assertEquals(100, image.width)
        assertEquals(50, image.height)
        assertEquals(1f, image.toPixelMap()[50, 25].red)
        assertFailsWith<IllegalArgumentException> {
            decodePreviewImage(("<!DOCTYPE svg>" + svg).encodeToByteArray(), "preview.svg", maxPixels = 10_000)
        }
    }
}
