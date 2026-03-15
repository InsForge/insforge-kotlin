package dev.insforge.realtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionAttemptGuardTest {

    @Test
    fun `finish only runs once`() {
        val guard = ConnectionAttemptGuard()
        val callCount = AtomicInteger(0)

        repeat(3) {
            guard.finish {
                callCount.incrementAndGet()
            }
        }

        assertEquals(1, callCount.get())
        assertFalse(guard.isPending())
    }

    @Test
    fun `finish is single shot across concurrent callbacks`() {
        val guard = ConnectionAttemptGuard()
        val callCount = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(8)
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.submit {
                startLatch.await(2, TimeUnit.SECONDS)
                guard.finish {
                    callCount.incrementAndGet()
                }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, callCount.get())
        assertFalse(guard.isPending())
    }
}
