package dev.insforge.samples.todo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Todo item model matching the database schema
 */
@Serializable
data class Todo(
    val id: String? = null,
    val title: String,
    val description: String? = null,
    @SerialName("is_completed")
    val completed: Boolean? = false,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * Request model for creating a new todo
 */
@Serializable
data class CreateTodoRequest(
    val title: String,
    val description: String? = null,
    @SerialName("is_completed")
    val completed: Boolean = false,
    @SerialName("user_id")
    val userId: String
)

/**
 * Request model for updating a todo
 */
@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val description: String? = null,
    @SerialName("is_completed")
    val completed: Boolean? = null
)

/**
 * UI state for Todo list screen
 */
data class TodoListState(
    val todos: List<Todo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * UI state for Authentication
 */
data class AuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = true,
    val userEmail: String? = null,
    val userName: String? = null,
    val error: String? = null
)
