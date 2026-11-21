package studio.voxarium.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val CARD_WIDTH = 180f
private const val SPECIALIZED_CARD_WIDTH = 252f
private const val HEADER_HEIGHT = 34f
private const val BODY_HEIGHT = 32f
private const val PORT_HEIGHT = 20f
private const val PORT_DIAMETER = 10f
private val portHitSize = 24.dp
private const val COORDINATE_LIMIT = 1_000_000_000.0
internal val revisionPattern = Regex("[0-9a-f]{64}")
// Registry category colors mirror the graph legend; explicit per-node schema colors take precedence.
private val categoryColors = listOf(0xff7c5cff, 0xff39c5cf, 0xff3fb950, 0xffd29922, 0xffdb61a2, 0xff58a6ff, 0xfff85149)
// Same curated schema icons and fallback as frontend/src/lib/icons.ts.
private val pipelineNodeIcons = setOf(
    "blocks", "box", "boxes", "braces", "brain", "circle", "code", "cog", "cpu", "database", "download", "eye",
    "file-input", "file-json", "file-output", "file-plus", "file-text", "filter", "folder-search", "folder-symlink",
    "folder-tree", "folder-up", "globe", "grid-3x3", "hash", "image", "layers", "megaphone", "message-square",
    "package", "play", "plus", "repeat", "shapes", "sparkles", "tag", "terminal", "timer", "type", "upload", "wand-2",
)

internal class PipelineNodeDraft(val original: JsonObject, defaultLabel: String = "") {
    val nodeId = requireNotNull(original.string("id"))
    private val initialLabel = original.string("label")?.takeIf(String::isNotBlank) ?: defaultLabel
    private val initialX = original.obj("position")?.string("x") ?: "0"
    private val initialY = original.obj("position")?.string("y") ?: "0"
    private val initialDisabled = original.flag("disabled")
    private val initialPause = original.flag("pause_on_complete")
    var label by mutableStateOf(TextFieldValue(initialLabel))
    var x by mutableStateOf(TextFieldValue(initialX))
    var y by mutableStateOf(TextFieldValue(initialY))
    var disabled by mutableStateOf(initialDisabled)
    var pause by mutableStateOf(initialPause)
    val parameters = mutableStateMapOf<String, PipelineParameterEdit>()

    private val positionChanged: Boolean get() = x.text != initialX || y.text != initialY
    val validPosition get() = !positionChanged || coordinate(x) != null && coordinate(y) != null
    val validParameters get() = parameters.values.all(PipelineParameterEdit::valid)
    val dirty: Boolean get() = positionChanged || label.text != initialLabel || disabled != initialDisabled ||
        pause != initialPause || parameters.isNotEmpty()

    fun changes(): Map<String, JsonElement>? {
        if (!validPosition || !validParameters) return null
        return buildMap {
            if (label.text != initialLabel) put("label", label.text.takeIf(String::isNotBlank)?.let(::JsonPrimitive) ?: JsonNull)
            if (positionChanged) {
                val latest = original.obj("position").orEmpty()
                put("position", JsonObject(latest + mapOf("x" to JsonPrimitive(coordinate(x)), "y" to JsonPrimitive(coordinate(y)))))
            }
            if (disabled != initialDisabled) put("disabled", JsonPrimitive(disabled))
            if (pause != initialPause) put("pause_on_complete", JsonPrimitive(pause))
            if (parameters.isNotEmpty()) {
                val params = original.obj("params").orEmpty().toMutableMap()
                parameters.forEach { (id, edit) ->
                    val value = edit.value
                    if (value == null) params.remove(id) else params[id] = value
                }
                put("params", JsonObject(params))
            }
        }
    }

    fun editParameter(id: String, port: JsonObject?, text: TextFieldValue?) {
        val edit = PipelineParameterEdit(text, port)
        if (edit.valid && original.obj("params")?.get(id) == edit.value) parameters.remove(id)
        else parameters[id] = edit
    }

    private fun coordinate(field: TextFieldValue) = field.text.trim().replace(',', '.').coordinate()
}

// §8.2: the graph is serialized as one YAML-backed definition. Merge the changed
// fields into the fresh definition so plugin parameters, pins and edges round-trip.
internal fun mergePipelineNode(
    pipeline: JsonObject,
    original: JsonObject,
    changes: Map<String, JsonElement>,
): JsonObject? {
    val id = original.string("id") ?: return null
    val nodes = pipeline["nodes"] as? JsonArray ?: return null
    val index = nodes.indexOfFirst { (it as? JsonObject)?.string("id") == id }
    if (index < 0) return null
    val current = nodes[index].jsonObject
    val merged = nodes.toMutableList()
    merged[index] = mergePipelineNodeFields(current, original, changes) ?: return null
    return JsonObject(pipeline + ("nodes" to JsonArray(merged)))
}

private fun String.coordinate(): Double? = toDoubleOrNull()?.takeIf { abs(it) <= COORDINATE_LIMIT }

internal fun pipelineNodeDragPosition(start: Offset, pointerDelta: Offset, scale: Float): Offset {
    val point = start + pointerDelta / scale
    val limit = COORDINATE_LIMIT.toFloat()
    return Offset(point.x.coerceIn(-limit, limit), point.y.coerceIn(-limit, limit))
}

// §8.2: stage only the final position. The normal graph save rebases it before a guarded YAML write.
internal fun PipelineGraphState.stageNodePosition(id: String, pipeline: JsonObject, original: JsonObject, point: Offset): Boolean {
    val nodeId = original.string("id") ?: return false
    val current = preview(id, pipeline).objects("nodes").find { it.string("id") == nodeId }
    if (current == null || current.string("type") != original.string("type") || current["position"] != original["position"]) {
        problems[id] = Problem("pipelineGraphChanged")
        return false
    }
    return stage(id, pipeline) { graph ->
        // The inspector may have changed this node before the drag. Keep those fields too.
        val node = graph.objects("nodes").first { it.string("id") == nodeId }
        val position = JsonObject(node.obj("position").orEmpty() + mapOf(
            "x" to JsonPrimitive(point.x.toDouble()), "y" to JsonPrimitive(point.y.toDouble()),
        ))
        mergePipelineNode(graph, node, mapOf("position" to position)) ?: graph
    }
}

private data class PipelineNodeDrag(
    val node: GraphNode,
    val center: Offset,
    val scale: Float,
    val pointerDelta: Offset = Offset.Zero,
) {
    val point get() = pipelineNodeDragPosition(node.point, pointerDelta, scale)
}

internal data class GraphPort(
    val id: String, val label: String, val output: Boolean, val linkable: Boolean = true, val type: String? = null,
)

