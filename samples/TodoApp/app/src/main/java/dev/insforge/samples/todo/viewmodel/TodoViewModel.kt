package dev.insforge.samples.todo.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.insforge.auth.auth
import dev.insforge.database.database
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.realtime.InsforgeChannel
import dev.insforge.realtime.PostgresAction
import dev.insforge.realtime.decodeOldRecord
import dev.insforge.realtime.decodeRecord
import dev.insforge.realtime.postgresChangeFlow
import dev.insforge.realtime.realtime
import dev.insforge.samples.todo.data.CreateTodoRequest
import dev.insforge.samples.todo.data.InsforgeManager
import dev.insforge.samples.todo.data.Todo
import dev.insforge.samples.todo.data.TodoListState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * ViewModel for todo list operations
 */
class TodoViewModel : ViewModel() {

    private val database get() = InsforgeManager.client.database
    private val realtime get() = InsforgeManager.client.realtime
    private val auth get() = InsforgeManager.client.auth

    private val _state = MutableStateFlow(TodoListState())
    val state: StateFlow<TodoListState> = _state.asStateFlow()

    // Realtime channel for listening to todo changes
    private var realtimeChannel: InsforgeChannel? = null
    private var isRealtimeSyncing = false

    companion object {
        private const val TAG = "TodoViewModel"
        private const val CHANNEL_NAME = "todos"
    }

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
                // Get current user ID from auth
                val currentUser = InsforgeManager.client.auth.currentUser.value
                    ?: throw IllegalStateException("User must be authenticated to create todos")

                val request = CreateTodoRequest(
                    title = title,
                    description = description,
                    userId = currentUser.id
                )
                database
                    .from("todos")
                    .insertTyped(listOf(request))
                    .execute<Todo>()

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
                val newCompletedValue = !(todo.completed ?: false)
                val updateData = buildJsonObject {
                    put("is_completed", newCompletedValue)
                }
                database
                    .from("todos")
                    .eq("id", todo.id!!)
                    .update(updateData)
                    .execute<Todo>()

                // Update local state optimistically
                _state.value = _state.value.copy(
                    todos = _state.value.todos.map {
                        if (it.id == todo.id) it.copy(completed = newCompletedValue) else it
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
                val updateData = buildJsonObject {
                    put("title", title)
                    description?.let { put("description", it) }
                }
                database
                    .from("todos")
                    .eq("id", todoId)
                    .update(updateData)
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
                    .eq("id", todoId)
                    .delete()
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

    /**
     * Start real-time synchronization for todos
     * This will listen to INSERT, UPDATE, and DELETE events on the todos table
     * Only todos belonging to the current user will be synced
     */
    fun startRealtimeSync() {
        if (isRealtimeSyncing) {
            Log.d(TAG, "Realtime sync already started")
            return
        }

        viewModelScope.launch {
            try {
                // Get current user ID for filtering
                val currentUser = auth.currentUser.value
                if (currentUser == null) {
                    Log.e(TAG, "Cannot start realtime sync: user not authenticated")
                    return@launch
                }

                val currentUserId = currentUser.id
                Log.d(TAG, "Starting realtime sync for user: $currentUserId")

                // Connect to realtime server
                realtime.connect()

                // Create channel for todos
                val channel = realtime.channel(CHANNEL_NAME)

                // IMPORTANT: Define flows but DON'T launch them yet
                val insertFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "todos"
                    filter = "user_id=eq.$currentUserId"
                }.onEach { insert ->
                    try {
                        val newTodo = insert.decodeRecord<Todo>()
                        Log.d(TAG, "Realtime INSERT: ${newTodo.title}")

                        // Add new todo to the list
                        _state.value = _state.value.copy(
                            todos = listOf(newTodo) + _state.value.todos
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling INSERT: ${e.message}", e)
                    }
                }

                val updateFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "todos"
                    filter = "user_id=eq.$currentUserId"
                }.onEach { update ->
                    try {
                        val updatedTodo = update.decodeRecord<Todo>()
                        Log.d(TAG, "Realtime UPDATE: ${updatedTodo.title}")

                        // Update the todo in the list
                        _state.value = _state.value.copy(
                            todos = _state.value.todos.map {
                                if (it.id == updatedTodo.id) updatedTodo else it
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling UPDATE: ${e.message}", e)
                    }
                }

                val deleteFlow = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
                    table = "todos"
                    filter = "user_id=eq.$currentUserId"
                }.onEach { delete ->
                    try {
                        val deletedTodo = delete.decodeOldRecord<Todo>()
                        Log.d(TAG, "Realtime DELETE: ${deletedTodo.title}")

                        // Remove the todo from the list
                        _state.value = _state.value.copy(
                            todos = _state.value.todos.filter { it.id != deletedTodo.id }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling DELETE: ${e.message}", e)
                    }
                }

                // Subscribe to the channel BEFORE launching flows
                Log.d(TAG, "Subscribing to channel: $CHANNEL_NAME with filter user_id=eq.$currentUserId")
                channel.subscribe(blockUntilSubscribed = true)
                Log.d(TAG, "Channel subscription completed")

                // NOW launch all flows AFTER subscription is complete
                insertFlow.launchIn(viewModelScope)
                updateFlow.launchIn(viewModelScope)
                deleteFlow.launchIn(viewModelScope)
                Log.d(TAG, "All flows launched and listening for events")

                // Store the channel reference
                realtimeChannel = channel

                isRealtimeSyncing = true
                Log.d(TAG, "Realtime sync started successfully for user: $currentUserId")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting realtime sync: ${e.message}", e)
                _state.value = _state.value.copy(
                    error = "Failed to start realtime sync: ${e.message}"
                )
            }
        }
    }

    /**
     * Stop real-time synchronization
     */
    fun stopRealtimeSync() {
        if (!isRealtimeSyncing) {
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Stopping realtime sync")

                // Unsubscribe from channel
                realtimeChannel?.unsubscribe()

                // Disconnect from realtime server
                realtime.disconnect()

                realtimeChannel = null
                isRealtimeSyncing = false

                Log.d(TAG, "Realtime sync stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping realtime sync: ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRealtimeSync()
    }
}
