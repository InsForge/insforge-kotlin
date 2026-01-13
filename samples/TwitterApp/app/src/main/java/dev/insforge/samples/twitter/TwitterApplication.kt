package dev.insforge.samples.twitter

import android.app.Application
import dev.insforge.samples.twitter.data.InsforgeManager

class TwitterApplication : Application() {

    lateinit var insforgeManager: InsforgeManager
        private set

    override fun onCreate() {
        super.onCreate()
        insforgeManager = InsforgeManager.getInstance(this)
    }
}