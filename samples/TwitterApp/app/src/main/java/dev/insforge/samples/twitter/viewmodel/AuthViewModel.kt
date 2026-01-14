package dev.insforge.samples.twitter.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.insforge.InsforgeClient
import dev.insforge.auth.auth
import dev.insforge.auth.models.User
import dev.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(private val client: InsforgeClient) : ViewModel() {

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Track whether we've finished checking for a persisted session
    private val _isSessionLoading = MutableStateFlow(true)
    val isSessionLoading: StateFlow<Boolean> = _isSessionLoading.asStateFlow()

    init {
        // Observe auth state changes
        viewModelScope.launch {
            client.auth.currentUser.collect { user ->
                _currentUser.value = user
                // Session loading is complete once we get the first user state
                if (_isSessionLoading.value) {
                    _isSessionLoading.value = false
                    if (user != null) {
                        Log.d("AuthViewModel", "Session restored: ${user.email}")
                    }
                }
            }
        }
    }

    fun signUp(email: String, password: String, name: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = client.auth.signUp(email, password, name)
                Log.d("AuthViewModel", "Sign up successful: ${result.user?.email}")
            } catch (e: InsforgeHttpException) {
                _error.value = e.message ?: "Sign up failed"
                Log.e("AuthViewModel", "Sign up failed", e)
            } catch (e: Exception) {
                _error.value = "An unexpected error occurred"
                Log.e("AuthViewModel", "Sign up error", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = client.auth.signIn(email, password)
                Log.d("AuthViewModel", "Sign in successful: ${result.user?.email}")
            } catch (e: InsforgeHttpException) {
                _error.value = e.message ?: "Sign in failed"
                Log.e("AuthViewModel", "Sign in failed", e)
            } catch (e: Exception) {
                _error.value = "An unexpected error occurred"
                Log.e("AuthViewModel", "Sign in error", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signInWithOAuth(callbackUrl: String) {
        try {
            client.auth.signInWithDefaultPage(callbackUrl)
        } catch (e: Exception) {
            _error.value = "OAuth sign in failed"
            Log.e("AuthViewModel", "OAuth error", e)
        }
    }

    suspend fun handleAuthCallback(callbackUri: String): Boolean {
        return try {
            val result = client.auth.handleAuthCallback(callbackUri)
            Log.d("AuthViewModel", "OAuth callback successful: ${result.email}")
            true
        } catch (e: Exception) {
            _error.value = "OAuth callback failed"
            Log.e("AuthViewModel", "OAuth callback error", e)
            false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                client.auth.signOut()
                Log.d("AuthViewModel", "Sign out successful")
            } catch (e: Exception) {
                _error.value = "Sign out failed"
                Log.e("AuthViewModel", "Sign out error", e)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}