package studio.voxarium.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Image as SkiaImage

// §10.4, §10.9: briefs and chats carry upload references; reviews carry task-scoped names.
private const val maxAttachmentBytes = 20 * 1024 * 1024
private const val maxPreviewPixels = 16_000_000L
private val uploadFolder = Regex("upload_[0-9a-f]{32}")
private val invalidAttachmentName = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")
private val imageAttachmentFormats = setOf("png", "jpg", "jpeg", "gif", "webp")

internal class AttachmentTooLarge : IOException()

internal data class TaskAttachment(val name: String, val folder: String? = null) {
    val reference: String get() = folder?.let { "$it/$name" } ?: name
    val isImage: Boolean get() = name.substringAfterLast('.', "").lowercase() in imageAttachmentFormats

    fun endpoint(reviewEndpoint: String?): String? = folder?.let { "/attachments/${segment(it)}/${segment(name)}" }
        ?: reviewEndpoint?.let { "$it/attachments/${segment(name)}" }
}

internal fun JsonObject.taskAttachments(brief: Boolean) = objects("attachments").mapNotNull { it.taskAttachment(brief) }

internal fun List<TaskAttachment>.attachmentReferences() = JsonArray(map { JsonPrimitive(it.reference) })

private fun JsonObject.taskAttachment(brief: Boolean): TaskAttachment? {
    val name = string("name")?.takeIf {
        it.isNotEmpty() && it == it.trim(' ', '.') && !invalidAttachmentName.containsMatchIn(it)
    } ?: return null
    if (!brief) return TaskAttachment(name)
    val reference = string("reference") ?: return null
    val folder = reference.substringBefore('/')
    if (!uploadFolder.matches(folder) || reference != "$folder/$name") return null
    return TaskAttachment(name, folder)
}

internal class TaskReviewDraft {
    var attachments by mutableStateOf<List<TaskAttachment>>(emptyList())
    // A review may have committed before its request reports an error. Only delete unused uploads.
    val submitted = mutableSetOf<String>()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TaskAttachmentPicker(
    tab: ProjectTab,
    attachments: List<TaskAttachment>,
    onChange: (List<TaskAttachment>) -> Unit,
    reviewEndpoint: String? = null,
    removeReview: ((TaskAttachment) -> Unit)? = null,
    body: @Composable (Modifier) -> Unit = {},
) {
    val m = localMessages.current
    val owner = LocalAwtWindow.current
    val enabled = !tab.acting && tab.api != null
    var uploading by remember { mutableStateOf(false) }
    fun upload(sources: List<Pair<String, () -> ByteArray>>) {
        if (enabled && sources.isNotEmpty()) tab.action {
            uploading = true
            try {
                var staged = attachments
                val endpoint = reviewEndpoint?.let { "$it/attachments" } ?: "/attachments"
                for ((name, read) in sources) {
                    val bytes = runInterruptible(Dispatchers.IO) { read() }
                    if (bytes.size > maxAttachmentBytes) throw AttachmentTooLarge()
                    val attachment = uploadAttachment(endpoint, name, bytes)
                        .taskAttachment(brief = reviewEndpoint == null) ?: throw IOException("Invalid attachment response")
                    staged = staged + attachment
                    onChange(staged)
                }
            } finally {
                uploading = false
            }
        }
    }

    fun uploadFiles(paths: List<Path>) = upload(paths.map { file ->
        val name = file.fileName?.toString().orEmpty()
        name to {
            if (!Files.isRegularFile(file)) throw IOException(name)
            Files.newInputStream(file).use { it.readNBytes(maxAttachmentBytes + 1) }
        }
    })

    fun pasteImage(): Boolean {
        if (!enabled) return false
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return false
        if (!runCatching { clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false)) return false
        upload(listOf("image.png" to { clipboardPng(clipboard.getData(DataFlavor.imageFlavor) as java.awt.Image) }))
        return true
    }

    val canDrop by rememberUpdatedState(enabled)
    val onDrop by rememberUpdatedState(::uploadFiles)
    var dropHover by remember { mutableStateOf(false) }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropHover = canDrop }
            override fun onExited(event: DragAndDropEvent) { dropHover = false }
            override fun onEnded(event: DragAndDropEvent) { dropHover = false }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (!canDrop) return false
                val contents = runCatching { event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) }.getOrNull()
                val paths = (contents as? List<*>).orEmpty().filterIsInstance<File>().map(File::toPath)
                if (paths.isEmpty()) return false
                onDrop(paths)
                return true
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(if (dropHover && enabled) localColors.current.selected else Color.Transparent)
        .dragAndDropTarget(shouldStartDragAndDrop = {
            canDrop && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        }, target = dropTarget), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            WorkAction(m["nativeTaskAttachments.add"], "plus", enabled = enabled, busy = uploading) {
                uploadFiles(chooseAttachmentFiles(owner, m["nativeTaskAttachments.add"]))
            }
            WorkAction(m["nativeTaskAttachments.paste"], "image", enabled = enabled, iconOnly = true, outlined = false) {
                pasteImage()
            }
        }
        if (uploading) Muted(m["nativeTaskAttachments.uploading"])
        if (attachments.isNotEmpty()) {
            Box(Modifier.heightIn(max = 116.dp).verticalScroll(rememberScrollState())) {
                TaskAttachmentLinks(tab.api, attachments, reviewEndpoint, enabled, remove = removeReview ?: { entry ->
                    onChange(attachments - entry)
                })
            }
        }
        body(Modifier.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown && event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed) && pasteImage()
        })
    }
}

