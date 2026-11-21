package studio.voxarium.desktop

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt

private const val NATIVE_PREVIEW_LIMIT = 64 * 1024 * 1024

// Dev spec §6.2: use the project's registered previewer before rendering its format.
@Composable
internal fun NativePluginFilePreview(tab: ProjectTab, path: String, data: JsonObject, workspace: Workspace,
                                     modifier: Modifier = Modifier, compact: Boolean = false) {
    val m = localMessages.current
    val words = remember(m.language) { PluginWords(m.language, "preview") }
    val api = tab.api ?: return
    val previewer = data.obj("previewer")
    val revision = data.string("modified") to data.string("size")
    val text = data.string("text") ?: data.string("hex")
    val extension = path.substringAfterLast('.', "").lowercase()
    val provider = previewer?.string("plugin_id") to previewer?.string("id")
    val imageKind = data.string("kind") == "image" || data.string("mime")?.startsWith("image/") == true
    Box(modifier) {
        when {
            provider == ("pdf_preview" to "pdf-document") && extension == "pdf" ->
                key(api, path, revision) { NativePdfPreview(api, path, words) }
            nativeModelFormats[provider]?.contains(extension) == true ->
                key(api, path, revision) { NativeModelPreview(api, path, extension, words, compact) }
            previewer != null -> Centered {
                StudioIcon("box", Modifier.size(StudioTokens.largeIconSize), localColors.current.faint)
                WorkAction(m["pluginPreview"], "external-link") {
                    openBrowser(api.web("/explorer", query("path" to path)), workspace)
                }
            }
            imageKind -> key(api, path, revision) { NativePreviewImage(api, data, path, words) }
            text != null -> LibraryCodePreview(text, withNumbers = data.string("kind") == "text")
            else -> Centered { Muted(data.string("message") ?: m["libraryNoPreview"]) }
        }
    }
}

internal suspend fun ProjectApi.previewBytes(path: String): ByteArray =
    downloadAttachment("/explorer/raw" + query("path" to path), NATIVE_PREVIEW_LIMIT)

internal suspend fun ProjectApi.previewImage(data: JsonObject, path: String, maxBytes: Int, maxPixels: Long): ImageBitmap {
    val inline = data.string("data_url")?.takeIf { it.substringBefore(',').endsWith(";base64") }?.substringAfter(',')
    val bytes = if (inline == null) downloadAttachment("/explorer/raw" + query("path" to path), maxBytes) else {
        if (inline.length.toLong() * 3 / 4 > maxBytes + 2L) throw AttachmentTooLarge()
        withContext(Dispatchers.Default) { Base64.getDecoder().decode(inline) }
    }
    if (bytes.size > maxBytes) throw AttachmentTooLarge()
    return withContext(Dispatchers.Default) { decodePreviewImage(bytes, path, maxPixels) }
}

@Composable
internal fun PreviewError(error: Exception, words: PluginWords) {
    if (error is AttachmentTooLarge) Text(words["tooLarge"], color = localColors.current.warning)
    else ProblemText(error.problem())
}

@Composable
private fun NativePreviewImage(api: ProjectApi, data: JsonObject, path: String, words: PluginWords) {
    val m = localMessages.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember { mutableStateOf<Exception?>(null) }
    LaunchedEffect(api, data.string("data_url"), path) {
        try {
            image = api.previewImage(data, path, NATIVE_PREVIEW_LIMIT, 32_000_000L)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error
        }
    }
    val failure = problem
    val bitmap = image
    when {
        failure != null -> Centered { PreviewError(failure, words) }
        bitmap != null -> Image(bitmap, path.substringAfterLast('/'), Modifier.fillMaxSize().padding(StudioTokens.space2), contentScale = ContentScale.Fit)
        else -> Centered { Muted(m["loading"]) }
    }
}