internal data class GraphNode(
    val id: String,
    val type: String,
    val point: Offset,
    val data: JsonObject,
    val schema: JsonObject?,
    val ports: List<GraphPort>,
    val run: JsonObject?,
) {
    val title: String get() = data.string("label")?.takeIf(String::isNotBlank) ?: schema?.string("name") ?: type.ifBlank { id }
    val status: String? get() = run?.string("status")
    val preview = schema?.let { PipelinePreviewKind.from(type) }
    val width = if (preview == null) CARD_WIDTH else SPECIALIZED_CARD_WIDTH
    val bodyHeight = BODY_HEIGHT + if (schema == null) 16f else 0f
    private val statusHeight = if (status == null) 0f else 3f
    private val portTop = 1f + statusHeight + HEADER_HEIGHT + bodyHeight + 3f
    val height = 2f + statusHeight + HEADER_HEIGHT + bodyHeight +
        (if (ports.isEmpty()) 0f else 9f + PORT_HEIGHT * ports.size) +
        (if (status == "running") 26f else 0f) + (preview?.height ?: 0f)

    fun bounds(at: Offset) = Rect(at, Size(width, height))

    fun anchor(port: String, output: Boolean): Offset {
        val index = ports.indexOfFirst { it.id == port && it.output == output }
        val y = if (index < 0) height / 2f else portTop + PORT_HEIGHT * (index + .5f)
        return point + Offset(if (output) width else 0f, y)
    }
}

internal data class GraphLayout(val nodes: List<GraphNode>, val edges: List<GraphEdge>) {
    val byId = nodes.associateBy(GraphNode::id)
    val connectedArtifacts = edges.groupBy(GraphEdge::to).mapValues { (node, inputs) ->
        val observed = byId[node]?.run?.obj("inputs")
        inputs.associate { edge -> edge.input to (observed.artifact(edge.input)
            ?: byId[edge.from]?.run?.obj("outputs").artifact(edge.output)) }
    }
    val origin = Offset(nodes.minOfOrNull { it.point.x } ?: 0f, nodes.minOfOrNull { it.point.y } ?: 0f)
    val width = ((nodes.maxOfOrNull { it.point.x + it.width } ?: 0f) - origin.x).coerceAtLeast(1f)
    val height = ((nodes.maxOfOrNull { it.point.y + it.height } ?: 0f) - origin.y).coerceAtLeast(1f)
    val center = origin + Offset(width / 2f, height / 2f)
}

private fun GraphNode.previewParameters(draft: PipelineNodeDraft?): JsonObject {
    val configured = data.obj("params").orEmpty().toMutableMap()
    draft?.parameters?.forEach { (id, edit) ->
        if (edit.valid) {
            val value = edit.value
            if (value == null) configured.remove(id) else configured[id] = value
        }
    }
    schema?.objects("inputs").orEmpty().forEach { port ->
        val id = port.string("id") ?: return@forEach
        val default = port["default"] ?: return@forEach
        if (configured[id] == null || configured[id] == JsonNull) configured[id] = default
    }
    return JsonObject(configured)
}

private fun GraphNode.previewPortLabels(): Map<String, String> = schema?.objects("inputs").orEmpty().mapNotNull { port ->
    val id = port.string("id") ?: return@mapNotNull null
    id to (port.string("label") ?: id)
}.toMap()

internal fun JsonObject.graphLayout(registry: List<JsonObject>, nodeRuns: List<JsonObject>): GraphLayout {
    val schemas = registry.associateBy { it.string("id") }
    val runsByNode = nodeRuns.associateBy { it.string("node_id") }
    val edges = objects("edges").mapNotNull(JsonObject::pipelineEdge)
    val inputs = edges.groupBy(GraphEdge::to)
    val outputs = edges.groupBy(GraphEdge::from)
    val source = objects("nodes").mapNotNull { node ->
        val id = node.string("id") ?: return@mapNotNull null
        val position = node.obj("position")
        val x = position?.string("x")?.coordinate()?.toFloat()
        val y = position?.string("y")?.coordinate()?.toFloat()
        Triple(id, node, if (x != null && y != null) Offset(x, y) else null)
    }
    val specs = source.map { (id, node, authored) ->
        val type = node.string("type").orEmpty()
        val schema = schemas[type]
        val connected = inputs[id].orEmpty().map(GraphEdge::input).toSet()
        val pins = node.obj("pins")
        val inPorts = schema?.objects("inputs").orEmpty().mapNotNull { port ->
            val portId = port.string("id") ?: return@mapNotNull null
            val pinned = (pins?.get(portId) as? JsonPrimitive)?.booleanOrNull ?: port.flag("connectable")
            if (!pinned && portId !in connected) return@mapNotNull null
            GraphPort(portId, port.string("label")?.ifBlank { portId } ?: portId, false, type = port.string("type"))
        }
        val outPorts = schema?.objects("outputs").orEmpty().mapNotNull { port ->
            val portId = port.string("id") ?: return@mapNotNull null
            GraphPort(portId, port.string("label")?.ifBlank { portId } ?: portId, true, type = port.string("type"))
        }
        // Edges from older and unavailable plugin schemas still belong on the canvas.
        val unknownInputs = connected.filterNot { input -> inPorts.any { it.id == input } }
        val unknownOutputs = outputs[id].orEmpty().map(GraphEdge::output).distinct()
            .filterNot { output -> outPorts.any { it.id == output } }
        val ports = inPorts + unknownInputs.map { GraphPort(it, it, false, linkable = false) } +
            outPorts + unknownOutputs.map { GraphPort(it, it, true, linkable = false) }
        GraphNode(id, type, authored ?: Offset.Zero, node, schema, ports, runsByNode[id])
    }
    // §6.3: authored positions remain stable. Unpositioned cards also need room for embedded previews.
    val occupied = specs.zip(source).mapNotNull { (node, record) -> record.third?.let(node::bounds) }.toMutableList()
    val displayed = mutableSetOf<Offset>()
    val spacingX = (specs.maxOfOrNull(GraphNode::width) ?: CARD_WIDTH) + 58f
    var nextRow = 0f
    val rowTops = specs.chunked(4).map { row -> nextRow.also { nextRow += maxOf(130f, row.maxOf(GraphNode::height) + 36f) } }
    val nodes = specs.mapIndexed { index, node ->
        val authored = source[index].third
        if (authored != null && displayed.add(authored)) return@mapIndexed node
        var point = Offset((index % 4) * spacingX, rowTops[index / 4])
        while (occupied.any { it.overlaps(node.bounds(point)) }) point = point.copy(y = point.y + node.height + 36f)
        occupied += node.bounds(point)
        node.copy(point = point)
    }
    return GraphLayout(nodes, edges)
}