private fun clipboardPng(image: java.awt.Image): ByteArray {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0 || width.toLong() * height > maxPreviewPixels) throw AttachmentTooLarge()
    val buffered = if (image is BufferedImage) image else BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { result ->
        val graphics = result.createGraphics()
        try {
            if (!graphics.drawImage(image, 0, 0, null)) throw IOException("Clipboard image is unavailable")
        } finally {
            graphics.dispose()
        }
    }
    return ByteArrayOutputStream().use { output ->
        if (!ImageIO.write(buffered, "png", output)) throw IOException("PNG encoder is unavailable")
        output.toByteArray()
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TaskAttachmentLinks(
    api: ProjectApi?,
    attachments: List<TaskAttachment>,
    reviewEndpoint: String? = null,
    enabled: Boolean = true,
    remove: ((TaskAttachment) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    val m = localMessages.current
    val owner = LocalAwtWindow.current
    val scope = rememberCoroutineScope()
    var saving by remember(api, reviewEndpoint) { mutableStateOf(false) }
    var error by remember(api, reviewEndpoint) { mutableStateOf<Problem?>(null) }
    var preview by remember(api, reviewEndpoint) { mutableStateOf<TaskAttachment?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
        for (attachment in attachments) {
            val endpoint = attachment.endpoint(reviewEndpoint)
            Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                Text(attachment.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (remove == null && attachment.isImage && api != null && endpoint != null) {
                    val label = if (preview == attachment) m["nativeTaskAttachments.hidePreview"] else m["nativeTaskAttachments.preview"]
                    WorkAction(label.replace("{name}", attachment.name), "image", iconOnly = true, outlined = false) {
                        preview = attachment.takeUnless { preview == it }
                    }
                }
                val saveLabel = m["nativeTaskAttachments.save"].replace("{name}", attachment.name)
                WorkAction(saveLabel, "download", iconOnly = true, outlined = false,
                    enabled = enabled && !saving && api != null && endpoint != null) {
                    if (api != null && endpoint != null && !saving) {
                        val destination = chooseAttachmentFiles(owner, m["nativeTaskAttachments.saveTitle"], attachment.name)
                            .singleOrNull()
                        if (destination != null) {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    saveAttachment(destination, api.downloadAttachment(endpoint, maxAttachmentBytes))
                                } catch (cause: Exception) {
                                    error = cause.problem("nativeTaskAttachments.saveFailed")
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                }
                if (remove != null) {
                    val removeLabel = m["nativeTaskAttachments.remove"].replace("{name}", attachment.name)
                    WorkAction(removeLabel, "x", iconOnly = true, outlined = false, enabled = enabled && !saving) {
                        remove(attachment)
                    }
                }
            }
            if (preview == attachment && api != null && endpoint != null) key(endpoint) {
                AttachmentImage(api, endpoint, attachment.name)
            }
        }
        if (saving) Muted(m["nativeTaskAttachments.saving"])
        error?.let { ProblemText(it) }
    }
}

@Composable
private fun AttachmentImage(api: ProjectApi, endpoint: String, name: String) {
    val m = localMessages.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<Problem?>(null) }
    LaunchedEffect(api, endpoint) {
        try {
            val bytes = api.downloadAttachment(endpoint, maxAttachmentBytes)
            image = runInterruptible(Dispatchers.Default) {
                SkiaImage.makeFromEncoded(bytes).use { decoded ->
                    if (decoded.width.toLong() * decoded.height > maxPreviewPixels) throw IOException()
                    decoded.toComposeImageBitmap()
                }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            error = Problem("nativeTaskAttachments.previewFailed")
        }
    }
    val decoded = image
    when {
        decoded != null -> Image(decoded, name, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
        error != null -> error?.let { ProblemText(it) }
        else -> Muted(m["loading"])
    }
}

private fun chooseAttachmentFiles(owner: Window?, title: String, name: String? = null): List<Path> {
    val mode = if (name == null) FileDialog.LOAD else FileDialog.SAVE
    val dialog = if (owner is Dialog) FileDialog(owner, title, mode) else FileDialog(owner as? Frame, title, mode)
    return try {
        dialog.isMultipleMode = name == null
        if (name != null) dialog.file = name
        dialog.isVisible = true
        dialog.files.map { it.toPath() }
    } finally {
        dialog.dispose()
    }
}

private suspend fun saveAttachment(destination: Path, data: ByteArray) = runInterruptible(Dispatchers.IO) {
    val temporary = Files.createTempFile(destination.toAbsolutePath().parent, ".studio-attachment-", ".part")
    try {
        Files.write(temporary, data)
        try {
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
