@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package studio.voxarium.desktop

import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text as CommonMarkText
import org.commonmark.parser.Parser
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.WithChildBlocks
import org.jetbrains.jewel.markdown.WithInlineMarkdown
import org.jetbrains.jewel.markdown.WithTextContent
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableColors
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableMetrics
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableStyling
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableRendererExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.RowBackgroundStyle
import org.jetbrains.jewel.markdown.processing.MarkdownParserFactory
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Image as SkiaImage
import java.net.URI
import java.util.IdentityHashMap

internal const val DOCUMENT_LINK_SCHEME = "studio-doc"

private val documentExtensions = listOf(GitHubTableProcessorExtension, DocumentInlineExtension)

// Block editor spec §3: custom spans take precedence over Markdown link and table punctuation.
internal fun readDocumentMarkdown(markdown: String, resolve: (String) -> String?): List<MarkdownBlock> {
    val (masked, parser) = prepareDocumentMarkdown(markdown, resolve)
    return MarkdownProcessor(extensions = documentExtensions, commonMarkParser = parser).processMarkdownDocument(masked)
}

private data class DocumentToken(val range: IntRange, val raw: String, val wiki: MatchResult? = null,
                                 val color: DocumentColorSpan? = null)

private fun prepareDocumentMarkdown(markdown: String, resolve: (String) -> String?, depth: Int = 0): Pair<String, Parser> {
    val sentinel = ('\uE000'..'\uE003').firstOrNull { it !in markdown }
    val tokens = mutableListOf<DocumentToken>()
    val candidates = if (sentinel == null || depth >= 32) emptyList() else buildList {
        for (span in documentColorSpans(markdown)) {
            val range = span.start until span.end
            add(DocumentToken(range, markdown.substring(range), color = span))
        }
        for (match in wikiLinkPattern.findAll(markdown)) {
            var before = match.range.first - 1
            while (before >= 0 && markdown[before] == '\\') before--
            if ((match.range.first - before - 1) % 2 == 0) add(DocumentToken(match.range, match.value, wiki = match))
        }
    }
    val masked = if (candidates.isEmpty()) markdown else buildString {
        var cursor = 0
        for (item in candidates.sortedWith(compareBy({ it.range.first }, { -it.range.last }))) {
            if (item.range.first < cursor) continue
            append(markdown, cursor, item.range.first)
            tokens.add(item)
            append("$sentinel${tokens.lastIndex}$sentinel")
            cursor = item.range.last + 1
        }
        append(markdown, cursor, markdown.length)
    }
    val parser = MarkdownParserFactory.create(optimizeEdits = false, extensions = documentExtensions) {
        if (tokens.isEmpty()) this else postProcessor { document ->
            restoreDocumentTokens(document, Regex("$sentinel(\\d+)$sentinel"), tokens, resolve, depth)
            document
        }
    }
    return masked to parser
}

private val wikiLinkPattern = Regex("""\[\[([^\[\]|\r\n]+?)(?:\|([^\[\]\r\n]*))?]]""")

private fun restoreDocumentTokens(node: Node, token: Regex, tokens: List<DocumentToken>, resolve: (String) -> String?,
                                  depth: Int, literal: Boolean = false) {
    fun unmask(value: String?): String? = value?.let { text ->
        token.replace(text) { match -> tokens[match.groupValues[1].toInt()].raw }
    }
    when (node) {
        is CommonMarkText -> {
            if (literal) node.literal = unmask(node.literal).orEmpty()
            else replaceDocumentText(node, token, tokens, resolve, depth)
        }
        is Code -> node.literal = unmask(node.literal)
        is FencedCodeBlock -> {
            node.literal = unmask(node.literal)
            node.info = unmask(node.info)
        }
        is IndentedCodeBlock -> node.literal = unmask(node.literal)
        is HtmlInline -> node.literal = unmask(node.literal)
        is HtmlBlock -> node.literal = unmask(node.literal)
        is Link -> {
            node.destination = unmask(node.destination)
            node.title = unmask(node.title)
        }
        is LinkReferenceDefinition -> {
            node.destination = unmask(node.destination)
            node.title = unmask(node.title)
        }
        is Image -> {
            node.destination = unmask(node.destination)
            node.title = unmask(node.title)
        }
    }
    var child = node.firstChild
    while (child != null) {
        val next = child.next
        restoreDocumentTokens(child, token, tokens, resolve, depth, literal || node is Link || node is Image)
        child = next
    }
}

