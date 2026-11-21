package studio.voxarium.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class DocumentGraphTest {
    @Test
    fun graphIgnoresSelfLinksUnknownDocumentsAndReciprocalDuplicates() {
        val graph = readGraph()
        assertEquals(listOf("combat/attacks.md", "combat/weapons.md", "economy.md"), graph.nodes.map { it.path })
        assertEquals(listOf(DocumentGraphLink(0, 1)), graph.links)
        assertEquals(listOf(setOf(1), setOf(0), emptySet()), graph.neighbours)
    }

    @Test
    fun sparseGraphSeedsTheSameLayoutAndStopsAfterCooling() {
        val graph = readGraph()
        val first = DocumentGraphSimulation(graph)
        val second = DocumentGraphSimulation(graph)
        assertEquals(first.capture(), second.capture())
        repeat(330) { first.step() }
        assertFalse(first.moving)
        assertTrue(first.capture().values.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(first.radius(0) > first.radius(2))
        val restored = DocumentGraphSimulation(graph, first.capture(), startHeat = 0f)
        assertEquals(first.capture(), restored.capture())
        assertFalse(restored.moving)
    }

    @Test
    fun cursorZoomKeepsTheSameGraphPoint() {
        val view = DocumentGraphCamera(Offset(12f, -9f), .8f)
        val center = Offset(400f, 300f)
        val cursor = Offset(210f, 174f)
        val anchor = view.unproject(cursor, center)
        val zoomed = view.zoom(2f, cursor, center)
        val point = zoomed.project(anchor, center)
        assertEquals(cursor.x, point.x, .001f)
        assertEquals(cursor.y, point.y, .001f)
        val fitted = DocumentGraphCamera.fit(Rect(100f, 0f, 300f, 50f), Size(300f, 200f), 40f)
        assertEquals(Offset(150f, 100f), fitted.project(Offset(200f, 25f), Offset(150f, 100f)))
    }

    @Test
    fun fittingCameraGlidesAndStopsAtExactTarget() {
        val start = DocumentGraphCamera()
        val target = DocumentGraphCamera(Offset(100f, -40f), 2f)
        var frame = start.approach(target, .12f)
        assertTrue(frame.pan.x in 1f..99f)
        assertTrue(frame.scale in 1.01f..1.99f)
        repeat(120) { frame = frame.approach(target, .12f) }
        assertEquals(target, frame)
    }

    @Test
    fun documentNamesAndRenamedFolderPrefixesStayProjectRelative() {
        assertEquals("docs/Design/v1.2.md", nativeDocumentPath("Design\\v1.2", "docs"))
        assertEquals("Research/Question.MARKDOWN", nativeDocumentPath("Research/Question.MARKDOWN"))
        assertNull(nativeDocumentPath("../escaped"))
        assertNull(nativeDocumentPath("C:/data"))
        assertNull(nativeDocumentPath("folder/.md"))
        assertEquals("models/render/draft.md", followRenamedFolder("art/render/draft.md", "art", "models"))
        assertEquals("artists.md", followRenamedFolder("artists.md", "art", "models"))
    }

    private fun readGraph() = DocumentGraph.read(studioJson.parseToJsonElement("""
        {
          "nodes": [{"path":"economy.md"},{"path":"combat/weapons.md"},{"path":"combat/attacks.md","title":"Attacks"}],
          "edges": [
            {"source":"combat/attacks.md","target":"combat/weapons.md"},
            {"source":"combat/weapons.md","target":"combat/attacks.md"},
            {"source":"economy.md","target":"economy.md"},
            {"source":"economy.md","target":"missing.md"}
          ]
        }
    """.trimIndent()).jsonObject)
}
