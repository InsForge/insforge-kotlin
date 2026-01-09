package dev.insforge.samples.todo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.insforge.auth.auth
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.samples.todo.data.AuthState
import dev.insforge.samples.todo.data.InsforgeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for authentication operations
 */
class AuthViewModel : ViewModel() {

    private val auth get() = InsforgeManager.client.auth

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        // Observe auth state changes from the SDK
        viewModelScope.launch {
            auth.currentUser.collect { user ->
                _authState.value = _authState.value.copy(
                    isAuthenticated = user != null,
                    isLoading = false,
                    userEmail = user?.email,
                    userName = user?.metadata?.get("name")
                )
            }
        }
    }

    /**
     * Sign up with email and password
     */
    fun signUp(email: String, password: String, name: String? = null) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            try {
                auth.signUp(email, password, name)
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = null
                )
            } catch (e: InsforgeHttpException) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Sign up failed"
                )
            } catch (e: Exception) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Sign in with email and password
     */
    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            try {
                auth.signIn(email, password)
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = null
                )
            } catch (e: InsforgeHttpException) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Sign in failed"
                )
            } catch (e: Exception) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = e.message ?: "An error occurred"
                )
            }
        }
    }

    /**
     * Start OAuth flow with InsForge hosted page
     */
    fun signInWithOAuth() {
        try {
            auth.signInWithDefaultPage(InsforgeManager.OAUTH_CALLBACK_URL)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(
                error = e.message ?: "Failed to start OAuth flow"
            )
        }
    }

    /**
     * Handle OAuth callback URL
     */
    fun handleOAuthCallback(url: String) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            try {
                auth.handleAuthCallback(url)
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = e.message ?: "OAuth callback failed"
                )
            }
        }
    }

    /**
     * Sign out the current user
     */
    fun signOut() {
        viewModelScope.launch {
            try {
                auth.signOut()
            } catch (e: Exception) {
                _authState.value = _authState.value.copy(
                    error = e.message ?: "Sign out failed"
                )
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }
}
