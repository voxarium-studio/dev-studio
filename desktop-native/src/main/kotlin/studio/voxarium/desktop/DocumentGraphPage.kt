package studio.voxarium.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Slider
import kotlin.math.pow

@Serializable
private data class StoredGraph(
    val fingerprint: Int = 0,
    val positions: Map<String, List<Float>> = emptyMap(),
    val forces: Map<String, Float> = emptyMap(),
    val camera: List<Float>? = null,
)

internal class DocumentGraphPageState {
    var simulation by mutableStateOf<DocumentGraphSimulation?>(null)
        private set
    var folders by mutableStateOf<Map<String, JsonObject>>(emptyMap())
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set
    var filter by mutableStateOf(TextFieldValue())
    var showList by mutableStateOf(false)
    var showForces by mutableStateOf(false)
    var camera by mutableStateOf<DocumentGraphCamera?>(null)
        private set
    var animateCamera by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var forces by mutableStateOf<Map<DocumentGraphForce, Float>>(emptyMap())
        private set
    var frame by mutableIntStateOf(0)
        private set
    var wakeup by mutableIntStateOf(0)
        private set
    var saveVersion by mutableIntStateOf(0)
        private set
    private var restored: StoredGraph? = null
    private var havePreferences = false
    private val storage = Mutex()

