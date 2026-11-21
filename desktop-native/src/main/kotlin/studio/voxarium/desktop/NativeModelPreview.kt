package studio.voxarium.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text

private val modelPreviewLoads = Semaphore(2)

@Composable
internal fun NativeModelPreview(api: ProjectApi, path: String, extension: String, words: PluginWords, compact: Boolean = false) {
    val m = localMessages.current
    var result by remember { mutableStateOf<PreviewModel?>(null) }
    var problem by remember { mutableStateOf<Exception?>(null) }
    LaunchedEffect(api, path) {
        try {
            result = modelPreviewLoads.withPermit {
                val bytes = api.previewBytes(path)
                withContext(Dispatchers.IO) { decodePreviewModel(bytes, extension) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error
        }
    }
    val model = result
    val failure = problem
    when {
        model != null -> ModelViewport(model, path.substringAfterLast('/'), words, compact)
        failure != null -> Centered { PreviewError(failure, words) }
        else -> Centered { StudioLoadingIcon(Modifier.size(StudioTokens.iconSize)); Muted(m["loading"]) }
    }
}

@Composable
private fun ModelViewport(model: PreviewModel, title: String, words: PluginWords, compact: Boolean) {
    val colors = localColors.current
    val m = localMessages.current
    var hidden by remember(model) { mutableStateOf(model.objects.filter { it.hidden }.mapTo(mutableSetOf()) { it.id }.toSet()) }
    var objectsOpen by remember { mutableStateOf(false) }
    var panMode by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(ModelCamera()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(model) { mutableStateOf<Exception?>(null) }
    var ready by remember(model, hidden) { mutableStateOf(false) }
    val ranges = remember(model, hidden) { model.visibleTriangles(hidden) }

    LaunchedEffect(model, ranges, size) {
        if (size.width < 1 || size.height < 1) return@LaunchedEffect
        problem = null
        try {
            val bounds = withContext(Dispatchers.Default) { model.visibleBounds(ranges) }
            if (bounds == null) {
                image = null
                ready = true
            } else {
                snapshotFlow { camera }.conflate().collect { next ->
                    image = withContext(Dispatchers.Default) { model.renderPreview(next, size, bounds, ranges) }
                    ready = true
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error
        }
    }

    Column(Modifier.fillMaxSize()) {
        val toolGap = if (compact) StudioTokens.space1 else StudioTokens.space2
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = toolGap, vertical = toolGap),
            horizontalArrangement = Arrangement.spacedBy(toolGap), verticalArrangement = Arrangement.spacedBy(toolGap)) {
            LibraryIconAction("minus", words["zoomOut"], enabled = camera.zoom > .25f, bordered = true) {
                camera = camera.copy(zoom = (camera.zoom / 1.15f).coerceAtLeast(.25f))
            }
            LibraryIconAction("plus", words["zoomIn"], enabled = camera.zoom < 8f, bordered = true) {
                camera = camera.copy(zoom = (camera.zoom * 1.15f).coerceAtMost(8f))
            }
            LibraryIconAction("target", words["panModel"], bordered = true, selected = panMode) { panMode = !panMode }
            LibraryIconAction("maximize", words["resetCamera"], bordered = true) { camera = ModelCamera() }
            if (model.objects.isNotEmpty()) {
                if (compact) LibraryIconAction("layers", words["objects"], bordered = true, selected = objectsOpen) {
                    objectsOpen = !objectsOpen
                } else StudioChip(words["objects"], objectsOpen, { objectsOpen = it }, icon = "layers")
            }
        }
        Rule()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (objectsOpen) {
                ScrollList(Modifier.widthIn(min = if (compact) 100.dp else 145.dp, max = 225.dp)
                    .fillMaxWidth(if (compact) .46f else .32f).fillMaxHeight()
                    .background(colors.sidebar).padding(StudioTokens.space2)) {
                    items(model.objects, key = { it.id }) { item ->
                        val name = item.name.ifBlank { words.text("objectNumber", "number" to item.id + 1) }
                        val selected = item.id !in hidden
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                WorkCheckbox(name, selected, change = { show ->
                                    hidden = if (show) hidden - item.id else hidden + item.id
                                })
                            }
                            LibraryIconAction("eye", words["isolateObject"]) {
                                val all = model.objects.mapTo(mutableSetOf()) { it.id }
                                val isolated = all - item.id
                                hidden = if (hidden == isolated) emptySet() else isolated
                                camera = ModelCamera()
                            }
                        }
                    }
                }
                Rule(vertical = true)
            }
            Box(Modifier.fillMaxHeight().weight(1f).background(colors.canvas).clipToBounds().onSizeChanged { size = it }
                .pointerInput(panMode) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        camera = if (panMode) camera.copy(pan = camera.pan + amount) else camera.copy(
                            yaw = (camera.yaw + amount.x * .008f).mod((2 * Math.PI).toFloat()),
                            pitch = (camera.pitch + amount.y * .008f).coerceIn(-1.55f, 1.55f),
                        )
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { camera = ModelCamera() })
                }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val pointer = event.changes.firstOrNull() ?: continue
                            val delta = pointer.scrollDelta.y
                            if (delta == 0f) continue
                            camera = camera.copy(zoom = (camera.zoom * (if (delta < 0) 1.15f else 1f / 1.15f)).coerceIn(.25f, 8f))
                            pointer.consume()
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                val bitmap = image
                val error = problem
                when {
                    error != null -> Centered { PreviewError(error, words) }
                    bitmap != null -> Image(bitmap, title, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    ready -> Muted(words["nothingVisible"])
                    else -> StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                }
            }
        }
        Rule()
        val stats = words.text("modelStats", "count" to ranges.sumOf { it.last - it.first + 1 })
        if (compact) {
            LibraryCaption(stats, Modifier.padding(horizontal = StudioTokens.space1), maxLines = 1)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                Muted(listOf(stats, words[if (panMode) "panHint" else "orbit"]).joinToString(" · "))
                if (model.truncated) Text(m["previewTruncated"], color = colors.warning)
                for (warning in model.warnings.take(3)) Text(warning, color = colors.warning)
                if (model.omittedTextures.isNotEmpty()) {
                    Text(words.text("texturesSkipped", "names" to model.omittedTextures.take(3).joinToString(", ")), color = colors.warning)
                }
            }
        }
    }
}
