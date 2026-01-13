package dev.insforge.samples.twitter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val username: String? = null,
    val bio: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("avatar_key")
    val avatarKey: String? = null,
    @SerialName("followers_count")
    val followersCount: Int = 0,
    @SerialName("following_count")
    val followingCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class Tweet(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val content: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("image_key")
    val imageKey: String? = null,
    @SerialName("likes_count")
    val likesCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null,
    // Joined data
    val profile: Profile? = null
)

@Serializable
data class Like(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("tweet_id")
    val tweetId: String,
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class Follow(
    val id: String? = null,
    @SerialName("follower_id")
    val followerId: String,
    @SerialName("following_id")
    val followingId: String,
    @SerialName("created_at")
    val createdAt: String? = null
)

// Extended tweet with user information
@Serializable
data class TweetWithProfile(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val content: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("image_key")
    val imageKey: String? = null,
    @SerialName("likes_count")
    val likesCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String,
    // Profile data
    val username: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val bio: String? = null
)

// User data from auth
@Serializable
data class User(
    val id: String,
    val email: String,
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class UserProfile(
    val user: User,
    val profile: Profile
)