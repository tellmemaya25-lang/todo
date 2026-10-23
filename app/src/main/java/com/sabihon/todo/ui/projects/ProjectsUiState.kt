package com.sabihon.todo.ui.projects

import com.sabihon.todo.domain.model.Category

data class ProjectsUiState(
    val isLoading: Boolean = true,
    val projects: List<Category> = emptyList(),
    val userName: String = "User",
    val error: String? = null
) {
    /** Two-letter monogram for the header avatar, e.g. "Maya" -> "MA". */
    val userInitials: String
        get() {
            val parts = userName.trim().split(" ").filter { it.isNotEmpty() }
            return when {
                parts.size >= 2 -> (parts[0].take(1) + parts[1].take(1)).uppercase()
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> "DM"
            }
        }
}
