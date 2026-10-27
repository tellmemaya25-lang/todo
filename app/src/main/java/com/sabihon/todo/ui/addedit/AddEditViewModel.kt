package com.sabihon.todo.ui.addedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.SubTask
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.CategoryRepository
import com.sabihon.todo.domain.repository.TaskRepository
import com.sabihon.todo.domain.model.ai.TaskDraft
import com.sabihon.todo.domain.usecase.AddTaskUseCase
import com.sabihon.todo.domain.usecase.DeleteTaskUseCase
import com.sabihon.todo.domain.usecase.ParseTaskUseCase
import com.sabihon.todo.domain.usecase.UpdateTaskUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * ViewModel for Add/Edit Task – with debug info and robust error handling for Firestore.
 */
@HiltViewModel
class AddEditViewModel @Inject constructor(
    private val addTaskUseCase: AddTaskUseCase,
    private val updateTaskUseCase: UpdateTaskUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val parseTaskUseCase: ParseTaskUseCase,
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    init {
        observeCategories()
    }

    private fun observeCategories() {
        viewModelScope.launch {
            try {
                categoryRepository.observeCategories().collectLatest { result ->
                    if (result is Result.Success) {
                        _uiState.update { it.copy(categories = result.data) }
                    } else if (result is Result.Error) {
                        _uiState.update { it.copy(errorMessage = "Categories load failed: ${result.message}") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load categories: ${e.message}") }
                android.util.Log.e("AddEditVM", "observeCategories error", e)
            }
        }
    }

    fun loadTask(taskId: String?) {
        if (taskId == null) {
            _uiState.update { it.copy(isEditMode = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true, taskId = taskId, isEditMode = true) }
        viewModelScope.launch {
            try {
                taskRepository.observeTaskById(taskId).collectLatest { result ->
                    when (result) {
                        is Result.Success -> {
                            val task = result.data
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    title = task.title,
                                    description = task.description,
                                    categoryId = task.categoryId,
                                    priority = task.priority,
                                    dueAt = task.dueAt,
                                    dueDateMillis = task.dueAt,
                                    dueTimeMillis = task.dueAt,
                                    reminderEnabled = task.reminderAt != null,
                                    reminderAt = task.reminderAt,
                                    subTasks = task.subTasks
                                )
                            }
                        }
                        is Result.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                        is Result.Loading -> _uiState.update { it.copy(isLoading = true) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load task: ${e.message}") }
            }
        }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title, titleError = null, smartFillNotes = emptyList(), smartFillDegraded = false) }
    }

    /**
     * Smart fill: parse whatever the user typed in the title ("call mom tomorrow 5pm urgent")
     * into structured fields. The AI only proposes a [TaskDraft]; nothing is saved until the
     * user taps Save, and every field stays editable.
     */
    fun smartFill() {
        val text = _uiState.value.title
        if (text.isBlank()) {
            _uiState.update { it.copy(titleError = "Type a task first") }
            return
        }
        if (_uiState.value.isSmartFilling) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSmartFilling = true, errorMessage = null) }
            when (val result = parseTaskUseCase(text)) {
                is Result.Success -> applyDraft(result.data)
                is Result.Error -> _uiState.update { it.copy(isSmartFilling = false, errorMessage = result.message) }
                is Result.Loading -> _uiState.update { it.copy(isSmartFilling = false) }
            }
        }
    }

    private fun applyDraft(draft: TaskDraft) {
        _uiState.update { current ->
            val newSubTasks = draft.subTasks
                .filter { suggestion -> current.subTasks.none { it.title.equals(suggestion, ignoreCase = true) } }
                .mapIndexed { index, title ->
                    SubTask(id = System.currentTimeMillis().toString() + "_ai" + index, title = title, isDone = false)
                }
            current.copy(
                isSmartFilling = false,
                title = draft.title.ifBlank { current.title },
                description = if (current.description.isBlank()) draft.description else current.description,
                categoryId = draft.categoryId ?: current.categoryId,
                priority = draft.priority ?: current.priority,
                dueAt = draft.dueAt ?: current.dueAt,
                dueDateMillis = draft.dueAt ?: current.dueDateMillis,
                dueTimeMillis = draft.dueAt ?: current.dueTimeMillis,
                reminderEnabled = current.reminderEnabled || draft.reminderAt != null,
                reminderAt = draft.reminderAt ?: current.reminderAt,
                subTasks = current.subTasks + newSubTasks,
                smartFillNotes = draft.explanations,
                smartFillDegraded = draft.isDegraded,
                titleError = null
            )
        }
    }

    fun onDescriptionChange(desc: String) {
        _uiState.update { it.copy(description = desc) }
    }

    fun onCategorySelected(categoryId: String?) {
        _uiState.update { it.copy(categoryId = categoryId) }
    }

    fun onPrioritySelected(priority: Priority) {
        _uiState.update { it.copy(priority = priority) }
    }

    fun onDueDateSelected(millis: Long?) {
        if (millis == null) return
        _uiState.update { current ->
            try {
                val newDueAt = combineDateAndTime(millis, current.dueTimeMillis ?: current.dueAt)
                current.copy(dueDateMillis = millis, dueAt = newDueAt, errorMessage = null)
            } catch (e: Exception) {
                current.copy(errorMessage = "Invalid date: ${e.message}")
            }
        }
    }

    fun onDueTimeSelected(hour: Int, minute: Int) {
        _uiState.update { current ->
            try {
                val baseMillis = current.dueDateMillis ?: current.dueAt ?: System.currentTimeMillis()
                val cal = Calendar.getInstance().apply {
                    timeInMillis = baseMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val timeMillis = cal.timeInMillis
                val combined = combineDateAndTime(current.dueDateMillis ?: System.currentTimeMillis(), timeMillis)
                current.copy(dueTimeMillis = timeMillis, dueAt = combined, dueDateMillis = current.dueDateMillis ?: combined, errorMessage = null)
            } catch (e: Exception) {
                current.copy(errorMessage = "Invalid time: ${e.message}")
            }
        }
    }

    private fun combineDateAndTime(dateMillis: Long?, timeMillis: Long?): Long? {
        if (dateMillis == null && timeMillis == null) return null
        return try {
            val calDate = Calendar.getInstance()
            val calTime = Calendar.getInstance()
            if (dateMillis != null) calDate.timeInMillis = dateMillis
            if (timeMillis != null) calTime.timeInMillis = timeMillis

            val result = Calendar.getInstance()
            if (dateMillis != null) {
                result.set(Calendar.YEAR, calDate.get(Calendar.YEAR))
                result.set(Calendar.MONTH, calDate.get(Calendar.MONTH))
                result.set(Calendar.DAY_OF_MONTH, calDate.get(Calendar.DAY_OF_MONTH))
            }
            if (timeMillis != null) {
                result.set(Calendar.HOUR_OF_DAY, calTime.get(Calendar.HOUR_OF_DAY))
                result.set(Calendar.MINUTE, calTime.get(Calendar.MINUTE))
            } else {
                result.set(Calendar.HOUR_OF_DAY, 9)
                result.set(Calendar.MINUTE, 0)
            }
            result.set(Calendar.SECOND, 0)
            result.set(Calendar.MILLISECOND, 0)
            result.timeInMillis
        } catch (e: Exception) {
            dateMillis ?: timeMillis ?: System.currentTimeMillis()
        }
    }

    fun onReminderToggle(enabled: Boolean) {
        _uiState.update {
            it.copy(
                reminderEnabled = enabled,
                reminderAt = if (enabled) it.dueAt ?: it.dueDateMillis ?: System.currentTimeMillis() + 3600000 else null
            )
        }
    }

    fun onNewSubTaskTitleChange(title: String) {
        _uiState.update { it.copy(newSubTaskTitle = title) }
    }

    fun addSubTask() {
        val title = _uiState.value.newSubTaskTitle.trim()
        if (title.isBlank()) return
        val newSub = SubTask(id = System.currentTimeMillis().toString() + "_" + (0..999).random(), title = title, isDone = false)
        _uiState.update { it.copy(subTasks = it.subTasks + newSub, newSubTaskTitle = "") }
    }

    fun removeSubTask(subTaskId: String) {
        _uiState.update { it.copy(subTasks = it.subTasks.filter { st -> st.id != subTaskId }) }
    }

    fun toggleSubTask(subTaskId: String) {
        _uiState.update { current ->
            val updated = current.subTasks.map { if (it.id == subTaskId) it.copy(isDone = !it.isDone) else it }
            current.copy(subTasks = updated)
        }
    }

    fun editSubTask(subTaskId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        _uiState.update { current ->
            val updated = current.subTasks.map { if (it.id == subTaskId) it.copy(title = newTitle.trim()) else it }
            current.copy(subTasks = updated)
        }
    }

    fun saveTask(onSuccess: () -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.update { it.copy(errorMessage = "Not authenticated! Please login again. uid=null") }
            return
        }
        if (_uiState.value.title.isBlank()) {
            _uiState.update { it.copy(titleError = "Title required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val state = _uiState.value
                android.util.Log.d("AddEditVM", "Saving task: title=${state.title} uid=${currentUser.uid} dueAt=${state.dueAt} cat=${state.categoryId}")

                val task = Task(
                    id = state.taskId ?: "",
                    title = state.title.trim(),
                    description = state.description.trim(),
                    categoryId = state.categoryId,
                    priority = state.priority,
                    dueAt = state.dueAt,
                    reminderAt = if (state.reminderEnabled) state.reminderAt ?: state.dueAt else null,
                    subTasks = state.subTasks,
                    isCompleted = false
                )

                val result = if (state.isEditMode) {
                    updateTaskUseCase(task)
                } else {
                    addTaskUseCase(task).let { res ->
                        when (res) {
                            is Result.Success -> Result.Success(Unit)
                            is Result.Error -> {
                                android.util.Log.e("AddEditVM", "addTaskUseCase failed: ${res.message}", res.exception)
                                res
                            }
                            else -> Result.Error(Exception("Unknown error"))
                        }
                    }
                }

                when (result) {
                    is Result.Success -> {
                        android.util.Log.d("AddEditVM", "Task saved successfully")
                        _uiState.update { it.copy(isSaving = false) }
                        onSuccess()
                    }
                    is Result.Error -> {
                        val msg = result.message ?: "Failed to save task"
                        android.util.Log.e("AddEditVM", "Save failed: $msg", result.exception)
                        // Provide actionable message for common Firestore errors
                        val friendly = when {
                            msg.contains("PERMISSION_DENIED", ignoreCase = true) -> "Firestore permission denied. Check firestore.rules allows users/{uid}. See firestore.rules file in repo."
                            msg.contains("unavailable", ignoreCase = true) -> "Firestore unavailable (offline?). Task will sync when online if persistence enabled."
                            msg.contains("not authenticated", ignoreCase = true) -> "Not authenticated. Please logout and login again."
                            else -> msg
                        }
                        _uiState.update { it.copy(isSaving = false, errorMessage = friendly) }
                    }
                    else -> _uiState.update { it.copy(isSaving = false) }
                }
            } catch (e: Exception) {
                android.util.Log.e("AddEditVM", "saveTask crash prevented", e)
                _uiState.update { it.copy(isSaving = false, errorMessage = "Crash prevented: ${e.message}. Check logcat.") }
            }
        }
    }

    fun deleteTask(onSuccess: () -> Unit) {
        val taskId = _uiState.value.taskId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                when (val result = deleteTaskUseCase(taskId, softDelete = false)) {
                    is Result.Success -> {
                        _uiState.update { it.copy(isSaving = false) }
                        onSuccess()
                    }
                    is Result.Error -> _uiState.update { it.copy(isSaving = false, errorMessage = result.message) }
                    else -> _uiState.update { it.copy(isSaving = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Delete failed: ${e.message}") }
            }
        }
    }

    fun setShowDeleteConfirm(show: Boolean) {
        _uiState.update { it.copy(showDeleteConfirm = show) }
    }
}
