package com.sidekick.app.ui.components.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the hand-rolled markdown parser used by [MarkdownText].
 *
 * The parser is the workhorse of the chat UI overhaul — the LLM
 * emits markdown, the parser splits it into blocks + inline spans,
 * the renderer maps each block type to a Compose widget. If the
 * parser regresses, the chat bubbles break silently (no error from
 * Compose — just garbage rendering).
 *
 * Coverage:
 *  - Empty input → empty list
 *  - Plain paragraph → single paragraph block with one text span
 *  - Headings at all three rendered levels
 *  - Bullet list with multiple items
 *  - Ordered list with multiple items
 *  - Fenced code block with a language tag and multi-line body
 *  - Inline: bold, italic, inline code
 *  - Mixed: paragraph that contains all three inline types in one go
 *  - Paragraphs spanning multiple lines are collapsed into one
 *  - Unknown / malformed input doesn't throw — falls back to a text span
 */
class MarkdownParserTest {

    @Test
    fun emptySourceProducesEmptyBlockList() {
        assertEquals(emptyList<MarkdownBlock>(), MarkdownParser.parse(""))
        assertEquals(emptyList<MarkdownBlock>(), MarkdownParser.parse("\n\n\n"))
    }

    @Test
    fun plainTextRendersAsOneParagraph() {
        val blocks = MarkdownParser.parse("Hello, world.")
        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertTrue(block is MarkdownBlock.Paragraph)
        val inlines = (block as MarkdownBlock.Paragraph).inlines
        assertEquals(1, inlines.size)
        assertEquals(MarkdownInline.Text("Hello, world."), inlines.single())
    }

    @Test
    fun heading1ParsesCorrectly() {
        val blocks = MarkdownParser.parse("# Title")
        val block = blocks.single()
        assertTrue(block is MarkdownBlock.Heading)
        block as MarkdownBlock.Heading
        assertEquals(1, block.level)
        assertEquals("Title", block.text)
    }

    @Test
    fun heading3ParsesCorrectly() {
        val blocks = MarkdownParser.parse("### Sub-sub")
        val block = blocks.single()
        assertTrue(block is MarkdownBlock.Heading)
        block as MarkdownBlock.Heading
        assertEquals(3, block.level)
        assertEquals("Sub-sub", block.text)
    }

    @Test
    fun bulletListParsesAllItems() {
        val src = """
            - first
            - second
            - third
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        assertEquals(1, blocks.size)
        val list = blocks.single()
        assertTrue(list is MarkdownBlock.BulletList)
        list as MarkdownBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf(MarkdownInline.Text("first")), list.items[0])
        assertEquals(listOf(MarkdownInline.Text("second")), list.items[1])
        assertEquals(listOf(MarkdownInline.Text("third")), list.items[2])
    }

    @Test
    fun orderedListParsesNumberedItems() {
        val src = """
            1. one
            2. two
            3. three
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        assertEquals(1, blocks.size)
        val list = blocks.single()
        assertTrue(list is MarkdownBlock.OrderedList)
        list as MarkdownBlock.OrderedList
        assertEquals(3, list.items.size)
    }

    @Test
    fun fencedCodeBlockParsesWithLanguage() {
        val src = """
            ```kotlin
            fun main() {
              println("hi")
            }
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        val block = blocks.single()
        assertTrue(block is MarkdownBlock.CodeBlock)
        block as MarkdownBlock.CodeBlock
        assertEquals("kotlin", block.lang)
        assertEquals(
            """
            fun main() {
              println("hi")
            }
            """.trimIndent(),
            block.code,
        )
    }

    @Test
    fun fencedCodeBlockWithoutLanguageWorks() {
        val blocks = MarkdownParser.parse("```\nplain\n```")
        val block = blocks.single()
        assertTrue(block is MarkdownBlock.CodeBlock)
        block as MarkdownBlock.CodeBlock
        assertEquals("", block.lang)
        assertEquals("plain", block.code)
    }

    @Test
    fun inlineBoldParsesCorrectly() {
        val inlines = MarkdownParser.parseInline("this is **bold** text")
        assertEquals(3, inlines.size)
        assertEquals(MarkdownInline.Text("this is "), inlines[0])
        assertEquals(MarkdownInline.Bold("bold"), inlines[1])
        assertEquals(MarkdownInline.Text(" text"), inlines[2])
    }

    @Test
    fun inlineItalicParsesCorrectly() {
        val inlines = MarkdownParser.parseInline("an *italic* word")
        assertEquals(3, inlines.size)
        assertEquals(MarkdownInline.Italic("italic"), inlines[1])
    }

    @Test
    fun inlineCodeParsesCorrectly() {
        val inlines = MarkdownParser.parseInline("use `println()` to print")
        assertEquals(3, inlines.size)
        assertEquals(MarkdownInline.Code("println()"), inlines[1])
    }

    @Test
    fun inlineMixedBoldItalicAndCode() {
        val inlines = MarkdownParser.parseInline("`code` and **bold** and *italic*")
        assertEquals(5, inlines.size)
        assertTrue(inlines.any { it is MarkdownInline.Code && it.text == "code" })
        assertTrue(inlines.any { it is MarkdownInline.Bold && it.text == "bold" })
        assertTrue(inlines.any { it is MarkdownInline.Italic && it.text == "italic" })
    }

    @Test
    fun multiLineParagraphCollapsesIntoOneBlock() {
        val src = """
            line one
            line two
            line three
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertTrue(block is MarkdownBlock.Paragraph)
        block as MarkdownBlock.Paragraph
        // The three lines should be joined with a single space.
        assertEquals("line one line two line three", block.inlines.single().let { (it as MarkdownInline.Text).text })
    }

    @Test
    fun blankLinesSeparateBlocks() {
        val src = """
            first paragraph

            second paragraph
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun malformedBoldDoesNotCrash() {
        // Unclosed bold marker — should be treated as literal text.
        val inlines = MarkdownParser.parseInline("this **never ends")
        // No Bold span should be emitted; the literal asterisks become
        // part of the surrounding text run.
        assertTrue(
            "no Bold span should be emitted for an unclosed marker",
            inlines.none { it is MarkdownInline.Bold },
        )
    }

    @Test
    fun headingAfterParagraphIsSeparateBlock() {
        val src = """
            intro paragraph

            # Heading
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.Heading)
    }
}