package io.insforge.samples.todo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.insforge.database.database
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.samples.todo.data.CreateTodoRequest
import io.insforge.samples.todo.data.InsforgeManager
import io.insforge.samples.todo.data.Todo
import io.insforge.samples.todo.data.TodoListState
import io.insforge.samples.todo.data.UpdateTodoRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for todo list operations
 */
class TodoViewModel : ViewModel() {

    private val database get() = InsforgeManager.client.database

    private val _state = MutableStateFlow(TodoListState())
    val state: StateFlow<TodoListState> = _state.asStateFlow()

    /**
     * Load todos from the database
     */
    fun loadTodos() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val todos = database
                    .from("todos")
                    .select("*")
                    .order("created_at", ascending = false)
                    .execute<Todo>()

                _state.value = _state.value.copy(
                    todos = todos,
                    isLoading = false
                )
            } catch (e: InsforgeHttpException) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load todos"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Create a new todo
     */
    fun createTodo(title: String, description: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val request = CreateTodoRequest(
                    title = title,
                    description = description
                )
                database
                    .from("todos")
                    .insert(request)
                    .executeSingle<Todo>()

                // Reload todos after creating
                loadTodos()
            } catch (e: InsforgeHttpException) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to create todo"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Toggle todo completion status
     */
    fun toggleTodoCompleted(todo: Todo) {
        viewModelScope.launch {
            try {
                val update = UpdateTodoRequest(completed = !todo.completed)
                database
                    .from("todos")
                    .update(update)
                    .eq("id", todo.id!!)
                    .execute<Todo>()

                // Update local state optimistically
                _state.value = _state.value.copy(
                    todos = _state.value.todos.map {
                        if (it.id == todo.id) it.copy(completed = !it.completed) else it
                    }
                )
            } catch (e: InsforgeHttpException) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Failed to update todo"
                )
                // Reload to get correct state
                loadTodos()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Update a todo
     */
    fun updateTodo(todoId: String, title: String, description: String?) {
        viewModelScope.launch {
            try {
                val update = UpdateTodoRequest(
                    title = title,
                    description = description
                )
                database
                    .from("todos")
                    .update(update)
                    .eq("id", todoId)
                    .execute<Todo>()

                // Reload todos after updating
                loadTodos()
            } catch (e: InsforgeHttpException) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Failed to update todo"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Delete a todo
     */
    fun deleteTodo(todoId: String) {
        viewModelScope.launch {
            try {
                database
                    .from("todos")
                    .delete()
                    .eq("id", todoId)
                    .execute<Todo>()

                // Update local state
                _state.value = _state.value.copy(
                    todos = _state.value.todos.filter { it.id != todoId }
                )
            } catch (e: InsforgeHttpException) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Failed to delete todo"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
