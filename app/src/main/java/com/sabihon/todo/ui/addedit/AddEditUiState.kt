package com.sabihon.todo.ui.addedit

import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SubTask

/**
 * UiState for Add/Edit Task.
 */
data class AddEditUiState(
    val taskId: String? = null,
    val title: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val dueDateMillis: Long? = null,
    val dueTimeMillis: Long? = null,
    val dueAt: Long? = null,
    val reminderEnabled: Boolean = false,
    val reminderAt: Long? = null,
    val subTasks: List<SubTask> = emptyList(),
    val newSubTaskTitle: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val titleError: String? = null,
    val categories: List<Category> = emptyList(),
    val isEditMode: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    /** Smart fill (AI quick-add) – true while the title is being parsed. */
    val isSmartFilling: Boolean = false,
    /** Short notes shown under the title after Smart fill, e.g. "Due: Tomorrow 5:00 PM". */
    val smartFillNotes: List<String> = emptyList(),
    /** True when the result came from on-device rules because the AI tier was unavailable. */
    val smartFillDegraded: Boolean = false
)
