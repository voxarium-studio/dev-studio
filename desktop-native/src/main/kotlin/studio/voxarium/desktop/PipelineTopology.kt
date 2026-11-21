package studio.voxarium.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class PipelineGraphState {
    val drafts = mutableStateMapOf<String, PipelineNodeDraft>()
    private val definitions = mutableStateMapOf<String, PipelineDefinitionDraft>()
    val problems = mutableStateMapOf<String, Problem>()
    val selectedNodes = mutableStateMapOf<String, String>()
    var smoothPreviews by mutableStateOf(true)

    fun hasUnsavedChanges() = drafts.values.any(PipelineNodeDraft::dirty) || definitions.values.any(PipelineDefinitionDraft::dirty)

    fun dirty(id: String) = drafts[id]?.dirty == true || definitions[id]?.dirty == true

    fun preview(id: String, persisted: JsonObject) = definitions[id]?.working ?: persisted

    fun reservedNodeIds(id: String): Set<String> = definitions[id]?.original?.objects("nodes")
        .orEmpty().mapNotNull { it.string("id") }.toSet()

    fun stage(id: String, persisted: JsonObject, change: (JsonObject) -> JsonObject = { it }): Boolean {
        val current = preview(id, persisted)
        val stagedNode = mergeNodeDraft(id, current) ?: run {
            if (drafts[id]?.changes() != null) problems[id] = Problem("pipelineGraphChanged")
            return false
        }
        val next = change(stagedNode)
        if (next != current) {
            val definition = definitions.getOrPut(id) { PipelineDefinitionDraft(persisted) }
            definition.working = next
            if (!definition.dirty) definitions.remove(id)
        }
        drafts.remove(id)
        problems.remove(id)
        return true
    }

    fun payload(id: String, latest: JsonObject): JsonObject? {
        val definition = definitions[id] ?: return mergeNodeDraft(id, latest)
        val edited = mergeNodeDraft(id, definition.working) ?: return null
        return mergePipelineDefinition(latest, definition.original, edited)
    }

    fun discard(id: String) {
        definitions.remove(id)
        drafts.remove(id)
        problems.remove(id)
    }

    private fun mergeNodeDraft(id: String, pipeline: JsonObject): JsonObject? {
        val draft = drafts[id]?.takeIf(PipelineNodeDraft::dirty) ?: return pipeline
        val changes = draft.changes() ?: return null
        return mergePipelineNode(pipeline, draft.original, changes)
    }
}

private class PipelineDefinitionDraft(val original: JsonObject) {
    var working by mutableStateOf(original)
    val dirty get() = original != working
}

internal data class PipelinePort(val node: String, val port: String)

internal data class GraphEdge(val from: String, val output: String, val to: String, val input: String)

internal fun JsonObject.pipelineEdge(): GraphEdge? {
    val from = obj("from") ?: return null
    val to = obj("to") ?: return null
    return GraphEdge(from.string("node") ?: return null, from.string("port").orEmpty(),
        to.string("node") ?: return null, to.string("port").orEmpty())
}

private fun JsonObject.edgeTarget(): PipelinePort? = obj("to")?.let {
    PipelinePort(it.string("node") ?: return null, it.string("port").orEmpty())
}

internal fun JsonObject.addPipelineNode(type: String, x: Double, y: Double,
    reserved: Set<String> = emptySet()): Pair<JsonObject, String> {
    val ids = objects("nodes").mapNotNull { it.string("id") }.toSet() + reserved
    val id = generateSequence(1) { it + 1 }.map { "n$it" }.first { it !in ids }
    val added = buildJsonObject {
        put("id", id)
        put("type", type)
        putJsonObject("position") {
            put("x", x)
            put("y", y)
        }
    }
    return JsonObject(this + ("nodes" to JsonArray(objects("nodes") + added))) to id
}

internal fun JsonObject.removePipelineNode(id: String): JsonObject = JsonObject(this + mapOf(
    "nodes" to JsonArray(objects("nodes").filterNot { it.string("id") == id }),
    "edges" to JsonArray(objects("edges").filterNot { it.obj("from")?.string("node") == id || it.obj("to")?.string("node") == id }),
))

internal fun JsonObject.disconnectPipelineEdge(edge: GraphEdge): JsonObject =
    JsonObject(this + ("edges" to JsonArray(objects("edges").filterNot { it.pipelineEdge() == edge })))

internal fun JsonObject.connectPipelinePorts(source: PipelinePort, target: PipelinePort): JsonObject {
    val edges = objects("edges")
    if (source.node == target.node || edges.any { it.pipelineEdge() == GraphEdge(source.node, source.port, target.node, target.port) }) return this
    val added = buildJsonObject {
        putJsonObject("from") { put("node", source.node); put("port", source.port) }
        putJsonObject("to") { put("node", target.node); put("port", target.port) }
    }
    return JsonObject(this + ("edges" to JsonArray(edges.filterNot { it.edgeTarget() == target } + added)))
}

