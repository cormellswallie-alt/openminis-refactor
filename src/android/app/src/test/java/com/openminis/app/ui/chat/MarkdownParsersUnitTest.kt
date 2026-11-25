package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for the extracted Markdown parsers:
 * [MarkdownBlockParser] and [MarkdownInlineParser].
 */
class MarkdownParsersUnitTest {

    @Test
    fun `block parser parses headings, fenced code, lists, blockquote, hr, table and display math`() {
        val md = """
            # Title

            ```kotlin
            fun hi() = 1
            ```

            - item 1
            - item 2

            1. first
            2. second

            - [x] done
            - [ ] todo

            > quoted line

            ---

            | Col A | Col B |
            | :--- | ---: |
            | 1 | 2 |

            $$
            E = mc^2
            $$
        """.trimIndent()

        val blocks = parseMarkdownBlocksBlocking(md)
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 && it.text == "Title" })
        assertTrue(blocks.any { it is MdBlock.CodeBlock && it.language == "kotlin" && it.code.contains("fun hi()") })
        assertTrue(blocks.any { it is MdBlock.UnorderedList && it.items.size == 2 })
        assertTrue(blocks.any { it is MdBlock.OrderedList && it.items.size == 2 })
        val tasks = blocks.filterIsInstance<MdBlock.TaskList>().single()
        assertEquals(listOf(true, false), tasks.items.map { it.checked })
        assertTrue(blocks.any { it is MdBlock.BlockQuote })
        assertTrue(blocks.any { it is MdBlock.HorizontalRule })
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Col A", "Col B"), table.headers)
        assertEquals(listOf(MdTableAlign.START, MdTableAlign.END), table.alignments)
        assertTrue(blocks.any { it is MdBlock.MathDisplay && it.latex.contains("E = mc^2") })
    }

    @Test
    fun `block parser incremental stream matches full parse`() {
        val chunks = listOf(
            "## Heading\n\nFirst para",
            "graph finishes.\n\n```py\nprint(",
            "1)\n```\n\nTail paragraph.",
        )
        val full = debugParseBlockRaws(chunks.joinToString(""))
        val incremental = debugParseIncrementalRaws(chunks)
        assertEquals(full, incremental)
    }

    @Test
    fun `table parser handles alignments and pipe artifact detection`() {
        assertEquals(
            listOf(MdTableAlign.START, MdTableAlign.CENTER, MdTableAlign.END),
            parseTableAlignments("| :--- | :---: | ---: |"),
        )
        assertTrue(isTablePipeArtifact("| | |"))
        assertFalse(isTablePipeArtifact("normal sentence"))
        assertEquals(
            "line1\nline2",
            fencedCodeContent("  ```kotlin", listOf("  line1", "  line2")),
        )
    }

    @Test
    fun `inline parser finds code spans, inline math and filters non-math currency`() {
        assertEquals(5, findInlineCodeClose("`abc`", 0))
        assertEquals(-1, findInlineCodeClose("`unterminated", 0))

        assertTrue(looksLikeMath("x^2 + y^2 = z^2"))
        assertTrue(looksLikeMath("\\frac{a}{b}"))
        assertFalse(looksLikeMath("50 and "))

        val collected = collectInlineMathLatex("Price is \$5, while \$x^2 + 1\$ and \\(\\alpha + \\beta\\) are math.")
        assertTrue(collected.any { it.contains("x^2") })
        assertTrue(collected.any { it.contains("\\alpha") })
        assertTrue(katexInlineTagFor("x^2").endsWith("x^2"))
    }
}
