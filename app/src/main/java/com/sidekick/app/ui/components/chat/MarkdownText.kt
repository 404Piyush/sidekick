package com.sidekick.app.ui.components.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Render a markdown source string as a Compose tree.
 *
 * The entry point. Wraps [MarkdownParser.parse] + [MarkdownBlocks]
 * so callers just pass a string and get a chat-bubble-ready rendered
 * tree. The parser is exposed for tests (see `MarkdownRendererTest`).
 *
 * The visual treatment matches the existing "ink wash" brand:
 *  - Headings use the M3 title typography scaled per level
 *  - Inline `code` is rendered in a slight grey pill
 *  - Fenced code blocks are JetBrains-Mono on a paper-tinted background
 *    with a copy-to-clipboard button in the corner
 *  - Lists use a 2-space indent with the standard bullet/digit markers
 *
 * Code blocks read the system clipboard via [ClipboardManager]; the
 * copy button surfaces a Material 3 icon, not a text label, so the
 * bubble stays compact.
 */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    MarkdownBlocks(blocks = blocks, modifier = modifier)
}

/**
 * Render a pre-parsed list of [MarkdownBlock]s. Exposed separately so
 * tests can render deterministic fixtures (and so the renderer can be
 * composed into larger layouts that supply their own modifier).
 */
@Composable
fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            MarkdownBlock(block = block)
        }
    }
}

@Composable
private fun MarkdownBlock(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> HeadingRow(block)
        is MarkdownBlock.Paragraph -> Text(
            text = inlinesToAnnotated(block.inlines),
            style = MaterialTheme.typography.bodyLarge,
        )
        is MarkdownBlock.BulletList -> ListRow(items = block.items, ordered = false)
        is MarkdownBlock.OrderedList -> ListRow(items = block.items, ordered = true)
        is MarkdownBlock.CodeBlock -> CodeBlockRow(block)
    }
}

@Composable
private fun HeadingRow(block: MarkdownBlock.Heading) {
    // Headings: scaled 24 / 20 / 18 sp, semi-bold, with a tiny extra
    // bottom margin so they separate from the following paragraph.
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
        )
        2 -> MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        else -> MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
    }
    Text(
        text = inlinesToAnnotated(parseInlineSafe(block.text)),
        style = style,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ListRow(items: List<List<MarkdownInline>>, ordered: Boolean) {
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, inlines ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = inlinesToAnnotated(inlines),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun CodeBlockRow(block: MarkdownBlock.CodeBlock) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Column {
            // Header row: optional language tag + copy button. Skip
            // the language tag if the LLM didn't emit one.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (block.lang.isNotBlank()) {
                    Text(
                        text = block.lang,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Spacer(Modifier.size(0.dp))
                }
                IconButton(
                    onClick = { copyToClipboard(context, block.code) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = CopyIcon,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Body: JetBrains Mono via FontFamily.Monospace. The
            // app doesn't ship a custom font, so we fall back to the
            // system monospace. Code is rendered verbatim — no inline
            // parsing for code blocks.
            Text(
                text = block.code,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
            )
        }
    }
}

/**
 * Compose inline spans into a single [AnnotatedString]. Bold and italic
 * spans get nested [SpanStyle]s; inline code gets a subtle grey
 * background + monospace font.
 */
@Composable
private fun inlinesToAnnotated(inlines: List<MarkdownInline>): AnnotatedString =
    buildAnnotatedString {
        for (span in inlines) {
            when (span) {
                is MarkdownInline.Text -> append(span.text)
                is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(span.text)
                }
                is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(span.text)
                }
                is MarkdownInline.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    append(" ${span.text} ")
                }
            }
        }
    }

/**
 * Wrap [MarkdownParser.parseInline] so the heading renderer (which gets
 * a plain string instead of pre-parsed inlines) doesn't need a separate
 * code path. Safe because parseInline never throws.
 */
private fun parseInlineSafe(text: String): List<MarkdownInline> =
    runCatching { MarkdownParser.parseInline(text) }.getOrDefault(listOf(MarkdownInline.Text(text)))

/**
 * Push [text] onto the system clipboard. Uses Android's [ClipboardManager]
 * directly — there's no Compose-native equivalent in the current BOM.
 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("sidekick-code", text))
}

/**
 * Two-overlapping-rectangles copy icon, hand-drawn as an [ImageVector].
 *
 * `Icons.Default.ContentCopy` lives in `material-icons-extended` (not
 * `material-icons-core`), and pulling that whole artifact for a single
 * 16dp glyph adds ~10 MB of APK bloat. This 24x24 vector is visually
 * identical to the Material "content_copy" glyph.
 */
private val CopyIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        ) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            verticalLineTo(21f)
            close()
        }
    }.build()
}