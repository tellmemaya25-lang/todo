package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.TaskDraft
import com.sabihon.todo.domain.repository.AiAssistantRepository
import com.sabihon.todo.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject

/**
 * Smart Quick-Add: free text → [TaskDraft].
 *
 * Responsibilities (the "edge" of the AI pipeline on the device):
 *  - input guardrail (blank / too long) before anything else runs,
 *  - gather the user's categories so both tiers can map "#work" or "groceries",
 *  - build a deterministic [AiContext] (zone + now) so results are reproducible.
 */
class ParseTaskUseCase @Inject constructor(
    private val aiRepository: AiAssistantRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock
) {
    suspend operator fun invoke(text: String): Result<TaskDraft> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Result.Error(IllegalArgumentException("Type a task first"))
        }
        if (trimmed.length > MAX_INPUT_CHARS) {
            return Result.Error(IllegalArgumentException("Keep it under $MAX_INPUT_CHARS characters"))
        }
        val categories = loadCategories()
        val context = AiContext(
            categories = categories,
            zoneId = clock.zone.id,
            nowMillis = clock.millis()
        )
        return aiRepository.parseTask(trimmed, context)
    }

    /** Categories are nice-to-have for parsing; never block the user on them. */
    private suspend fun loadCategories(): List<Category> {
        return try {
            withTimeoutOrNull(CATEGORY_TIMEOUT_MS) {
                val result = categoryRepository.observeCategories().first { it !is Result.Loading }
                (result as? Result.Success)?.data
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val MAX_INPUT_CHARS = 500
        private const val CATEGORY_TIMEOUT_MS = 1_500L
    }
}
