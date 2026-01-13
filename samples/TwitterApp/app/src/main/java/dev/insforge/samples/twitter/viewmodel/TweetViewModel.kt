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
import dev.insforge.samples.twitter.data.Like
import dev.insforge.samples.twitter.data.Tweet
import dev.insforge.samples.twitter.data.TweetWithProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TweetViewModel(
    private val client: InsforgeClient,
    private val context: Context
) : ViewModel() {

    private val _tweets = MutableStateFlow<List<TweetWithProfile>>(emptyList())
    val tweets: StateFlow<List<TweetWithProfile>> = _tweets.asStateFlow()

    private val _userTweets = MutableStateFlow<List<Tweet>>(emptyList())
    val userTweets: StateFlow<List<Tweet>> = _userTweets.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _likedTweetIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTweetIds: StateFlow<Set<String>> = _likedTweetIds.asStateFlow()

    init {
        loadTimeline()
    }

    fun loadTimeline() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Load tweets with profile information using a join
                val result = client.database
                    .from("tweets")
                    .select("""
                        id,
                        user_id,
                        content,
                        image_url,
                        image_key,
                        likes_count,
                        created_at,
                        profiles!tweets_user_id_fkey(username, avatar_url, bio)
                    """.trimIndent())
                    .order("created_at", ascending = false)
                    .limit(50)
                    .execute<Map<String, Any>>()

                // Parse the results manually
                val parsedTweets = result.mapNotNull { tweetMap ->
                    try {
                        val profileMap = tweetMap["profiles"] as? Map<*, *>
                        TweetWithProfile(
                            id = tweetMap["id"] as String,
                            userId = tweetMap["user_id"] as String,
                            content = tweetMap["content"] as String,
                            imageUrl = tweetMap["image_url"] as? String,
                            imageKey = tweetMap["image_key"] as? String,
                            likesCount = (tweetMap["likes_count"] as? Number)?.toInt() ?: 0,
                            createdAt = tweetMap["created_at"] as String,
                            username = profileMap?.get("username") as? String,
                            avatarUrl = profileMap?.get("avatar_url") as? String,
                            bio = profileMap?.get("bio") as? String
                        )
                    } catch (e: Exception) {
                        Log.e("TweetViewModel", "Error parsing tweet", e)
                        null
                    }
                }

                _tweets.value = parsedTweets
                loadLikedTweets()
            } catch (e: Exception) {
                _error.value = "Failed to load timeline"
                Log.e("TweetViewModel", "Error loading timeline", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadLikedTweets() {
        viewModelScope.launch {
            try {
                val currentUserId = client.auth.currentUser.value?.id ?: return@launch
                val likes = client.database
                    .from("likes")
                    .eq("user_id", currentUserId)
                    .select("tweet_id")
                    .execute<Map<String, String>>()

                _likedTweetIds.value = likes.mapNotNull { it["tweet_id"] }.toSet()
            } catch (e: Exception) {
                Log.e("TweetViewModel", "Error loading liked tweets", e)
            }
        }
    }

    fun loadUserTweets(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = client.database
                    .from("tweets")
                    .eq("user_id", userId)
                    .select()
                    .order("created_at", ascending = false)
                    .execute<Tweet>()

                _userTweets.value = result
            } catch (e: Exception) {
                _error.value = "Failed to load user tweets"
                Log.e("TweetViewModel", "Error loading user tweets", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createTweet(content: String, imageUri: Uri?) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val currentUserId = client.auth.currentUser.value?.id
                    ?: throw Exception("User not authenticated")

                var imageUrl: String? = null
                var imageKey: String? = null

                // Upload image if provided
                if (imageUri != null) {
                    val imageData = context.contentResolver.openInputStream(imageUri)?.use {
                        it.readBytes()
                    } ?: throw Exception("Failed to read image")

                    val contentType = context.contentResolver.getType(imageUri)
                    val uploadResult = client.storage
                        .from("tweet-images")
                        .uploadWithAutoKey("image.jpg", imageData) {
                            this.contentType = contentType
                        }

                    imageUrl = uploadResult.url
                    imageKey = uploadResult.key
                }

                // Create tweet
                val tweet = Tweet(
                    userId = currentUserId,
                    content = content,
                    imageUrl = imageUrl,
                    imageKey = imageKey
                )

                client.database
                    .from("tweets")
                    .insertTyped(listOf(tweet))
                    .returning()
                    .execute<Tweet>()

                // Reload timeline
                loadTimeline()
            } catch (e: Exception) {
                _error.value = "Failed to post tweet"
                Log.e("TweetViewModel", "Error creating tweet", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteTweet(tweetId: String, imageKey: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Delete image from storage if exists
                if (imageKey != null) {
                    try {
                        client.storage
                            .from("tweet-images")
                            .delete(imageKey)
                    } catch (e: Exception) {
                        Log.e("TweetViewModel", "Error deleting image", e)
                    }
                }

                // Delete tweet
                client.database
                    .from("tweets")
                    .eq("id", tweetId)
                    .delete()
                    .execute<Tweet>()

                // Reload timeline
                loadTimeline()
            } catch (e: Exception) {
                _error.value = "Failed to delete tweet"
                Log.e("TweetViewModel", "Error deleting tweet", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleLike(tweetId: String) {
        viewModelScope.launch {
            try {
                val currentUserId = client.auth.currentUser.value?.id
                    ?: throw Exception("User not authenticated")

                val isLiked = _likedTweetIds.value.contains(tweetId)

                if (isLiked) {
                    // Unlike
                    client.database
                        .from("likes")
                        .eq("user_id", currentUserId)
                        .eq("tweet_id", tweetId)
                        .delete()
                        .execute<Like>()

                    _likedTweetIds.value = _likedTweetIds.value - tweetId
                } else {
                    // Like
                    val like = Like(
                        userId = currentUserId,
                        tweetId = tweetId
                    )
                    client.database
                        .from("likes")
                        .insertTyped(listOf(like))
                        .execute<Like>()

                    _likedTweetIds.value = _likedTweetIds.value + tweetId
                }

                // Reload timeline to get updated likes count
                loadTimeline()
            } catch (e: Exception) {
                _error.value = "Failed to update like"
                Log.e("TweetViewModel", "Error toggling like", e)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}