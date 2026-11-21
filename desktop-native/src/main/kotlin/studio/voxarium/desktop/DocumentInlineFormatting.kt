package studio.voxarium.desktop

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.net.URI
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

private val parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
private val backticks = Regex("`+")

internal enum class DocumentInlineMark(val key: String, val icon: String, val delimiter: String,
                                       val alternatives: List<String> = listOf(delimiter)) {
    BOLD("bold", "bold", "**", listOf("**", "__")),
    ITALIC("italic", "italic", "*", listOf("*", "_")),
    STRIKE("strike", "strikethrough", "~~"),
    CODE("code", "code", "`"),
}

internal class DocumentInlineSelection private constructor(
    private val value: TextFieldValue,
    private val content: Node,
    private val start: Int,
    private val end: Int,
) {
    private val source get() = value.text
    private val selected get() = source.substring(start, end)

    companion object {
        fun of(value: TextFieldValue): DocumentInlineSelection? {
            if (value.composition != null || value.selection.collapsed) return null
            val source = value.text
            val selection = source.substring(value.selection.min, value.selection.max)
            if (selection.isBlank() || '\n' in selection || '\r' in selection) return null
            val start = value.selection.min + selection.indexOfFirst { !it.isWhitespace() }
            val end = value.selection.min + selection.indexOfLast { !it.isWhitespace() } + 1
            val content = nodes(parser.parse(source)).firstOrNull { node ->
                if (node !is Paragraph && node !is Heading) return@firstOrNull false
                val first = node.firstChild?.sourceSpans?.firstOrNull() ?: return@firstOrNull false
                val last = node.lastChild?.sourceSpans?.lastOrNull() ?: return@firstOrNull false
                start >= first.inputIndex && end <= last.inputIndex + last.length
            } ?: return null
            return DocumentInlineSelection(value, content, start, end)
        }
    }

    private fun replace(from: Int, to: Int, body: String, prefix: String = "", suffix: String = ""): TextFieldValue {
        val bodyStart = from + prefix.length
        val bodyEnd = bodyStart + body.length
        val selection = if (value.selection.reversed) TextRange(bodyEnd, bodyStart) else TextRange(bodyStart, bodyEnd)
        return TextFieldValue(source.replaceRange(from, to, prefix + body + suffix), selection)
    }

    private fun delimiters(mark: DocumentInlineMark): Pair<Int, Int>? {
        for (marker in mark.alternatives) {
            val width = marker.length
            fun isolated(from: Int, until: Int): Boolean =
                source.getOrNull(from - 1) != marker[0] && source.getOrNull(until) != marker[0] &&
                    !isEscaped(source, from)
            if (end - start > 2 * width && source.startsWith(marker, start) && source.startsWith(marker, end - width) &&
                isolated(start, start + width) && isolated(end - width, end)) return start + width to end - width
            if (start >= width && end + width <= source.length && source.startsWith(marker, start - width) &&
                source.startsWith(marker, end) && isolated(start - width, start) && isolated(end, end + width)) return start to end
        }
        return null
    }

    private fun code(): Code? = nodes(content).filterIsInstance<Code>().firstOrNull { code ->
        val span = code.sourceSpans.singleOrNull() ?: return@firstOrNull false
        val from = span.inputIndex
        val to = from + span.length
        if (from == start && to == end) return@firstOrNull true
        val width = source.substring(from, to).takeWhile { it == '`' }.length
        val afterOpen = from + width
        val beforeClose = to - width
        (start == afterOpen && end == beforeClose) ||
            (start == afterOpen + 1 && end == beforeClose - 1 && source[afterOpen] == ' ' && source[beforeClose - 1] == ' ')
    }

    fun marked(mark: DocumentInlineMark): Boolean = if (mark == DocumentInlineMark.CODE) code() != null else delimiters(mark) != null

    fun format(mark: DocumentInlineMark): TextFieldValue {
        if (mark == DocumentInlineMark.CODE) {
            val code = code()
            if (code != null) {
                val span = code.sourceSpans.single()
                return replace(span.inputIndex, span.inputIndex + span.length, code.literal)
            }
            val width = (backticks.findAll(selected).maxOfOrNull { it.value.length } ?: 0) + 1
            val delimiter = "`".repeat(width)
            val padding = if (selected.startsWith('`') || selected.endsWith('`')) " " else ""
            return replace(start, end, selected, delimiter + padding, padding + delimiter)
        }
        val existing = delimiters(mark)
        if (existing != null) {
            val width = mark.delimiter.length
            return replace(existing.first - width, existing.second + width, source.substring(existing.first, existing.second))
        }
        return replace(start, end, selected, mark.delimiter, mark.delimiter)
    }

    private fun link(): Pair<Link, IntRange>? = nodes(content).filterIsInstance<Link>().mapNotNull { node ->
        val span = node.sourceSpans.singleOrNull() ?: return@mapNotNull null
        val first = span.inputIndex
        val last = first + span.length
        // Inline links carry a destination after their label. Reference links are shared with other prose.
        val opening = node.firstChild?.sourceSpans?.firstOrNull()?.inputIndex ?: return@mapNotNull null
        val tail = node.lastChild?.sourceSpans?.lastOrNull() ?: return@mapNotNull null
        val closing = tail.inputIndex + tail.length
        if (source.getOrNull(first) != '[' || opening != first + 1 || !source.startsWith("](", closing) ||
            source.getOrNull(last - 1) != ')' || !(start == first && end == last || start >= opening && end <= closing)) {
            return@mapNotNull null
        }
        node to (first until last)
    }.firstOrNull()

    fun destination(): String? = link()?.first?.destination

    private val currentColor: DocumentColorSpan? by lazy { documentColorSpans(source).firstOrNull { span ->
        start == span.start && end == span.end || start == span.start + 1 && end == span.close
    } }

    fun color(): DocumentColor? = currentColor?.color

    fun changeColor(color: DocumentColor?): TextFieldValue? {
        val current = currentColor
        if (color == current?.color) return null
        val from = current?.start ?: start
        val to = current?.end ?: end
        val body = current?.let { source.substring(it.start + 1, it.close) } ?: selected
        if (color == null) return replace(from, to, body)
        val suffix = "]{.${color.word}}"
        // An unmatched bracket or an escape in the selection must not swallow the closing delimiter.
        val candidate = "[$body$suffix"
        if (documentColorSpans(candidate).none { it.start == 0 && it.end == candidate.length }) return null
        return replace(from, to, body, "[", suffix)
    }

    fun changeLink(rawDestination: String): TextFieldValue? {
        val destination = if (rawDestination.isBlank()) "" else documentLinkDestination(rawDestination) ?: return null
        val existing = link()
        if (existing != null) {
            val (node, span) = existing
            val tail = node.lastChild.sourceSpans.last()
            val label = source.substring(span.first + 1, tail.inputIndex + tail.length)
            val title = node.title?.let { " \"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }.orEmpty()
            return if (destination.isEmpty()) replace(span.first, span.last + 1, label)
            else replace(span.first, span.last + 1, label, "[", "](<$destination>$title)")
        }
        if (destination.isEmpty() || nodes(content).filterIsInstance<Link>().any { node ->
                node.sourceSpans.any { span -> span.inputIndex < end && span.inputIndex + span.length > start }
            }) return null
        return replace(start, end, selected, "[", "](<$destination>)")
    }
}

private fun nodes(node: Node): Sequence<Node> = sequence {
    yield(node)
    var child = node.firstChild
    while (child != null) {
        yieldAll(nodes(child))
        child = child.next
    }
}

private fun isEscaped(source: String, index: Int): Boolean {
    var before = index - 1
    while (before >= 0 && source[before] == '\\') before--
    return (index - before - 1) % 2 == 1
}

// Block editor spec §3.4: accept project-relative destinations and unambiguous HTTP(S) addresses.
internal fun documentLinkDestination(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty() || input.any { it.isISOControl() || it in "<>\\" }) return null
    val uri = runCatching { URI(input.replace(" ", "%20")) }.getOrNull() ?: return null
    if (uri.isAbsolute) return webDocumentImage(uri.toASCIIString())?.toASCIIString()
    if (uri.rawAuthority != null || input.startsWith("//")) return null
    return uri.toASCIIString()
}

