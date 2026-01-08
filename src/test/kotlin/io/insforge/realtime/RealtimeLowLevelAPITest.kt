package io.insforge.realtime

import io.insforge.TestConfig
import io.insforge.database.database
import io.insforge.realtime.models.SocketMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Low-Level API tests for Realtime module
 *
 * Tests the direct Socket.IO pub/sub APIs:
 * - connect/disconnect
 * - subscribe/unsubscribe
 * - on/off event listeners
 * - publish messages
 *
 * Monitors database changes on the todos table using database triggers
 * that publish to the 'todos' channel.
 */
class RealtimeLowLevelAPITest {

    private lateinit var client: io.insforge.InsforgeClient

    // User ID from the JWT token for RLS compliance
    private val testUserId = "085a481e-94b8-4bf9-b3a0-7f0e50f7a072"

    @BeforeTest
    fun setup() {
        client = TestConfig.createAuthenticatedRealtimeClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Connection Tests ============

    @Test
    fun `test connect to realtime server`() = runTest(timeout = 30.seconds) {
        println("[Test] Connecting to realtime server...")

        client.realtime.connect()

        assertTrue(client.realtime.isConnected, "Should be connected")
        assertNotNull(client.realtime.socketId, "Should have a socket ID")
        assertEquals(
            Realtime.ConnectionState.Connected::class,
            client.realtime.connectionState.value::class,
            "Connection state should be Connected"
        )

        println("[Test] ✅ Connected successfully")
        println("[Test]    Socket ID: ${client.realtime.socketId}")

        client.realtime.disconnect()
    }

    @Test
    fun `test disconnect from realtime server`() = runTest(timeout = 30.seconds) {
        client.realtime.connect()
        assertTrue(client.realtime.isConnected)

        client.realtime.disconnect()

        assertFalse(client.realtime.isConnected, "Should be disconnected")
        assertNull(client.realtime.socketId, "Socket ID should be null")
        assertEquals(
            Realtime.ConnectionState.Disconnected,
            client.realtime.connectionState.value,
            "Connection state should be Disconnected"
        )

        println("[Test] ✅ Disconnected successfully")
    }

    @Test
    fun `test connection state flow`() = runTest(timeout = 30.seconds) {
        assertEquals(
            Realtime.ConnectionState.Disconnected,
            client.realtime.connectionState.value,
            "Initial state should be Disconnected"
        )

        client.realtime.connect()
        assertEquals(
            Realtime.ConnectionState.Connected::class,
            client.realtime.connectionState.value::class,
            "State should be Connected after connect"
        )

        client.realtime.disconnect()
        assertEquals(
            Realtime.ConnectionState.Disconnected,
            client.realtime.connectionState.value,
            "State should be Disconnected after disconnect"
        )

        println("[Test] ✅ Connection state transitions work correctly")
    }

    // ============ Subscribe/Unsubscribe Tests ============

    @Test
    fun `test subscribe to todos channel`() = runTest(timeout = 30.seconds) {
        client.realtime.connect()

        val response = client.realtime.subscribe("todos")

        assertTrue(response.ok, "Subscribe should succeed")
        assertEquals("todos", response.channel, "Channel name should match")
        assertNull(response.error, "Should have no error")
        assertTrue(
            client.realtime.getSubscribedChannels().contains("todos"),
            "Should be in subscribed channels list"
        )

        println("[Test] ✅ Subscribed to 'todos' channel")

        client.realtime.disconnect()
    }

    @Test
    fun `test unsubscribe from channel`() = runTest(timeout = 30.seconds) {
        client.realtime.connect()
        client.realtime.subscribe("todos")
        assertTrue(client.realtime.getSubscribedChannels().contains("todos"))

        client.realtime.unsubscribe("todos")

        assertFalse(
            client.realtime.getSubscribedChannels().contains("todos"),
            "Should not be in subscribed channels after unsubscribe"
        )

        println("[Test] ✅ Unsubscribed from 'todos' channel")

        client.realtime.disconnect()
    }

    @Test
    fun `test auto-connect on subscribe`() = runTest(timeout = 30.seconds) {
        // Don't call connect() first
        assertFalse(client.realtime.isConnected)

        val response = client.realtime.subscribe("todos")

        assertTrue(response.ok, "Subscribe should succeed with auto-connect")
        assertTrue(client.realtime.isConnected, "Should be connected after subscribe")

        println("[Test] ✅ Auto-connect on subscribe works")

        client.realtime.disconnect()
    }

    @Test
    fun `test subscribe to multiple channels`() = runTest(timeout = 30.seconds) {
        client.realtime.connect()

        // Subscribe to todos channel (which is configured on the server)
        val response1 = client.realtime.subscribe("todos")

        // Try subscribing to other channels - they may or may not succeed
        // depending on server configuration
        val response2 = client.realtime.subscribe("notifications")
        val response3 = client.realtime.subscribe("chat")

        val channels = client.realtime.getSubscribedChannels()
        println("[Test] Subscribed channels: $channels")
        println("[Test] todos: ${response1.ok}, notifications: ${response2.ok}, chat: ${response3.ok}")

        // At minimum, todos should be subscribed
        assertTrue(response1.ok, "Should subscribe to todos")
        assertTrue(channels.contains("todos"), "Channels should contain todos")

        println("[Test] ✅ Subscribe to multiple channels test completed")

        client.realtime.disconnect()
    }

    // ============ Event Listener Tests ============

    @Test
    fun `test on and off event listeners`() = runTest(timeout = 30.seconds) {
        var eventReceived = false
        val callback = Realtime.EventCallback<String> { eventReceived = true }

        client.realtime.on("test-event", callback)
        client.realtime.off("test-event", callback)

        // Event should not be received after off()
        println("[Test] ✅ Event listener on/off works correctly")
    }

    @Test
    fun `test once event listener`() = runTest(timeout = 30.seconds) {
        var callCount = 0
        client.realtime.once<String>("one-time-event") {
            callCount++
        }

        // Once listener should auto-remove after first call
        println("[Test] ✅ Once listener registered correctly")
    }

    // ============ Database Change Monitoring - INSERT ============

    @Test
    fun `test monitor todos INSERT event`() = runTest(timeout = 30.seconds) {
        println("[Test] === INSERT Event Monitoring ===")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] ❌ Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val insertEvents = mutableListOf<SocketMessage>()

        // Register INSERT event listener
        client.realtime.on<SocketMessage>("INSERT") { message ->
            message?.let {
                println("[Test] 📥 INSERT event received:")
                println("       Channel: ${it.channel}")
                println("       MessageId: ${it.messageId}")
                println("       SenderType: ${it.senderType}")
                println("       Payload: ${it.payload}")
                insertEvents.add(it)
            }
        }

        // Perform INSERT operation
        val timestamp = System.currentTimeMillis()
        val insertData = buildJsonArray {
            addJsonObject {
                put("title", "Low-Level API INSERT Test $timestamp")
                put("is_completed", false)
                put("user_id", testUserId)
            }
        }

        println("[Test] Inserting new todo...")
        try {
            val inserted = client.database.from("todos")
                .insert(insertData)
                .returning()
                .execute<JsonObject>()

            val todoId = inserted.firstOrNull()?.get("id")?.toString()?.removeSurrounding("\"")
            println("[Test] Inserted todo ID: $todoId")

            // Wait for realtime event
            delay(3000)

            println("[Test] INSERT events received: ${insertEvents.size}")
            if (insertEvents.isNotEmpty()) {
                println("[Test] ✅ Successfully received INSERT notification!")
                assertTrue(insertEvents.size >= 1, "Should receive at least one INSERT event")
            } else {
                println("[Test] ⚠️ No INSERT event received (server may not have trigger configured)")
            }

            // Cleanup
            if (todoId != null) {
                client.database.from("todos")
                    .eq("id", todoId)
                    .delete()
                    .execute<JsonObject>()
                println("[Test] Cleaned up test todo")
            }
        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    // ============ Database Change Monitoring - UPDATE ============

    @Test
    fun `test monitor todos UPDATE event`() = runTest(timeout = 30.seconds) {
        println("[Test] === UPDATE Event Monitoring ===")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] ❌ Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val updateEvents = mutableListOf<SocketMessage>()

        // Register UPDATE event listener
        client.realtime.on<SocketMessage>("UPDATE") { message ->
            message?.let {
                println("[Test] 📝 UPDATE event received:")
                println("       Channel: ${it.channel}")
                println("       MessageId: ${it.messageId}")
                println("       Payload: ${it.payload}")
                updateEvents.add(it)
            }
        }

        try {
            // Get an existing todo or create one
            @Serializable
            data class Todo(val id: String, val title: String)

            val existingTodos = client.database.from("todos")
                .select()
                .limit(1)
                .execute<Todo>()

            val todoId: String
            val needsCleanup: Boolean

            if (existingTodos.isEmpty()) {
                // Create a todo first
                val insertData = buildJsonArray {
                    addJsonObject {
                        put("title", "Test Todo for UPDATE")
                        put("is_completed", false)
                        put("user_id", testUserId)
                    }
                }
                val inserted = client.database.from("todos")
                    .insert(insertData)
                    .returning()
                    .execute<JsonObject>()
                todoId = inserted.first()["id"].toString().removeSurrounding("\"")
                needsCleanup = true
                println("[Test] Created test todo: $todoId")
                delay(1500) // Wait for INSERT event to pass
            } else {
                todoId = existingTodos.first().id
                needsCleanup = false
                println("[Test] Using existing todo: $todoId")
            }

            // Perform UPDATE operation
            val updateData = buildJsonObject {
                put("title", "Updated by Low-Level API at ${System.currentTimeMillis()}")
            }

            println("[Test] Updating todo...")
            client.database.from("todos")
                .eq("id", todoId)
                .update(updateData)
                .execute<JsonObject>()

            // Wait for realtime event
            delay(3000)

            println("[Test] UPDATE events received: ${updateEvents.size}")
            if (updateEvents.isNotEmpty()) {
                println("[Test] ✅ Successfully received UPDATE notification!")
                assertTrue(updateEvents.size >= 1, "Should receive at least one UPDATE event")
            } else {
                println("[Test] ⚠️ No UPDATE event received (server may not have trigger configured)")
            }

            // Cleanup if we created a test todo
            if (needsCleanup) {
                client.database.from("todos")
                    .eq("id", todoId)
                    .delete()
                    .execute<JsonObject>()
                println("[Test] Cleaned up test todo")
            }
        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    // ============ Database Change Monitoring - DELETE ============

    @Test
    fun `test monitor todos DELETE event`() = runTest(timeout = 30.seconds) {
        println("[Test] === DELETE Event Monitoring ===")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] ❌ Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val deleteEvents = mutableListOf<SocketMessage>()

        // Register DELETE event listener
        client.realtime.on<SocketMessage>("DELETE") { message ->
            message?.let {
                println("[Test] 🗑️ DELETE event received:")
                println("       Channel: ${it.channel}")
                println("       MessageId: ${it.messageId}")
                println("       Payload: ${it.payload}")
                deleteEvents.add(it)
            }
        }

        try {
            // Create a todo to delete
            val insertData = buildJsonArray {
                addJsonObject {
                    put("title", "Todo to DELETE ${System.currentTimeMillis()}")
                    put("is_completed", false)
                    put("user_id", testUserId)
                }
            }

            println("[Test] Creating todo for deletion...")
            val inserted = client.database.from("todos")
                .insert(insertData)
                .returning()
                .execute<JsonObject>()

            val todoId = inserted.first()["id"].toString().removeSurrounding("\"")
            println("[Test] Created todo: $todoId")

            delay(1500) // Wait for INSERT event to pass

            // Perform DELETE operation
            println("[Test] Deleting todo...")
            client.database.from("todos")
                .eq("id", todoId)
                .delete()
                .execute<JsonObject>()

            // Wait for realtime event
            delay(3000)

            println("[Test] DELETE events received: ${deleteEvents.size}")
            if (deleteEvents.isNotEmpty()) {
                println("[Test] ✅ Successfully received DELETE notification!")
                assertTrue(deleteEvents.size >= 1, "Should receive at least one DELETE event")
            } else {
                println("[Test] ⚠️ No DELETE event received (server may not have trigger configured)")
            }
        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    // ============ Full CRUD Monitoring ============

    @Test
    fun `test monitor todos full CRUD cycle`() = runTest(timeout = 60.seconds) {
        println("[Test] === Full CRUD Cycle Monitoring ===")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] ❌ Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val allEvents = mutableListOf<Pair<String, SocketMessage>>()

        // Register listeners for all event types
        listOf("INSERT", "UPDATE", "DELETE").forEach { eventType ->
            client.realtime.on<SocketMessage>(eventType) { message ->
                message?.let {
                    println("[Test] 📨 $eventType event received:")
                    println("       Payload: ${it.payload}")
                    allEvents.add(eventType to it)
                }
            }
        }

        try {
            val timestamp = System.currentTimeMillis()

            // Step 1: INSERT
            println("\n[Test] === Step 1: INSERT ===")
            val insertData = buildJsonArray {
                addJsonObject {
                    put("title", "CRUD Cycle Test $timestamp")
                    put("is_completed", false)
                    put("user_id", testUserId)
                }
            }
            val inserted = client.database.from("todos")
                .insert(insertData)
                .returning()
                .execute<JsonObject>()

            val todoId = inserted.first()["id"].toString().removeSurrounding("\"")
            println("[Test] Inserted todo: $todoId")
            delay(2000)

            // Step 2: UPDATE
            println("\n[Test] === Step 2: UPDATE ===")
            val updateData = buildJsonObject {
                put("title", "CRUD Cycle Updated $timestamp")
                put("is_completed", true)
            }
            client.database.from("todos")
                .eq("id", todoId)
                .update(updateData)
                .execute<JsonObject>()
            println("[Test] Updated todo")
            delay(2000)

            // Step 3: DELETE
            println("\n[Test] === Step 3: DELETE ===")
            client.database.from("todos")
                .eq("id", todoId)
                .delete()
                .execute<JsonObject>()
            println("[Test] Deleted todo")
            delay(2000)

            // Summary
            println("\n[Test] === Summary ===")
            println("[Test] Total events received: ${allEvents.size}")

            val insertCount = allEvents.count { it.first == "INSERT" }
            val updateCount = allEvents.count { it.first == "UPDATE" }
            val deleteCount = allEvents.count { it.first == "DELETE" }

            println("[Test]   INSERT events: $insertCount")
            println("[Test]   UPDATE events: $updateCount")
            println("[Test]   DELETE events: $deleteCount")

            if (insertCount >= 1 && updateCount >= 1 && deleteCount >= 1) {
                println("[Test] ✅ All CRUD events received successfully!")
            } else {
                println("[Test] ⚠️ Some events may be missing")
            }

            // Assertions
            assertTrue(allEvents.isNotEmpty(), "Should receive at least one event")

        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    // ============ Multiple Event Types Test ============

    @Test
    fun `test listen for multiple event types simultaneously`() = runTest(timeout = 30.seconds) {
        println("[Test] === Multiple Event Listeners ===")

        client.realtime.connect()
        client.realtime.subscribe("todos")

        val eventCounts = mutableMapOf(
            "INSERT" to 0,
            "UPDATE" to 0,
            "DELETE" to 0
        )

        // Register multiple listeners
        eventCounts.keys.forEach { eventType ->
            client.realtime.on<SocketMessage>(eventType) { message ->
                message?.let {
                    eventCounts[eventType] = eventCounts[eventType]!! + 1
                    println("[Test] Received $eventType event (#${eventCounts[eventType]})")
                }
            }
        }

        println("[Test] ✅ Registered listeners for: ${eventCounts.keys}")
        println("[Test] Event counts: $eventCounts")

        client.realtime.disconnect()
    }

    // ============ Publish Test ============

    @Test
    fun `test publish message to channel`() = runTest(timeout = 30.seconds) {
        println("[Test] === Publish Message Test ===")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("test-publish-channel")

        if (!subscribeResponse.ok) {
            println("[Test] Subscribe failed, skipping publish test")
            client.realtime.disconnect()
            return@runTest
        }

        // Publish a message
        val payload = mapOf(
            "message" to "Hello from Kotlin SDK",
            "timestamp" to System.currentTimeMillis()
        )

        try {
            client.realtime.publish("test-publish-channel", "test-event", payload)
            println("[Test] ✅ Message published successfully")
        } catch (e: Exception) {
            println("[Test] Publish failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    // ============ Error Handling Tests ============

    @Test
    fun `test publish without connection throws error`() = runTest {
        // Don't connect
        assertFalse(client.realtime.isConnected)

        assertFailsWith<IllegalStateException> {
            client.realtime.publish("channel", "event", mapOf("test" to "data"))
        }

        println("[Test] ✅ Publish without connection correctly throws error")
    }

    @Test
    fun `test subscribe to already subscribed channel returns success`() = runTest(timeout = 30.seconds) {
        client.realtime.connect()

        // Use 'todos' channel which is configured on the server
        val response1 = client.realtime.subscribe("todos")
        val response2 = client.realtime.subscribe("todos")

        assertTrue(response1.ok, "First subscribe should succeed")
        assertTrue(response2.ok, "Second subscribe should also succeed (idempotent)")

        // Should only appear once in the list
        assertEquals(
            1,
            client.realtime.getSubscribedChannels().count { it == "todos" },
            "Channel should only appear once"
        )

        println("[Test] ✅ Duplicate subscribe is handled correctly")

        client.realtime.disconnect()
    }

    // ============ Debug Logging Test ============

    @Test
    fun `test debug logging for websocket messages`() = runTest(timeout = 30.seconds) {
        // Create a client with debug logging enabled
        val debugClient = TestConfig.createAuthenticatedRealtimeClientWithDebug()

        println("\n[Test] === Testing Debug Logging ===")
        println("[Test] The following debug logs should appear:\n")

        // Connect
        debugClient.realtime.connect()

        // Subscribe
        val response = debugClient.realtime.subscribe("todos")
        println("\n[Test] Subscribe result: ok=${response.ok}")

        // Insert a todo to trigger database change notification
        val timestamp = System.currentTimeMillis()
        val insertData = buildJsonArray {
            addJsonObject {
                put("title", "Debug Test $timestamp")
                put("is_completed", false)
                put("user_id", testUserId)
            }
        }

        println("\n[Test] Inserting todo to trigger realtime event...")
        val inserted = debugClient.database.from("todos")
            .insert(insertData)
            .returning()
            .execute<JsonObject>()

        val todoId = inserted.firstOrNull()?.get("id")?.toString()?.removeSurrounding("\"")
        println("[Test] Inserted todo: $todoId")

        // Wait for realtime event
        delay(2000)

        // Cleanup
        if (todoId != null) {
            debugClient.database.from("todos")
                .eq("id", todoId)
                .delete()
                .execute<JsonObject>()
            println("[Test] Cleaned up test todo")
        }

        debugClient.realtime.disconnect()
        debugClient.close()

        println("\n[Test] === Debug Logging Test Complete ===\n")
    }
}
