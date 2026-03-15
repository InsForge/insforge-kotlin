package dev.insforge.realtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures a connection attempt is only completed once across racing callbacks
 * like timeout, connect success, and connect error.
 */
internal class ConnectionAttemptGuard {
    private val completed = AtomicBoolean(false)

    fun finish(block: () -> Unit): Boolean {
        if (!completed.compareAndSet(false, true)) {
            return false
        }
        block()
        return true
    }

    fun isPending(): Boolean = !completed.get()
}
