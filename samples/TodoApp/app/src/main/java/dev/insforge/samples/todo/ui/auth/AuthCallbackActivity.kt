package dev.insforge.samples.todo.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.insforge.auth.auth
import dev.insforge.samples.todo.MainActivity
import dev.insforge.samples.todo.data.InsforgeManager
import kotlinx.coroutines.launch

/**
 * Activity to handle OAuth callback from browser.
 *
 * This activity is configured in AndroidManifest.xml to intercept the callback URL:
 * insforge-todo://auth/callback
 *
 * When the OAuth flow completes, the browser redirects to this URL,
 * and Android routes it to this activity.
 */
class AuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            lifecycleScope.launch {
                try {
                    // Handle the OAuth callback
                    val result = InsforgeManager.client.auth.handleAuthCallback(uri.toString())

                    Toast.makeText(
                        this@AuthCallbackActivity,
                        "Welcome, ${result.name ?: result.email}!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Navigate to main activity
                    navigateToMain()

                } catch (e: Exception) {
                    Toast.makeText(
                        this@AuthCallbackActivity,
                        "Authentication failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()

                    // Still navigate to main (will show login screen)
                    navigateToMain()
                }
            }
        } ?: run {
            // No URI data, just go to main
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(mainIntent)
        finish()
    }
}
