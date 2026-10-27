package com.sabihon.todo

import com.sabihon.todo.data.ai.AiConfig
import com.sabihon.todo.data.ai.RuleBasedTaskParser
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.DraftSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Golden set for Tier 0. These double as the "functional evaluations" the video asks for:
 * if a change in the parser breaks one of these phrases, CI tells us before a user does.
 *
 * Clock is frozen at Tuesday 27 Oct 2026, 10:00 in Asia/Manila.
 */
class RuleBasedTaskParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Manila")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, zone)
    private val parser = RuleBasedTaskParser(monthFirstNumericDates = true)

    private val categories = listOf(
        Category(id = "c-grocery", name = "Grocery"),
        Category(id = "c-edu", name = "Educational"),
        Category(id = "c-home", name = "Home Related"),
        Category(id = "c-work", name = "Work Related"),
        Category(id = "c-notes", name = "Personal Notes")
    )

    private fun ctx(withCategories: Boolean = true) = AiContext(
        categories = if (withCategories) categories else emptyList(),
        zoneId = zone.id,
        nowMillis = now.toInstant().toEpochMilli()
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `tomorrow at 5pm urgent - full structure, confident, no network needed`() {
        val d = parser.parse("Call mom tomorrow at 5pm urgent", ctx())
        assertEquals("Call mom", d.title)
        assertEquals(Priority.URGENT, d.priority)
        assertEquals(at(2026, 10, 28, 17), d.dueAt)
        assertEquals(DraftSource.RULES, d.source)
        assertTrue("confidence=${d.confidence}", d.confidence >= AiConfig.CONFIDENT_THRESHOLD)
        assertTrue(d.explanations.contains("Due: Tomorrow 5:00 PM"))
        assertTrue(d.explanations.contains("Priority: Urgent"))
    }

    @Test
    fun `by Friday 5pm resolves to the coming Friday`() {
        val d = parser.parse("Submit report by Friday 5pm", ctx())
        assertEquals("Submit report", d.title)
        assertEquals(at(2026, 10, 30, 17), d.dueAt)
    }

    @Test
    fun `next tue means Tuesday of next week`() {
        val d = parser.parse("Dentist next tue", ctx())
        assertEquals("Dentist", d.title)
        assertEquals(at(2026, 11, 3, 9), d.dueAt) // default 09:00
    }

    @Test
    fun `hashtag category is applied and removed from title`() {
        val d = parser.parse("buy rice #grocery", ctx())
        assertEquals("Buy rice", d.title)
        assertEquals("c-grocery", d.categoryId)
        assertTrue(d.explanations.contains("Category: Grocery"))
    }

    @Test
    fun `explicit category with preposition is removed, keywords are not`() {
        val explicit = parser.parse("Finish slides for work related !!", ctx())
        assertEquals("Finish slides", explicit.title)
        assertEquals("c-work", explicit.categoryId)
        assertEquals(Priority.URGENT, explicit.priority)

        val keyword = parser.parse("buy milk and eggs", ctx())
        assertEquals("Buy milk and eggs", keyword.title)
        assertEquals("c-grocery", keyword.categoryId)
    }

    @Test
    fun `tonight implies 8pm today and filler is stripped`() {
        val d = parser.parse("Remind me to water the plants tonight", ctx())
        assertEquals("Water the plants", d.title)
        assertEquals(at(2026, 10, 27, 20), d.dueAt)
    }

    @Test
    fun `in 2 hours is relative to now`() {
        val d = parser.parse("call back in 2 hours", ctx())
        assertEquals("Call back", d.title)
        assertEquals(now.plusHours(2).toInstant().toEpochMilli(), d.dueAt)
    }

    @Test
    fun `bare time without am pm assumes afternoon and today`() {
        val d = parser.parse("meeting at 3:30 with client", ctx())
        assertEquals("Meeting with client", d.title)
        assertEquals(at(2026, 10, 27, 15, 30), d.dueAt)
        assertEquals("c-work", d.categoryId) // "meeting", "client" keywords
    }

    @Test
    fun `time already passed today rolls to tomorrow`() {
        val d = parser.parse("Take vitamins at 8am", ctx())
        assertEquals(at(2026, 10, 28, 8), d.dueAt)
    }

    @Test
    fun `month-name date without year rolls forward when in the past`() {
        val future = parser.parse("Pay electricity bill before Oct 30", ctx())
        assertEquals("Pay electricity bill", future.title)
        assertEquals(at(2026, 10, 30, 9), future.dueAt)

        val past = parser.parse("Renew license Jan 5", ctx())
        assertEquals(at(2027, 1, 5, 9), past.dueAt)
    }

    @Test
    fun `numeric date is month first and ranges are not dates`() {
        val date = parser.parse("tax filing 10/30", ctx())
        assertEquals(at(2026, 10, 30, 9), date.dueAt)

        val range = parser.parse("Read pages 10-15", ctx())
        assertNull(range.dueAt)
        assertEquals("Read pages 10-15", range.title)
    }

    @Test
    fun `tomorrow morning uses the morning word time`() {
        val d = parser.parse("Gym tomorrow morning", ctx())
        assertEquals("Gym", d.title)
        assertEquals(at(2026, 10, 28, 9), d.dueAt)
    }

    @Test
    fun `end of week and weekend`() {
        assertEquals(at(2026, 10, 30, 17), parser.parse("send invoice by end of week", ctx()).dueAt)
        assertEquals(at(2026, 10, 31, 10), parser.parse("clean garage this weekend", ctx()).dueAt)
    }

    @Test
    fun `fuzzy sentence has no structure and low confidence so the model is consulted`() {
        val d = parser.parse("Plan Mom's birthday party and book a venue", ctx())
        assertNull(d.dueAt)
        assertNull(d.priority)
        assertEquals("c-notes", d.categoryId) // "birthday" keyword → Personal Notes
        assertTrue("confidence=${d.confidence}", d.confidence < AiConfig.CONFIDENT_THRESHOLD)
        assertFalse(d.isDegraded)
    }

    @Test
    fun `plain short title is returned untouched`() {
        val d = parser.parse("Buy milk", ctx(withCategories = false))
        assertEquals("Buy milk", d.title)
        assertNull(d.dueAt)
        assertNull(d.categoryId)
        assertTrue(d.explanations.isEmpty())
    }

    @Test
    fun `blank input yields empty draft`() {
        val d = parser.parse("   ", ctx())
        assertEquals("", d.title)
        assertEquals(0f, d.confidence)
    }

    @Test
    fun `invalid zone id falls back instead of crashing`() {
        val d = parser.parse("Call mom tomorrow", ctx().copy(zoneId = "Not/AZone"))
        assertEquals("Call mom", d.title)
        assertTrue(d.dueAt != null)
    }
}
