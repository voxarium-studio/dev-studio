@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package studio.voxarium.desktop

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock

class DocumentReadingTest {
    @Test
    fun wikiCaptionsKeepTheirProjectPathsAndIgnoreCode() {
        val blocks = readDocumentMarkdown(
            "[[test-plan|Checklist]] and [[missing]] with `[[test-plan]]`. [Web](https://example.org)",
        ) { if (it == "test-plan") "qa/Тест план.md" else null }
        val paragraph = assertIs<MarkdownBlock.Paragraph>(blocks.single())
        val links = paragraph.inlineContent.filterIsInstance<InlineMarkdown.Link>()
        val target = URI(links.first().destination)
        assertEquals(DOCUMENT_LINK_SCHEME, target.scheme)
        assertEquals("/qa/Тест план.md", target.path)
        assertEquals("Checklist", assertIs<InlineMarkdown.Text>(links.first().inlineContent.single()).content)
        assertEquals("https://example.org", links.last().destination)
        assertTrue(paragraph.inlineContent.filterIsInstance<InlineMarkdown.Text>().any { "missing" in it.content })
        assertEquals("[[test-plan]]", paragraph.inlineContent.filterIsInstance<InlineMarkdown.Code>().single().content)
    }

    @Test
    fun documentTablesAndHeadingsRetainTheirStructure() {
        val blocks = readDocumentMarkdown(
            "# Overview\n\n## `Dash` setup\n\n| Stat | Value |\n| --- | --- |\n| Distance | 6 m |",
        ) { null }
        val headings = documentOutline(blocks)
        assertEquals(listOf("Overview", "Dash setup"), headings.map { it.title })
        assertEquals(listOf(1, 2), headings.map { it.block.level })
        assertIs<MarkdownBlock.CustomBlock>(blocks.last())
    }

    @Test
    fun documentImagesOnlyLoadThroughTheProjectForRelativeReferences() {
        assertTrue(fetchableDocumentImage("../images/Test figure.png"))
        assertFalse(fetchableDocumentImage("HTTPS://external.example/photo.jpg"))
        assertFalse(fetchableDocumentImage("//external.example/photo.jpg"))
        assertFalse(fetchableDocumentImage("file:///C:/photo.jpg"))
        assertFalse(fetchableDocumentImage("\\\\external\\photo.jpg"))
        assertEquals("external.example", webDocumentImage("https://external.example/photo.jpg")?.host)
        assertNull(webDocumentImage("https://user@external.example/photo.jpg"))
    }

    @Test
    fun strikethroughRendersNestedFormattingAndWikiLinksOutsideCode() {
        val paragraph = assertIs<MarkdownBlock.Paragraph>(readDocumentMarkdown(
            "~~**Archived** [[notes]]~~ and `~~Literal~~` and \\~\\~escaped~~",
        ) { "$it.md" }.single())
        val strike = assertIs<DocumentStyledInline>(paragraph.inlineContent.first())
        assertNull(strike.color)
        assertIs<InlineMarkdown.StrongEmphasis>(strike.inlineContent.first())
        assertTrue(strike.inlineContent.any { it is InlineMarkdown.Link })
        assertEquals("~~Literal~~", paragraph.inlineContent.filterIsInstance<InlineMarkdown.Code>().single().content)
        assertEquals(1, paragraph.inlineContent.filterIsInstance<DocumentStyledInline>().size)
    }

    @Test
    fun renderColorsWithNestedMarksAndLeaveUnknownColorsAndCodeLiteral() {
        val paragraph = assertIs<MarkdownBlock.Paragraph>(readDocumentMarkdown(
            "[**Main** [inner]{.grey} [[page]]]{.green} `[code]{.red}` [x]{.custom}",
        ) { "$it.md" }.single())
        val outer = assertIs<DocumentStyledInline>(paragraph.inlineContent.first())
        assertEquals(DocumentColor.GREEN, outer.color)
        assertIs<InlineMarkdown.StrongEmphasis>(outer.inlineContent.first())
        assertEquals(DocumentColor.GRAY, outer.inlineContent.filterIsInstance<DocumentStyledInline>().single().color)
        assertTrue(outer.inlineContent.any { it is InlineMarkdown.Link })
        assertEquals("[code]{.red}", paragraph.inlineContent.filterIsInstance<InlineMarkdown.Code>().single().content)
        assertTrue(paragraph.inlineContent.filterIsInstance<InlineMarkdown.Text>().any { "[x]{.custom}" in it.content })
    }
}
