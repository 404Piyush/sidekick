package com.sidekick.app.ui.components.chat

/**
 * Hand-rolled markdown parser. Built from scratch because the spec
 * (`compose-richtext`, `https://github.com/halilozercan/compose-richtext`)
 * is unmaintained for Compose 1.5+ — its `MarkdownText` widget
 * references Compose internals that have moved APIs.
 *
 * Scope is intentionally narrow: this is a chat renderer, not a
 * full CommonMark implementation. We handle the subset the LLM
 * actually produces:
 *
 *  - ATX-style headings (`#`, `##`, `###`)
 *  - Bullet lists (`- item` / `* item`)
 *  - Ordered lists (`1. item`)
 *  - Bold (`**text**` or `__text__`)
 *  - Italic (`*text*` or `_text_`)
 *  - Inline code (`` `code` ``)
 *  - Fenced code blocks (```` ```lang ```` ... ```` ``` ````)
 *  - Plain paragraphs
 *
 * What we DON'T support (intentionally):
 *  - Reference-style links, image links — the chat transcript has no
 *    need for them; the LLM would just emit URLs as inline text
 *  - Tables — out of scope for the demo
 *  - Nested lists — flat list rendering is enough
 *  - HTML blocks — LLMs rarely emit them and stripping them is safer
 *    than rendering them
 *
 * The parser is a two-pass operation:
 *  1. Split the input into block-level chunks (paragraph, code block,
 *     heading, bullet list, ordered list).
 *  2. For each text block, parse the inline spans (bold, italic,
 *     inline code).
 *
 * Both passes live in this file so the renderer can render them
 * directly without intermediate state.
 */
internal object MarkdownParser {

    /**
     * Parse [source] into a list of [MarkdownBlock]s. Each block is a
     * self-contained rendering unit the renderer iterates over.
     */
    fun parse(source: String): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        val lines = source.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block: starts with ``` and runs until the next ```
            if (line.trimStart().startsWith("```")) {
                val fenceMarker = line.trimStart().takeWhile { it == '`' }
                require(fenceMarker.length >= 3) { "fence must be at least 3 backticks" }
                val lang = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fenceMarker)) {
                    codeLines.add(lines[i])
                    i++
                }
                // Skip the closing fence if present.
                if (i < lines.size) i++
                out.add(MarkdownBlock.CodeBlock(lang = lang, code = codeLines.joinToString("\n")))
                continue
            }

            // Heading: # / ## / ### (we don't render #### and deeper as
            // distinct styles — collapse them into `###`).
            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (headingMatch != null) {
                val hashes = headingMatch.groupValues[1]
                val text = headingMatch.groupValues[2].trim()
                val level = hashes.length.coerceIn(1, 3)
                out.add(MarkdownBlock.Heading(level = level, text = text))
                i++
                continue
            }

            // Bullet list: a sequence of "- item" or "* item" lines.
            if (BULLET_REGEX.matches(line.trimStart()) && line.trimStart().startsWith("-")) {
                val items = mutableListOf<List<MarkdownInline>>()
                while (i < lines.size && BULLET_REGEX.matches(lines[i].trimStart())) {
                    val text = BULLET_REGEX.matchEntire(lines[i].trimStart())!!.groupValues[1]
                    items.add(parseInline(text))
                    i++
                }
                out.add(MarkdownBlock.BulletList(items))
                continue
            }
            // (Same shape for ordered lists — number-prefixed.)
            if (ORDERED_REGEX.matches(line.trimStart())) {
                val items = mutableListOf<List<MarkdownInline>>()
                while (i < lines.size && ORDERED_REGEX.matches(lines[i].trimStart())) {
                    val text = ORDERED_REGEX.matchEntire(lines[i].trimStart())!!.groupValues[1]
                    items.add(parseInline(text))
                    i++
                }
                out.add(MarkdownBlock.OrderedList(items))
                continue
            }

            // Blank line: paragraph separator. Skip it.
            if (line.isBlank()) {
                i++
                continue
            }

            // Paragraph: collect consecutive non-blank, non-block-prefixed lines.
            val paragraphLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("```") &&
                !HEADING_REGEX.matches(lines[i]) &&
                !BULLET_REGEX.matches(lines[i].trimStart()) &&
                !ORDERED_REGEX.matches(lines[i].trimStart())
            ) {
                paragraphLines.add(lines[i])
                i++
            }
            if (paragraphLines.isNotEmpty()) {
                out.add(MarkdownBlock.Paragraph(parseInline(paragraphLines.joinToString(" "))))
            }
        }
        return out
    }

    /**
     * Parse inline spans out of [text]. The LLM's typical inline usage is
     * bold + italic + inline code; we don't try to handle nested bold-inside-italic
     * — the parser walks left-to-right and emits spans in order.
     *
     * The implementation is a small recursive-descent walker:
     *  - `` ` `` starts an inline-code run that ends at the next backtick
     *  - `**` or `__` starts a bold run that ends at the matching marker
     *  - `*` or `_` starts an italic run (but only if not preceded/followed
     *    by a word char, so we don't trip over snake_case identifiers)
     *  - everything else is plain text
     */
    fun parseInline(text: String): List<MarkdownInline> {
        val out = mutableListOf<MarkdownInline>()
        var i = 0
        val buf = StringBuilder()
        fun flushText() {
            if (buf.isNotEmpty()) {
                out.add(MarkdownInline.Text(buf.toString()))
                buf.clear()
            }
        }
        while (i < text.length) {
            val c = text[i]
            // Inline code: read until the matching backtick.
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushText()
                    out.add(MarkdownInline.Code(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }
            // Bold: ** or __
            if (i + 1 < text.length && ((c == '*' && text[i + 1] == '*') || (c == '_' && text[i + 1] == '_'))) {
                val marker = "${c}$c"
                val end = text.indexOf(marker, i + 2)
                if (end > i + 1) {
                    flushText()
                    out.add(MarkdownInline.Bold(text.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }
            // Italic: single * or _ (with boundary check).
            if (c == '*' || c == '_') {
                val prevChar = if (i > 0) text[i - 1] else ' '
                val nextChar = if (i + 1 < text.length) text[i + 1] else ' '
                val isWordBoundary = !prevChar.isLetterOrDigit() && !nextChar.isLetterOrDigit()
                if (isWordBoundary) {
                    val end = text.indexOf(c, i + 1)
                    if (end > i) {
                        flushText()
                        out.add(MarkdownInline.Italic(text.substring(i + 1, end)))
                        i = end + 1
                        continue
                    }
                }
            }
            buf.append(c)
            i++
        }
        flushText()
        return out
    }

    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET_REGEX = Regex("^- (.*)$")
    private val ORDERED_REGEX = Regex("^\\d+\\. (.*)$")
}

/**
 * One block in a parsed markdown document. Sealed so the renderer's
 * `when` is exhaustive.
 */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class BulletList(val items: List<List<MarkdownInline>>) : MarkdownBlock()
    data class OrderedList(val items: List<List<MarkdownInline>>) : MarkdownBlock()
    data class CodeBlock(val lang: String, val code: String) : MarkdownBlock()
}

/**
 * One inline span inside a paragraph or list item.
 */
sealed class MarkdownInline {
    data class Text(val text: String) : MarkdownInline()
    data class Bold(val text: String) : MarkdownInline()
    data class Italic(val text: String) : MarkdownInline()
    data class Code(val text: String) : MarkdownInline()
}