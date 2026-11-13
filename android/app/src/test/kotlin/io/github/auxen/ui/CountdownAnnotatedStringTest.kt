package io.github.auxen.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure function: locates [timeText] inside the already-substituted
 * [countdownAnnotatedString] template and spans just that portion
 * monospace, matching this screen's convention for time-value text
 * elsewhere (position/duration in the seek row) -- final review round,
 * Minor #8. No Robolectric needed: [androidx.compose.ui.text.AnnotatedString]
 * is a plain data type, not Android-runtime-backed.
 */
class CountdownAnnotatedStringTest {
    @Test
    fun `spans just the time portion monospace`() {
        val result = countdownAnnotatedString("Pausing in 14:32", "14:32")
        assertEquals("Pausing in 14:32", result.text)
        val spans = result.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals(11, span.start)
        assertEquals(16, span.end)
        assertEquals("14:32", result.text.substring(span.start, span.end))
    }

    @Test
    fun `spans the time portion wherever it falls in a longer sentence`() {
        val result = countdownAnnotatedString(
            "Pausing in 3:07 — will finish the playing track",
            "3:07",
        )
        val span = result.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("3:07", result.text.substring(span.start, span.end))
    }

    @Test
    fun `applies no span when the time text is not found`() {
        // Defensive: a template that doesn't actually contain timeText
        // (shouldn't happen given how callers build it, but must not crash
        // or mis-span if it ever does).
        val result = countdownAnnotatedString("Pausing after this track", "14:32")
        assertEquals(emptyList<SpanStyle>(), result.spanStyles.map { it.item })
    }
}
