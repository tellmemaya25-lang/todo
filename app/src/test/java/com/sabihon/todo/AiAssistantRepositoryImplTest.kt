package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiAssistantRepositoryImpl
import com.sabihon.todo.data.ai.AiFailure
import com.sabihon.todo.data.ai.AiFeatureFlags
import com.sabihon.todo.data.ai.AiGateway
import com.sabihon.todo.data.ai.AiGatewayException
import com.sabihon.todo.data.ai.CircuitBreaker
import com.sabihon.todo.data.ai.RuleBasedTaskParser
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.DraftSource
import com.sabihon.todo.domain.model.ai.TaskDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Router behaviour: when do we stay on-device, when do we call the gateway, and how do we
 * degrade. No Android or Firebase dependencies – a fake gateway stands in for the function.
 */
class AiAssistantRepositoryImplTest {

    private val zone: ZoneId = ZoneId.of("Asia/Manila")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, zone)
    private val categories = listOf(Category(id = "c-work", name = "Work Related"), Category(id = "c-notes", name = "Personal Notes"))
    private val context = AiContext(categories = categories, zoneId = zone.id, nowMillis = now.toInstant().toEpochMilli())

    /** A sentence Tier 0 cannot structure – forces the remote path. */
    private val fuzzy = "Plan the quarterly offsite agenda and book a venue"

    private class FakeGateway(
        var onParse: (String, AiContext) -> TaskDraft = { text, _ -> TaskDraft(title = text, source = DraftSource.SMALL_MODEL) }
    ) : AiGateway {
        var parseCalls = 0
        override suspend fun parseTask(text: String, context: AiContext): TaskDraft {
            parseCalls++
            return onParse(text, context)
        }
        override suspend fun suggestSubTasks(title: String, description: String): List<String> = emptyList()
    }

    private class Flags(override val aiEnabled: Boolean = true, override val remoteTierEnabled: Boolean = true) : AiFeatureFlags

    private var clockMillis = 0L

    private fun repo(gateway: AiGateway, flags: AiFeatureFlags = Flags(), breaker: CircuitBreaker = CircuitBreaker(nowProvider = { clockMillis })) =
        AiAssistantRepositoryImpl(
            ruleParser = RuleBasedTaskParser(),
            gateway = gateway,
            flags = flags,
            breaker = breaker,
            ioDispatcher = Dispatchers.Unconfined
        )

    @Test
    fun `confident tier-0 result never calls the gateway`() = runTest {
        val gateway = FakeGateway()
        val result = repo(gateway).parseTask("Call mom tomorrow at 5pm urgent", context)
        val draft = (result as Result.Success).data
        assertEquals(0, gateway.parseCalls)
        assertEquals(DraftSource.RULES, draft.source)
        assertEquals(Priority.URGENT, draft.priority)
        assertFalse(draft.isDegraded)
    }

    @Test
    fun `very short input stays local even with no structure`() = runTest {
        val gateway = FakeGateway()
        val result = repo(gateway).parseTask("Buy milk", context)
        assertEquals(0, gateway.parseCalls)
        assertEquals("Buy milk", (result as Result.Success).data.title)
    }

    @Test
    fun `flags off means rules only`() = runTest {
        val gateway = FakeGateway()
        repo(gateway, flags = Flags(remoteTierEnabled = false)).parseTask(fuzzy, context)
        assertEquals(0, gateway.parseCalls)
    }

    @Test
    fun `fuzzy input goes remote, rules win on explicit fields, unknown category dropped`() = runTest {
        val localDue = now.plusDays(1).withHour(17).withMinute(0).toInstant().toEpochMilli()
        val gateway = FakeGateway { _, _ ->
            TaskDraft(
                title = "Plan quarterly offsite agenda",
                categoryId = "not-a-real-category",
                priority = Priority.HIGH,
                dueAt = now.plusDays(10).toInstant().toEpochMilli(),
                subTasks = listOf("Shortlist venues", "Draft agenda", "", "Draft agenda"),
                confidence = 0.9f,
                source = DraftSource.SMALL_MODEL,
                explanations = listOf("Priority: High", "Due: in 10 days")
            )
        }
        // Long + ambiguous ("after …") keeps Tier-0 confidence low, but the explicit
        // "tomorrow 5pm" gives the rules an opinion the model must not override.
        val result = repo(gateway).parseTask("$fuzzy after the budget review tomorrow 5pm", context)
        val draft = (result as Result.Success).data

        assertEquals(1, gateway.parseCalls)
        assertEquals("Plan quarterly offsite agenda", draft.title)   // model cleans the title
        assertEquals(localDue, draft.dueAt)                            // rules win on the explicit date
        assertEquals(Priority.HIGH, draft.priority)                    // model fills the gap
        assertNull(draft.categoryId)                                   // unknown id rejected by client guardrail
        assertEquals(listOf("Shortlist venues", "Draft agenda"), draft.subTasks)
        assertEquals(DraftSource.SMALL_MODEL, draft.source)
        assertFalse(draft.isDegraded)
        assertTrue(draft.explanations.any { it.startsWith("Due: Tomorrow") })
        assertFalse(draft.explanations.contains("Due: in 10 days"))   // rules already explained "Due"
    }

    @Test
    fun `identical request is served from cache without a second gateway call`() = runTest {
        val gateway = FakeGateway()
        val r = repo(gateway)
        r.parseTask(fuzzy, context)
        val second = (r.parseTask(fuzzy, context) as Result.Success).data
        assertEquals(1, gateway.parseCalls)
        assertEquals(DraftSource.CACHE, second.source)
    }

    @Test
    fun `outage degrades to rules and opens the breaker after repeated failures`() = runTest {
        val gateway = FakeGateway { _, _ -> throw AiGatewayException(AiFailure.UNAVAILABLE) }
        val r = repo(gateway)
        repeat(3) { i ->
            val d = (r.parseTask("$fuzzy number $i", context) as Result.Success).data
            assertTrue(d.isDegraded)
            assertEquals(DraftSource.RULES, d.source)
        }
        assertEquals(3, gateway.parseCalls)
        // Breaker is now open – no network, still a usable draft.
        val d = (r.parseTask("$fuzzy number 99", context) as Result.Success).data
        assertEquals(3, gateway.parseCalls)
        assertTrue(d.isDegraded)
        assertTrue(d.explanations.any { it.contains("resting") })
    }

    @Test
    fun `rate limiting degrades without tripping the breaker`() = runTest {
        var calls = 0
        val gateway = FakeGateway { _, _ -> calls++; throw AiGatewayException(AiFailure.RATE_LIMITED) }
        val r = repo(gateway)
        repeat(4) { i -> r.parseTask("$fuzzy number $i", context) }
        assertEquals(4, calls) // every call still probes – limits are transient, not an outage
        val d = (r.parseTask("$fuzzy number 5", context) as Result.Success).data
        assertTrue(d.isDegraded)
        assertTrue(d.explanations.any { it.contains("going fast") })
    }

    @Test
    fun `sanitize rejects absurd dates and clamps lengths`() = runTest {
        val r = repo(FakeGateway())
        val wild = TaskDraft(
            title = "  a".repeat(100),
            dueAt = now.plusYears(5).toInstant().toEpochMilli(),
            reminderAt = now.minusYears(3).toInstant().toEpochMilli(),
            subTasks = (1..20).map { "step $it" },
            confidence = 7f
        )
        val clean = r.sanitize(wild, context)
        assertNull(clean.dueAt)
        assertNull(clean.reminderAt)
        assertTrue(clean.title.length <= 120)
        assertEquals(8, clean.subTasks.size)
        assertEquals(1f, clean.confidence)
    }

    @Test
    fun `blank text is an error, nothing else ever is`() = runTest {
        val r = repo(FakeGateway { _, _ -> throw IllegalStateException("boom") })
        assertTrue(r.parseTask("   ", context) is Result.Error)
        assertTrue(r.parseTask(fuzzy, context) is Result.Success)
    }
}
