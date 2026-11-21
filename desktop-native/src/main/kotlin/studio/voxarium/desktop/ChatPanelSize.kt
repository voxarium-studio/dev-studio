package studio.voxarium.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.awt.Cursor
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// §10.9: the saved size is a preference. Only the currently drawn size fits the window.
@Serializable
internal data class ChatPanelSize(val width: Int = 384, val height: Int = 544) {
    fun fit(available: ChatPanelSize) = ChatPanelSize(fitAxis(width, 320, available.width), fitAxis(height, 360, available.height))

    fun resize(edge: ChatPanelEdge, shown: ChatPanelSize, dx: Float, dy: Float, available: ChatPanelSize): ChatPanelSize {
        return ChatPanelSize(
            if (edge != ChatPanelEdge.TOP && dx != 0f) fitAxis((shown.width - dx).roundToInt(), 320, available.width) else width,
            if (edge != ChatPanelEdge.LEFT && dy != 0f) fitAxis((shown.height - dy).roundToInt(), 360, available.height) else height,
        )
    }

    private fun fitAxis(desired: Int, minimum: Int, available: Int): Int {
        val ceiling = available.coerceAtLeast(1)
        return desired.coerceIn(minimum.coerceAtMost(ceiling), ceiling)
    }
}

internal enum class ChatPanelEdge(val cursor: Int, val key: String) {
    LEFT(Cursor.W_RESIZE_CURSOR, "floatingChat.resizeWidth"),
    TOP(Cursor.N_RESIZE_CURSOR, "floatingChat.resizeHeight"),
    CORNER(Cursor.NW_RESIZE_CURSOR, "floatingChat.resize"),
}

internal class ChatPanelPreference(scope: CoroutineScope, private val path: Path) {
    var chosen by mutableStateOf(ChatPanelSize())
        private set
    private val changes = Channel<ChatPanelSize>(Channel.CONFLATED)

    init {
        scope.launch {
            for (size in changes) withContext(Dispatchers.IO) { runCatching { persist(size) } }
        }
    }

    suspend fun load() {
        val saved = withContext(Dispatchers.IO) {
            runCatching { studioJson.decodeFromString<ChatPanelSize>(Files.readString(path)) }.getOrNull()
        }
        if (saved != null && saved.width > 0 && saved.height > 0) chosen = saved
    }

    fun choose(size: ChatPanelSize) {
        if (chosen == size) return
        chosen = size
        changes.trySend(size)
    }

    private fun persist(size: ChatPanelSize) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "chat-panel-", ".tmp")
        try {
            Files.writeString(temp, studioJson.encodeToString(ChatPanelSize.serializer(), size))
            try {
                Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

@Composable
internal fun ChatPanelResizeHandle(
    edge: ChatPanelEdge,
    chosen: ChatPanelSize,
    available: ChatPanelSize,
    modifier: Modifier,
    preview: (ChatPanelSize?) -> Unit,
    commit: (ChatPanelSize) -> Unit,
) {
    val m = localMessages.current
    val colors = localColors.current
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentChosen by rememberUpdatedState(chosen)
    val currentAvailable by rememberUpdatedState(available)
    val currentPreview by rememberUpdatedState(preview)
    val currentCommit by rememberUpdatedState(commit)
    val icon = remember(edge) { PointerIcon(Cursor.getPredefinedCursor(edge.cursor)) }

    Box(modifier.studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = colors.cardFill)
        .background(if (hovered) colors.hover else Color.Transparent)
        .hoverable(interaction).pointerHoverIcon(icon).onGloballyPositioned { coordinates = it }
        .semantics { contentDescription = m[edge.key] }
        .then(if (edge == ChatPanelEdge.CORNER) Modifier.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { currentCommit(ChatPanelSize()) })
        } else Modifier)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val step = if (event.isShiftPressed) 32f else 8f
            val dx = when (event.key) {
                Key.DirectionLeft -> -step
                Key.DirectionRight -> step
                else -> 0f
            }
            val dy = when (event.key) {
                Key.DirectionUp -> -step
                Key.DirectionDown -> step
                else -> 0f
            }
            if (edge != ChatPanelEdge.TOP && dx != 0f || edge != ChatPanelEdge.LEFT && dy != 0f) {
                commit(chosen.resize(edge, chosen.fit(available), dx, dy, available))
                true
            } else if (edge == ChatPanelEdge.CORNER && event.key == Key.MoveHome) {
                commit(ChatPanelSize())
                true
            } else false
        }.focusable().pointerInput(edge, density) {
            var initial = ChatPanelSize()
            var shown = ChatPanelSize()
            var origin: Offset? = null
            var final: ChatPanelSize? = null
            try {
                detectDragGestures(
                    onDragStart = { position ->
                        initial = currentChosen
                        shown = initial.fit(currentAvailable)
                        origin = coordinates?.takeIf { it.isAttached }?.localToRoot(position)
                        final = null
                    },
                    onDragEnd = {
                        final?.let(currentCommit)
                        currentPreview(null)
                    },
                    onDragCancel = { currentPreview(null) },
                ) { event, _ ->
                    val start = origin ?: return@detectDragGestures
                    val point = coordinates?.takeIf { it.isAttached }?.localToRoot(event.position) ?: return@detectDragGestures
                    val delta = (point - start) / density
                    val size = initial.resize(edge, shown, delta.x, delta.y, currentAvailable)
                    final = size
                    currentPreview(size)
                }
            } finally {
                currentPreview(null)
            }
        })
}
