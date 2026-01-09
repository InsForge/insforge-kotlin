package dev.insforge.samples.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.insforge.samples.todo.ui.auth.LoginScreen
import dev.insforge.samples.todo.ui.theme.TodoAppTheme
import dev.insforge.samples.todo.ui.todo.TodoListScreen
import dev.insforge.samples.todo.viewmodel.AuthViewModel
import dev.insforge.samples.todo.viewmodel.TodoViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TodoAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TodoApp()
                }
            }
        }
    }
}

@Composable
fun TodoApp(
    authViewModel: AuthViewModel = viewModel(),
    todoViewModel: TodoViewModel = viewModel()
) {
    val authState by authViewModel.authState.collectAsState()
    val todoState by todoViewModel.state.collectAsState()

    when {
        // Show loading while checking initial auth state
        authState.isLoading && !authState.isAuthenticated -> {
            // Could show a splash screen here
            LoginScreen(
                authState = authState,
                onSignIn = authViewModel::signIn,
                onSignUp = authViewModel::signUp,
                onSignInWithOAuth = authViewModel::signInWithOAuth,
                onClearError = authViewModel::clearError
            )
        }
        // Show login if not authenticated
        !authState.isAuthenticated -> {
            LoginScreen(
                authState = authState,
                onSignIn = authViewModel::signIn,
                onSignUp = authViewModel::signUp,
                onSignInWithOAuth = authViewModel::signInWithOAuth,
                onClearError = authViewModel::clearError
            )
        }
        // Show todo list if authenticated
        else -> {
            TodoListScreen(
                state = todoState,
                userEmail = authState.userEmail,
                onRefresh = todoViewModel::loadTodos,
                onCreateTodo = todoViewModel::createTodo,
                onToggleTodo = todoViewModel::toggleTodoCompleted,
                onDeleteTodo = todoViewModel::deleteTodo,
                onSignOut = authViewModel::signOut,
                onStartRealtimeSync = todoViewModel::startRealtimeSync,
                onStopRealtimeSync = todoViewModel::stopRealtimeSync
            )
        }
    }
}
