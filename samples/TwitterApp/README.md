# Twitter Clone Android App

A full-featured Twitter clone built with Kotlin, Jetpack Compose, and InsForge Backend-as-a-Service.

## Features

### Authentication
- ✅ Email/Password sign up and sign in
- ✅ OAuth authentication (Google, GitHub, etc.) via hosted auth page
- ✅ Session persistence with DataStore
- ✅ Automatic profile creation on signup

### Tweets
- ✅ Create tweets with text and optional images
- ✅ View timeline feed with all tweets
- ✅ Like/unlike tweets with real-time count updates
- ✅ Delete your own tweets
- ✅ Image upload to InsForge Storage

### Social Features
- ✅ User profiles with avatar, bio, and username
- ✅ Follow/unfollow users
- ✅ Follower and following counts (auto-updated)
- ✅ View user-specific tweet feeds
- ✅ Edit your own profile

### UI/UX
- ✅ Modern Material Design 3 (Material You)
- ✅ Twitter-inspired color scheme
- ✅ Responsive Jetpack Compose UI
- ✅ Image loading with Coil
- ✅ Dark and light theme support

## Architecture

### Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM with ViewModels
- **Backend**: InsForge (PostgreSQL + Storage + Realtime)
- **Image Loading**: Coil
- **Navigation**: Navigation Compose
- **State Management**: StateFlow & Compose State
- **Serialization**: Kotlinx Serialization

### Project Structure

```
app/src/main/java/dev/insforge/samples/twitter/
├── data/
│   ├── Models.kt              # Data classes for tweets, profiles, etc.
│   └── InsforgeManager.kt     # InsForge client initialization
├── viewmodel/
│   ├── AuthViewModel.kt       # Authentication logic
│   ├── TweetViewModel.kt      # Tweet CRUD and timeline
│   └── ProfileViewModel.kt    # Profile and follow system
├── ui/
│   ├── auth/
│   │   ├── LoginScreen.kt     # Login/signup screen
│   │   └── AuthCallbackActivity.kt  # OAuth callback handler
│   ├── home/
│   │   └── HomeScreen.kt      # Timeline feed
│   ├── tweet/
│   │   └── CreateTweetScreen.kt  # Tweet creation
│   ├── profile/
│   │   └── ProfileScreen.kt   # User profiles
│   ├── components/
│   │   └── TweetItem.kt       # Reusable tweet card
│   └── theme/
│       └── Theme.kt           # Material Design theme
├── MainActivity.kt            # Main entry point
└── TwitterApplication.kt      # Application class
```

## Backend Schema

### Database Tables

**profiles**
- `id` - UUID primary key
- `user_id` - References auth.users
- `username` - Unique username
- `bio` - User biography
- `avatar_url` - Avatar image URL
- `avatar_key` - Storage key for avatar
- `followers_count` - Number of followers
- `following_count` - Number of users following
- Automatically created when user signs up

**tweets**
- `id` - UUID primary key
- `user_id` - References auth.users
- `content` - Tweet text
- `image_url` - Optional tweet image URL
- `image_key` - Storage key for image
- `likes_count` - Number of likes
- `created_at` - Timestamp

**likes**
- `id` - UUID primary key
- `user_id` - References auth.users
- `tweet_id` - References tweets
- Unique constraint on (user_id, tweet_id)
- Triggers auto-update likes_count on tweets

**follows**
- `id` - UUID primary key
- `follower_id` - User who is following
- `following_id` - User being followed
- Unique constraint on (follower_id, following_id)
- Triggers auto-update follower/following counts

### Storage Buckets
- `avatars` - User profile pictures
- `tweet-images` - Tweet images

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 24+
- InsForge backend instance

### Configuration

1. **Update Backend Credentials**

   Edit `app/build.gradle.kts` and update:
   ```kotlin
   buildConfigField("String", "INSFORGE_URL", "\"YOUR_INSFORGE_URL\"")
   buildConfigField("String", "INSFORGE_ANON_KEY", "\"YOUR_ANON_KEY\"")
   ```

2. **OAuth Callback URL** (Optional)

   If using OAuth, update the callback URL in:
   - `AndroidManifest.xml` - Update the intent filter data scheme
   - `LoginScreen.kt` - Update the callback URL in `signInWithOAuth()`

   Example:
   ```xml
   <data
       android:scheme="your-app-scheme"
       android:host="auth"
       android:path="/callback" />
   ```

3. **Build and Run**
   ```bash
   ./gradlew assembleDebug
   ```
   Or open in Android Studio and click Run.

## Key Features Implementation

### Real-time Updates
The app uses triggers on the backend to automatically update counts:
- Like count updates when likes are added/removed
- Follower/following counts update when follows are created/deleted

### Image Handling
- Images are uploaded to InsForge Storage with auto-generated keys
- URLs are stored in the database
- Images are loaded asynchronously with Coil
- Old images are deleted when profiles/tweets are updated/deleted

### Authentication Flow
1. User signs up/signs in
2. Profile is automatically created via database trigger
3. Session is persisted in DataStore
4. Auth state is observed via StateFlow
5. UI automatically updates based on auth state

### Profile Management
- Users can edit username, bio, and avatar
- View their own profile or other users' profiles
- Follow/unfollow with real-time count updates
- View user-specific tweet feeds

## Dependencies

Key dependencies used in this project:

```kotlin
// InsForge SDK
implementation("dev.insforge:insforge-kotlin:0.1.1")

// Jetpack Compose
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.navigation:navigation-compose")

// Image Loading
implementation("io.coil-kt:coil-compose:2.5.0")

// Networking (Ktor)
implementation("io.ktor:ktor-client-okhttp:2.3.7")

// Serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

// DataStore
implementation("androidx.datastore:datastore-preferences")
```

## Troubleshooting

### Common Issues

**Network Errors**
- Ensure `android:usesCleartextTraffic="true"` is in AndroidManifest if using HTTP
- Check that INTERNET permission is declared
- Verify backend URL is correct

**OAuth Not Working**
- Verify callback URL scheme matches in all locations
- Check that the activity is exported in AndroidManifest
- Ensure browser launcher is configured in InsForgeManager

**Images Not Loading**
- Check storage bucket permissions (should be public)
- Verify image URLs are accessible
- Check Coil is properly configured

**Authentication Issues**
- Clear app data and try again
- Check that session persistence is enabled
- Verify anon key is correct

## Future Enhancements

Potential features to add:
- [ ] Real-time tweet updates using InsForge Realtime
- [ ] Reply/comment system
- [ ] Retweet functionality
- [ ] Direct messaging
- [ ] Notifications
- [ ] Search tweets and users
- [ ] Hashtags and mentions
- [ ] Image galleries (multiple images per tweet)
- [ ] Video support
- [ ] Tweet analytics

## License

This project is a sample application for educational purposes.

## Credits

Built with [InsForge](https://insforge.app) - Backend-as-a-Service platform