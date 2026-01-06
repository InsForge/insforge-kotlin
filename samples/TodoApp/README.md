# InsForge Todo App Sample

A sample Android application demonstrating how to use the InsForge Kotlin SDK for:

- User authentication (email/password and OAuth)
- Database operations (CRUD for todo items)
- Session persistence

## Features

- **Sign Up / Sign In**: Create accounts and authenticate with email/password
- **OAuth Authentication**: Sign in with OAuth providers via InsForge hosted auth page
- **Todo Management**: Create, read, update, and delete todo items
- **Session Persistence**: Stay logged in across app restarts
- **Material 3 Design**: Modern Android UI with Jetpack Compose

## Project Structure

```
app/src/main/java/io/insforge/samples/todo/
├── data/
│   ├── Models.kt              # Data models (Todo, AuthState, etc.)
│   └── InsforgeManager.kt     # InsForge client singleton
├── viewmodel/
│   ├── AuthViewModel.kt       # Authentication state management
│   └── TodoViewModel.kt       # Todo list state management
├── ui/
│   ├── auth/
│   │   ├── LoginScreen.kt     # Login/signup UI
│   │   └── AuthCallbackActivity.kt  # OAuth callback handler
│   ├── todo/
│   │   └── TodoListScreen.kt  # Todo list UI
│   └── theme/
│       └── Theme.kt           # Material 3 theme
├── MainActivity.kt            # Main entry point
└── TodoApplication.kt         # Application class
```

## Setup

### 1. Configure InsForge Credentials

Edit `app/build.gradle.kts` and replace the placeholder values:

```kotlin
buildConfigField("String", "INSFORGE_URL", "\"https://your-project.insforge.io\"")
buildConfigField("String", "INSFORGE_ANON_KEY", "\"your-anon-key\"")
```

### 2. Build the InsForge SDK

From the root project directory:

```bash
./gradlew build
```

This creates the SDK JAR file that the sample app depends on.

### 3. Create the Database Table

In your InsForge dashboard, create a `todos` table with the following schema:

```sql
CREATE TABLE todos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    description TEXT,
    completed BOOLEAN DEFAULT FALSE,
    user_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable Row Level Security
ALTER TABLE todos ENABLE ROW LEVEL SECURITY;

-- Policy: Users can only access their own todos
CREATE POLICY "Users can manage their own todos" ON todos
    FOR ALL
    USING (user_id = auth.uid());
```

### 4. Configure OAuth (Optional)

For OAuth authentication to work:

1. Enable OAuth providers in your InsForge dashboard
2. The callback URL is pre-configured as: `insforge-todo://auth/callback`
3. For production, consider using App Links instead of custom URL schemes

### 5. Run the App

Open the project in Android Studio and run on a device or emulator.

## Key Implementation Details

### InsForge Client Initialization

The client is initialized in `TodoApplication.onCreate()`:

```kotlin
InsforgeManager.initialize(this)
```

The `InsforgeManager` configures:
- `BrowserLauncher` for opening OAuth URLs
- `SessionStorage` using Jetpack DataStore for persistence
- Database module for todo CRUD operations

### Authentication Flow

1. **Email/Password**: Uses `auth.signIn()` and `auth.signUp()`
2. **OAuth**: Calls `auth.signInWithDefaultPage(callbackUrl)` which opens the browser
3. **Callback Handling**: `AuthCallbackActivity` intercepts the callback URL and calls `auth.handleAuthCallback(url)`

### Database Operations

Todo operations use the Database module:

```kotlin
// Fetch todos
database.from("todos")
    .select("*")
    .order("created_at", ascending = false)
    .execute<Todo>()

// Create todo
database.from("todos")
    .insert(request)
    .executeSingle<Todo>()

// Update todo
database.from("todos")
    .update(update)
    .eq("id", todoId)
    .execute<Todo>()

// Delete todo
database.from("todos")
    .delete()
    .eq("id", todoId)
    .execute<Todo>()
```

## Dependencies

- InsForge Kotlin SDK
- Jetpack Compose (Material 3)
- Kotlin Coroutines
- Ktor Client
- Jetpack DataStore

## License

MIT License
