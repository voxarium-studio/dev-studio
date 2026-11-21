package studio.voxarium.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private const val maxImageBytes = 32 * 1024 * 1024
private const val maxImagePixels = 24_000_000L
private val pipelinePreviewLoads = Semaphore(3)

internal enum class PipelinePreviewKind(val nodeTypes: Set<String>, val height: Float = 193f) {
    IMAGE(setOf("preview2d.view")), MESH(setOf("preview3d.view"), 205f),
    CROP(setOf("image_ops.crop")), KEY_COLOR(setOf("image_ops.remove_background")),
    GENERATE_MESH(setOf("hunyuan3d.image_to_mesh", "hunyuan3d.multiview_to_mesh"), 205f);

    companion object {
        fun from(type: String) = entries.firstOrNull { type in it.nodeTypes }
    }
}

internal data class PipelinePreviewInput(val id: String, val label: String, val path: String)

internal data class PipelinePreviewSources(val inputs: List<PipelinePreviewInput>, val result: String?, val resultIsModel: Boolean)

internal fun pipelinePreviewSources(
    kind: PipelinePreviewKind,
    params: JsonObject,
    outputs: JsonObject?,
    connected: Map<String, String?>,
    portLabels: Map<String, String>,
): PipelinePreviewSources {
    fun path(id: String) = if (id in connected) connected[id] else params.artifact(id)
    val still = when (kind) {
        PipelinePreviewKind.IMAGE -> path("image")
        PipelinePreviewKind.MESH -> path("file")
        PipelinePreviewKind.CROP, PipelinePreviewKind.KEY_COLOR -> outputs.artifact("source") ?: path("image")
        PipelinePreviewKind.GENERATE_MESH -> null
    }
    val inputs = when (kind) {
        PipelinePreviewKind.CROP, PipelinePreviewKind.KEY_COLOR ->
            listOfNotNull(still?.let { PipelinePreviewInput("image", portLabels["image"] ?: "image", it) })
        PipelinePreviewKind.GENERATE_MESH -> listOf("image", "front", "left", "back", "right").mapNotNull { id ->
            path(id)?.let { PipelinePreviewInput(id, portLabels[id] ?: id, it) }
        }
        else -> emptyList()
    }
    val result = when (kind) {
        PipelinePreviewKind.IMAGE -> outputs.artifact("image") ?: still
        PipelinePreviewKind.MESH -> outputs.artifact("file") ?: still
        PipelinePreviewKind.CROP, PipelinePreviewKind.KEY_COLOR -> outputs.artifact("image")
        PipelinePreviewKind.GENERATE_MESH -> outputs.artifact("mesh_file")
    }
    return PipelinePreviewSources(inputs, result, kind == PipelinePreviewKind.MESH || kind == PipelinePreviewKind.GENERATE_MESH)
}

