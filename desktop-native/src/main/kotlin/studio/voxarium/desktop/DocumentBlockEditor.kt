package studio.voxarium.desktop

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import kotlin.math.roundToInt
import kotlin.math.abs

private enum class BlockTemplate(val key: String, val source: String, val aliases: String) {
    PARAGRAPH("paragraph", "", "text body текст абзац"),
    HEADING_1("heading1", "# ", "h1 head title заг заголовок"),
    HEADING_2("heading2", "## ", "h2 head section заг заголовок"),
    HEADING_3("heading3", "### ", "h3 head subsection заг заголовок"),
    QUOTE("quote", "> ", "quote цитата"),
    BULLETS("bullets", "- ", "bullet list ul список"),
    NUMBERED("numbered", "1. ", "ordered list ol список"),
    CHECKLIST("checklist", "- [ ] ", "checklist task todo чекбокс список"),
    DIVIDER("divider", "---", "separator rule линия разделитель"),
    CODE("code", "```\n\n```", "code pre код"),
    TABLE("table", "|   |   |\n| --- | --- |\n|   |   |", "table grid таблица"),
    CALLOUT("callout", ":::note\n\n:::", "callout note tip warning выноска заметка"),
    TOGGLE("toggle", ":::toggle\n\n:::", "toggle details сворачивание спойлер");

    fun text(eol: String) = source.replace("\n", eol)
}

internal fun documentDropIndex(origin: Int, gap: Int, size: Int): Int =
    (if (gap > origin) gap - 1 else gap).coerceIn(0, size - 1)

private class BlockDrag {
    val handles = mutableMapOf<Int, LayoutCoordinates>()
    var blockId by mutableStateOf<Int?>(null)
    var pointer by mutableStateOf<Offset?>(null)
    var viewport: LayoutCoordinates? = null

    fun grip(position: Offset): Int? {
        val root = viewport?.takeIf { it.isAttached }?.localToRoot(position) ?: return null
        return handles.entries.firstOrNull { (_, handle) -> handle.isAttached && handle.boundsInRoot().contains(root) }?.key
    }

    fun drop(list: LazyListState): Pair<Int, Int>? {
        val point = pointer ?: return null
        val size = viewport?.size ?: return null
        if (point.x < 0 || point.x >= size.width || point.y < 0 || point.y >= size.height) return null
        val layout = list.layoutInfo
        val visible = layout.visibleItemsInfo.filter { it.key is Int }
        val next = visible.firstOrNull { point.y + layout.viewportStartOffset < it.offset + it.size / 2f }
        val last = visible.lastOrNull() ?: return null
        return if (next != null) next.index to next.offset - layout.viewportStartOffset
        else last.index + 1 to last.offset + last.size - layout.viewportStartOffset
    }

    fun clear() {
        blockId = null
        pointer = null
    }
}

