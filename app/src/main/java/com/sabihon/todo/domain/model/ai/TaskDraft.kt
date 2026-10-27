package com.sabihon.todo.domain.model.ai

import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority

/**
 * Which tier of the "minimum sufficient intelligence" ladder produced a draft.
 * Surfaced in the UI (so the app can explain itself) and in metrics (so we can
 * see how much traffic never leaves the device).
 */
enum class DraftSource { RULES, CACHE, SMALL_MODEL, LARGE_MODEL }

/**
 * A *proposal* for a task, produced by the AI assistant.
 *
 * It is deliberately not a [com.sabihon.todo.domain.model.Task]: the AI never writes
 * to the repository. A draft is shown to the user, who edits/confirms it, and only
 * then does it travel through the normal AddTask/UpdateTask use cases
 * (human in the loop by construction).
 *
 * Null fields mean "the assistant has no opinion" – the UI keeps whatever the user
 * already selected.
 */
data class TaskDraft(
    val title: String,
    val description: String = "",
    val categoryId: String? = null,
    val priority: Priority? = null,
    val dueAt: Long? = null,
    val reminderAt: Long? = null,
    val subTasks: List<String> = emptyList(),
    /** 0f..1f – how sure the producing tier is that the structure is right. */
    val confidence: Float = 0f,
    val source: DraftSource = DraftSource.RULES,
    /** True when a smarter tier was wanted but unavailable (offline, outage, over budget). */
    val isDegraded: Boolean = false,
    /** Short human-readable notes, e.g. "Due: tomorrow 5:00 PM". */
    val explanations: List<String> = emptyList()
) {
    /** True when at least one structured field was extracted. */
    val hasStructure: Boolean
        get() = categoryId != null || priority != null || dueAt != null || subTasks.isNotEmpty()
}

/**
 * Everything the assistant needs to interpret text for *this* user, *now*.
 * Passed explicitly (instead of read from globals) so parsing is deterministic and testable.
 */
data class AiContext(
    val categories: List<Category> = emptyList(),
    /** IANA zone id, e.g. "Asia/Manila". Relative dates ("tomorrow") are resolved in this zone. */
    val zoneId: String,
    val nowMillis: Long
)