private fun replaceDocumentText(node: CommonMarkText, token: Regex, tokens: List<DocumentToken>,
                                resolve: (String) -> String?, depth: Int) {
    val text = node.literal
    var cursor = 0
    for (match in token.findAll(text)) {
        if (cursor < match.range.first) node.insertBefore(CommonMarkText(text.substring(cursor, match.range.first)))
        val item = tokens[match.groupValues[1].toInt()]
        val replaced = item.wiki?.let { wiki ->
            val target = wiki.groupValues[1].trim()
            val caption = wiki.groupValues[2].trim().ifEmpty { target.ifEmpty { wiki.value } }
            val path = target.takeIf(String::isNotEmpty)?.let(resolve)
            if (path == null) CommonMarkText(caption) else
                Link(URI(DOCUMENT_LINK_SCHEME, null, "/$path", null).toASCIIString(), null).apply { appendChild(CommonMarkText(caption)) }
        } ?: coloredDocumentText(item, resolve, depth)
        node.insertBefore(replaced)
        cursor = match.range.last + 1
    }
    if (cursor > 0) {
        if (cursor < text.length) node.insertBefore(CommonMarkText(text.substring(cursor)))
        node.unlink()
    }
}

private fun coloredDocumentText(item: DocumentToken, resolve: (String) -> String?, depth: Int): Node {
    val span = requireNotNull(item.color)
    val prefix = "x "
    val inner = item.raw.substring(1, span.close - span.start)
    val (source, parser) = prepareDocumentMarkdown(prefix + inner, resolve, depth + 1)
    val paragraph = parser.parse(source).firstChild as? Paragraph ?: return CommonMarkText(item.raw)
    val first = paragraph.firstChild as? CommonMarkText ?: return CommonMarkText(item.raw)
    if (!first.literal.startsWith(prefix)) return CommonMarkText(item.raw)
    first.literal = first.literal.removePrefix(prefix)
    if (first.literal.isEmpty()) first.unlink()
    return StyledDocumentInline("[", span.color).apply {
        while (paragraph.firstChild != null) appendChild(paragraph.firstChild)
    }
}

internal data class DocumentHeading(val block: MarkdownBlock.Heading, val title: String)

internal fun documentOutline(blocks: List<MarkdownBlock>): List<DocumentHeading> = buildList {
    fun visit(block: MarkdownBlock) {
        if (block is MarkdownBlock.Heading) {
            val title = buildString { appendInlineText(block.inlineContent) }.trim().ifEmpty { "—" }
            add(DocumentHeading(block, title))
        }
        if (block is WithChildBlocks) block.children.forEach(::visit)
    }
    blocks.forEach(::visit)
}

private fun StringBuilder.appendInlineText(inlines: List<InlineMarkdown>) {
    for (inline in inlines) {
        when (inline) {
            is WithTextContent -> append(inline.content)
            is WithInlineMarkdown -> appendInlineText(inline.inlineContent)
            InlineMarkdown.SoftLineBreak, InlineMarkdown.HardLineBreak -> append(' ')
        }
    }
}