// Block editor spec §4.1–4.3: gutter actions and a pointer-driven insertion line.
@Composable
internal fun DocumentBlockEditor(blocks: DocumentBlocks, change: () -> Unit, enabled: Boolean,
                                 modifier: Modifier = Modifier, selectionChanged: (String) -> Unit = {}) {
    val messages = localMessages.current
    val colors = localColors.current
    val reduced = LocalStudioReducedMotion.current
    val list = rememberLazyListState()
    val drag = remember(blocks) { BlockDrag() }
    var active by remember(blocks) { mutableStateOf<Int?>(null) }
    var focus by remember(blocks) { mutableStateOf<Int?>(null) }
    var templateFor by remember(blocks) { mutableStateOf<Int?>(null) }
    val currentChange by rememberUpdatedState(change)
    val currentEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    fun insert(index: Int, text: String) {
        val block = blocks.insert(index, text)
        currentChange()
        active = block.id
        focus = block.id
        scope.launch { list.scrollToItem(index) }
    }
    fun move(from: Int, to: Int) {
        if (from != to) {
            blocks.move(from, to)
            currentChange()
        }
    }

    val placement = if (reduced) null else tween<IntOffset>(StudioMotion.normal, easing = StudioMotion.standard)
    Box(modifier.clipToBounds()) {
        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { drag.viewport = it }
            .pointerInput(blocks, list) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val origin = drag.grip(down.position) ?: return@awaitEachGesture
                        if (!currentEnabled || down.isConsumed) return@awaitEachGesture
                        down.consume()
                        try {
                            while (currentEnabled) {
                                // The grip owns this gesture before LazyColumn can treat it as a scroll.
                                val event = awaitPointerEvent(PointerEventPass.Initial).changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (event.isConsumed) break
                                event.consume()
                                if (drag.blockId != null || abs(event.position.y - down.position.y) >= viewConfiguration.touchSlop) {
                                    drag.blockId = origin
                                    drag.pointer = event.position
                                }
                                if (!event.pressed) {
                                    val from = blocks.indexOf(origin)
                                    val gap = drag.drop(list)?.first
                                    if (from >= 0 && gap != null) move(from, documentDropIndex(from, gap, blocks.size))
                                    break
                                }
                            }
                        } finally {
                            drag.clear()
                        }
                    }
                } finally {
                    drag.clear()
                }
            }, state = list, contentPadding = PaddingValues(horizontal = StudioTokens.space5, vertical = StudioTokens.space4),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            items(blocks.size, key = { blocks[it].id }) { index ->
                val block = blocks[index]
                val request = remember { FocusRequester() }
                LaunchedEffect(focus) {
                    if (focus == block.id) {
                        request.requestFocus()
                        focus = null
                    }
                }
                DisposableEffect(block.id, drag) {
                    onDispose { drag.handles.remove(block.id) }
                }
                val field = block.field.value
                val selection = if (enabled && active == block.id && drag.blockId == null) {
                    remember(field) { DocumentInlineSelection.of(field) }
                } else null
                val listMoves = if (enabled && active == block.id && drag.blockId == null) {
                    remember(field) { documentListItemMoves(field) }
                } else null
                var editingLink by remember(field.text) { mutableStateOf(false) }
                fun updateField(value: TextFieldValue) {
                    val previous = block.field.value
                    block.field.value = value
                    if (previous.text != value.text) currentChange()
                    if (previous.text != value.text || previous.selection != value.selection) {
                        selectionChanged(value.text.substring(value.selection.min, value.selection.max))
                    }
                }
                fun format(value: TextFieldValue) {
                    updateField(value)
                    editingLink = false
                    focus = block.id
                }
                val slash = field.text.takeIf { it.startsWith('/') && '\n' !in it && '\r' !in it }
                val visible = enabled && (templateFor == block.id || active == block.id && slash != null)
                Column(Modifier.fillMaxWidth().animateItem(
                    fadeInSpec = if (reduced) null else tween(StudioMotion.fast),
                    placementSpec = placement,
                    fadeOutSpec = if (reduced) null else tween(StudioMotion.fast),
                ).graphicsLayer { alpha = if (drag.blockId == block.id) StudioTokens.draggedOpacity else 1f }) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
                        val gripLabel = messages["documentBlocks.reorder"]
                        Box(Modifier.size(StudioTokens.space5, StudioTokens.controlMinHeight).onGloballyPositioned { drag.handles[block.id] = it }
                            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                            .semantics { contentDescription = gripLabel }, contentAlignment = Alignment.Center) {
                            StudioIcon("grip-vertical", Modifier.size(StudioTokens.iconSize), colors.faint)
                        }
                        TextArea(field, ::updateField, Modifier.weight(1f).heightIn(min = StudioTokens.rowHeight, max = 500.dp)
                            .focusRequester(request).onFocusChanged {
                                if (it.isFocused && active != block.id) {
                                    active = block.id
                                    val focused = block.field.value
                                    selectionChanged(focused.text.substring(focused.selection.min, focused.selection.max))
                                }
                            }.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed) {
                                    val moved = when (event.key) {
                                        Key.DirectionUp -> listMoves?.up
                                        Key.DirectionDown -> listMoves?.down
                                        else -> null
                                    }
                                    if (moved != null) {
                                        format(moved)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                if (selection == null || event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed) {
                                    return@onPreviewKeyEvent false
                                }
                                val mark = when (event.key) {
                                    Key.B -> if (!event.isShiftPressed) DocumentInlineMark.BOLD else null
                                    Key.I -> if (!event.isShiftPressed) DocumentInlineMark.ITALIC else null
                                    Key.X -> if (event.isShiftPressed) DocumentInlineMark.STRIKE else null
                                    else -> null
                                }
                                if (mark != null) {
                                    format(selection.format(mark))
                                    true
                                } else if (event.key == Key.K && event.isShiftPressed) {
                                    editingLink = true
                                    true
                                } else false
                            }
                            .semantics { contentDescription = messages["documentBlocks.edit"] }, enabled = enabled)
                        LibraryIconAction("ellipsis", messages["documentBlocks.actions"], enabled,
                            selected = active == block.id) { active = if (active == block.id) null else block.id }
                    }
                    if (selection != null) {
                        Box(Modifier.padding(start = StudioTokens.space5 + StudioTokens.space2)
                            .studioArrival(distance = StudioTokens.space1, duration = StudioMotion.fast)) {
                            DocumentFormatToolbar(selection, editingLink, { open ->
                                editingLink = open
                                if (!open) focus = block.id
                            }, ::format)
                        }
                    }
                    StudioDisclosure(active == block.id && enabled && drag.blockId == null,
                        Modifier.padding(start = StudioTokens.space5 + StudioTokens.space2)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                            LibraryIconAction("plus", messages["documentBlocks.add"], selected = visible) {
                                templateFor = if (templateFor == block.id) null else block.id
                            }
                            LibraryIconAction("arrow-up", messages["documentBlocks.up"], enabled = index > 0) {
                                move(index, index - 1)
                            }
                            LibraryIconAction("arrow-down", messages["documentBlocks.down"], enabled = index < blocks.size - 1) {
                                move(index, index + 1)
                            }
                            if (listMoves != null) {
                                LibraryIconAction("list-tree", messages["documentFormat.listItemUp"], enabled = listMoves.up != null) {
                                    listMoves.up?.let(::format)
                                }
                                LibraryIconAction("list-restart", messages["documentFormat.listItemDown"], enabled = listMoves.down != null) {
                                    listMoves.down?.let(::format)
                                }
                            }
                            LibraryIconAction("copy", messages["documentBlocks.duplicate"]) {
                                insert(index + 1, block.field.value.text)
                            }
                            LibraryIconAction("trash-2", messages["documentBlocks.delete"]) {
                                blocks.remove(index)
                                currentChange()
                                selectionChanged("")
                                templateFor = null
                                active = null
                            }
                        }
                    }
                    StudioDisclosure(visible, Modifier.padding(start = StudioTokens.space5 + StudioTokens.space2)) {
                        val query = slash?.drop(1).orEmpty().trim()
                        TemplateButtons(query) { template ->
                            val text = template.text(blocks.lineEnding)
                            if (slash == null) insert(index + 1, text)
                            else {
                                setBlockText(block, text)
                                currentChange()
                                focus = block.id
                            }
                            templateFor = null
                        }
                    }
                }
            }
            item(key = "add") {
                LibraryButton("plus", messages["documentBlocks.add"], enabled) { insert(blocks.size, "") }
            }
        }
        val dragged = drag.blockId
        if (dragged != null) {
            // Gesture edge-zone is fixed independently of the editor padding.
            val distance = with(LocalDensity.current) { 48.dp.toPx() }
            LaunchedEffect(dragged, distance) {
                var previous = withFrameNanos { it }
                while (true) {
                    val time = withFrameNanos { it }
                    val elapsed = ((time - previous) / 1_000_000_000f).coerceAtMost(.05f)
                    previous = time
                    val point = drag.pointer ?: continue
                    val view = drag.viewport?.size ?: continue
                    if (point.x !in 0f..view.width.toFloat() || point.y !in 0f..view.height.toFloat()) continue
                    val direction = (1f - (view.height - point.y) / distance).coerceAtLeast(0f) -
                        (1f - point.y / distance).coerceAtLeast(0f)
                    if (direction != 0f) list.scrollBy(direction * elapsed * distance * 12f)
                }
            }
            val drop = drag.drop(list)
            if (drop != null && blocks.indexOf(dragged).let { it >= 0 && documentDropIndex(it, drop.first, blocks.size) != it }) {
                Box(Modifier.offset { IntOffset(0, drop.second - 1) }.zIndex(1f)
                    .fillMaxWidth().padding(horizontal = StudioTokens.controlMinHeight).height(StudioTokens.focusWidth).background(colors.accent))
            }
            val text = blocks.indexOf(dragged).takeIf { it >= 0 }?.let { blocks[it].field.value.text }.orEmpty()
            val shape = RoundedCornerShape(StudioTokens.radiusMd)
            Box(Modifier.offset {
                val position = drag.pointer ?: Offset.Zero
                val viewport = drag.viewport?.size
                // Keep the two-line drag ghost in view and clear of the original grip.
                val room = (viewport?.height ?: 0) - 56.dp.roundToPx()
                IntOffset(42.dp.roundToPx(), (position.y.roundToInt() + StudioTokens.space3.roundToPx()).coerceIn(0, room.coerceAtLeast(0)))
            }.zIndex(2f).widthIn(max = 360.dp).background(colors.surface, shape)
                .border(StudioTokens.borderWidth, colors.accent, shape).clearAndSetSemantics { }.padding(StudioTokens.space2)) {
                Text(text.ifBlank { messages["documentBlocks.paragraph"] }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun setBlockText(block: EditableDocumentBlock, text: String) {
    val cursor = text.indexOf('\n').takeIf { it >= 0 }?.plus(1) ?: text.length
    block.field.value = TextFieldValue(text, TextRange(cursor))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateButtons(query: String, choose: (BlockTemplate) -> Unit) {
    val m = localMessages.current
    val matching = BlockTemplate.entries.filter {
        query.isEmpty() || m["documentBlocks.${it.key}"].contains(query, ignoreCase = true) ||
            it.aliases.contains(query, ignoreCase = true)
    }
    FlowRow(Modifier.fillMaxWidth().padding(vertical = StudioTokens.space1),
        horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        for (template in matching) WorkAction(m["documentBlocks.${template.key}"], null) { choose(template) }
    }
}