internal fun pipelineMeshSummary(params: JsonObject, messages: Messages): String? {
    val parts = listOfNotNull(
        params.string("seed")?.let { messages["pipelinePreviewMeshSeed"].replace("{seed}", it) },
        params.string("steps")?.let { messages["pipelinePreviewMeshSteps"].replace("{steps}", it) },
        params.string("octree_resolution")?.let { messages["pipelinePreviewMeshGrid"].replace("{grid}", it) },
    ).toMutableList()
    val texture = params.flag("texture")
    if (texture) {
        params.string("texture_resolution")?.let { parts += messages["pipelinePreviewMeshTexture"].replace("{size}", it) }
        val views = params.string("max_num_view")?.toIntOrNull()?.takeIf { it > 0 }
        parts += views?.let { messages["pipelinePreviewMeshViews"].replace("{count}", it.toString()) }
            ?: messages["pipelinePreviewMeshAutoViews"]
    }
    params.string("output_format")?.let { parts += if (it == "obj" && texture) "OBJ ZIP" else it.uppercase(Locale.ROOT) }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun JsonObject?.artifact(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

private fun JsonObject?.dimensions(key: String, count: Int): List<Float>? =
    (this?.get(key) as? JsonArray)?.takeIf { it.size == count }?.map { item ->
        (item as? JsonPrimitive)?.floatOrNull?.takeIf(Float::isFinite) ?: return null
    }

internal fun pipelineImageBounds(view: Size, width: Int, height: Int): Rect {
    val scale = min(view.width / width, view.height / height)
    val size = Size(width * scale, height * scale)
    return Rect(Offset((view.width - size.width) / 2, (view.height - size.height) / 2), size)
}

/** (§6.3) The canvas zooms the viewport and its pointer coordinates together. */
@Composable
internal fun PipelineScaledPreview(scale: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = { Box(Modifier.fillMaxSize()) { content() } }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val child = measurables.single().measure(Constraints.fixed(
            (width / scale).roundToInt().coerceAtLeast(1), (height / scale).roundToInt().coerceAtLeast(1),
        ))
        layout(width, height) {
            child.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

// §6.2, §6.3: resolve files with the current tab's scoped Explorer API. Mesh rendering
// stays with the registered file previewer. Sampling stages a normal node-param edit.
@Composable
internal fun PipelineNodePreview(
    tab: ProjectTab,
    workspace: Workspace,
    type: String,
    params: JsonObject,
    connected: Map<String, String?>,
    ports: Map<String, String>,
    outputs: JsonObject?,
    run: JsonObject?,
    sampleColor: ((String) -> Unit)?,
    compact: Boolean = false,
) {
    val api = tab.api ?: return
    val m = localMessages.current
    val colors = localColors.current
    val kind = PipelinePreviewKind.from(type) ?: return
    val sources = pipelinePreviewSources(kind, params, outputs, connected, ports)
    val result = sources.result
    var sourceId by remember(kind) { mutableStateOf<String?>(null) }
    val source = sources.inputs.firstOrNull { it.id == sourceId } ?: sources.inputs.firstOrNull()
    var showSource by remember(kind, result) { mutableStateOf(result == null) }
    var picking by remember(kind, source?.path) { mutableStateOf(false) }
    val state = tab.pipelineGraphs
    val smooth = state.smoothPreviews
    val path = if (showSource) source?.path else result
    val mesh = !showSource && sources.resultIsModel
    val freshness = run?.string("id") to run?.string("finished_at")
    var descriptor by remember(api, path, freshness, outputs) { mutableStateOf<JsonObject?>(null) }
    var image by remember(api, path, freshness, outputs) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(api, path, freshness, outputs) { mutableStateOf(false) }
    LaunchedEffect(api, path, freshness, outputs) {
        if (path == null) return@LaunchedEffect
        try {
            pipelinePreviewLoads.withPermit {
                val file = api.get("/explorer/file" + query("path" to path)).jsonObject
                if (mesh || file.obj("previewer") != null) {
                    descriptor = file
                } else {
                    image = api.previewImage(file, path, maxImageBytes, maxImagePixels)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
    }
    val reduced = LocalStudioReducedMotion.current
    val alpha by animateFloatAsState(if (image != null || descriptor != null) 1f else 0f,
        tween(if (reduced) 0 else StudioMotion.fast, easing = StudioMotion.standard), label = "Pipeline preview arrival")
    Column(Modifier.fillMaxWidth().then(if (compact) Modifier.fillMaxHeight()
        .padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1) else Modifier),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        if (!compact) LibrarySectionTitle(m["pipelinePreviewTitle"])
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
            if (source != null && result != null) {
                PipelineButton("", m[if (showSource) "pipelinePreviewResult" else "pipelinePreviewSource"], compact = true) {
                    showSource = !showSource
                    picking = false
                }
            }
            if (showSource && sources.inputs.size > 1 && source != null) {
                PluginChoice(m["pipelinePreviewSource"], sources.inputs.map { it.id to it.label }, source.id, true) { sourceId = it }
            }
            if (kind == PipelinePreviewKind.KEY_COLOR && sampleColor != null && source != null) {
                LibraryIconAction("palette", m["pipelinePreviewSample"], selected = picking) {
                    picking = !picking
                    if (picking) showSource = true
                }
            }
            if (image != null) PipelineButton("eye", m[if (smooth) "pipelinePreviewSharp" else "pipelinePreviewSmooth"],
                iconOnly = true) { state.smoothPreviews = !smooth }
            if (kind == PipelinePreviewKind.KEY_COLOR) {
                val keyColor = connected["key_color"] ?: params.string("key_color").takeUnless { "key_color" in connected }
                val rgb = keyColor?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
                val label = m["pipelineFieldPickColor"]
                if (rgb != null) {
                    // The swatch shows the exact sampled artifact color, not a theme tint.
                    val shape = RoundedCornerShape(StudioTokens.radiusSm)
                    val swatch = Modifier.size(StudioTokens.iconSize).background(Color(0xff000000 or rgb), shape)
                    if (sampleColor == null) {
                        Spacer(swatch)
                    } else {
                        StudioTooltip(label) {
                            Box(Modifier.size(StudioTokens.controlMinHeight).studioFocusRing(shape, background = colors.surface)
                                .clickable(role = Role.Button) { choosePipelineColor(keyColor.orEmpty(), label)?.let(sampleColor) }
                                .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                                Spacer(swatch)
                            }
                        }
                    }
                    val tolerance = connected["tolerance"] ?: params.string("tolerance").takeUnless { "tolerance" in connected }
                    LibraryCaption(listOfNotNull(keyColor, tolerance?.let { "±$it%" }).joinToString(" "), mono = true)
                }
            }
        }
        if (kind == PipelinePreviewKind.CROP) {
            val width = params.string("width") ?: "—"
            val height = params.string("height") ?: "—"
            val threshold = params.string("alpha_threshold") ?: "—"
            LibraryCaption(m["pipelinePreviewCropTarget"].replace("{width}", width).replace("{height}", height)
                .replace("{alpha}", threshold), mono = true)
        }
        val loaded = image
        val file = descriptor
        when {
            failed -> Muted(m["libraryNoPreview"])
            path == null -> Muted(m["pipelinePreviewNoArtifact"])
            file != null -> key(path, freshness, outputs) {
                Box(Modifier.fillMaxWidth().then(if (compact) Modifier.weight(1f) else Modifier.height(230.dp))
                    .graphicsLayer { this.alpha = alpha }) {
                    if (file.obj("previewer") != null) NativePluginFilePreview(tab, path, file, workspace, Modifier.fillMaxSize(), compact)
                    else LibraryEmpty("file-question", m["libraryNoPreview"], null)
                }
            }
            loaded != null -> {
                val measured = outputs.dimensions("source_size", 2)
                val overlays = if (kind == PipelinePreviewKind.CROP && showSource && measured == listOf(loaded.width.toFloat(), loaded.height.toFloat())) {
                    listOf("bounds" to colors.good, "frame" to colors.warning).mapNotNull { (key, color) ->
                        outputs.dimensions(key, 4)?.let { rect -> rect to color }
                    }
                } else emptyList()
                val onSample = rememberUpdatedState(sampleColor)
                val sampleModifier = if (picking && showSource) Modifier.pointerInput(loaded) {
                    detectTapGestures { point ->
                        val bounds = pipelineImageBounds(Size(size.width.toFloat(), size.height.toFloat()), loaded.width, loaded.height)
                        if (!bounds.contains(point)) return@detectTapGestures
                        val x = ((point.x - bounds.left) * loaded.width / bounds.width).toInt().coerceIn(0, loaded.width - 1)
                        val y = ((point.y - bounds.top) * loaded.height / bounds.height).toInt().coerceIn(0, loaded.height - 1)
                        val color = loaded.toPixelMap(x, y, 1, 1)[0, 0]
                        if (color.alpha > 0f) {
                            onSample.value?.invoke(String.format(Locale.ROOT, "#%06x", color.toArgb() and 0xffffff))
                            picking = false
                        }
                    }
                } else Modifier
                Canvas(Modifier.fillMaxWidth().then(if (compact) Modifier.weight(1f) else Modifier.height(180.dp))
                    .clip(RoundedCornerShape(StudioTokens.radiusMd)).background(colors.inset).then(sampleModifier).graphicsLayer { this.alpha = alpha }) {
                    val bounds = pipelineImageBounds(size, loaded.width, loaded.height)
                    val tile = 8.dp.toPx()
                    for (row in 0 until (bounds.height / tile).toInt() + 1) {
                        for (column in row % 2 until (bounds.width / tile).toInt() + 1 step 2) {
                            val x = column * tile
                            val y = row * tile
                            drawRect(colors.faint.copy(alpha = .15f), bounds.topLeft + Offset(x, y),
                                Size(min(tile, (bounds.width - x).coerceAtLeast(0f)), min(tile, (bounds.height - y).coerceAtLeast(0f))))
                        }
                    }
                    drawImage(loaded, dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
                        dstSize = IntSize(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)),
                        filterQuality = if (smooth) FilterQuality.Medium else FilterQuality.None)
                    val scale = bounds.width / loaded.width
                    overlays.forEach { (rect, color) ->
                        val (x, y, width, height) = rect
                        if (width > 0 && height > 0) drawRect(color, bounds.topLeft + Offset(x * scale, y * scale),
                            Size(width * scale, height * scale), style = Stroke(1.5.dp.toPx()))
                    }
                }
                val removed = outputs?.string("removed_pixels")?.takeUnless { showSource }
                val edge = outputs?.string("edge_pixels")
                val caption = if (removed == null) "${loaded.width}×${loaded.height} · ${path.substringAfterLast('/').substringAfterLast('\\')}"
                    else if (edge == null) m["pipelinePreviewRemoved"].replace("{count}", removed)
                    else m["pipelinePreviewRemovedEdge"].replace("{removed}", removed).replace("{edge}", edge)
                LibraryCaption(caption,
                    mono = true, maxLines = if (compact) 1 else 2)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                Muted(m["loading"])
            }
        }
    }
}
