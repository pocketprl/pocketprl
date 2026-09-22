package dev.pocketprl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.pocketprl.ui.theme.Mono

/**
 * A small block-level Markdown renderer for release notes. It covers what GitHub
 * release bodies actually use: headings, bullets, numbered lists, block quotes,
 * horizontal rules, fenced code, and inline bold / italic / code / links. It is
 * deliberately not a general parser.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.text, color),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.notes(),
                    color = color,
                    modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )

                is MdBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•", color = color, modifier = Modifier.width(18.dp))
                    Text(inlineAnnotated(block.text, color), style = MaterialTheme.typography.bodyMedium.notes(), color = color, modifier = Modifier.weight(1f))
                }

                is MdBlock.Numbered -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${block.number}.", color = color, modifier = Modifier.width(28.dp))
                    Text(inlineAnnotated(block.text, color), style = MaterialTheme.typography.bodyMedium.notes(), color = color, modifier = Modifier.weight(1f))
                }

                is MdBlock.Quote -> Text(
                    inlineAnnotated(block.text, color),
                    style = MaterialTheme.typography.bodyMedium.notes(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                )

                is MdBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = color,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                )

                MdBlock.Rule -> Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

                is MdBlock.Paragraph -> Text(inlineAnnotated(block.text, color), style = MaterialTheme.typography.bodyMedium.notes(), color = color)
            }
        }
    }
}

/**
 * Release notes read as prose. Soft hyphenation lets a long word break with a
 * hyphen and continue on the next line instead of jumping down whole and leaving
 * a ragged, misaligned line behind it.
 */
private fun TextStyle.notes(): TextStyle = copy(hyphens = Hyphens.Auto)

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val number: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    object Rule : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val RULE = Regex("""^\s*([-*_])\1{2,}\s*$""")

private fun parseMarkdown(markdown: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    var fenced = false
    val code = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) out.add(MdBlock.Paragraph(paragraph.toString().trim()))
        paragraph.clear()
    }

    for (raw in markdown.replace("\r\n", "\n").split('\n')) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (fenced) { out.add(MdBlock.Code(code.toString().trimEnd())); code.clear(); fenced = false }
            else { flushParagraph(); fenced = true }
            continue
        }
        if (fenced) { code.append(line).append('\n'); continue }
        when {
            line.isBlank() -> flushParagraph()
            RULE.matches(line) -> { flushParagraph(); out.add(MdBlock.Rule) }
            HEADING.matches(line) -> { flushParagraph(); val m = HEADING.find(line)!!; out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())) }
            BULLET.matches(line) -> { flushParagraph(); out.add(MdBlock.Bullet(BULLET.find(line)!!.groupValues[1].trim())) }
            NUMBERED.matches(line) -> { flushParagraph(); val m = NUMBERED.find(line)!!; out.add(MdBlock.Numbered(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].trim())) }
            QUOTE.matches(line) -> { flushParagraph(); out.add(MdBlock.Quote(QUOTE.find(line)!!.groupValues[1].trim())) }
            else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(line.trim()) }
        }
    }
    if (fenced && code.isNotEmpty()) out.add(MdBlock.Code(code.toString().trimEnd()))
    flushParagraph()
    return out
}

private val INLINE = Regex("""(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(`([^`]+)`)|(\[([^\]]+)]\(([^)\s]+)\))""")

/** Applies bold / italic / code / link styling to one line of text. */
private fun inlineAnnotated(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > index) append(text.substring(index, m.range.first))
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = Mono)) { append(g[6]) }
            g[7].isNotEmpty() -> withLink(LinkAnnotation.Url(g[9])) {
                withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline)) { append(g[8]) }
            }
            else -> append(m.value)
        }
        index = m.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}
