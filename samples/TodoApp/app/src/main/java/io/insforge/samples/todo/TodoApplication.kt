package io.insforge.samples.todo

import android.app.Application
import io.insforge.samples.todo.data.InsforgeManager

/**
 * Application class for initializing InsForge SDK
 */
class TodoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize InsForge client
        InsforgeManager.initialize(this)
    }
}
