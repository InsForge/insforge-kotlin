package dev.insforge.realtime

import dev.insforge.InsforgeClient
import dev.insforge.TestConfig
import dev.insforge.createInsforgeClient
import io.socket.client.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Tag
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RealtimeConnectionLifecycleTest {

    @Test
    fun `timeout ignores late connect from stale socket`() = runTest(timeout = 5.seconds) {
        val (client, realtime, factory) = createRealtimeUnderTest(connectTimeoutMs = 25)

        try {
            var connectEvents = 0
            realtime.on<Unit>("connect") { connectEvents++ }

            val connectAttempt = async {
                assertFailsWith<Exception> { realtime.connect() }
            }

            yield()
            val socket = factory.awaitCreatedSocket()
            val failure = connectAttempt.await()

            assertEquals("Connection timeout after 25ms", failure.message)
            assertEquals(Realtime.ConnectionState.Disconnected, realtime.connectionState.value)
            assertEquals(0, connectEvents)

            socket.fireConnect(stale = true)
            delay(20)

            assertEquals(Realtime.ConnectionState.Disconnected, realtime.connectionState.value)
            assertEquals(0, connectEvents)
        } finally {
            client.close()
        }
    }

    @Test
    fun `timeout ignores late connect error from stale socket`() = runTest(timeout = 5.seconds) {
        val (client, realtime, factory) = createRealtimeUnderTest(connectTimeoutMs = 25)

        try {
            var connectErrors = 0
            realtime.on<String>("connect_error") { connectErrors++ }

            val connectAttempt = async {
                assertFailsWith<Exception> { realtime.connect() }
            }

            yield()
            val socket = factory.awaitCreatedSocket()
            val failure = connectAttempt.await()

            assertEquals("Connection timeout after 25ms", failure.message)
            assertEquals(Realtime.ConnectionState.Disconnected, realtime.connectionState.value)
            assertEquals(0, connectErrors)

            socket.fireConnectError(stale = true)
            delay(20)

            assertEquals(Realtime.ConnectionState.Disconnected, realtime.connectionState.value)
            assertEquals(0, connectErrors)
        } finally {
            client.close()
        }
    }

    @Test
    fun `current socket reconnect still replays subscriptions`() = runTest(timeout = 5.seconds) {
        val (client, realtime, factory) = createRealtimeUnderTest(connectTimeoutMs = 1_000)

        try {
            var connectEvents = 0
            realtime.on<Unit>("connect") { connectEvents++ }

            val connectAttempt = async { realtime.connect() }
            val socket = factory.awaitConnectedSocket()
            socket.fireConnect()
            connectAttempt.await()

            assertEquals(Realtime.ConnectionState.Connected::class, realtime.connectionState.value::class)
            assertEquals(1, connectEvents)

            val subscribeResult = realtime.subscribe("todos")
            assertTrue(subscribeResult.ok)

            socket.emittedEvents.clear()
            socket.fireConnect()
            delay(20)

            assertEquals(2, connectEvents)
            assertEquals(listOf("realtime:subscribe"), socket.emittedEvents.map { it.first })
        } finally {
            client.close()
        }
    }

    @Test
    fun `concurrent connect callers share one socket`() = runTest(timeout = 5.seconds) {
        val (client, realtime, factory) = createRealtimeUnderTest(connectTimeoutMs = 1_000)

        try {
            val firstConnect = async { realtime.connect() }
            val secondConnect = async { realtime.connect() }

            val socket = factory.awaitConnectedSocket()
            delay(20)

            assertEquals(1, factory.createdSockets.size)
            assertEquals(1, socket.connectCallCount)

            socket.fireConnect()
            firstConnect.await()
            secondConnect.await()

            assertEquals(Realtime.ConnectionState.Connected::class, realtime.connectionState.value::class)
        } finally {
            client.close()
        }
    }

    private fun createRealtimeUnderTest(
        connectTimeoutMs: Long
    ): Triple<InsforgeClient, Realtime, FakeRealtimeSocketFactory> {
        val client = createInsforgeClient(
            baseURL = TestConfig.BASE_URL,
            anonKey = TestConfig.ANON_KEY
        )
        val factory = FakeRealtimeSocketFactory()
        val realtime = Realtime(client, RealtimeConfig(), factory, connectTimeoutMs)
        return Triple(client, realtime, factory)
    }

    private suspend fun FakeRealtimeSocketFactory.awaitConnectedSocket(): FakeRealtimeSocket {
        repeat(20) {
            createdSockets.lastOrNull()?.takeIf { it.connectCallCount > 0 }?.let { return it }
            delay(10)
        }
        error("Timed out waiting for fake socket connect()")
    }

    private suspend fun FakeRealtimeSocketFactory.awaitCreatedSocket(): FakeRealtimeSocket {
        repeat(20) {
            createdSockets.lastOrNull()?.let { return it }
            delay(10)
        }
        error("Timed out waiting for fake socket creation")
    }
}

private class FakeRealtimeSocketFactory : RealtimeSocketFactory {
    val createdSockets = mutableListOf<FakeRealtimeSocket>()

    override fun create(baseUrl: String, options: IO.Options): RealtimeSocket {
        return FakeRealtimeSocket().also(createdSockets::add)
    }
}

private class FakeRealtimeSocket : RealtimeSocket {
    private val listeners = linkedMapOf<String, MutableList<RealtimeSocketListener>>()
    private var staleListeners = emptyMap<String, List<RealtimeSocketListener>>()

    val emittedEvents = mutableListOf<Pair<String, Any>>()
    var connectCallCount = 0
    private var connected = false
    private val socketId = "fake-socket"

    override fun on(event: String, listener: RealtimeSocketListener) {
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
    }

    override fun onAnyIncoming(listener: RealtimeSocketListener) {
        listeners.getOrPut("__on_any__") { mutableListOf() }.add(listener)
    }

    override fun off() {
        staleListeners = listeners.mapValues { (_, callbacks) -> callbacks.toList() }
        listeners.clear()
    }

    override fun emit(event: String, data: Any) {
        emittedEvents += event to data
    }

    override fun emitWithAck(event: String, data: Any, callback: RealtimeSocketListener) {
        emittedEvents += event to data
        callback(arrayOf(JSONObject().put("ok", true)))
    }

    override fun connect() {
        connectCallCount++
    }

    override fun disconnect() {
        connected = false
    }

    override fun isConnected(): Boolean = connected

    override fun id(): String? = socketId

    fun fireConnect(stale: Boolean = false) {
        connected = true
        dispatch(io.socket.client.Socket.EVENT_CONNECT, emptyArray(), stale)
    }

    fun fireDisconnect(reason: String = "transport close", stale: Boolean = false) {
        connected = false
        dispatch(io.socket.client.Socket.EVENT_DISCONNECT, arrayOf(reason), stale)
    }

    fun fireConnectError(message: String = "late connect error", stale: Boolean = false) {
        connected = false
        dispatch(io.socket.client.Socket.EVENT_CONNECT_ERROR, arrayOf(message), stale)
    }

    private fun dispatch(event: String, args: Array<out Any?>, stale: Boolean) {
        val callbacks = if (stale) {
            staleListeners[event].orEmpty()
        } else {
            listeners[event].orEmpty()
        }

        callbacks.forEach { callback -> callback(args) }
    }
}
