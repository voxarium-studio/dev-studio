package studio.voxarium.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlinx.serialization.json.*

internal data class NativeProducerAttention(
    val id: String, val epoch: String, val root: String, val project: String, val chats: List<String>,
) {
    fun matches(binding: ProjectBinding?): Boolean =
        binding != null && binding.id == id && binding.epoch == epoch && binding.root == root

    val page: Page get() = Page("/producer", Selectors(chat = chats.singleOrNull()))
}

private val projectId = Regex("[a-zA-Z0-9_-]+")
private val controlCharacter = Regex("[\\x00-\\x1f\\x7f]")
private fun JsonObject.label(key: String) = (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
    ?.content?.takeIf { it.isNotBlank() && !controlCharacter.containsMatchIn(it) }
private fun JsonObject.sequence(key: String) = (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)
    ?.longOrNull?.takeIf { it in 0..9_007_199_254_740_991L }

private fun JsonObject.producerAttention(epoch: String): NativeProducerAttention? {
    val project = obj("project") ?: return null
    if (project.label("epoch") != epoch || label("target") != "/producer" || sequence("source_seq") == null) return null
    val id = project.label("project_id")?.takeIf(projectId::matches) ?: return null
    val name = project.label("name") ?: return null
    val root = project.label("root")?.takeIf { it == it.trim() } ?: return null
    val path = runCatching { Path.of(root) }.getOrNull() ?: return null
    if (!path.isAbsolute || path.any { it.toString() in setOf(".", "..") }) return null
    val rows = this["waiting"] as? JsonArray ?: return null
    val chats = rows.map { item ->
        val row = item as? JsonObject ?: return null
        val chat = row.label("chat_id") ?: return null
        val target = row.label("target") ?: return null
        if (!target.startsWith("/producer?chat=") || '&' in target || '#' in target || '\\' in target) return null
        val raw = target.removePrefix("/producer?chat=")
        val decoded = runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrNull()
        if (decoded != chat) return null
        chat
    }
    if (chats.distinct().size != chats.size) return null
    return NativeProducerAttention(id, epoch, root, name, chats)
}

// §5.3.6: one reducer per connection. The hello and gap snapshots carry the
// complete app-level attention state, including projects without a window.
internal class NativeAttentionStream {
    private var epoch: String? = null
    private var watermark = 0L
    private var active = emptyMap<String, NativeProducerAttention>()

    fun receive(frame: JsonObject): List<NativeProducerAttention>? {
        if ("project_id" in frame) return null
        val data = frame.obj("data") ?: return null
        val incomingEpoch = frame.label("epoch") ?: return null
        val type = frame.label("type") ?: return null
        if (type == "system.hello") {
            if (epoch != null || data.label("epoch") != incomingEpoch || data["resumed_from"] != JsonNull) return null
            val seq = frame.sequence("seq") ?: return null
            val snapshot = snapshot(data, incomingEpoch) ?: return null
            epoch = incomingEpoch
            watermark = seq
            active = snapshot
            return active.values.toList()
        }
        if (incomingEpoch != epoch) return null
        if (type == "system.dropped") {
            if (!data.flag("resync") || data.sequence("count") == null || data.label("reason") == null) return null
            val seq = data.sequence("snapshot_seq")?.takeIf { it >= watermark } ?: return null
            if ("seq" in frame && frame.sequence("seq")?.let { it in 1..seq } != true) return null
            val snapshot = snapshot(data, incomingEpoch) ?: return null
            watermark = seq
            active = snapshot
            return active.values.toList()
        }
        if (type != "desktop.producer_attention") return null
        val seq = frame.sequence("seq")?.takeIf { it > watermark } ?: return null
        val attention = data.producerAttention(incomingEpoch) ?: return null
        watermark = seq
        active = if (attention.chats.isEmpty()) active - attention.id else active + (attention.id to attention)
        return active.values.toList()
    }

    private fun snapshot(data: JsonObject, epoch: String): Map<String, NativeProducerAttention>? {
        val rows = data["attention"] as? JsonArray ?: return null
        val values = rows.map { (it as? JsonObject)?.producerAttention(epoch) ?: return null }
        if (values.distinctBy(NativeProducerAttention::id).size != values.size) return null
        return values.filter { it.chats.isNotEmpty() }.associateBy(NativeProducerAttention::id)
    }
}

private val producerTaskbarBadge by lazy {
    BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).apply {
        val graphics = createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color(0x2c, 0x20, 0x0b)
            graphics.fillOval(0, 0, 20, 20)
            graphics.color = Color(0xff, 0xc4, 0x56)
            graphics.fillOval(1, 1, 18, 18)
            graphics.color = Color(0x2c, 0x20, 0x0b)
            graphics.fillRoundRect(9, 5, 2, 7, 2, 2)
            graphics.fillOval(9, 13, 2, 2)
        } finally {
            graphics.dispose()
        }
    }
}

internal fun setProducerTaskbarAttention(window: Window, waiting: Boolean) {
    if (!window.isDisplayable || !Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW)) {
        taskbar.setWindowIconBadge(window, if (waiting) producerTaskbarBadge else null)
    }
    if (waiting && !window.isFocused && taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
        taskbar.requestWindowUserAttention(window)
    }
}
