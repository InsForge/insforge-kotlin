package dev.insforge.samples.twitter.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.insforge.samples.twitter.MainActivity
import dev.insforge.samples.twitter.TwitterApplication
import dev.insforge.samples.twitter.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

class AuthCallbackActivity : ComponentActivity() {

    private lateinit var authViewModel: AuthViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as TwitterApplication
        authViewModel = AuthViewModel(app.insforgeManager.client)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.data?.let { uri ->
            lifecycleScope.launch {
                try {
                    val success = authViewModel.handleAuthCallback(uri.toString())

                    if (success) {
                        Toast.makeText(
                            this@AuthCallbackActivity,
                            "Authentication successful!",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Navigate to main screen
                        startActivity(Intent(this@AuthCallbackActivity, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(
                            this@AuthCallbackActivity,
                            "Authentication failed",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@AuthCallbackActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }
}