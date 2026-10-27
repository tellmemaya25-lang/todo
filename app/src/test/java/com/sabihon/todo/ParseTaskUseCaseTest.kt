package com.sabihon.todo

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.TaskDraft
import com.sabihon.todo.domain.repository.AiAssistantRepository
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.usecase.ParseTaskUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ParseTaskUseCaseTest {

    private val aiRepository = mockk<AiAssistantRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"), ZoneId.of("Asia/Manila"))
    private val useCase = ParseTaskUseCase(aiRepository, categoryRepository, clock)

    @Test
    fun `blank input is rejected before anything runs`() = runTest {
        val result = useCase("   ")
        assertTrue(result is Result.Error)
    }

    @Test
    fun `over-long input is rejected by the client guardrail`() = runTest {
        val result = useCase("x".repeat(ParseTaskUseCase.MAX_INPUT_CHARS + 1))
        assertTrue(result is Result.Error)
    }

    @Test
    fun `categories, zone and now are passed to the repository`() = runTest {
        val cats = listOf(Category(id = "c1", name = "Work Related"))
        every { categoryRepository.observeCategories() } returns flowOf(Result.Loading, Result.Success(cats))
        val ctx = slot<AiContext>()
        coEvery { aiRepository.parseTask(any(), capture(ctx)) } returns Result.Success(TaskDraft(title = "ok"))

        val result = useCase("  finish slides for work related  ")

        assertTrue(result is Result.Success)
        assertEquals(cats, ctx.captured.categories)
        assertEquals("Asia/Manila", ctx.captured.zoneId)
        assertEquals(clock.millis(), ctx.captured.nowMillis)
    }

    @Test
    fun `category failure does not block parsing`() = runTest {
        every { categoryRepository.observeCategories() } returns flow { throw IllegalStateException("offline") }
        val ctx = slot<AiContext>()
        coEvery { aiRepository.parseTask(any(), capture(ctx)) } returns Result.Success(TaskDraft(title = "ok"))

        val result = useCase("call mom tomorrow")

        assertTrue(result is Result.Success)
        assertTrue(ctx.captured.categories.isEmpty())
    }
}
