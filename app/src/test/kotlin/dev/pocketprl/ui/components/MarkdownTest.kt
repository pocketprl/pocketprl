package dev.pocketprl.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `a bullet wrapped across source lines stays one block`() {
        val notes = """
            * **Biometric unlock.** Asking for biometrics during
              onboarding now raises the system prompt right there instead of
              a deferred prompt that could arrive late.
        """.trimIndent()
        val blocks = parseMarkdown(notes)
        assertEquals(1, blocks.size)
        val b = blocks.single() as MdBlock.Bullet
        assertEquals(
            "**Biometric unlock.** Asking for biometrics during onboarding now raises the system prompt right there instead of a deferred prompt that could arrive late.",
            b.text,
        )
    }

    @Test
    fun `a numbered item wrapped across source lines stays one block`() {
        val blocks = parseMarkdown("1. first line\n   second line\n   third line")
        val n = blocks.single() as MdBlock.Numbered
        assertEquals(1, n.number)
        assertEquals("first line second line third line", n.text)
    }

    @Test
    fun `a blank line ends the list so the next text is its own paragraph`() {
        val blocks = parseMarkdown("* one\n  wrapped\n\nAfter the list.")
        assertEquals(2, blocks.size)
        assertEquals("one wrapped", (blocks[0] as MdBlock.Bullet).text)
        assertEquals("After the list.", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `separate bullets do not merge and are not damaged by continuation`() {
        val blocks = parseMarkdown("* one\n  one-b\n* two\n* three")
        assertEquals(3, blocks.size)
        assertEquals("one one-b", (blocks[0] as MdBlock.Bullet).text)
        assertEquals("two", (blocks[1] as MdBlock.Bullet).text)
        assertEquals("three", (blocks[2] as MdBlock.Bullet).text)
    }

    @Test
    fun `an absurdly long numbered marker does not throw`() {
        val blocks = parseMarkdown("99999999999999. item")
        assertTrue(blocks.single() is MdBlock.Numbered)
    }
}