internal class DocumentBlockRenderer(
    style: MarkdownStyling,
    private val colors: StudioColors,
    outline: List<DocumentHeading>,
    private val positionHeading: (Int, Float) -> Unit,
    private val imageLoader: (suspend (String) -> ByteArray)? = null,
) : DefaultMarkdownBlockRenderer(style, listOf(GitHubTableRendererExtension(documentTableStyle(colors), style), DocumentInlineStyleRenderer(colors.dark))) {
    // The parser can produce two headings with identical text. Each occurrence has its own target.
    private val headingTargets = IdentityHashMap<MarkdownBlock.Heading, Int>().apply {
        outline.forEachIndexed { index, heading -> put(heading.block, index) }
    }

    @Composable
    override fun RenderParagraph(block: MarkdownBlock.Paragraph, styling: MarkdownStyling.Paragraph, enabled: Boolean,
                                 onUrlClick: (String) -> Unit, modifier: Modifier) {
        val inline = block.inlineContent.singleOrNull()
        val wrapped = inline as? InlineMarkdown.Link
        val reference = inline as? InlineMarkdown.Image ?: wrapped?.inlineContent?.singleOrNull() as? InlineMarkdown.Image
        if (reference == null) {
            super.RenderParagraph(block, styling, enabled, onUrlClick, modifier)
            return
        }
        val label = reference.alt.takeIf(String::isNotBlank) ?: reference.source.substringAfterLast('/')
        val remote = webDocumentImage(reference.source)
        if (remote != null) {
            val link = InlineMarkdown.Link(remote.toASCIIString(), reference.title, listOf(InlineMarkdown.Text(label)))
            super.RenderParagraph(MarkdownBlock.Paragraph(listOf(link)), styling, enabled, onUrlClick, modifier)
            return
        }
        val loader = imageLoader?.takeIf { fetchableDocumentImage(reference.source) }
        var unavailable = loader == null
        val bitmap = if (loader == null) null else {
            val result by produceState<Result<ImageBitmap>?>(null, reference.source, loader) {
                value = try {
                    val bytes = loader(reference.source)
                    withContext(Dispatchers.Default) {
                        Result.success(SkiaImage.makeFromEncoded(bytes).use { encoded ->
                            require(encoded.width.toLong() * encoded.height <= 32_000_000L)
                            encoded.toComposeImageBitmap()
                        })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: Exception) {
                    Result.failure(problem)
                }
            }
            unavailable = result?.isFailure == true
            result?.getOrNull()
        }
        if (bitmap == null) {
            val key = if (unavailable) "documentBlocks.imageUnavailable" else "documentBlocks.imageLoading"
            Muted("$label · ${localMessages.current[key]}", modifier = modifier)
        } else {
            val width = with(LocalDensity.current) { bitmap.width.toDp() }
            val click = if (wrapped == null) Modifier else Modifier
                .studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), enabled)
                .clickable(enabled = enabled, role = Role.Button) { onUrlClick(wrapped.destination) }
            ComposeImage(bitmap, label, modifier.then(click).widthIn(max = width).fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height))
        }
    }

    @Composable
    override fun RenderHeading(
        block: MarkdownBlock.Heading,
        styling: MarkdownStyling.Heading,
        enabled: Boolean,
        onUrlClick: (String) -> Unit,
        modifier: Modifier,
    ) {
        val index = headingTargets[block]
        super.RenderHeading(block, styling, enabled, onUrlClick,
            if (index != null) modifier.onGloballyPositioned { positionHeading(index, it.positionInRoot().y) } else modifier)
    }

    @Composable
    override fun RenderUnorderedList(
        block: MarkdownBlock.ListBlock.UnorderedList,
        styling: MarkdownStyling.List.Unordered,
        enabled: Boolean,
        onUrlClick: (String) -> Unit,
        modifier: Modifier,
    ) {
        val markers = block.children.map(::taskListMarker)
        if (markers.none { it != null }) {
            super.RenderUnorderedList(block, styling, enabled, onUrlClick, modifier)
            return
        }
        Column(modifier.padding(start = StudioTokens.space5), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            block.children.zip(markers).forEach { (item, marker) ->
                TaskListRow(item, marker, "•", enabled, onUrlClick)
            }
        }
    }

    @Composable
    override fun RenderOrderedList(
        block: MarkdownBlock.ListBlock.OrderedList,
        styling: MarkdownStyling.List.Ordered,
        enabled: Boolean,
        onUrlClick: (String) -> Unit,
        modifier: Modifier,
    ) {
        val markers = block.children.map(::taskListMarker)
        if (markers.none { it != null }) {
            super.RenderOrderedList(block, styling, enabled, onUrlClick, modifier)
            return
        }
        Column(modifier.padding(start = StudioTokens.space5), verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
            block.children.zip(markers).forEachIndexed { index, (item, marker) ->
                TaskListRow(item, marker, "${index + block.startFrom}${block.delimiter}", enabled, onUrlClick)
            }
        }
    }

    @Composable
    private fun TaskListRow(item: MarkdownBlock.ListItem, marker: MatchResult?, bullet: String,
                            enabled: Boolean, onUrlClick: (String) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            if (marker == null) {
                Text(bullet)
                RenderListItem(item, enabled, onUrlClick, Modifier.weight(1f))
            } else {
                val checked = marker.groupValues[1].equals("x", ignoreCase = true)
                // The marker reports the Markdown state but is not an edit action.
                val shape = RoundedCornerShape(StudioTokens.radiusSm)
                val fill = if (checked) colors.accent else colors.inset
                val border = if (checked) StudioTokens.ensureContrast(fill, colors.canvas) else colors.controlBorder
                Box(Modifier.padding(top = StudioTokens.space1).size(StudioTokens.checkboxSize)
                    .background(fill, shape).border(StudioTokens.borderWidth, border, shape).semantics {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(checked)
                    }, contentAlignment = Alignment.Center) {
                    if (checked) StudioIcon("check", Modifier.size(StudioTokens.compactIconSize), colors.onAccent)
                }
                val paragraph = item.children.first() as MarkdownBlock.Paragraph
                val plain = paragraph.inlineContent.first() as InlineMarkdown.Text
                val remaining = plain.content.drop(marker.value.length)
                val inlines = listOfNotNull(remaining.takeIf(String::isNotEmpty)?.let { InlineMarkdown.Text(it) }) +
                    paragraph.inlineContent.drop(1)
                val style = rootStyling.paragraph
                val taskStyle = if (!checked) style else MarkdownStyling.Paragraph(checkedTextStyle(style.inlinesStyling))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                    RenderParagraph(MarkdownBlock.Paragraph(inlines), taskStyle, enabled, onUrlClick, Modifier)
                    item.children.drop(1).forEach { child -> RenderBlock(child, enabled, onUrlClick, Modifier) }
                }
            }
        }
    }

    private fun checkedTextStyle(style: InlinesStyling): InlinesStyling = InlinesStyling(
        style.textStyle.copy(color = colors.faint, textDecoration = TextDecoration.LineThrough),
        style.inlineCode, style.link, style.linkDisabled, style.linkFocused, style.linkHovered,
        style.linkPressed, style.linkVisited, style.emphasis, style.strongEmphasis, style.inlineHtml,
    )

    @Suppress("DEPRECATION")
    @Composable
    override fun RenderFencedCodeBlock(
        block: MarkdownBlock.CodeBlock.FencedCodeBlock,
        styling: MarkdownStyling.Code.Fenced,
        enabled: Boolean,
        modifier: Modifier,
    ) {
        val clipboard = LocalClipboardManager.current
        Row(modifier.fillMaxWidth().background(styling.background, styling.shape)
            .border(styling.borderWidth, styling.borderColor, styling.shape), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                val innerStyle = MarkdownStyling.Code.Fenced(
                    styling.editorTextStyle, styling.padding, styling.shape, styling.background, 0.dp,
                    styling.borderColor, styling.fillWidth, styling.scrollsHorizontally,
                    styling.infoTextStyle, styling.infoPadding, styling.infoPosition,
                )
                super.RenderFencedCodeBlock(block, innerStyle, enabled, Modifier)
            }
            Box(Modifier.padding(top = StudioTokens.space2, end = StudioTokens.space2)) {
                LibraryIconAction("copy", localMessages.current["libraryCopy"], enabled = enabled,
                    bordered = true) { clipboard.setText(AnnotatedString(block.content)) }
            }
        }
    }
}

