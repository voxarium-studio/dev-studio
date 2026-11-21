package studio.voxarium.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal data class DocumentGraphNode(val path: String, val title: String) {
    val folder: String = path.substringBeforeLast('/', "")
}

internal data class DocumentGraphLink(val source: Int, val target: Int)

internal data class DocumentGraph(
    val nodes: List<DocumentGraphNode>,
    val links: List<DocumentGraphLink>,
) {
    val byPath = nodes.withIndex().associate { it.value.path to it.index }
    val neighbours: List<Set<Int>> = List(nodes.size) { mutableSetOf<Int>() }.also { sets ->
        links.forEach { (source, target) ->
            sets[source].add(target)
            sets[target].add(source)
        }
    }

    companion object {
        fun read(data: JsonObject): DocumentGraph {
            val nodes = data.objects("nodes").mapNotNull { node ->
                val path = node.string("path")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                DocumentGraphNode(path, node.string("title")?.takeIf(String::isNotBlank) ?: path.substringAfterLast('/'))
            }.distinctBy(DocumentGraphNode::path).sortedBy(DocumentGraphNode::path)
            val ids = nodes.withIndex().associate { it.value.path to it.index }
            val links = data.objects("edges").mapNotNull { edge ->
                val a = ids[edge.string("source")] ?: return@mapNotNull null
                val b = ids[edge.string("target")] ?: return@mapNotNull null
                if (a == b) null else DocumentGraphLink(min(a, b), max(a, b))
            }.distinct().sortedWith(compareBy(DocumentGraphLink::source, DocumentGraphLink::target))
            return DocumentGraph(nodes, links)
        }
    }
}

internal enum class DocumentGraphForce(val label: String, val range: ClosedFloatingPointRange<Float>, val default: Float) {
    LINK("link", 0f..2f, 1f), FOLDER("folder", 0f..2f, 0f), UNLINKED("unlinked", 0f..3f, 1f),
    RADIUS("radius", 3f..24f, 8f), DISTANCE("distance", 20f..300f, 180f);
}

internal data class DocumentGraphCamera(val pan: Offset = Offset.Zero, val scale: Float = 1f) {
    fun project(world: Offset, center: Offset): Offset = center + pan + world * scale
    fun unproject(screen: Offset, center: Offset): Offset = (screen - center - pan) / scale

    fun zoom(factor: Float, anchor: Offset, center: Offset): DocumentGraphCamera {
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return DocumentGraphCamera(anchor - center - unproject(anchor, center) * next, next)
    }

    fun approach(target: DocumentGraphCamera, fraction: Float): DocumentGraphCamera {
        if ((target.pan - pan).getDistanceSquared() <= .25f && kotlin.math.abs(target.scale - scale) <= .002f) {
            return target
        }
        return DocumentGraphCamera(pan + (target.pan - pan) * fraction, scale + (target.scale - scale) * fraction)
    }

    companion object {
        const val MIN_SCALE = .08f
        const val MAX_SCALE = 4f

        fun fit(bounds: Rect, size: Size, padding: Float): DocumentGraphCamera {
            val fitX = (size.width - 2 * padding) / bounds.width.coerceAtLeast(1f)
            val fitY = (size.height - 2 * padding) / bounds.height.coerceAtLeast(1f)
            val scale = min(fitX, fitY).coerceIn(MIN_SCALE, 1.65f)
            return DocumentGraphCamera(-bounds.center * scale, scale)
        }
    }
}