    suspend fun load(api: ProjectApi, root: String) {
        loading = true
        try {
            if (!havePreferences) restore(root)
            coroutineScope {
                val styled = async { api.get("/docs/folders").objects() }
                val next = DocumentGraph.read(api.get("/docs/graph").jsonObject)
                val current = simulation
                val saved = restored
                val preserved = current?.capture() ?: saved?.positions.orEmpty().mapNotNull { (path, point) ->
                    storedPoint(point)?.let { path to it }
                }.toMap()
                if (current?.graph != next) {
                    val heat = when {
                        saved?.fingerprint == next.hashCode() && next.nodes.all { it.path in preserved } -> 0f
                        preserved.isNotEmpty() -> .4f
                        else -> 1f
                    }
                    simulation = DocumentGraphSimulation(next, preserved, forces, heat)
                }
                folders = styled.await().mapNotNull { style -> style.string("path")?.let { it to style } }.toMap()
                restored = null
                problem = null
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            problem = error.problem("docGraph.loadFailed")
        } finally {
            loading = false
        }
    }

    fun adjustCamera(value: DocumentGraphCamera?, animate: Boolean = false) {
        camera = value
        animateCamera = animate
        saveVersion++
    }

    fun adjust(force: DocumentGraphForce, value: Float) {
        val valid = value.coerceIn(force.range)
        forces = if (valid == force.default) forces - force else forces + (force to valid)
        applyForces()
    }

    fun resetForces() {
        forces = emptyMap()
        applyForces()
    }

    private fun applyForces() {
        simulation?.forces = forces
        restart()
    }

    fun restart() {
        simulation?.warm()
        wakeup++
        saveVersion++
    }

    fun movePin(index: Int, point: Offset) {
        val sim = simulation ?: return
        val started = sim.pinned == null
        sim.pinned = index to point
        if (started) restart()
    }

    fun releasePin() {
        simulation?.pinned = null
        restart()
    }

    fun drawNextFrame(steps: Int = 1, publishIntermediate: Boolean = true) {
        val sim = simulation ?: return
        var remaining = steps
        while (remaining-- > 0 && sim.moving) sim.step()
        if (publishIntermediate || !sim.moving) frame++
        if (!sim.moving) saveVersion++
    }

    private suspend fun restore(root: String) {
        val saved = withContext(Dispatchers.IO) {
            val path = graphStatePath(root)
            runCatching { studioJson.decodeFromString<StoredGraph>(Files.readString(path)) }.getOrNull()
        }
        if (saved != null) {
            restored = saved
            forces = DocumentGraphForce.entries.mapNotNull { force ->
                saved.forces[force.name]?.takeIf(Float::isFinite)?.coerceIn(force.range)?.let { force to it }
            }.toMap()
            val view = saved.camera
            val position = view?.take(2)?.let(::storedPoint)
            val scale = view?.getOrNull(2)?.takeIf(Float::isFinite)
            if (position != null && scale != null) {
                camera = DocumentGraphCamera(position, scale.coerceIn(DocumentGraphCamera.MIN_SCALE, DocumentGraphCamera.MAX_SCALE))
            }
        }
        havePreferences = true
    }

    suspend fun persist(root: String) = storage.withLock {
        val active = simulation ?: return@withLock
        val snapshot = StoredGraph(
            fingerprint = active.graph.hashCode(),
            positions = active.capture().mapValues { (_, point) -> listOf(point.x, point.y) },
            forces = forces.mapKeys { (force, _) -> force.name },
            camera = camera?.let { listOf(it.pan.x, it.pan.y, it.scale) },
        )
        // §5.1.1: reader preferences stay local to this installation and project.
        withContext(Dispatchers.IO) {
            runCatching {
                val target = graphStatePath(root)
                Files.createDirectories(target.parent)
                val tmp = Files.createTempFile(target.parent, "graph-", ".tmp")
                try {
                    Files.writeString(tmp, studioJson.encodeToString(snapshot))
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }
        Unit
    }
}

private fun graphStatePath(root: String): Path {
    val key = MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return studioHome().resolve("kotlin-desktop/document-graphs/$key.json")
}

private fun storedPoint(values: List<Float>): Offset? = values.takeIf { it.size == 2 && it.all(Float::isFinite) }
    ?.let { Offset(it[0], it[1]) }

@Composable
internal fun DocumentGraphPage(tab: ProjectTab, navigate: (Page) -> Unit, workspace: Workspace, modifier: Modifier) {
    val api = tab.api ?: return
    val m = localMessages.current
    val state = tab.documentGraph
    val sim = state.simulation
    val graph = sim?.graph
    val readerPath = tab.page.query.path
    var retainedReaderPath by remember(tab) { mutableStateOf(readerPath) }
    if (readerPath != null) SideEffect { retainedReaderPath = readerPath }
    val reducedMotion = LocalStudioReducedMotion.current
    val matching = remember(graph, state.filter.text) {
        val query = state.filter.text.trim()
        graph?.nodes?.indices?.filter { index ->
            val node = graph.nodes[index]
            node.title.contains(query, ignoreCase = true) || node.path.contains(query, ignoreCase = true)
        }.orEmpty()
    }
    val corpus = remember(graph) { graph?.nodes?.map { WorkspaceItem(it.path, it.title) }.orEmpty() }
    fun read(path: String?) { navigate(Page("/documents/graph", Selectors(path = path))) }

    LaunchedEffect(api, tab.pageRefresh) { state.load(api, tab.root) }
    LaunchedEffect(sim, state.wakeup, reducedMotion) {
        while (sim?.moving == true) withFrameNanos {
            val settleWithoutMotion = reducedMotion && sim.pinned == null
            state.drawNextFrame(if (settleWithoutMotion) 10 else 1, publishIntermediate = !settleWithoutMotion)
        }
    }
    LaunchedEffect(tab.root) {
        val initially = state.saveVersion
        snapshotFlow { state.saveVersion }.collectLatest { version ->
            if (version != initially) {
                delay(350)
                state.persist(tab.root)
            }
        }
    }
    DisposableEffect(tab) {
        onDispose {
            if (state.saveVersion != 0) tab.scope.launch { state.persist(tab.root) }
        }
    }

    Column(modifier.onPreviewKeyEvent {
        if (readerPath != null && it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
            read(null)
            true
        } else false
    }) {
        LibraryHeader(m["documentGraph"], graph?.let { graphCounts(it, m) }) {
            LibraryIconAction("refresh-cw", m["refresh"], bordered = true, loading = state.loading) { tab.refresh() }
            LibraryIconAction("maximize", m["docGraph.fit"], bordered = true, enabled = sim != null) { state.adjustCamera(null) }
            LibraryIconAction("sliders-horizontal", m["docGraph.forces"], bordered = true, selected = state.showForces) {
                state.showForces = !state.showForces
            }
            LibraryIconAction("list-tree", m["documents"], bordered = true, selected = state.showList) {
                state.showList = !state.showList
            }
        }
        state.problem?.let { WorkProblemBanner(it) }
        StudioDisclosure(state.showForces) { DocumentGraphForces(state) }
        when {
            sim == null -> if (state.problem == null) WorkEmpty(m["loading"])
            sim.graph.nodes.isEmpty() -> LibraryEmpty("waypoints", m["docGraph.empty"], m["docGraph.emptyHint"])
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = maxWidth
                val colors = localColors.current
                val listShown = (state.showList || state.filter.text.isNotBlank()) && (readerPath == null || width >= 920.dp)
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space2),
                            verticalAlignment = Alignment.CenterVertically) {
                            LibrarySearchField(state.filter, { state.filter = it }, m["docGraph.find"], Modifier.weight(1f))
                            if (state.filter.text.isNotEmpty()) {
                                LibraryIconAction("x", m["docGraph.clearFilter"]) { state.filter = TextFieldValue() }
                            }
                        }
                        DocumentGraphCanvas(state, sim, matching, readerPath, Modifier.weight(1f).fillMaxWidth(), ::read)
                        LibraryCaption(m["docGraph.help"], Modifier.padding(horizontal = StudioTokens.space3, vertical = StudioTokens.space1), maxLines = 2)
                    }
                    LibrarySidePane(listShown) {
                        Rule(vertical = true)
                        ScrollList(Modifier.width((width * .25f).coerceIn(165.dp, 260.dp)).fillMaxHeight().background(colors.surface)) {
                            items(matching, key = { sim.graph.nodes[it].path }) { index ->
                                val node = sim.graph.nodes[index]
                                ListRow(node.title, node.path, readerPath == node.path) { read(node.path) }
                            }
                        }
                    }
                    LibrarySidePane(readerPath != null) {
                        val path = readerPath ?: retainedReaderPath ?: return@LibrarySidePane
                        Rule(vertical = true)
                        Box(Modifier.width((width * .42f).coerceAtMost(560.dp)).fillMaxHeight()) {
                            key(path) {
                                val item = corpus.firstOrNull { it.id == path }
                                var detail by remember { mutableStateOf<WorkspaceDetail?>(null) }
                                var problem by remember { mutableStateOf<Problem?>(null) }
                                LaunchedEffect(api, tab.pageRefresh) {
                                    try {
                                        val data = api.get(documentEndpoint(path)).jsonObject
                                        detail = WorkspaceDetail(item ?: WorkspaceItem(path, data.string("title") ?: path), data)
                                        problem = null
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        problem = error.problem("docGraph.readerFailed")
                                        detail = null
                                    }
                                }
                                DocumentPanel(tab, path, detail, navigate, workspace,
                                    graphDocuments = corpus, graphProblem = problem, closeReader = { read(null) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun graphCounts(graph: DocumentGraph, m: Messages): String = m["docGraph.counts"]
    .replace("{documents}", graph.nodes.size.toString())
    .replace("{links}", graph.links.size.toString())

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun DocumentGraphForces(state: DocumentGraphPageState) {
    val m = localMessages.current
    val number = remember(m.language) { NumberFormat.getIntegerInstance(Locale.forLanguageTag(m.language)) }
    Row(Modifier.fillMaxWidth().background(localColors.current.surface).padding(horizontal = StudioTokens.space4, vertical = StudioTokens.space2),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.Top) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
            for (force in DocumentGraphForce.entries) {
                val value = state.forces[force] ?: force.default
                val label = m["docGraph.force.${force.label}"]
                val formatted = if (force == DocumentGraphForce.RADIUS || force == DocumentGraphForce.DISTANCE) {
                    m["docGraph.pixels"].replace("{value}", number.format(value))
                } else m["docGraph.percent"].replace("{value}", number.format(value * 100f))
                Column(Modifier.width(160.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LibraryCaption(label, Modifier.weight(1f), maxLines = 2)
                        LibraryCaption(formatted)
                    }
                    // The Jewel slider retains native range semantics and keyboard stepping.
                    Slider(value, { state.adjust(force, it) }, Modifier.fillMaxWidth()
                        .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = localColors.current.surface)
                        .semantics { contentDescription = label },
                        valueRange = force.range)
                }
            }
        }
        LibraryIconAction("refresh-cw", m["docGraph.resetForces"], bordered = true, enabled = state.forces.isNotEmpty()) { state.resetForces() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DocumentGraphCanvas(state: DocumentGraphPageState, simulation: DocumentGraphSimulation,
                                matching: List<Int>, selectedPath: String?, modifier: Modifier, read: (String?) -> Unit) {
    val m = localMessages.current
    val density = LocalDensity.current
    val colors = localColors.current
    val graph = simulation.graph
    val reducedMotion = LocalStudioReducedMotion.current
    val matchingSet = remember(matching) { matching.toHashSet() }
    val selected = graph.byPath[selectedPath]
    var hover by remember(simulation, matching) { mutableStateOf<Int?>(null) }
    var view by remember { mutableStateOf(Size.Zero) }
    val textMeasurer = rememberTextMeasurer(cacheSize = 96)
    val theme = localStudioTheme.current
    val looks = remember(graph, state.folders, colors, theme) {
        graph.nodes.map { node ->
            val config = state.folders[node.folder]
            val color = graphFolderColor(config?.string("color"), colors, theme)
            val icon = graphFolderIcon(config?.string("icon"))
            color to icon
        }
    }
    val icons = looks.map { it.second }.distinct().associateWith { painterResource("studio-icons/$it.svg") }
    val layoutVersion = state.frame
    val center = Offset(view.width / 2f, view.height / 2f)
    val targetCamera = state.camera ?: remember(simulation, layoutVersion, view, density) {
        simulation.bounds()?.takeIf { view.width > 0f && view.height > 0f }
            ?.let { DocumentGraphCamera.fit(it, view, with(density) { 48.dp.toPx() }) } ?: DocumentGraphCamera()
    }
    var displayedCamera by remember(state) { mutableStateOf(state.camera ?: DocumentGraphCamera()) }
    val latestTarget by rememberUpdatedState(targetCamera)
    val glide = !reducedMotion && (state.camera == null || state.animateCamera)
    LaunchedEffect(state, glide) {
        snapshotFlow { latestTarget }.collect {
            if (!glide) {
                displayedCamera = it
                return@collect
            }
            var previousFrame = 0L
            while (displayedCamera != latestTarget) {
                withFrameNanos { frame ->
                    val elapsed = if (previousFrame == 0L) 1f else (frame - previousFrame) / 16_666_667f
                    displayedCamera = displayedCamera.approach(latestTarget, 1f - .88f.pow(elapsed))
                    previousFrame = frame
                }
            }
        }
    }
    val camera = if (glide) displayedCamera else targetCamera
    fun setDirectCamera(value: DocumentGraphCamera) {
        displayedCamera = value
        state.adjustCamera(value)
    }
    fun under(pointer: Offset): Int? {
        val world = camera.unproject(pointer, center)
        val near = with(density) { 6.dp.toPx() } / camera.scale
        return matching.minByOrNull { index -> (simulation.point(index) - world).getDistanceSquared() }
            ?.takeIf { index -> (simulation.point(index) - world).getDistance() <= simulation.radius(index).coerceAtLeast(near) }
    }
    val latestCamera by rememberUpdatedState(camera)
    val latestCenter by rememberUpdatedState(center)
    val hit by rememberUpdatedState<(Offset) -> Int?>(::under)
    val select by rememberUpdatedState<(Int?) -> Unit> { read(it?.let { graph.nodes[it].path }) }

    Box(modifier.clipToBounds().background(colors.canvas)) {
        Canvas(Modifier.matchParentSize().onSizeChanged { view = it.toSize() }.focusable()
            .semantics { contentDescription = graphCounts(graph, m) }
            .onPointerEvent(PointerEventType.Move) { hover = it.changes.firstOrNull()?.position?.let(hit) }
            .onPointerEvent(PointerEventType.Exit) { hover = null }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val point = event.changes.firstOrNull() ?: return@onPointerEvent
                if (point.scrollDelta.y == 0f) return@onPointerEvent
                setDirectCamera(displayedCamera.zoom(1.14f.pow(-point.scrollDelta.y.coerceIn(-5f, 5f)), point.position, latestCenter))
                point.consume()
            }
            .pointerInput(simulation, matching) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val node = hit(down.position)
                    var last = down.position
                    var dragged = false
                    var gestureCamera = latestCamera
                    try {
                        while (true) {
                            val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) {
                                if (!dragged && !pointer.isConsumed) select(node)
                                break
                            }
                            if (pointer.isConsumed) break
                            if (!dragged && (pointer.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                            if (!dragged) {
                                gestureCamera = latestCamera
                                setDirectCamera(gestureCamera)
                            }
                            dragged = true
                            if (node == null) {
                                gestureCamera = gestureCamera.copy(pan = gestureCamera.pan + (pointer.position - last))
                                setDirectCamera(gestureCamera)
                            } else state.movePin(node, gestureCamera.unproject(pointer.position, latestCenter))
                            last = pointer.position
                            pointer.consume()
                        }
                    } finally {
                        if (node != null && dragged && simulation.pinned?.first == node) state.releasePin()
                    }
                }
            }) {
            fun at(index: Int) = camera.project(simulation.point(index), center)
            val highlight = hover ?: selected
            val focus = graph.neighbours.getOrNull(highlight ?: -1).orEmpty()
            fun emphasized(index: Int) = highlight == null || index == highlight || index in focus
            for ((source, target) in graph.links) {
                if (source !in matchingSet || target !in matchingSet) continue
                val active = highlight != null && (source == highlight || target == highlight)
                drawLine(if (active) colors.accent else colors.border.copy(alpha = if (highlight == null) .85f else .32f),
                    at(source), at(target), if (active) 1.4.dp.toPx() else 1.dp.toPx())
            }
            for (index in matching) {
                val point = at(index)
                val radius = (simulation.radius(index) * camera.scale).coerceAtLeast(2.dp.toPx())
                if (point.x + radius < 0 || point.y + radius < 0 || point.x - radius > size.width || point.y - radius > size.height) continue
                val (color, name) = looks[index]
                val alpha = if (emphasized(index)) 1f else .4f
                drawCircle(color, radius, point, alpha = alpha)
                if (index == selected || index == hover) drawCircle(colors.text, radius + 3.dp.toPx(), point, style = Stroke(1.dp.toPx()))
                val icon = icons[name]
                if (icon != null && radius >= 4.dp.toPx()) {
                    val side = radius * 1.3f
                    translate(point.x - side / 2, point.y - side / 2) {
                        with(icon) { draw(Size(side, side), alpha = alpha, colorFilter = ColorFilter.tint(colors.canvas)) }
                    }
                }
                if (index == selected || index == hover || (camera.scale >= 1.1f && matching.size <= 90)) {
                val label = textMeasurer.measure(graph.nodes[index].title, TextStyle(colors.text.copy(alpha = alpha), fontSize = StudioTokens.textSmall),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = 168.dp.roundToPx()))
                    drawText(label, topLeft = Offset(point.x - label.size.width / 2f, point.y + radius + 5.dp.toPx()))
                }
            }
        }
        if (matching.isEmpty()) LibraryEmpty("filter", m["noMatches"])
        Row(Modifier.align(Alignment.BottomEnd).padding(StudioTokens.space2).background(colors.surface, RoundedCornerShape(StudioTokens.radiusMd)), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            LibraryIconAction("minus", m["docGraph.zoomOut"], bordered = true) {
                state.adjustCamera(targetCamera.zoom(1f / 1.25f, center, center), animate = true)
            }
            LibraryIconAction("plus", m["docGraph.zoomIn"], bordered = true) {
                state.adjustCamera(targetCamera.zoom(1.25f, center, center), animate = true)
            }
        }
    }
}

private val graphIconName = Regex("[a-z0-9-]+")
internal fun graphFolderIcon(name: String?): String = name?.takeIf { graphIconName.matches(it) &&
    DocumentGraph::class.java.getResource("/studio-icons/$it.svg") != null } ?: "folder"

private val graphPalette = mapOf(
    "red" to (0xfff85149L to 0xffcd222eL), "orange" to (0xfff0883eL to 0xffb34800L),
    "yellow" to (0xffe3b341L to 0xff8f6000L), "green" to (0xff3fb950L to 0xff197a35L),
    "cyan" to (0xff39c5cfL to 0xff00696fL), "blue" to (0xff58a6ffL to 0xff0a66d0L),
    "purple" to (0xffbc8cffL to 0xff7d49deL), "pink" to (0xffdb61a2L to 0xffb73783L),
    "brown" to (0xffb58a63L to 0xff794e35L), "gray" to (0xff93959eL to 0xff5f6068L),
)
// Folder looks are document metadata and must retain their color identities across screens.
internal val documentFolderColors: Set<String> = graphPalette.keys

internal fun graphFolderColor(name: String?, colors: StudioColors, theme: JsonObject?): Color {
    val canonical = if (name == "grey") "gray" else name
    val values = graphPalette[canonical] ?: return colors.accent
    val custom = theme?.obj("custom_tokens")?.string("--vxs-md-$canonical")?.trim()?.removePrefix("#")
        ?.takeIf { it.length == 6 }?.toLongOrNull(16)
    return Color(if (custom != null) 0xff000000L or custom else if (colors.dark) values.first else values.second)
}
