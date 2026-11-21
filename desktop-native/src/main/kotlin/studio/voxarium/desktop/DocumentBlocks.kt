package studio.voxarium.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

private val blockParser = Parser.builder().extensions(listOf(TablesExtension.create()))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
private val newline = Regex("\\r\\n|\\r|\\n")
private val markdownLine = Regex("([^\\r\\n]*)(?:\\r\\n|\\r|\\n|$)")
private val frontmatter = Regex("^---(?:\\r\\n|\\r|\\n)(?:[\\s\\S]*?(?:\\r\\n|\\r|\\n))??(?:---|\\.\\.\\.)[ \\t]*(?:(?:\\r\\n|\\r|\\n)+|$)")
private val directiveOpening = Regex(" {0,3}(:{3,})[a-zA-Z][a-zA-Z0-9_-]*(?:$|[\\s\\[{].*)")
private val directiveClosing = Regex(" {0,3}(:{3,})[ \\t]*")

internal class EditableDocumentBlock(val id: Int, source: String) {
    val field = mutableStateOf(TextFieldValue(source))
}

// Block editor spec §7–8: source ranges are copied verbatim, including unsupported syntax.
// CommonMark classifies the rows; it never serializes the file or normalizes its whitespace.
internal class DocumentBlocks(source: String) {
    private data class Row(val block: EditableDocumentBlock, val gapBefore: String)

    private var nextId = 0
    private val rows = mutableStateListOf<Row>()
    private val prefix: String
    private val suffix: String
    val lineEnding = newline.find(source)?.value ?: "\n"
    val size: Int get() = rows.size

    init {
        val bodyStart = frontmatter.find(source)?.value?.length ?: 0
        val body = source.substring(bodyStart)
        var previousEnd = 0
        for (range in blockRanges(body)) {
            rows.add(Row(EditableDocumentBlock(nextId++, body.substring(range.first, range.last + 1)),
                body.substring(previousEnd, range.first)))
            previousEnd = range.last + 1
        }
        prefix = source.substring(0, bodyStart)
        suffix = body.substring(previousEnd)
    }

    operator fun get(index: Int): EditableDocumentBlock = rows[index].block

    fun indexOf(id: Int): Int = rows.indexOfFirst { it.block.id == id }

    fun source(): String = buildString {
        append(prefix)
        for (row in rows) {
            append(row.gapBefore)
            append(row.block.field.value.text)
        }
        append(suffix)
    }

    fun insert(at: Int, text: String): EditableDocumentBlock {
        require(at in 0..size)
        val block = EditableDocumentBlock(nextId++, text)
        val gap = if (at == 0) rows.firstOrNull()?.gapBefore.orEmpty() else lineEnding.repeat(2)
        if (at == 0 && rows.isNotEmpty()) rows[0] = rows[0].copy(gapBefore = lineEnding.repeat(2))
        val separatesFrontmatter = at == 0 && prefix.isNotEmpty() && prefix.last() !in "\r\n"
        rows.add(at, Row(block, if (separatesFrontmatter) lineEnding.repeat(2) else gap))
        return block
    }

    fun remove(at: Int) {
        val removed = rows.removeAt(at)
        if (at == 0 && rows.isNotEmpty()) rows[0] = rows[0].copy(gapBefore = removed.gapBefore)
    }

    fun move(from: Int, to: Int) {
        require(from in rows.indices && to in rows.indices)
        if (from == to) return
        val start = minOf(from, to)
        val end = maxOf(from, to)
        val gaps = rows.subList(start, end + 1).map(Row::gapBefore)
        val moved = rows.removeAt(from)
        rows.add(to, moved)
        for (index in start..minOf(end + 1, rows.lastIndex)) {
            val current = rows[index]
            val originalGap = gaps.getOrNull(index - start) ?: current.gapBefore
            // A boundary of one newline may depend on the original block types, e.g. setext headings.
            val gap = if (index == 0 || newline.findAll(originalGap).take(2).count() == 2) originalGap
                else lineEnding.repeat(2)
            rows[index] = current.copy(gapBefore = gap)
        }
    }
}

private fun blockRanges(body: String): List<IntRange> {
    val lines = markdownLine.findAll(body).map { it.groups[1]!!.range }.toList()
    val ranges = mutableListOf<IntRange>()
    val literalLines = BooleanArray(lines.size)
    var node = blockParser.parse(body).firstChild
    while (node != null) {
        val spans = node.sourceSpans
        if (spans.isNotEmpty()) {
            val first = spans.first().lineIndex
            val last = spans.last().lineIndex
            ranges.add(first..last)
            if (node is FencedCodeBlock || node is IndentedCodeBlock || node is HtmlBlock) {
                for (index in first..last) literalLines[index] = true
            }
        }
        node = node.next
    }

    // Studio callouts and toggles contain Markdown. Keep the container and its contents together.
    val directives = ArrayDeque<Pair<Int, Int>>()
    for ((index, range) in lines.withIndex()) {
        if (literalLines[index]) continue
        val line = body.substring(range)
        val opening = directiveOpening.matchEntire(line)
        if (opening != null) {
            directives.addLast(index to opening.groupValues[1].length)
        } else {
            val closing = directiveClosing.matchEntire(line)?.groupValues?.get(1)?.length ?: continue
            while (directives.lastOrNull()?.second?.let { it <= closing } == true) {
                ranges.add(directives.removeLast().first..index)
            }
        }
    }
    for ((start) in directives) ranges.add(start..lines.lastIndex)

    return mergeBlockRanges(ranges).map { lines[it.first].first..lines[it.last].last }
}

private fun mergeBlockRanges(ranges: List<IntRange>): List<IntRange> = buildList {
    for (range in ranges.sortedBy(IntRange::first)) {
        val previous = lastOrNull()
        if (previous == null || range.first > previous.last) add(range)
        else this[lastIndex] = previous.first..maxOf(previous.last, range.last)
    }
}