// Block-editor spec §5.1.1: all documents share one cooling simulation. Link, folder,
// unlinked repulsion, node size and rest distance follow the Vue graph's force model.
internal class DocumentGraphSimulation(
    val graph: DocumentGraph,
    previous: Map<String, Offset> = emptyMap(),
    var forces: Map<DocumentGraphForce, Float> = emptyMap(),
    startHeat: Float = 1f,
) {
    private val seedSpread = force(DocumentGraphForce.DISTANCE) * sqrt(graph.nodes.size.coerceAtLeast(1).toFloat())
    private val position = Array(graph.nodes.size) { i ->
        val node = graph.nodes[i]
        previous[node.path] ?: seedGraphPoint(node.path, seedSpread)
    }
    private val velocity = Array(graph.nodes.size) { Offset.Zero }
    private val degree = graph.neighbours.map(Set<Int>::size)
    private val maxDegree = degree.maxOrNull()?.coerceAtLeast(1) ?: 1
    private val folderGroups = graph.nodes.indices.groupBy { graph.nodes[it].folder }.values.filter { it.size > 1 }
    private var heat = startHeat
    private var tick = 0
    var pinned: Pair<Int, Offset>? = null

    val moving: Boolean get() = graph.nodes.isNotEmpty() && (pinned != null || heat >= .001f)

    private fun force(type: DocumentGraphForce): Float = forces[type] ?: type.default

    fun point(index: Int): Offset = position[index]

    fun radius(index: Int): Float = force(DocumentGraphForce.RADIUS) * (1 + .75f * sqrt(degree[index].toFloat() / maxDegree))

    fun capture(): Map<String, Offset> = graph.nodes.withIndex().associate { it.value.path to position[it.index] }

    fun warm(value: Float = .5f) { heat = max(heat, value) }

    fun bounds(indices: Iterable<Int> = position.indices): Rect? {
        if (!indices.iterator().hasNext()) return null
        return Rect(
            indices.minOf { position[it].x - radius(it) },
            indices.minOf { position[it].y - radius(it) },
            indices.maxOf { position[it].x + radius(it) },
            indices.maxOf { position[it].y + radius(it) },
        )
    }

    fun step() {
        if (!moving) return
        val target = if (pinned == null) 0f else .3f
        heat += (target - heat) * (1f - .001f.pow(1f / 300f))
        tick++
        val linkStrength = force(DocumentGraphForce.LINK) * heat
        val distance = force(DocumentGraphForce.DISTANCE)
        for ((a, b) in graph.links) {
            val delta = avoidCoincidence(position[b] + velocity[b] - position[a] - velocity[a], a, b)
            val length = delta.getDistance()
            val spring = delta * ((length - distance) / length * linkStrength / min(degree[a], degree[b]))
            val bias = degree[a].toFloat() / (degree[a] + degree[b])
            velocity[b] -= spring * bias
            velocity[a] += spring * (1f - bias)
        }
        repel()
        val folderPull = .08f * force(DocumentGraphForce.FOLDER) * heat
        if (folderPull > 0) for (group in folderGroups) {
            val center = group.fold(Offset.Zero) { sum, i -> sum + position[i] } / group.size.toFloat()
            for (i in group) velocity[i] += (center - position[i]) * folderPull
        }
        position.indices.forEach { i ->
            if (pinned?.first == i) {
                position[i] = requireNotNull(pinned).second
                velocity[i] = Offset.Zero
            } else {
                velocity[i] = (velocity[i] - position[i] * (.03f * heat)) * .6f
                position[i] += velocity[i]
            }
        }
    }

    private fun repel() {
        val charge = (150f + 12 * force(DocumentGraphForce.RADIUS)) * heat
        val unlinked = 250f * force(DocumentGraphForce.UNLINKED) * heat
        // Exact for ordinary corpora. Rotate the sampled pairs for very large sets so
        // a folder with thousands of documents doesn't lock the UI for a full pair pass.
        val stride = (position.size / 320).coerceAtLeast(1)
        for (a in position.indices) {
            val first = a + 1 + tick.mod(stride)
            val reach = radius(a) + 4f
            for (b in first until position.size step stride) {
                val delta = avoidCoincidence(position[b] - position[a], a, b)
                val squared = delta.getDistanceSquared()
                if (squared < 900f * 900f) {
                    val push = if (b in graph.neighbours[a]) charge else charge + unlinked
                    val amount = delta * (push / squared.coerceAtLeast(1f))
                    velocity[a] -= amount
                    velocity[b] += amount
                }
                val apart = reach + radius(b)
                if (squared < apart * apart) {
                    val length = sqrt(squared)
                    val amount = delta * ((apart - length) / length * .35f)
                    velocity[a] -= amount
                    velocity[b] += amount
                }
            }
        }
    }
}

private fun seedGraphPoint(path: String, spread: Float): Offset {
    var hash = 0x811c9dc5u
    path.forEach { char -> hash = (hash xor char.code.toUInt()) * 0x01000193u }
    val x = (hash and 0xffffu).toFloat() / 32767.5f - 1f
    val y = (hash shr 16).toFloat() / 32767.5f - 1f
    return Offset(x, y) * spread
}

private fun avoidCoincidence(delta: Offset, a: Int, b: Int): Offset = if (delta.getDistanceSquared() >= 1e-6f) delta else {
    Offset(.001f + (a * 29 + b * 97).mod(10) * .0001f, .001f + (a * 93 + b * 31).mod(10) * .0001f)
}