// §8.1: only pin overrides are persisted. A hidden port cannot keep an incoming edge.
internal fun JsonObject.setPipelinePin(nodeId: String, portId: String, checked: Boolean, schemaDefault: Boolean): JsonObject {
    val nodes = objects("nodes").map { node ->
        if (node.string("id") != nodeId) node else {
            val pins = node.obj("pins").orEmpty().toMutableMap()
            if (checked == schemaDefault) pins.remove(portId) else pins[portId] = JsonPrimitive(checked)
            JsonObject(node + ("pins" to JsonObject(pins)))
        }
    }
    val updates = mutableMapOf<String, JsonElement>("nodes" to JsonArray(nodes))
    if (!checked) updates["edges"] = JsonArray(objects("edges").filterNot { it.edgeTarget() == PipelinePort(nodeId, portId) })
    return JsonObject(this + updates)
}

internal fun mergePipelineNodeFields(
    current: JsonObject,
    original: JsonObject,
    changes: Map<String, JsonElement>,
): JsonObject? {
    if (current.string("type") != original.string("type")) return null
    val updated = current.toMutableMap()
    for ((key, value) in changes) {
        if (key in setOf("params", "pins") && value is JsonObject) {
            val before = original.obj(key).orEmpty()
            val fresh = current.obj(key).orEmpty().toMutableMap()
            for (entry in before.keys + value.keys) {
                if (before[entry] == value[entry]) continue
                if (before[entry] != fresh[entry]) return null
                val replacement = value[entry]
                if (replacement == null) fresh.remove(entry) else fresh[entry] = replacement
            }
            updated[key] = JsonObject(fresh)
        } else {
            if (current[key] != original[key]) return null
            updated[key] = value
        }
    }
    return JsonObject(updated)
}

// §8.2: compare edited fields and occupied destination ports before rebasing over fresh YAML.
// The server then verifies the revision used for this merge while writing the definition.
internal fun mergePipelineDefinition(latest: JsonObject, original: JsonObject, edited: JsonObject): JsonObject? {
    if (latest == original) return edited
    val originalNodes = original.objects("nodes").associateBy { it.string("id") }
    val editedNodes = edited.objects("nodes").associateBy { it.string("id") }
    val nodes = latest.objects("nodes").associateByTo(linkedMapOf()) { it.string("id") }
    val hiddenPorts = mutableSetOf<PipelinePort>()
    for ((id, before) in originalNodes) {
        val after = editedNodes[id]
        if (before == after) continue
        val current = nodes[id] ?: return null
        if (after == null) {
            if (before != current) return null
            nodes.remove(id)
        } else {
            val updates = after.filter { (key, value) -> before[key] != value }
            nodes[id] = mergePipelineNodeFields(current, before, updates) ?: return null
            if (id != null && "pins" in updates) {
                val oldPins = before.obj("pins").orEmpty()
                val newPins = after.obj("pins").orEmpty()
                for (port in oldPins.keys + newPins.keys) {
                    val oldPin = oldPins[port]
                    val newPin = newPins[port]
                    if (oldPin != newPin && (newPin == JsonPrimitive(false) || oldPin == JsonPrimitive(true) && newPin == null)) {
                        hiddenPorts.add(PipelinePort(id, port))
                    }
                }
            }
        }
    }
    for ((id, node) in editedNodes) {
        if (id !in originalNodes && nodes.putIfAbsent(id, node) != null) return null
    }

    val originalEdges = original.objects("edges").groupBy(JsonObject::edgeTarget)
    val editedEdges = edited.objects("edges").groupBy(JsonObject::edgeTarget)
    val latestEdges = latest.objects("edges")
    val latestByTarget = latestEdges.groupBy(JsonObject::edgeTarget)
    val changedTargets = (originalEdges.keys + editedEdges.keys).filterTo(mutableSetOf()) { originalEdges[it] != editedEdges[it] }
    if ((changedTargets + hiddenPorts).any { originalEdges[it] != latestByTarget[it] }) return null
    val edges = latestEdges.filterNot { it.edgeTarget() in changedTargets } +
        edited.objects("edges").filter { it.edgeTarget() in changedTargets }
    if (edges.any { edge -> edge.pipelineEdge()?.let { it.from !in nodes || it.to !in nodes } == true }) return null

    val other = edited.filter { (key, value) -> key != "nodes" && key != "edges" && original[key] != value }
    if (other.keys.any { latest[it] != original[it] }) return null
    return JsonObject(latest + other + mapOf("nodes" to JsonArray(nodes.values.toList()), "edges" to JsonArray(edges)))
}
