package com.sabihon.todo.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.TaskFilter
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Projects hub – the user's lists with live open/complete counts
 * plus the signed-in user's name for the header avatar.
 */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val taskRepository: TaskRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    init {
        val displayName = auth.currentUser?.displayName
            ?: auth.currentUser?.email?.substringBefore("@")
            ?: "User"
        _uiState.update { it.copy(userName = displayName) }
        observeProjects()
    }

    private fun observeProjects() {
        viewModelScope.launch {
            combine(
                categoryRepository.observeCategories(),
                taskRepository.observeTasks(TaskFilter())
            ) { catResult, taskResult ->
                Pair(catResult, taskResult)
            }.collectLatest { (catResult, taskResult) ->
                if (catResult is Result.Success && taskResult is Result.Success) {
                    val tasks = taskResult.data.filter { !it.isDeleted }
                    val projects = catResult.data.map { cat ->
                        val catTasks = tasks.filter { it.categoryId == cat.id }
                        cat.copy(
                            taskCount = catTasks.size,
                            completedCount = catTasks.count { it.isCompleted }
                        )
                    }
                    _uiState.update { it.copy(isLoading = false, projects = projects, error = null) }
                } else if (catResult is Result.Loading || taskResult is Result.Loading) {
                    _uiState.update { it.copy(isLoading = true) }
                } else if (catResult is Result.Error) {
                    _uiState.update { it.copy(isLoading = false, error = catResult.message) }
                } else if (taskResult is Result.Error) {
                    _uiState.update { it.copy(isLoading = false, error = taskResult.message) }
                }
            }
        }
    }
}
