package dev.insforge.samples.twitter.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.insforge.samples.twitter.data.Profile
import dev.insforge.samples.twitter.viewmodel.AuthViewModel
import dev.insforge.samples.twitter.viewmodel.ProfileViewModel
import dev.insforge.samples.twitter.viewmodel.TweetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userId: String?,
    authViewModel: AuthViewModel,
    profileViewModel: ProfileViewModel,
    tweetViewModel: TweetViewModel,
    onNavigateBack: () -> Unit
) {
    val currentUser by authViewModel.currentUser.collectAsState()
    val currentProfile by profileViewModel.currentProfile.collectAsState()
    val viewedProfile by profileViewModel.viewedProfile.collectAsState()
    val isFollowing by profileViewModel.isFollowing.collectAsState()
    val userTweets by tweetViewModel.userTweets.collectAsState()
    val likedTweetIds by tweetViewModel.likedTweetIds.collectAsState()
    val isLoading by profileViewModel.isLoading.collectAsState()

    val isOwnProfile = userId == null || userId == currentUser?.id
    val profile = if (isOwnProfile) currentProfile else viewedProfile

    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        if (!isOwnProfile && userId != null) {
            profileViewModel.loadProfile(userId)
            tweetViewModel.loadUserTweets(userId)
        } else {
            currentUser?.id?.let { tweetViewModel.loadUserTweets(it) }
        }
    }

    if (showEditDialog && isOwnProfile && profile != null) {
        EditProfileDialog(
            profile = profile,
            profileViewModel = profileViewModel,
            onDismiss = { showEditDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isOwnProfile) "My Profile" else "Profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading && profile == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            profile != null -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    item {
                        ProfileHeader(
                            profile = profile,
                            isOwnProfile = isOwnProfile,
                            isFollowing = isFollowing,
                            onEditClick = { showEditDialog = true },
                            onFollowClick = {
                                if (!isOwnProfile && userId != null) {
                                    profileViewModel.toggleFollow(userId)
                                }
                            }
                        )
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Tweets",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(userTweets, key = { it.id ?: "" }) { tweet ->
                        dev.insforge.samples.twitter.ui.components.TweetItem(
                            tweet = dev.insforge.samples.twitter.data.TweetWithProfile(
                                id = tweet.id ?: "",
                                userId = tweet.userId,
                                content = tweet.content,
                                imageUrl = tweet.imageUrl,
                                imageKey = tweet.imageKey,
                                likesCount = tweet.likesCount,
                                createdAt = tweet.createdAt ?: "",
                                username = profile.username,
                                avatarUrl = profile.avatarUrl,
                                bio = profile.bio
                            ),
                            isLiked = likedTweetIds.contains(tweet.id),
                            isOwner = isOwnProfile,
                            onLikeClick = {
                                tweet.id?.let { tweetViewModel.toggleLike(it) }
                            },
                            onDeleteClick = {
                                tweet.id?.let { tweetViewModel.deleteTweet(it, tweet.imageKey) }
                            },
                            onProfileClick = { },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            else -> {
                // Profile not found or error state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "No profile",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Profile not found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isOwnProfile) {
                            Button(
                                onClick = { profileViewModel.loadCurrentProfile() }
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    profile: Profile,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    onEditClick: () -> Unit,
    onFollowClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Avatar
            if (profile.avatarUrl != null) {
                AsyncImage(
                    // Fix localhost URL for Android emulator
                    model = profile.avatarUrl.replace("localhost", "10.0.2.2"),
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default avatar",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Edit/Follow button
            if (isOwnProfile) {
                OutlinedButton(onClick = onEditClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit profile",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit Profile")
                }
            } else {
                Button(
                    onClick = onFollowClick,
                    colors = if (isFollowing) {
                        ButtonDefaults.outlinedButtonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (isFollowing) "Unfollow" else "Follow")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.username ?: "Unknown User",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (profile.bio != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = profile.bio,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = profile.followingCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Following",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                Text(
                    text = profile.followersCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Followers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileDialog(
    profile: Profile,
    profileViewModel: ProfileViewModel,
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf(profile.username ?: "") }
    var bio by remember { mutableStateOf(profile.bio ?: "") }
    var selectedAvatarUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedAvatarUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar preview
                if (selectedAvatarUri != null || profile.avatarUrl != null) {
                    AsyncImage(
                        // Fix localhost URL for Android emulator
                        model = selectedAvatarUri ?: profile.avatarUrl?.replace("localhost", "10.0.2.2"),
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Default avatar",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Text("Change Avatar")
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Bio") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    profileViewModel.updateProfile(username, bio, selectedAvatarUri)
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}