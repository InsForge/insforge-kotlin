package dev.insforge.samples.twitter.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.insforge.InsforgeClient
import dev.insforge.auth.auth
import dev.insforge.database.database
import dev.insforge.storage.storage
import dev.insforge.samples.twitter.data.Follow
import dev.insforge.samples.twitter.data.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfileViewModel(
    private val client: InsforgeClient,
    private val context: Context
) : ViewModel() {

    private val _currentProfile = MutableStateFlow<Profile?>(null)
    val currentProfile: StateFlow<Profile?> = _currentProfile.asStateFlow()

    private val _viewedProfile = MutableStateFlow<Profile?>(null)
    val viewedProfile: StateFlow<Profile?> = _viewedProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    init {
        loadCurrentProfile()
    }

    fun loadCurrentProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val currentUserId = client.auth.currentUser.value?.id
                    ?: throw Exception("User not authenticated")

                val profiles = client.database
                    .from("profiles")
                    .eq("user_id", currentUserId)
                    .select()
                    .execute<Profile>()

                _currentProfile.value = profiles.firstOrNull()
            } catch (e: Exception) {
                _error.value = "Failed to load profile"
                Log.e("ProfileViewModel", "Error loading current profile", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadProfile(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val profiles = client.database
                    .from("profiles")
                    .eq("user_id", userId)
                    .select()
                    .execute<Profile>()

                _viewedProfile.value = profiles.firstOrNull()

                // Check if current user is following this profile
                checkFollowStatus(userId)
            } catch (e: Exception) {
                _error.value = "Failed to load profile"
                Log.e("ProfileViewModel", "Error loading profile", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun checkFollowStatus(userId: String) {
        viewModelScope.launch {
            try {
                val currentUserId = client.auth.currentUser.value?.id ?: return@launch

                val follows = client.database
                    .from("follows")
                    .eq("follower_id", currentUserId)
                    .eq("following_id", userId)
                    .select()
                    .execute<Follow>()

                _isFollowing.value = follows.isNotEmpty()
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error checking follow status", e)
            }
        }
    }

    fun updateProfile(username: String?, bio: String?, avatarUri: Uri?) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val currentUserId = client.auth.currentUser.value?.id
                    ?: throw Exception("User not authenticated")

                var avatarUrl: String? = _currentProfile.value?.avatarUrl
                var avatarKey: String? = _currentProfile.value?.avatarKey

                // Upload new avatar if provided
                if (avatarUri != null) {
                    // Delete old avatar if exists
                    if (avatarKey != null) {
                        try {
                            client.storage
                                .from("avatars")
                                .delete(avatarKey)
                        } catch (e: Exception) {
                            Log.e("ProfileViewModel", "Error deleting old avatar", e)
                        }
                    }

                    val imageData = context.contentResolver.openInputStream(avatarUri)?.use {
                        it.readBytes()
                    } ?: throw Exception("Failed to read avatar")

                    val contentType = context.contentResolver.getType(avatarUri)
                    val uploadResult = client.storage
                        .from("avatars")
                        .uploadWithAutoKey("avatar.jpg", imageData) {
                            this.contentType = contentType
                        }

                    avatarUrl = uploadResult.url
                    avatarKey = uploadResult.key
                }

                // Update profile
                val updates = buildJsonObject {
                    username?.let { put("username", it) }
                    bio?.let { put("bio", it) }
                    avatarUrl?.let { put("avatar_url", it) }
                    avatarKey?.let { put("avatar_key", it) }
                }

                client.database
                    .from("profiles")
                    .eq("user_id", currentUserId)
                    .update(updates)
                    .execute<Profile>()

                // Reload current profile
                loadCurrentProfile()
            } catch (e: Exception) {
                _error.value = "Failed to update profile"
                Log.e("ProfileViewModel", "Error updating profile", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleFollow(userId: String) {
        viewModelScope.launch {
            try {
                val currentUserId = client.auth.currentUser.value?.id
                    ?: throw Exception("User not authenticated")

                if (_isFollowing.value) {
                    // Unfollow
                    client.database
                        .from("follows")
                        .eq("follower_id", currentUserId)
                        .eq("following_id", userId)
                        .delete()
                        .execute<Follow>()

                    _isFollowing.value = false
                } else {
                    // Follow
                    val follow = Follow(
                        followerId = currentUserId,
                        followingId = userId
                    )
                    client.database
                        .from("follows")
                        .insertTyped(listOf(follow))
                        .execute<Follow>()

                    _isFollowing.value = true
                }

                // Reload profile to get updated counts
                loadProfile(userId)
            } catch (e: Exception) {
                _error.value = "Failed to update follow status"
                Log.e("ProfileViewModel", "Error toggling follow", e)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}