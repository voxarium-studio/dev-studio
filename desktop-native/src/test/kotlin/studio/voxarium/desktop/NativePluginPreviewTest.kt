package studio.voxarium.desktop

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle

class NativePluginPreviewTest {
    @Test
    fun builtInDecoderFeedsTheNativeDepthRenderer(): Unit = runBlocking {
        val file = "v -1 -1 0\nv 1 -1 0\nv 0 1 0\nf 1 2 3\n".encodeToByteArray()
        val mesh = decodePreviewModel(file, "obj")
        assertEquals(1, mesh.count)
        val ranges = mesh.visibleTriangles(emptySet())
        val bounds = assertNotNull(mesh.visibleBounds(ranges))
        val frame = mesh.renderPreview(ModelCamera(yaw = 0f, pitch = 0f), IntSize(120, 90), bounds, ranges)
        val pixels = frame.toPixelMap()
        assertEquals(1f, pixels[60, 45].alpha)
        assertEquals(0f, pixels[0, 0].alpha)

        assertFailsWith<IOException> { decodePreviewModel("bad model".encodeToByteArray(), "obj") }
        Unit
    }

    @Test
    fun pdfRenderUsesPageSizeRotationAndPaging() {
        val bytes = PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(100f, 200f)))
            doc.addPage(PDPage(PDRectangle(100f, 200f)).apply { rotation = 90 })
            ByteArrayOutputStream().use { output -> doc.save(output); output.toByteArray() }
        }
        val page = renderPdfPage(bytes, 1, 1f)
        assertEquals(2, page.pages)
        assertEquals(2f, page.aspect)
        assertTrue(page.bitmap.width > page.bitmap.height)
        assertFailsWith<IllegalArgumentException> { renderPdfPage(bytes, 2, 1f) }
    }
}
