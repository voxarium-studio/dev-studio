package studio.voxarium.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jewel.ui.component.Text

// §10.4: Waiting represents several statuses, and Archive changes a separate flag.
internal fun taskColumnPatch(task: JsonObject, statuses: List<String>, language: String): JsonObject? {
    val status = task.string("status") ?: return null
    if (task.flag("is_running")) return null
    val archived = task.flag("archived")
    val destination = statuses.firstOrNull()
    if (destination == null && (archived || status != "done")) return null
    if (destination != null && !archived && status in statuses) return null
    return buildJsonObject {
        if (destination == null || archived) put("archived", destination == null)
        if (destination != null && status != destination) put("status", destination)
        put("summary_locale", language)
    }
}

internal class TaskBoardDrag {
    var item by mutableStateOf<WorkspaceItem?>(null)
        private set
    var viewport: LayoutCoordinates? = null
    private val columns = mutableMapOf<List<String>, LayoutCoordinates>()
    private var pointer by mutableStateOf<Offset?>(null)
    var target by mutableStateOf<List<String>?>(null)
        private set

    fun column(statuses: List<String>, coordinates: LayoutCoordinates) {
        columns[statuses] = coordinates
    }

    fun move(task: WorkspaceItem, position: Offset) {
        if (item != null && item?.id != task.id) return
        item = task
        val board = viewport?.takeIf { it.isAttached }?.boundsInRoot()
        target = if (board?.contains(position) == true) {
            columns.entries.firstOrNull { (statuses, coordinates) ->
                (statuses.isNotEmpty() || task.status == "done") &&
                    coordinates.isAttached && coordinates.boundsInRoot().contains(position)
            }?.key
        } else null
        pointer = position
    }

    fun clear(taskId: String) {
        if (item?.id != taskId) return
        pointer = null
        target = null
        item = null
    }

    fun previewOffset(gap: Int, size: IntSize): IntOffset {
        val board = viewport?.takeIf { it.isAttached } ?: return IntOffset.Zero
        val local = (pointer ?: return IntOffset.Zero) - board.localToRoot(Offset.Zero)
        fun offset(position: Float, extent: Int, available: Int): Int {
            val point = position.roundToInt()
            val after = point + gap
            val limit = (available - extent).coerceAtLeast(0)
            return if (after <= limit) after.coerceAtLeast(0) else (point - gap - extent).coerceIn(0, limit)
        }
        return IntOffset(offset(local.x, size.width, board.size.width), offset(local.y, size.height, board.size.height))
    }

    fun edgeScroll(edge: Float): Float {
        val board = viewport?.takeIf { it.isAttached }?.boundsInRoot() ?: return 0f
        val position = pointer ?: return 0f
        if (!board.contains(position)) return 0f
        val fromLeft = position.x - board.left
        val fromRight = board.right - position.x
        return (1f - fromRight / edge).coerceAtLeast(0f) - (1f - fromLeft / edge).coerceAtLeast(0f)
    }

    fun updateTarget() {
        val task = item ?: return
        val position = pointer ?: return
        move(task, position)
    }
}

@Composable
internal fun Modifier.taskDragSource(
    task: WorkspaceItem,
    board: TaskBoardDrag,
    enabled: Boolean,
    drop: (List<String>) -> Unit,
): Modifier {
    if (!enabled) return this
    val currentTask by rememberUpdatedState(task)
    val currentDrop by rememberUpdatedState(drop)
    var source by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { source = it }.pointerInput(board, task.id) {
        try {
            detectDragGestures(
                onDragEnd = {
                    if (board.item?.id == task.id) {
                        board.updateTarget()
                        val target = board.target
                        board.clear(task.id)
                        target?.let(currentDrop)
                    }
                },
                onDragCancel = { board.clear(task.id) },
            ) { change, _ ->
                val coordinates = source?.takeIf { it.isAttached } ?: return@detectDragGestures
                board.move(currentTask, coordinates.localToRoot(change.position))
            }
        } finally {
            board.clear(task.id)
        }
    }
}

@Composable
internal fun TaskDragOverlay(board: TaskBoardDrag, scroll: ScrollState) {
    val task = board.item ?: return
    val colors = localColors.current
    // Drag auto-scroll triggers within this hit band, independent of the gutter.
    val edge = with(LocalDensity.current) { 42.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(board, task.id, edge) {
        var lastFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, .05f)
            lastFrame = frame
            val direction = board.edgeScroll(edge)
            if (direction != 0f && scroll.scrollBy(direction * seconds * edge * 12f) != 0f) {
                withFrameNanos { }
                board.updateTarget()
            }
        }
    }
    val shape = RoundedCornerShape(StudioTokens.radiusMd)
    // The pointer clearance keeps the dragged item legible without occluding its drop target.
    Column(Modifier.offset { board.previewOffset(12.dp.roundToPx(), size) }.width(210.dp).onSizeChanged { size = it }
        .background(colors.cardFill, shape).border(StudioTokens.borderWidth, colors.accent, shape)
        .clearAndSetSemantics { }.padding(StudioTokens.space2), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        Muted(task.payload.string("display_id").orEmpty())
        Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