@Composable
internal fun PipelineGraph(
    tab: ProjectTab,
    workspace: Workspace,
    pipeline: JsonObject,
    registry: List<JsonObject>,
    registryError: Problem?,
    nodeRuns: List<JsonObject>,
    runId: String?,
    canEdit: Boolean,
    modifier: Modifier,
    canRun: Boolean = false,
    runFrom: (String) -> Unit = {},
) {
    val pipelineId = pipeline.string("id") ?: return
    val colors = localColors.current
    val m = localMessages.current
    val state = tab.pipelineGraphs
    val working = state.preview(pipelineId, pipeline)
    val layout = remember(working, registry, nodeRuns) { working.graphLayout(registry, nodeRuns) }
    val draft = state.drafts[pipelineId]
    val selectedId = state.selectedNodes[pipelineId]
    val selected = layout.byId[draft?.nodeId ?: selectedId]
    val editing = canEdit && !tab.acting
    val canStage = editing && draft?.validPosition != false && draft?.validParameters != false
    var pendingSource by remember(pipelineId) { mutableStateOf<PipelinePort?>(null) }
    val sourcePort = pendingSource?.takeIf { source ->
        layout.byId[source.node]?.ports?.any { it.output && it.id == source.port && it.linkable } == true
    }
    var focusedPort by remember(pipelineId) { mutableStateOf<Pair<String, String>?>(null) }
    val palette = remember(registry) {
        registry.mapNotNull { it.string("category") }.distinct().sorted().mapIndexed { index, category ->
            category to Color(categoryColors[index % categoryColors.size])
        }.toMap()
    }
    fun colorFor(schema: JsonObject?): Color {
        if (schema == null) return colors.faint
        val explicit = schema.string("color")?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
        return explicit?.let { Color(0xff000000 or it) } ?: palette[schema.string("category")] ?: colors.accent
    }

    var pan by remember(pipelineId) { mutableStateOf(Offset.Zero) }
    var zoom by remember(pipelineId) { mutableStateOf<Float?>(null) }
    var nodeDrag by remember(pipelineId) { mutableStateOf<PipelineNodeDrag?>(null) }
    var viewSize by remember(pipelineId) { mutableStateOf(IntSize.Zero) }
    val reduced = LocalStudioReducedMotion.current
    val scope = rememberCoroutineScope()
    var cameraJob by remember(pipelineId) { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val maxScale = 2f * density.density
    val showMiniMap = viewSize.width >= with(density) { 280.dp.toPx() } &&
        viewSize.height >= with(density) { 360.dp.toPx() }
    val lowerInset = with(density) {
        (if (showMiniMap) 168.dp.toPx() else 52.dp.toPx()).coerceAtMost(viewSize.height * .45f)
    }
    val fit = if (viewSize.width == 0 || viewSize.height == 0 || layout.nodes.isEmpty()) density.density else minOf(
        viewSize.width / (layout.width * 1.1f),
        (viewSize.height - lowerInset) / (layout.height * 1.1f),
        maxScale,
    )
    val scale = nodeDrag?.scale ?: zoom ?: fit
    val minScale = minOf(.2f * density.density, fit / 3f)
    val viewCenter = Offset(viewSize.width / 2f, (viewSize.height - lowerInset) / 2f)
    fun project(point: Offset) = viewCenter + (point - (nodeDrag?.center ?: layout.center)) * scale + pan
    fun movement(node: GraphNode) = nodeDrag?.takeIf { it.node.id == node.id }?.let { it.point - node.point } ?: Offset.Zero
    fun moveCamera(target: Offset, targetZoom: Float?, animated: Boolean) {
        cameraJob?.cancel()
        if (nodeDrag != null) return
        if (!animated || reduced) {
            pan = target
            zoom = targetZoom
            return
        }
        val fromPan = pan
        val fromScale = scale
        val toScale = targetZoom ?: fit
        cameraJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(StudioMotion.normal, easing = StudioMotion.standard)) { progress, _ ->
                pan = fromPan + (target - fromPan) * progress
                zoom = fromScale + (toScale - fromScale) * progress
            }
            zoom = targetZoom
        }
    }
    fun changeZoom(multiplier: Float, center: Offset = viewCenter, animated: Boolean = false) {
        val next = (scale * multiplier).coerceIn(minScale, maxScale)
        val target = (pan + viewCenter - center) * (next / scale) + center - viewCenter
        moveCamera(target, next, animated)
    }
    fun resetView() {
        moveCamera(Offset.Zero, null, animated = true)
    }
    fun select(node: GraphNode?): Boolean {
        if (node?.id != draft?.nodeId && draft != null && (!editing || !state.stage(pipelineId, pipeline))) return false
        if (node == null) state.selectedNodes.remove(pipelineId) else state.selectedNodes[pipelineId] = node.id
        return true
    }
    fun focus(node: GraphNode) {
        if (nodeDrag != null) return
        if (!select(node)) return
        val next = scale.coerceAtLeast(density.density)
        moveCamera((layout.center - node.point - Offset(node.width / 2f, node.height / 2f)) * next, next, animated = true)
    }
    fun portClicked(node: GraphNode, port: GraphPort) {
        if (nodeDrag != null || !canStage || !port.linkable) return
        val endpoint = PipelinePort(node.id, port.id)
        if (port.output) {
            if (select(node)) pendingSource = endpoint.takeUnless { it == sourcePort }
        } else if (sourcePort != null && sourcePort.node != node.id) {
            if (state.stage(pipelineId, pipeline) { it.connectPipelinePorts(sourcePort, endpoint) }) {
                state.selectedNodes[pipelineId] = node.id
                pendingSource = null
            }
        }
    }
    fun startDrag(node: GraphNode) {
        if (canStage && nodeDrag == null) {
            cameraJob?.cancel()
            nodeDrag = PipelineNodeDrag(node, layout.center, scale)
        }
    }
    fun moveDrag(nodeId: String, amount: Offset) {
        val active = nodeDrag?.takeIf { it.node.id == nodeId } ?: return
        nodeDrag = active.copy(pointerDelta = active.pointerDelta + amount)
    }
    fun finishDrag(nodeId: String, commit: Boolean) {
        val active = nodeDrag?.takeIf { it.node.id == nodeId } ?: return
        if (commit && canStage && active.point != active.node.point &&
            state.stageNodePosition(pipelineId, pipeline, active.node.data, active.point)) {
            val center = state.preview(pipelineId, pipeline).graphLayout(registry, nodeRuns).center
            // A drag can change the graph bounds. Keep the same world point under the pointer.
            pan += (center - active.center) * active.scale
            zoom = active.scale
        }
        nodeDrag = null
    }
    fun addNode(schema: JsonObject) {
        val type = schema.string("id") ?: return
        val width = if (PipelinePreviewKind.from(type) != null) SPECIALIZED_CARD_WIDTH else CARD_WIDTH
        val position = layout.center - pan / scale - Offset(width / 2f, BODY_HEIGHT / 2f)
        var nodeId: String? = null
        if (state.stage(pipelineId, pipeline) { graph ->
                val added = graph.addPipelineNode(type, position.x.toDouble(), position.y.toDouble(), state.reservedNodeIds(pipelineId))
                nodeId = added.second
                added.first
            }) {
            nodeId?.let { state.selectedNodes[pipelineId] = it }
            pendingSource = null
            resetView()
        }
    }

    fun sampleColor(node: GraphNode, color: String) {
        if (!canStage || nodeHasInput(layout, node.id, "key_color") || !select(node)) return
        val port = node.schema?.objects("inputs")?.firstOrNull { it.string("id") == "key_color" } ?: return
        state.problems.remove(pipelineId)
        val edit = state.drafts.getOrPut(pipelineId) { PipelineNodeDraft(node.data, node.title) }
        edit.editParameter("key_color", port, TextFieldValue(color))
    }

    val theme = localStudioTheme.current
    val customTint = theme?.obj("custom_tokens")?.string("--vxs-node-tint")
        ?.removeSuffix("%")?.toFloatOrNull()?.takeIf(Float::isFinite)
    val tint = (customTint ?: theme?.string("node_tint")?.toFloatOrNull() ?: 16f).coerceIn(0f, 60f) / 100f
    val grid = theme?.string("canvas_grid") ?: "dots"
    val present = remember(layout) { layout.nodes.map(GraphNode::type).toSet() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val paletteWidth = minOf(256.dp, maxWidth * .28f)
        val inspectorWidth = minOf(320.dp, maxWidth * .36f)
        Row(Modifier.fillMaxSize()) {
            PipelinePalette(registry, registryError, present, ::colorFor, Modifier.width(paletteWidth).fillMaxHeight(),
                add = ::addNode, canAdd = canStage) { schema ->
                val matching = layout.nodes.filter { it.type == schema.string("id") }
                if (matching.isNotEmpty()) {
                    val next = matching.indexOfFirst { it.id == selectedId } + 1
                    focus(matching[next.mod(matching.size)])
                }
            }
            PipelineRailSeparator()
            val currentZoom by rememberUpdatedState<(Float, Offset) -> Unit>({ multiplier, center ->
                changeZoom(multiplier, center)
            })
            val clearSelection by rememberUpdatedState {
                if (nodeDrag == null) {
                    pendingSource = null
                    select(null)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().clipToBounds().background(colors.canvas)
                    .onSizeChanged { viewSize = it }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { clearSelection() }
                    .pointerInput(pipelineId) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            if (nodeDrag == null) {
                                cameraJob?.cancel()
                                pan += amount
                            }
                        }
                    }.pointerInput(pipelineId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                val pointer = event.changes.firstOrNull() ?: continue
                                if (pointer.isConsumed || pointer.scrollDelta.y == 0f) continue
                                currentZoom(if (pointer.scrollDelta.y < 0f) 1.2f else 1f / 1.2f, pointer.position)
                                pointer.consume()
                            }
                        }
                    },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val step = 18f * scale
                    if (grid != "none" && step >= 3.dp.toPx()) {
                        val zero = project(Offset.Zero)
                        val startX = (zero.x - 1 - step / 2f).mod(step)
                        val startY = (zero.y - 1 - step / 2f).mod(step)
                        var y = startY
                        while (y < size.height) {
                            if (grid == "lines") drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), 1f)
                            else {
                                var x = startX
                                while (x < size.width) {
                                    drawCircle(colors.grid, .5f * scale, Offset(x, y))
                                    x += step
                                }
                            }
                            y += step
                        }
                        if (grid == "lines") {
                            var x = startX
                            while (x < size.width) {
                                drawLine(colors.grid, Offset(x, 0f), Offset(x, size.height), 1f)
                                x += step
                            }
                        }
                    }
                    for (edge in layout.edges) {
                        val source = layout.byId[edge.from] ?: continue
                        val target = layout.byId[edge.to] ?: continue
                        val start = project(source.anchor(edge.output, true) + movement(source))
                        val end = project(target.anchor(edge.input, false) + movement(target))
                        val dx = (end.x - start.x) / scale
                        val bend = (if (dx >= 0f) dx / 2f else 6.25f * sqrt(-dx)) * scale
                        val line = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                        }
                        drawPath(line, colors.faint, style = Stroke(2f * scale))
                    }
                }
                for (node in layout.nodes) {
                    val point = project(node.point + movement(node))
                    val moving = nodeDrag?.node?.id == node.id
                    if (!moving && (point.x + node.width * scale < 0 || point.y + node.height * scale < 0 ||
                            point.x > viewSize.width || point.y > viewSize.height)) continue
                    key(node.id) {
                        val beginMove by rememberUpdatedState { startDrag(node) }
                        val finishMove by rememberUpdatedState<(Boolean) -> Unit> { finishDrag(node.id, it) }
                        val header = if (canStage || moving) Modifier.pointerInput(pipelineId, node.id) {
                            try {
                                detectDragGestures(
                                    onDragStart = { beginMove() },
                                    onDragEnd = { finishMove(true) },
                                    onDragCancel = { finishMove(false) },
                                ) { change, amount ->
                                    change.consume()
                                    moveDrag(node.id, amount)
                                }
                            } finally {
                                finishMove(false)
                            }
                        } else Modifier
                        val nodeDraft = draft?.takeIf { it.nodeId == node.id }
                        PipelineNodeCard(node, nodeDraft, node.id == selected?.id,
                            colorFor(node.schema), tint, scale / density.density,
                            runId, tab.pipelineProgress.frame(runId, node.id),
                            Modifier.zIndex(if (moving) 1f else 0f).offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) },
                            headerModifier = header, onSelect = { select(node) }, sourcePort = sourcePort,
                        ) {
                            PipelineNodePreview(tab, workspace, node.type, node.previewParameters(nodeDraft),
                                layout.connectedArtifacts[node.id].orEmpty(), node.previewPortLabels(),
                                node.run?.obj("outputs"), node.run,
                                sampleColor = if (canStage && !nodeHasInput(layout, node.id, "key_color")) {
                                    { color -> sampleColor(node, color) }
                                } else null,
                                compact = true)
                        }
                    }
                }
                for (node in layout.nodes) for (port in node.ports) {
                    val point = project(node.anchor(port.id, port.output) + movement(node))
                    if (point.x !in 0f..viewSize.width.toFloat() || point.y !in 0f..viewSize.height.toFloat()) continue
                    key(node.id, port.id, port.output) {
                        val canLink = canStage && port.linkable && (port.output || sourcePort != null && sourcePort.node != node.id)
                        val selectedPort = port.output && sourcePort == PipelinePort(node.id, port.id)
                        val direction = m[if (port.output) "pipelineOutputs" else "pipelineInputs"]
                        val identity = listOf(port.id.takeUnless { it == port.label }, port.type).filterNotNull().joinToString(" · ")
                        val title = "${node.title} · $direction · ${port.label}" + if (identity.isEmpty()) "" else " ($identity)"
                        // Keep adjacent hit regions disjoint at extreme zoom, but leave the
                        // unscaled target wide and preserve keyboard access at every zoom level.
                        val nearest = node.ports.asSequence().filter { it != port && it.output == port.output }.minOfOrNull {
                            abs(node.anchor(it.id, it.output).y - node.anchor(port.id, port.output).y)
                        }
                        val hit = with(density) {
                            nearest?.let { (it * scale).toDp().coerceIn(1.dp, portHitSize) } ?: portHitSize
                        }
                        val portKey = node.id + ":" + port.output + ":" + port.id
                        PipelinePortTarget(point, hit, scale / density.density, port.output, title, colorFor(node.schema),
                            selectedPort, canLink, focused = {
                                if (it) focusedPort = portKey to title
                                else if (focusedPort?.first == portKey) focusedPort = null
                            }) { portClicked(node, port) }
                    }
                }
                focusedPort?.second?.let { label ->
                    Text(label, Modifier.align(Alignment.TopEnd).padding(StudioTokens.space2).zIndex(2f)
                        .background(colors.surface, RoundedCornerShape(StudioTokens.radiusMd)).padding(StudioTokens.space2),
                        color = colors.text, fontSize = StudioTokens.textSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (sourcePort != null) {
                    Row(Modifier.align(Alignment.TopStart).padding(StudioTokens.space2)
                        .background(colors.infoSurface, RoundedCornerShape(StudioTokens.radiusMd))
                        .padding(horizontal = StudioTokens.space2), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        Text(m["pipelineLinkPickInput"],
                            color = StudioTokens.ensureContrast(colors.info, colors.infoSurface, StudioTokens.textContrast),
                            fontSize = StudioTokens.textSmall)
                        PipelineButton("x", m["cancel"], iconOnly = true) { pendingSource = null }
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).padding(StudioTokens.space2)
                    .studioShadow(RoundedCornerShape(StudioTokens.radiusMd), colors.shadowSm)
                    .clip(RoundedCornerShape(StudioTokens.radiusMd)).background(colors.surface)) {
                    PipelineButton("plus", m["pipelineGraphZoomIn"], enabled = scale < maxScale, iconOnly = true) {
                        changeZoom(1.35f, animated = true)
                    }
                    PipelineButton("minus", m["pipelineGraphZoomOut"], enabled = scale > minScale, iconOnly = true) {
                        changeZoom(1f / 1.35f, animated = true)
                    }
                    PipelineButton("maximize", m["pipelineGraphFit"], iconOnly = true, onClick = ::resetView)
                }
                if (layout.nodes.isNotEmpty() && showMiniMap) {
                    PipelineMiniMap(layout, viewSize, viewCenter, scale, pan, selected?.id,
                        Modifier.align(Alignment.BottomEnd).padding(StudioTokens.space2)) { point ->
                        moveCamera((layout.center - point) * scale, scale, animated = true)
                    }
                }
            }
            if (selected != null) {
                PipelineRailSeparator()
                PipelineNodeInspector(tab, workspace, pipelineId, selected, layout, canEdit, canStage, ::focus,
                    Modifier.width(inspectorWidth).fillMaxHeight(),
                    remove = {
                        if (state.stage(pipelineId, pipeline) { it.removePipelineNode(selected.id) }) {
                            state.selectedNodes.remove(pipelineId)
                            pendingSource = null
                        }
                    }, disconnect = { edge -> state.stage(pipelineId, pipeline) { it.disconnectPipelineEdge(edge) } },
                    togglePin = { port, checked -> port.string("id")?.let { id ->
                        state.stage(pipelineId, pipeline) { it.setPipelinePin(selected.id, id, checked, port.flag("connectable")) }
                    } },
                    sourcePort = sourcePort, onPort = { port -> portClicked(selected, port) },
                    runFrom = if (canRun) ({ runFrom(selected.id) }) else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PipelinePortTarget(
    point: Offset, hitHeight: androidx.compose.ui.unit.Dp, scale: Float, output: Boolean, label: String,
    category: Color, linked: Boolean, enabled: Boolean, focused: (Boolean) -> Unit, onClick: () -> Unit,
) {
    val colors = localColors.current
    val shape = RoundedCornerShape(StudioTokens.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val currentFocused by rememberUpdatedState(focused)
    val isFocused = remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (isFocused.value) currentFocused(false) } }
    StudioTooltip(label, modifier = Modifier.offset {
        IntOffset((point.x - portHitSize.toPx() / 2f).roundToInt(), (point.y - hitHeight.toPx() / 2f).roundToInt())
    }) {
        val action = if (enabled) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            else Modifier.focusable()
        Canvas(Modifier.size(portHitSize, hitHeight).studioFocusRing(shape, background = colors.surface)
            .onFocusChanged {
                if (isFocused.value != it.isFocused) {
                    isFocused.value = it.isFocused
                    currentFocused(it.isFocused)
                }
            }.hoverable(interaction).semantics { contentDescription = label }.then(action)) {
            val radius = (PORT_DIAMETER.dp.toPx() * scale / 2f).coerceIn(2.5.dp.toPx(), size.width / 2f)
            val border = if (linked || hovered || isFocused.value) colors.focusOn(colors.surface) else colors.controlBorder
            // The node clips the outer half. The edge still attaches at the centre of the port.
            clipRect(left = if (output) 0f else center.x, right = if (output) center.x else size.width) {
                drawCircle(border, radius, center)
                drawCircle(category, (radius - (1.5.dp.toPx() * scale).coerceAtLeast(1.dp.toPx())).coerceAtLeast(.5f), center)
            }
        }
    }
}

@Composable
private fun PipelineRailSeparator() {
    val colors = localColors.current
    Box(Modifier.width(StudioTokens.space1 + StudioTokens.borderWidth).fillMaxHeight()) {
        Box(Modifier.width(StudioTokens.borderWidth).fillMaxHeight().background(colors.border))
    }
}

@Composable
private fun PipelinePalette(
    registry: List<JsonObject>,
    problem: Problem?,
    presentTypes: Set<String>,
    colorFor: (JsonObject) -> Color,
    modifier: Modifier,
    add: (JsonObject) -> Unit,
    canAdd: Boolean,
    focusType: (JsonObject) -> Unit,
) {
    val colors = localColors.current
    val m = localMessages.current
    var filter by remember { mutableStateOf(TextFieldValue()) }
    val cssLineBox = JewelTheme.defaultTextStyle.copy(lineHeightStyle =
        LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
    val groups = remember(registry, filter.text) {
        val needle = filter.text.trim()
        registry.filter { schema ->
            listOf("name", "id", "category", "description").any { schema.string(it)?.contains(needle, ignoreCase = true) == true }
        }.groupBy { it.string("category").orEmpty() }.toSortedMap()
    }
    Column(modifier.background(colors.surface)) {
        StudioField(m["pipelinePaletteSearch"], filter, { filter = it },
            modifier = Modifier.fillMaxWidth().padding(StudioTokens.space2), placeholder = m["pipelinePaletteSearch"])
        Rule()
        ScrollList(Modifier.weight(1f).padding(StudioTokens.space2)) {
            if (problem != null) item { ProblemText(problem) }
            if (groups.isEmpty() && problem == null) {
                item {
                    Muted(m["pipelinePaletteNoMatch"].replace("{query}", filter.text),
                        modifier = Modifier.padding(horizontal = StudioTokens.space1, vertical = StudioTokens.space2))
                }
            }
            groups.entries.forEachIndexed { index, (category, schemas) ->
                item {
                    Text(category.uppercase(), Modifier.padding(start = StudioTokens.space1, top = if (index == 0) 0.dp else StudioTokens.space3, bottom = StudioTokens.space1),
                        style = cssLineBox, color = colors.faint, fontWeight = FontWeight.SemiBold,
                        fontSize = StudioTokens.textSmall, lineHeight = StudioTokens.textSmall * StudioTokens.lineHeightMultiplier)
                }
                items(schemas, key = { it.string("id").orEmpty() }) { schema ->
                    val content: @Composable RowScope.() -> Unit = {
                        StudioIcon(pipelineNodeIcon(schema), Modifier.size(StudioTokens.iconSize), colorFor(schema))
                        Column(Modifier.weight(1f)) {
                            Text(schema.string("name").orEmpty(), style = cssLineBox, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(schema.string("id").orEmpty(), style = cssLineBox, fontFamily = localStudioMono.current, color = colors.faint,
                                fontSize = StudioTokens.textTiny, lineHeight = StudioTokens.textTiny * StudioTokens.lineHeightMultiplier, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PipelineButton("plus", listOfNotNull(m["pipelineNodeAdd"], schema.string("name"), schema.string("id"))
                            .distinct().joinToString(" · "), enabled = canAdd, iconOnly = true) { add(schema) }
                    }
                    if (schema.string("id") in presentTypes) {
                        StudioSelectionRow(false, { focusType(schema) }, Modifier.fillMaxWidth(), role = Role.Button, content = content)
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.rowPadX, vertical = StudioTokens.space2),
                            horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
                            verticalAlignment = Alignment.CenterVertically, content = content)
                    }
                }
            }
        }
    }
}

private fun pipelineNodeIcon(schema: JsonObject?): String {
    val name = schema?.string("icon")?.lowercase()
    return when (name) {
        "grid" -> "grid-3x3"
        "wand" -> "wand-2"
        in pipelineNodeIcons -> requireNotNull(name)
        else -> "box"
    }
}

@Composable
private fun PipelineNodeCard(node: GraphNode, draft: PipelineNodeDraft?, selected: Boolean, category: Color,
    tint: Float, scale: Float, runId: String?, progress: JsonObject?, modifier: Modifier, headerModifier: Modifier, onSelect: () -> Unit,
    sourcePort: PipelinePort?, previewContent: @Composable () -> Unit) {
    val colors = localColors.current
    val m = localMessages.current
    val disabled = draft?.disabled ?: node.data.flag("disabled")
    val pause = draft?.pause ?: node.data.flag("pause_on_complete")
    val radius = RoundedCornerShape(StudioTokens.radiusLg * scale)
    val status = node.status
    val title = draft?.label?.text?.ifBlank { node.schema?.string("name") ?: node.type } ?: node.title
    val nodeIdentity = listOfNotNull(title, node.id, node.type.takeIf(String::isNotBlank),
        status?.let { m.pipelineStatus(it) }).distinct().joinToString(" · ")
    val shadow = colors.shadowSm
    Column(
        modifier.size((node.width * scale).dp, (node.height * scale).dp)
            .alpha(if (disabled) StudioTokens.disabledOpacity else 1f)
            .studioFocusRing(radius, background = colors.surface)
            .studioShadow(radius, shadow.copy(offsetY = shadow.offsetY * scale, blur = shadow.blur * scale))
            .clip(radius).background(colors.surface)
            .border(StudioTokens.borderWidth, studioColor(if (selected) colors.accent else colors.border), radius)
            .selectable(selected, role = Role.Button, onClick = onSelect).semantics { contentDescription = nodeIdentity }
            .padding(StudioTokens.borderWidth * scale),
    ) {
        if (status != null) {
            key(runId) { PipelineNodeStrip(status, scale) }
        }
        if (scale < .22f) return@Column
        Row(Modifier.fillMaxWidth().height(((HEADER_HEIGHT - 1) * scale).dp).background(category.copy(alpha = tint))
            .then(headerModifier).padding(horizontal = StudioTokens.space2 * scale),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1 * scale)) {
            StudioIcon(pipelineNodeIcon(node.schema), Modifier.size(StudioTokens.iconSize * scale), category)
            Text(title,
                Modifier.weight(1f), fontSize = (colors.textSize * scale).sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (node.schema?.string("iterates") != null) StudioIcon("repeat", Modifier.size(StudioTokens.compactIconSize * scale), colors.faint)
            if (pause) StudioIcon("circle-pause", Modifier.size(StudioTokens.compactIconSize * scale), colors.faint)
            if (disabled) StudioIcon("eye-off", Modifier.size(StudioTokens.compactIconSize * scale), colors.faint)
            if (status != null) PipelineStatus(status, scale = scale)
        }
        Box(Modifier.fillMaxWidth().height(StudioTokens.borderWidth * scale).background(colors.subtleBorder))
        Column(Modifier.fillMaxWidth().height((node.bodyHeight * scale).dp).padding(horizontal = StudioTokens.space2 * scale),
            verticalArrangement = Arrangement.Center) {
            val details = if (node.preview == PipelinePreviewKind.GENERATE_MESH) {
                pipelineMeshSummary(node.previewParameters(draft), m)
            } else null
            Text(details ?: node.schema?.string("category") ?: m["pipelineUnknownType"],
            fontSize = StudioTokens.textSmall * scale, color = colors.faint, lineHeight = StudioTokens.textSmall * (StudioTokens.lineHeightMultiplier * scale),
                maxLines = if (details.isNullOrEmpty()) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (node.schema == null) Text(m["pipelineTypeUnregistered"].replace("{type}", node.type),
                fontSize = StudioTokens.textSmall * scale, lineHeight = StudioTokens.textSmall * (StudioTokens.lineHeightMultiplier * scale), color = colors.danger,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (node.ports.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(StudioTokens.borderWidth * scale).background(colors.subtleBorder))
            Spacer(Modifier.height(StudioTokens.spaceHair * scale))
            for (port in node.ports) {
                val canLink = port.linkable && (port.output || sourcePort != null && sourcePort.node != node.id)
                val highlight = port.output && sourcePort == PipelinePort(node.id, port.id) || !port.output && canLink
                Box(Modifier.fillMaxWidth().height((PORT_HEIGHT * scale).dp)
                    .background(studioColor(if (highlight) colors.selected else Color.Transparent))
                    .padding(horizontal = StudioTokens.space2 * scale),
                    contentAlignment = if (port.output) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(port.label, fontSize = StudioTokens.textSmall * scale, color = colors.muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (status == "running" && progress != null) PipelineNodeProgress(progress, category, scale)
        node.preview?.let { preview ->
            PipelineScaledPreview(scale, Modifier.fillMaxWidth().height((preview.height * scale).dp), previewContent)
        }
    }
}

@Composable
private fun PipelineNodeStrip(status: String, scale: Float) {
    val reduced = LocalStudioReducedMotion.current
    val tone = studioColor(pipelineStatusColor(status))
    var previous by remember { mutableStateOf(status) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(status, reduced) {
        val finished = status == "success" || status == "cached"
        val wasFinished = previous == "success" || previous == "cached"
        previous = status
        flash.snapTo(if (!reduced && finished && !wasFinished) 1f else 0f)
        if (flash.value > 0f) {
            delay(StudioMotion.quick.toLong())
            flash.animateTo(0f, tween(StudioMotion.normal, easing = StudioMotion.standard))
        }
    }
    val pulse = if (!reduced && status == "running") workPulse(low = .3f) else null
    Box(Modifier.fillMaxWidth().height((3 * scale).dp)
        .graphicsLayer { alpha = pulse?.value ?: 1f }.background(tone)) {
        Canvas(Modifier.fillMaxWidth().requiredHeight((11 * scale).dp).wrapContentHeight(Alignment.Top, unbounded = true)) {
            val alpha = flash.value
            if (alpha > 0f) drawRect(Brush.verticalGradient(listOf(tone.copy(alpha = alpha * .8f), Color.Transparent)))
        }
    }
}

@Composable
private fun PipelineMiniMap(layout: GraphLayout, viewSize: IntSize, viewCenter: Offset, scale: Float, pan: Offset,
    selectedId: String?, modifier: Modifier, navigateTo: (Offset) -> Unit) {
    val colors = localColors.current
    val density = LocalDensity.current
    val minView = layout.center - (pan + viewCenter) / scale
    val view = Size(viewSize.width / scale, viewSize.height / scale)
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    val currentNavigation by rememberUpdatedState(navigateTo)
    val left = minOf(minView.x, layout.origin.x)
    val top = minOf(minView.y, layout.origin.y)
    val right = maxOf(minView.x + view.width, layout.origin.x + layout.width)
    val bottom = maxOf(minView.y + view.height, layout.origin.y + layout.height)
    val miniScale = with(density) { minOf(190.dp.toPx() / (right - left), 140.dp.toPx() / (bottom - top)) }
    val center = Offset((left + right) / 2f, (top + bottom) / 2f)
    val origin = with(density) { Offset(101.dp.toPx(), 76.dp.toPx()) } - center * miniScale
    val currentOrigin by rememberUpdatedState(origin)
    val currentScale by rememberUpdatedState(miniScale)
    Canvas(modifier.size(202.dp, 152.dp).clip(shape).background(colors.inset).border(StudioTokens.borderWidth, colors.border, shape)
        .pointerInput(Unit) {
            detectTapGestures { currentNavigation((it - currentOrigin) / currentScale) }
        }.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                change.consume()
                currentNavigation((change.position - currentOrigin) / currentScale)
            }
        },
    ) {
        val visible = origin + minView * miniScale
        layout.nodes.forEach { node ->
            val point = origin + node.point * miniScale
            drawRect(if (node.id == selectedId) colors.accent else colors.faint, point,
                Size(node.width * miniScale, node.height * miniScale))
        }
        val mask = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
            addRect(androidx.compose.ui.geometry.Rect(visible, Size(view.width * miniScale, view.height * miniScale)))
        }
        drawPath(mask, colors.scrim)
    }
}

@Composable
private fun PipelineNodeInspector(tab: ProjectTab, workspace: Workspace, pipelineId: String, node: GraphNode, layout: GraphLayout,
    canEdit: Boolean, canStage: Boolean, focus: (GraphNode) -> Unit, modifier: Modifier, remove: () -> Unit,
    disconnect: (GraphEdge) -> Unit, togglePin: (JsonObject, Boolean) -> Unit, sourcePort: PipelinePort?,
    onPort: (GraphPort) -> Unit, runFrom: (() -> Unit)?) {
    val m = localMessages.current
    val colors = localColors.current
    val state = tab.pipelineGraphs
    val draft = state.drafts[pipelineId]
    val enabled = canEdit && !tab.acting
    fun edit(update: (PipelineNodeDraft) -> Unit) {
        val target = state.drafts.getOrPut(pipelineId) { PipelineNodeDraft(node.data, node.title) }
        state.problems.remove(pipelineId)
        update(target)
    }
    var title by remember(node.id, node.title) { mutableStateOf(TextFieldValue(node.title)) }
    val scroll = rememberScrollState()
    Column(modifier.background(colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                if (enabled || draft != null) {
                    StudioField(m["pipelineGraphNodeLabel"], draft?.label ?: title, onValueChange = { next ->
                        if (draft != null || next.text != node.title) edit { it.label = next }
                        else title = next
                    }, modifier = Modifier.weight(1f), enabled = enabled)
                } else Text(node.title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                PipelineButton("trash-2", m["pipelineNodeRemove"], enabled = canStage, iconOnly = true, onClick = remove)
            }
            Text(node.type, color = colors.faint, fontFamily = localStudioMono.current, fontSize = StudioTokens.textSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            node.status?.let { WorkStatus(it, variant = WorkStatusVariant.Chip, label = m.pipelineStatus(it), live = false) }
        }
        Rule()
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(StudioTokens.space4),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                node.run?.string("error")?.takeIf(String::isNotBlank)?.let {
                    Text(it, Modifier.fillMaxWidth().background(colors.dangerSurface, RoundedCornerShape(StudioTokens.radiusMd))
                        .padding(StudioTokens.space2),
                        color = StudioTokens.ensureContrast(colors.danger, colors.dangerSurface, StudioTokens.textContrast),
                        fontSize = StudioTokens.textSmall, fontFamily = localStudioMono.current)
                }
                if (node.schema == null) Text(m["pipelineTypeUnregistered"].replace("{type}", node.type), color = colors.danger)
                node.schema?.string("description")?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted) }
                if (!canEdit) Muted(m["pipelineGraphWebEditOnly"])
                Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                    WorkCheckbox(m["pipelineGraphDisable"], draft?.disabled ?: node.data.flag("disabled"), enabled = enabled) {
                        checked -> edit { it.disabled = checked }
                    }
                    WorkCheckbox(m["pipelineGraphPause"], draft?.pause ?: node.data.flag("pause_on_complete"), enabled = enabled) {
                        checked -> edit { it.pause = checked }
                    }
                }
                val position = node.data.obj("position")
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                    PipelineNodeField(m["pipelineGraphX"], draft?.x ?: TextFieldValue(position?.string("x") ?: "0"),
                        enabled, Modifier.weight(1f)) { next -> edit { it.x = next } }
                    PipelineNodeField(m["pipelineGraphY"], draft?.y ?: TextFieldValue(position?.string("y") ?: "0"),
                        enabled, Modifier.weight(1f)) { next -> edit { it.y = next } }
                }
                if (draft?.validPosition == false) Text(m["pipelineGraphInvalidPosition"], color = colors.warning)
                val inputs = node.schema?.objects("inputs").orEmpty()
                val params = node.data.obj("params").orEmpty()
                val colorPort = inputs.find { it.string("id") == "key_color" }
                if (node.preview != null) {
                    PipelineNodePreview(tab, workspace, node.type, node.previewParameters(draft), layout.connectedArtifacts[node.id].orEmpty(),
                        node.previewPortLabels(), node.run?.obj("outputs"), node.run,
                        sampleColor = if (enabled && colorPort != null && !nodeHasInput(layout, node.id, "key_color")) {
                            { color -> edit { it.editParameter("key_color", colorPort, TextFieldValue(color)) } }
                        } else null)
                } else if (node.schema?.string("ui_component_url") != null) {
                    PipelineButton("external-link", m["pipelinePluginNodeWeb"]) {
                        tab.api?.web("/pipelines/${segment(pipelineId)}")?.let { openBrowser(it, workspace) }
                    }
                }
                if (inputs.isNotEmpty() || params.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        PipelineInspectorHeading(m["pipelineInputs"])
                        val known = inputs.mapNotNull { it.string("id") }.toSet()
                        inputs.forEach { port ->
                            val id = port.string("id") ?: return@forEach
                            val incoming = layout.edges.firstOrNull { it.to == node.id && it.input == id }
                            val source = layout.byId[incoming?.from]
                            val label = port.string("label")?.ifBlank { id } ?: id
                            val pinned = (node.data.obj("pins")?.get(id) as? JsonPrimitive)?.booleanOrNull ?: port.flag("connectable")
                            val controls: @Composable RowScope.() -> Unit = {
                                if (enabled) {
                                    PipelineButton(if (pinned) "eye-off" else "eye", m[if (pinned) "pipelineHidePin" else "pipelineShowPin"],
                                        enabled = canStage, iconOnly = true) { togglePin(port, !pinned) }
                                    if (sourcePort != null && sourcePort.node != node.id && (pinned || incoming != null)) {
                                        PipelineButton("link-2", m["pipelineLinkConnect"], enabled = canStage, iconOnly = true) {
                                            onPort(GraphPort(id, label, false))
                                        }
                                    }
                                }
                            }
                            if (source == null) PipelineParamEditor(label, params[id], port, draft?.parameters?.get(id),
                                enabled, tab.root, controls) { next -> edit { it.editParameter(id, port, next) } }
                            else Column(Modifier.fillMaxWidth().border(StudioTokens.borderWidth, colors.subtleBorder, RoundedCornerShape(StudioTokens.radiusMd)).padding(StudioTokens.space2),
                                verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    controls()
                                }
                                PipelineButton("link-2", m["pipelineConnected"], tint = colors.info, compact = true) { focus(source) }
                            }
                        }
                        params.filterKeys { it !in known }.forEach { (name, value) ->
                            PipelineParamEditor(name, value, null, draft?.parameters?.get(name), enabled, tab.root) { next ->
                                edit { it.editParameter(name, null, next) }
                            }
                        }
                    }
                }
                val outputs = node.schema?.objects("outputs").orEmpty()
                if (outputs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                        PipelineInspectorHeading(m["pipelineOutputs"])
                        outputs.forEach { port ->
                            Row(Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(StudioTokens.radiusSm))
                                .padding(horizontal = StudioTokens.space2, vertical = StudioTokens.space1), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                                val id = port.string("id").orEmpty()
                                Text(port.string("label")?.ifBlank { id } ?: id, Modifier.weight(1f))
                                Text(port.string("type").orEmpty(), color = colors.faint, fontFamily = localStudioMono.current,
                                    fontSize = StudioTokens.textTiny)
                                if (canEdit && id.isNotEmpty()) PipelineButton("link-2", m["pipelineLinkStart"],
                                    enabled = canStage, iconOnly = true) { onPort(GraphPort(id, port.string("label") ?: id, true)) }
                            }
                        }
                    }
                }
                val connections = layout.edges.filter { it.from == node.id || it.to == node.id }
                if (connections.isNotEmpty()) {
                    Column {
                        PipelineInspectorHeading(m["pipelineGraphConnections"])
                        connections.forEach { edge ->
                            val peer = layout.byId[if (edge.from == node.id) edge.to else edge.from] ?: return@forEach
                            val from = layout.byId[edge.from]?.title ?: edge.from
                            val to = layout.byId[edge.to]?.title ?: edge.to
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    ListRow("$from · ${edge.output} → $to · ${edge.input}", null, selected = false) { focus(peer) }
                                }
                                if (canEdit) PipelineButton("trash-2", m["pipelineLinkDisconnect"],
                                    enabled = canStage, iconOnly = true) { disconnect(edge) }
                            }
                        }
                    }
                }
                node.schema?.obj("execution")?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                        PipelineInspectorHeading(m["pipelineExecution"])
                        Muted(listOfNotNull(it.string("kind"), it.string("resource_class")).joinToString(" · "))
                        Text(it.string("entry").orEmpty(), fontFamily = localStudioMono.current, color = colors.faint, fontSize = StudioTokens.textSmall)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        Rule()
        FlowRow(Modifier.fillMaxWidth().padding(StudioTokens.space2), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            PipelineButton("", m["pipelineGraphCenterNode"]) { focus(node) }
            if (runFrom != null) PipelineButton("play", m["pipelineRunFrom"]) { runFrom() }
            if (draft != null) PipelineButton("", m["cancel"], enabled = !tab.acting) {
                state.drafts.remove(pipelineId)
                state.problems.remove(pipelineId)
            }
        }
    }
}

