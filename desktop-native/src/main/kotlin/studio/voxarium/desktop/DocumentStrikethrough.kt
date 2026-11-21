@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package studio.voxarium.desktop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.commonmark.node.CustomNode
import org.commonmark.node.Delimited
import org.commonmark.node.Document
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.MarkdownDelimitedInlineProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownDelimitedInlineRendererExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.InlinesStyling

internal class StyledDocumentInline(private val marker: String, val color: DocumentColor? = null) : CustomNode(), Delimited {
    override fun getOpeningDelimiter() = marker
    override fun getClosingDelimiter() = marker
}

internal data class DocumentStyledInline(override val openingDelimiter: String, val color: DocumentColor?,
                                         override val inlineContent: List<InlineMarkdown>) : InlineMarkdown.CustomDelimitedNode

// Block editor spec §3.2: let the CommonMark scanner handle escapes, flanking and code spans.
private object StrikeDelimiter : DelimiterProcessor {
    override fun getOpeningCharacter() = '~'
    override fun getClosingCharacter() = '~'
    override fun getMinLength() = 1

    override fun process(openingRun: DelimiterRun, closingRun: DelimiterRun): Int {
        val length = openingRun.length()
        if (length !in 1..2 || length != closingRun.length()) return 0
        val opener = openingRun.opener
        val closer = closingRun.closer
        val strike = StyledDocumentInline("~".repeat(length))
        var child = opener.next
        while (child != null && child !== closer) {
            val next = child.next
            strike.appendChild(child)
            child = next
        }
        opener.insertAfter(strike)
        return length
    }
}

internal object DocumentInlineExtension : MarkdownProcessorExtension {
    override val parserExtension = Parser.ParserExtension { it.customDelimiterProcessor(StrikeDelimiter) }
    override val delimitedInlineProcessorExtension = object : MarkdownDelimitedInlineProcessorExtension {
        override fun canProcess(delimited: Delimited): Boolean = delimited is StyledDocumentInline

        override fun processDelimitedInline(delimited: Delimited, markdownProcessor: MarkdownProcessor): DocumentStyledInline {
            val inline = delimited as StyledDocumentInline
            val paragraph = Paragraph()
            val container = Document().apply { appendChild(paragraph) }
            // Jewel exposes child block processing. A temporary paragraph reuses its inline conversion
            // for nested marks, wiki links and previously-resolved Markdown reference links.
            while (inline.firstChild != null) paragraph.appendChild(inline.firstChild)
            return try {
                val inlines = (markdownProcessor.processChildren(container).singleOrNull() as? MarkdownBlock.Paragraph)?.inlineContent.orEmpty()
                DocumentStyledInline(inline.openingDelimiter, inline.color, inlines)
            } finally {
                while (paragraph.firstChild != null) inline.appendChild(paragraph.firstChild)
            }
        }
    }
}

internal class DocumentInlineStyleRenderer(private val dark: Boolean) : MarkdownRendererExtension {
    override val delimitedInlineRenderer = object : MarkdownDelimitedInlineRendererExtension {
        override fun canRender(node: InlineMarkdown.CustomDelimitedNode): Boolean = node is DocumentStyledInline

        override fun render(node: InlineMarkdown.CustomDelimitedNode, inlineRenderer: InlineMarkdownRenderer,
                            inlinesStyling: InlinesStyling, enabled: Boolean, onUrlClicked: ((String) -> Unit)?): AnnotatedString =
            buildAnnotatedString {
                val color = (node as DocumentStyledInline).color
                val style = if (color == null) SpanStyle(textDecoration = TextDecoration.LineThrough)
                    else SpanStyle(color = color.tint(dark))
                withStyle(style) {
                    append(inlineRenderer.renderAsAnnotatedString(node.inlineContent, inlinesStyling, enabled, onUrlClicked))
                }
            }
    }
}