private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

// Block editor spec §3.4: agent-written remote images are shown as links and never auto-fetched.
internal fun webDocumentImage(source: String): URI? = runCatching { URI(source) }.getOrNull()?.takeIf {
    it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null
}

internal fun fetchableDocumentImage(source: String): Boolean = source.isNotBlank() &&
    !source.startsWith('/') && !source.startsWith('\\') && !uriScheme.containsMatchIn(source)

private val taskListPattern = Regex("""^\[([ xX])](?:\s+|$)""")

private fun taskListMarker(item: MarkdownBlock.ListItem): MatchResult? {
    val paragraph = item.children.firstOrNull() as? MarkdownBlock.Paragraph ?: return null
    val plain = paragraph.inlineContent.firstOrNull() as? InlineMarkdown.Text ?: return null
    return taskListPattern.find(plain.content)
}

private fun documentTableStyle(colors: StudioColors) = GfmTableStyling(
    GfmTableColors(colors.border, colors.elevated, colors.canvas, RowBackgroundStyle.Striped),
    GfmTableMetrics(StudioTokens.borderWidth, PaddingValues(horizontal = StudioTokens.space3, vertical = StudioTokens.space2),
        Alignment.Start, Alignment.Start),
    androidx.compose.ui.text.font.FontWeight.SemiBold,
)