private fun nodeHasInput(layout: GraphLayout, id: String, port: String): Boolean =
    layout.edges.any { it.to == id && it.input == port }

@Composable
private fun PipelineInspectorHeading(label: String) {
    Text(label.uppercase(), color = localColors.current.faint, fontWeight = FontWeight.SemiBold, fontSize = StudioTokens.textSmall)
}

internal suspend fun ProjectApi.savePipelineDraft(tab: ProjectTab, pipelineId: String): JsonObject? {
    val state = tab.pipelineGraphs
    val endpoint = "/pipelines/${segment(pipelineId)}"
    try {
        val latest = get(endpoint).jsonObject
        val revision = latest.string("revision")?.takeIf { it.matches(revisionPattern) }
        val current = latest.obj("pipeline")
        val payload = current?.let { state.payload(pipelineId, it) }
        when {
            revision == null -> state.problems[pipelineId] = Problem("pipelineGraphWebEditOnly")
            payload == null -> state.problems[pipelineId] = Problem("pipelineGraphChanged")
            else -> {
                val saved = if (payload == current) latest else put(endpoint + query("expected_revision" to revision), payload).jsonObject
                state.discard(pipelineId)
                return saved
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        state.problems[pipelineId] = if (error is ApiFailure && error.status in setOf(404, 409))
            Problem("pipelineGraphChanged") else error.problem()
    }
    return null
}

@Composable
private fun PipelineNodeField(
    label: String,
    value: TextFieldValue,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (TextFieldValue) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Muted(label)
        StudioField(label, value, onChange, Modifier.fillMaxWidth(), enabled = enabled)
    }
}
