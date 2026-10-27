package com.sabihon.todo.domain.repository

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.TaskDraft

/**
 * AI assistant contract – never exposes network/Firebase types.
 *
 * Implementations are expected to follow the tiered strategy documented in
 * docs/ai/02-daymark-ai-system-design.md: deterministic rules first, remote
 * models only when needed, and graceful degradation on any failure.
 */
interface AiAssistantRepository {

    /**
     * Turn free text ("call mom tomorrow at 5pm, urgent") into a structured [TaskDraft].
     * Always returns a usable draft when the text is non-blank – at worst the rules-only
     * result flagged with [TaskDraft.isDegraded].
     */
    suspend fun parseTask(text: String, context: AiContext): Result<TaskDraft>

    /**
     * Suggest up to 8 short sub-task titles for a task. Remote-only; returns an
     * empty list when the assistant is unavailable.
     */
    suspend fun suggestSubTasks(title: String, description: String): Result<List<String>>
}
