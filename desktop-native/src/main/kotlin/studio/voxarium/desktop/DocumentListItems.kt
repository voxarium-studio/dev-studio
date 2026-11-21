package studio.voxarium.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

private val listParser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
private val orderedMarker = Regex("[0-9]{1,9}(?=[.)])")

internal data class DocumentListItemMoves(val up: TextFieldValue?, val down: TextFieldValue?)

// Block editor spec §4.1: a list item includes its paragraphs and nested items.
// Move only sibling items and keep the spelling of their markers, indent and separators.
internal fun documentListItemMoves(value: TextFieldValue): DocumentListItemMoves? {
    if (value.composition != null || value.text.none { it == '\r' || it == '\n' }) return null
    val source = value.text

    fun range(item: ListItem): IntRange? {
        val start = item.sourceSpans.firstOrNull()?.inputIndex ?: return null
        val tail = item.sourceSpans.last()
        var first = start
        while (first > 0 && source[first - 1] !in "\r\n") first--
        var end = tail.inputIndex + tail.length
        while (end < source.length && source[end] !in "\r\n") end++
        return first until end
    }

    fun selectedItem(parent: Node): ListItem? {
        var child = parent.firstChild
        while (child != null) {
            if (child is ListItem) {
                val own = range(child)
                if (own != null && value.selection.min >= own.first && value.selection.max <= own.last + 1) {
                    return selectedItem(child) ?: child
                }
            } else selectedItem(child)?.let { return it }
            child = child.next
        }
        return null
    }

    val item = selectedItem(listParser.parse(source)) ?: return null
    val current = range(item) ?: return null
    fun move(neighbor: Node?): TextFieldValue? {
        val other = neighbor as? ListItem ?: return null
        val neighborRange = range(other) ?: return null
        val earlier = if (current.first < neighborRange.first) current else neighborRange
        val later = if (current.first < neighborRange.first) neighborRange else current
        if (earlier.last >= later.first) return null
        val gap = source.substring(earlier.last + 1, later.first)
        var earlierText = source.substring(earlier)
        var laterText = source.substring(later)
        if (item.parent is OrderedList) {
            val (left, right) = if (current == earlier) item to other else other to item
            val leftOffset = left.sourceSpans.first().inputIndex - earlier.first
            val rightOffset = right.sourceSpans.first().inputIndex - later.first
            val first = orderedMarker.matchAt(earlierText, leftOffset)
            val second = orderedMarker.matchAt(laterText, rightOffset)
            if (first != null && second != null && first.value.length == second.value.length) {
                earlierText = earlierText.replaceRange(first.range, second.value)
                laterText = laterText.replaceRange(second.range, first.value)
            } else if (left.previous == null) {
                // A different indentation width could change the meaning of nested blocks.
                // Do not silently change the starting number of an ordered list either.
                return null
            }
        }
        val reordered = source.replaceRange(earlier.first, later.last + 1, laterText + gap + earlierText)
        val start = if (current == later) earlier.first else earlier.first + later.count() + gap.length
        val distance = start - current.first
        return TextFieldValue(reordered, TextRange(value.selection.start + distance, value.selection.end + distance))
    }

    val up = move(item.previous)
    val down = move(item.next)
    return if (up != null || down != null) DocumentListItemMoves(up, down) else null
}
