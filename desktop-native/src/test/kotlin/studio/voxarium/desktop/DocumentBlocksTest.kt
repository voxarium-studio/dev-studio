package studio.voxarium.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentBlocksTest {
    @Test
    fun exactSourceSurvivesOpeningAndContainersStayTogether() {
        val parts = listOf("# Heading  ", "::::note[Outer]\r\n\r\n:::toggle\r\nBody\r\n:::\r\n\r\n::::",
            "```md\r\n:::note\r\n```", "- first\r\n  - nested\r\n- third", "| A |\r\n| --- |\r\n| 1 |",
            "    example", "[label]: https://example.org")
        val prefix = "---\r\ntitle: 'Unchanged'\r\n---\r\n\r\n"
        val source = prefix + parts.joinToString("\r\n\r\n") + "\r\n"
        val blocks = DocumentBlocks(source)
        assertEquals(source, blocks.source())
        assertEquals(parts, (0 until blocks.size).map { blocks[it].field.value.text })
        assertEquals("\r\n", blocks.lineEnding)
    }

    @Test
    fun reorderPreservesBlockBodiesAndWhitespaceEnvelope() {
        val blocks = DocumentBlocks("\nTitle\n====\n# Next\n\nText")
        val moved = blocks[2]
        blocks.move(2, 0)
        assertEquals("\nText\n\nTitle\n====\n\n# Next", blocks.source())
        assertEquals(moved.id, blocks[0].id)
        blocks.move(0, 0)
        assertEquals("\nText\n\nTitle\n====\n\n# Next", blocks.source())
    }

    @Test
    fun appendingToFrontmatterOnlyAddsASeparator() {
        val blocks = DocumentBlocks("---\nname: Plain\n---")
        assertEquals(0, blocks.size)
        blocks.insert(0, "# New")
        assertEquals("---\nname: Plain\n---\n\n# New", blocks.source())
        blocks.remove(0)
        assertEquals("---\nname: Plain\n---", blocks.source())
    }

    @Test
    fun closingEmptyFrontmatterDoesNotConsumeAHorizontalRuleFromTheBody() {
        val blocks = DocumentBlocks("---\n---\n\n# Heading\n\n---\n")
        assertEquals(2, blocks.size)
        assertEquals("# Heading", blocks[0].field.value.text)
        assertEquals("---", blocks[1].field.value.text)
    }

    @Test
    fun sourceAndBlockModesShareTheSameDraftAndRevisionGuard() {
        val draft = DocumentDraft("Title", "# First\n\nSecond\n", "version", "backend-A", 9)
        val blocks = draft.blocks()
        assertFalse(draft.dirty)
        blocks[1].field.value = TextFieldValue("Edited", TextRange(6))
        draft.changeBlocks()
        assertTrue(draft.dirty)
        assertEquals("# First\n\nEdited\n", draft.payload().string("content"))
        assertEquals("version", draft.payload().string("expected_updated_at"))
        assertEquals("backend-A", draft.payload().string("backend_epoch"))

        val source = "# First\n\nSource mode\n"
        draft.changeSource(TextFieldValue(source))
        assertEquals("Source mode", draft.blocks()[1].field.value.text)
        assertEquals(source, draft.blocks().source())
    }

    @Test
    fun dropGapMapsToTheIndexAfterRemovingTheOriginalBlock() {
        assertEquals(0, documentDropIndex(2, 0, 4))
        assertEquals(2, documentDropIndex(2, 2, 4))
        assertEquals(2, documentDropIndex(2, 3, 4))
        assertEquals(3, documentDropIndex(2, 4, 4))
    }
}