// Block editor spec §4.4: the editor retains the selected source range while toolbar buttons take focus.
@OptIn(ExperimentalJewelApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun DocumentFormatToolbar(selection: DocumentInlineSelection, editingLink: Boolean,
                                   editLink: (Boolean) -> Unit, update: (TextFieldValue) -> Unit) {
    val messages = localMessages.current
    val colors = localColors.current
    var pickingColor by remember(selection) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().semantics { contentDescription = messages["documentFormat.toolbar"] },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
        if (editingLink) {
            val current = selection.destination()
            var address by remember(current) { mutableStateOf(TextFieldValue(current.orEmpty(), TextRange(0, current?.length ?: 0))) }
            val request = remember { FocusRequester() }
            val formatted = remember(address.text, selection) { selection.changeLink(address.text) }
            LaunchedEffect(Unit) { request.requestFocus() }
            val label = messages["documentFormat.linkAddress"]
            StudioField(label, address, { address = it }, Modifier.weight(1f).focusRequester(request)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.Enter, Key.NumPadEnter -> formatted?.let { value -> update(value); true } ?: false
                        Key.Escape -> { editLink(false); true }
                        else -> false
                    }
                }, placeholder = label)
            WorkAction(messages["documentFormat.apply"], null, enabled = formatted != null) { formatted?.let(update) }
            LibraryIconAction("x", messages["cancel"]) { editLink(false) }
        } else if (pickingColor) {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                val selected = selection.color()
                DocumentColorSwatch(messages["documentFormat.clearColor"], colors.muted, selected == null,
                    enabled = selected != null, icon = "x") {
                    selection.changeColor(null)?.let(update)
                    pickingColor = false
                }
                for (color in DocumentColor.entries) {
                    val label = messages["documentFormat.color.${color.word}"]
                    val formatted = selection.changeColor(color)
                    DocumentColorSwatch(label, color.tint(colors.dark), selected == color, enabled = formatted != null) {
                        formatted?.let(update)
                        pickingColor = false
                    }
                }
            }
        } else {
            for (mark in DocumentInlineMark.entries) {
                LibraryIconAction(mark.icon, messages["documentFormat.${mark.key}"], selected = selection.marked(mark)) {
                    update(selection.format(mark))
                }
            }
            LibraryIconAction("link-2", messages["documentFormat.link"], selected = selection.destination() != null) {
                editLink(true)
            }
            LibraryIconAction("palette", messages["documentFormat.textColor"], selected = selection.color() != null) {
                pickingColor = true
            }
        }
    }
}
