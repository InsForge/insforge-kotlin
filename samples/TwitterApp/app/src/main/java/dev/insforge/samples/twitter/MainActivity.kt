package dev.insforge.samples.twitter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.insforge.samples.twitter.ui.auth.LoginScreen
import dev.insforge.samples.twitter.ui.home.HomeScreen
import dev.insforge.samples.twitter.ui.profile.ProfileScreen
import dev.insforge.samples.twitter.ui.theme.TwitterTheme
import dev.insforge.samples.twitter.ui.tweet.CreateTweetScreen
import dev.insforge.samples.twitter.viewmodel.AuthViewModel
import dev.insforge.samples.twitter.viewmodel.ProfileViewModel
import dev.insforge.samples.twitter.viewmodel.TweetViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as TwitterApplication
        val client = app.insforgeManager.client

        setContent {
            TwitterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TwitterApp(
                        authViewModel = AuthViewModel(client),
                        tweetViewModel = TweetViewModel(client, this),
                        profileViewModel = ProfileViewModel(client, this)
                    )
                }
            }
        }
    }
}

@Composable
fun TwitterApp(
    authViewModel: AuthViewModel,
    tweetViewModel: TweetViewModel,
    profileViewModel: ProfileViewModel
) {
    val navController = rememberNavController()
    val currentUser by authViewModel.currentUser.collectAsState()
    val isSessionLoading by authViewModel.isSessionLoading.collectAsState()

    // Show loading screen while checking for persisted session
    if (isSessionLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (currentUser != null) "home" else "login"
    ) {
        composable("login") {
            LoginScreen(
                authViewModel = authViewModel,
                onNavigateToSignUp = {
                    // Sign up is handled within LoginScreen
                }
            )
        }

        composable("home") {
            if (currentUser == null) {
                navController.navigate("login") {
                    popUpTo("home") { inclusive = true }
                }
            } else {
                HomeScreen(
                    authViewModel = authViewModel,
                    tweetViewModel = tweetViewModel,
                    onNavigateToProfile = { userId ->
                        navController.navigate("profile/$userId")
                    },
                    onNavigateToCreateTweet = {
                        navController.navigate("create_tweet")
                    },
                    onNavigateToCurrentUserProfile = {
                        navController.navigate("profile/me")
                    }
                )
            }
        }

        composable("create_tweet") {
            CreateTweetScreen(
                tweetViewModel = tweetViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "profile/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userIdArg = backStackEntry.arguments?.getString("userId")
            val userId = if (userIdArg == "me") null else userIdArg

            ProfileScreen(
                userId = userId,
                authViewModel = authViewModel,
                profileViewModel = profileViewModel,
                tweetViewModel = tweetViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }

    // Listen to auth state changes
    if (currentUser == null && navController.currentDestination?.route != "login") {
        navController.navigate("login") {
            popUpTo(0) { inclusive = true }
        }
    }
}