// §6.2: SVGs are drawn as static image documents, with a bounded raster size.
internal fun decodePreviewImage(bytes: ByteArray, path: String, maxPixels: Long): ImageBitmap {
    if (!path.endsWith(".svg", ignoreCase = true)) return SkiaImage.makeFromEncoded(bytes).use { decoded ->
        require(decoded.width.toLong() * decoded.height <= maxPixels)
        decoded.toComposeImageBitmap()
    }
    val parser = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }.createXMLStreamReader(bytes.inputStream())
    try {
        while (parser.hasNext()) require(parser.next() != XMLStreamConstants.DTD)
    } finally {
        parser.close()
    }
    return Data.makeFromBytes(bytes).use { source ->
        SVGDOM(source).use { dom ->
            val root = requireNotNull(dom.root)
            val viewBox = root.viewBox
            val intrinsic = root.getIntrinsicSize(SVGLengthContext(300f, 150f, 16f))
            val width = intrinsic.x.takeIf { it.isFinite() && it > 0f } ?: viewBox?.width ?: 300f
            val height = intrinsic.y.takeIf { it.isFinite() && it > 0f } ?: viewBox?.height ?: 150f
            require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
            val scale = (2048f / maxOf(width, height)).coerceAtMost(1f)
            val pixelsWide = (width * scale).roundToInt().coerceAtLeast(1)
            val pixelsHigh = (height * scale).roundToInt().coerceAtLeast(1)
            require(pixelsWide.toLong() * pixelsHigh <= maxPixels)
            Surface.makeRasterN32Premul(pixelsWide, pixelsHigh).use { surface ->
                dom.setContainerSize(width, height)
                surface.canvas.scale(scale, scale)
                dom.render(surface.canvas)
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }
}

internal data class PdfPreviewPage(val bitmap: ImageBitmap, val pages: Int, val aspect: Float)

internal fun renderPdfPage(bytes: ByteArray, page: Int, zoom: Float): PdfPreviewPage = Loader.loadPDF(bytes).use { document ->
    require(page in 0 until document.numberOfPages)
    val size = document.getPage(page).cropBox
    val edge = maxOf(size.width, size.height)
    require(edge.isFinite() && edge > 0f)
    val scale = (1250f * zoom.coerceIn(0.5f, 2f) / edge).coerceAtMost(6f)
    val rendered = PDFRenderer(document).apply { isSubsamplingAllowed = true }.renderImage(page, scale, ImageType.RGB)
    PdfPreviewPage(rendered.previewBitmap(), document.numberOfPages, rendered.width.toFloat() / rendered.height)
}

private fun BufferedImage.previewBitmap(): ImageBitmap {
    val encoded = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(this, "png", output))
        output.toByteArray()
    }
    return SkiaImage.makeFromEncoded(encoded).use { it.toComposeImageBitmap() }
}

@Composable
private fun NativePdfPreview(api: ProjectApi, path: String, words: PluginWords) {
    val m = localMessages.current
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var result by remember { mutableStateOf<PdfPreviewPage?>(null) }
    var problem by remember { mutableStateOf<Exception?>(null) }
    LaunchedEffect(api, path) {
        try {
            bytes = api.previewBytes(path)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error
        }
    }
    LaunchedEffect(bytes, page, zoom) {
        val file = bytes ?: return@LaunchedEffect
        problem = null
        try {
            result = runInterruptible(Dispatchers.IO) { renderPdfPage(file, page, zoom) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error
        }
    }
    Column(Modifier.fillMaxSize()) {
        val rendered = result
        if (rendered != null) {
            FlowRow(Modifier.fillMaxWidth().padding(StudioTokens.space2), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                LibraryIconAction("chevron-left", words["previousPage"], enabled = page > 0, bordered = true) { page-- }
                Box(Modifier.heightIn(min = StudioTokens.controlMinHeight), contentAlignment = Alignment.Center) {
                    Text(words.text("pageNumber", "page" to page + 1, "pages" to rendered.pages))
                }
                LibraryIconAction("chevron-right", words["nextPage"], enabled = page + 1 < rendered.pages, bordered = true) { page++ }
                LibraryIconAction("minus", words["zoomOut"], enabled = zoom > 0.5f, bordered = true) { zoom -= 0.25f }
                WorkAction("${(zoom * 100).toInt()}%", null) { zoom = 1f }
                LibraryIconAction("plus", words["zoomIn"], enabled = zoom < 2f, bordered = true) { zoom += 0.25f }
            }
            Rule()
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            val error = problem
            when {
                error != null -> Centered { PreviewError(error, words) }
                rendered == null -> Centered { Muted(m["loading"]) }
                else -> {
                    val width = ((maxWidth - StudioTokens.space5).coerceAtLeast(1.dp).coerceAtMost(900.dp) * zoom)
                    key(page) {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                            .padding(StudioTokens.space3), contentAlignment = Alignment.TopCenter) {
                            Image(rendered.bitmap, path.substringAfterLast('/'), Modifier.size(width, width / rendered.aspect))
                        }
                    }
                }
            }
        }
    }
}
