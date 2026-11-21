package studio.voxarium.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentFormattingTest {
    @Test
    fun formatsOneSelectedRangeAndRestoresItsDirection() {
        val source = "# Before\r\n\r\nRead \tthis and later."
        val start = source.indexOf("this")
        val input = TextFieldValue(source, TextRange(start + 4, start - 1))
        val bold = assertNotNull(DocumentInlineSelection.of(input)).format(DocumentInlineMark.BOLD)
        assertEquals("# Before\r\n\r\nRead \t**this** and later.", bold.text)
        assertEquals(TextRange(start + 6, start + 2), bold.selection)
        val plain = assertNotNull(DocumentInlineSelection.of(bold)).format(DocumentInlineMark.BOLD)
        assertEquals(source, plain.text)
        assertEquals(TextRange(start + 4, start), plain.selection)
    }

    @Test
    fun wrappingInlineCodePreservesBackticksInside() {
        val selected = "`like this`"
        val source = "Write $selected in a note."
        val from = source.indexOf(selected)
        val input = TextFieldValue(source, TextRange(from, from + selected.length))
        val inline = assertNotNull(DocumentInlineSelection.of(input)).format(DocumentInlineMark.CODE)
        val existing = assertNotNull(DocumentInlineSelection.of(inline))
        val restored = existing.format(DocumentInlineMark.CODE)
        // Selecting an existing code span first removes it. Reapplying from its raw text recreates a valid span.
        assertEquals("Write like this in a note.", inline.text)
        assertEquals(source, restored.text)

        val literal = "Text tick` and word"
        val literalSelection = TextFieldValue(literal, TextRange(5, 10))
        val escaped = assertNotNull(DocumentInlineSelection.of(literalSelection)).format(DocumentInlineMark.CODE)
        assertEquals("Text `` tick` `` and word", escaped.text)
        assertEquals(literal, assertNotNull(DocumentInlineSelection.of(escaped)).format(DocumentInlineMark.CODE).text)
    }

    @Test
    fun skipsBlockPrefixesFencesAndMultilineSelections() {
        assertNull(DocumentInlineSelection.of(TextFieldValue("# Title", TextRange(0, 7))))
        assertNull(DocumentInlineSelection.of(TextFieldValue("```\ncode\n```", TextRange(4, 8))))
        assertNull(DocumentInlineSelection.of(TextFieldValue("A line\nAnother", TextRange(0, 14))))
    }

    @Test
    fun editsLinksAndKeepsTheirLabelsWithFormattingAndTitle() {
        val source = "See [**Guide**](old \"title\") now."
        val from = source.indexOf("Guide")
        val input = TextFieldValue(source, TextRange(from, from + 5))
        val selection = assertNotNull(DocumentInlineSelection.of(input))
        assertEquals("old", selection.destination())
        assertEquals("See [**Guide**](<qa/Test%20plan.md> \"title\") now.", selection.changeLink("qa/Test plan.md")?.text)
        assertEquals("See **Guide** now.", selection.changeLink("")?.text)
        assertNull(selection.changeLink("javascript:alert(1)"))
        assertNull(selection.changeLink("//example.org/redirect"))
    }

    @Test
    fun textColorsRoundTripAsBracketedMarkdownAndKeepUntouchedBytes() {
        val original = "See **Guide** later."
        val selected = TextFieldValue(original, TextRange(4, 13))
        val red = assertNotNull(DocumentInlineSelection.of(selected)?.changeColor(DocumentColor.RED))
        assertEquals("See [**Guide**]{.red} later.", red.text)
        val blue = assertNotNull(DocumentInlineSelection.of(red)?.changeColor(DocumentColor.BLUE))
        assertEquals("See [**Guide**]{.blue} later.", blue.text)
        assertEquals(original, DocumentInlineSelection.of(blue)?.changeColor(null)?.text)
    }

    @Test
    fun movesNestedListItemsWithoutReindentingOtherRows() {
        val source = "- First\r\n  - Inner one\r\n    continuation  \r\n  - Inner two\r\n- Last"
        val from = source.indexOf("Inner two")
        val selected = TextFieldValue(source, TextRange(from, from + 9))
        val moved = assertNotNull(documentListItemMoves(selected)?.up)
        assertEquals("- First\r\n  - Inner two\r\n  - Inner one\r\n    continuation  \r\n- Last", moved.text)
        assertEquals("Inner two", moved.text.substring(moved.selection.min, moved.selection.max))
        assertEquals(source, documentListItemMoves(moved)?.down?.text)
    }

    @Test
    fun movesWholeListItemSubtreesAndLeavesCodeAlone() {
        val source = "1. One\n   - Nested\n\n2. Two\n\nTail"
        val caret = TextFieldValue(source, TextRange(source.indexOf("One")))
        assertEquals("1. Two\n\n2. One\n   - Nested\n\nTail", documentListItemMoves(caret)?.down?.text)
        val code = "```\n- One\n- Two\n```"
        assertNull(documentListItemMoves(TextFieldValue(code, TextRange(code.indexOf("One")))))
    }
}
