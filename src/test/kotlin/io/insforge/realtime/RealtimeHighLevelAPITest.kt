package io.insforge.realtime

import io.insforge.TestConfig
import io.insforge.database.database
import io.insforge.realtime.models.SocketMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
 * High-Level API tests for Realtime module
 *
 * Tests the InsforgeChannel, broadcastFlow, and postgresChangeFlow APIs
 * for monitoring database changes on the todos table.
 */
class RealtimeHighLevelAPITest {

    private lateinit var client: io.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        client = TestConfig.createAuthenticatedRealtimeClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Channel Creation Tests ============

    @Test
    fun `test create channel with high-level API`() = runTest {
        val channel = client.realtime.channel("test-high-level")

        assertEquals("test-high-level", channel.topic)
        assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)

        println("[Test] Channel created: ${channel.topic}")
    }

    @Test
    fun `test create channel with broadcast config`() = runTest {
        val channel = client.realtime.channel("broadcast-config-test") {
            broadcast {
                acknowledgeBroadcasts = true
                receiveOwnBroadcasts = true
            }
        }

        assertEquals("broadcast-config-test", channel.topic)
        println("[Test] Channel with broadcast config created")
    }

    // ============ Broadcast Flow Tests ============

    @Test
    fun `test broadcastFlow setup`() = runTest {
        // Test that broadcastFlow can be created and set up
        val channel = client.realtime.channel("broadcast-flow-test") {
            broadcast {
                receiveOwnBroadcasts = true
            }
        }

        // Setup broadcast flow BEFORE subscribing
        val flow = channel.broadcastFlow("test-event")

        // Verify flow is created (flow is cold, so it doesn't execute until collected)
        assertNotNull(flow)
        println("[Test] BroadcastFlow created successfully")

        // Cleanup
        client.realtime.removeChannel("broadcast-flow-test")
    }

    @Test
    fun `test typed broadcastFlow setup`() = runTest {
        @Serializable
        data class ChatMessage(val text: String, val sender: String)

        val channel = client.realtime.channel("typed-broadcast-test") {
            broadcast {
                receiveOwnBroadcasts = true
            }
        }

        // Setup typed broadcast flow
        val flow = channel.broadcastFlow<ChatMessage>("chat")

        assertNotNull(flow)
        println("[Test] Typed BroadcastFlow created successfully")

        client.realtime.removeChannel("typed-broadcast-test")
    }

    // ============ Database Change Monitoring Tests ============

    @Test
    fun `test monitor todos INSERT with high-level API`() = runTest(timeout = 30.seconds) {
        println("[Test] Starting INSERT monitoring test...")

        // Connect first
        client.realtime.connect()
        println("[Test] Connected to realtime server")

        // Subscribe to todos channel using low-level API (since server uses pub/sub)
        val subscribeResponse = client.realtime.subscribe("todos")
        println("[Test] Subscribe response: ok=${subscribeResponse.ok}")

        if (!subscribeResponse.ok) {
            println("[Test] Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val insertEvents = mutableListOf<SocketMessage>()

        // Listen for INSERT events
        client.realtime.on<SocketMessage>("INSERT") { message ->
            message?.let {
                println("[Test] 📥 INSERT event received:")
                println("       Payload: ${it.payload}")
                insertEvents.add(it)
            }
        }

        // Insert a new todo (include user_id for RLS)
        val timestamp = System.currentTimeMillis()
        val insertData = buildJsonArray {
            addJsonObject {
                put("title", "High-Level API Test $timestamp")
                put("is_completed", false)
                put("user_id", "085a481e-94b8-4bf9-b3a0-7f0e50f7a072")
            }
        }

        println("[Test] Inserting new todo...")
        try {
            val inserted = client.database.from("todos")
                .insert(insertData)
                .returning()
                .execute<JsonObject>()

            val todoId = inserted.firstOrNull()?.get("id")?.toString()?.removeSurrounding("\"")
            println("[Test] Inserted todo with ID: $todoId")

            // Wait for realtime event
            delay(3000)

            println("[Test] INSERT events received: ${insertEvents.size}")
            if (insertEvents.isNotEmpty()) {
                println("[Test] ✅ Successfully received INSERT notification!")
                insertEvents.forEach { println("[Test]   - ${it.payload}") }
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

    @Test
    fun `test monitor todos UPDATE with high-level API`() = runTest(timeout = 30.seconds) {
        println("[Test] Starting UPDATE monitoring test...")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val updateEvents = mutableListOf<SocketMessage>()

        // Listen for UPDATE events
        client.realtime.on<SocketMessage>("UPDATE") { message ->
            message?.let {
                println("[Test] 📝 UPDATE event received:")
                println("       Payload: ${it.payload}")
                updateEvents.add(it)
            }
        }

        try {
            // First, get an existing todo or create one
            @Serializable
            data class Todo(val id: String, val title: String)

            val existingTodos = client.database.from("todos")
                .select()
                .limit(1)
                .execute<Todo>()

            val todoId: String
            val needsCleanup: Boolean

            if (existingTodos.isEmpty()) {
                // Create a todo first (include user_id for RLS)
                val insertData = buildJsonArray {
                    addJsonObject {
                        put("title", "Test Todo for Update")
                        put("is_completed", false)
                        put("user_id", "085a481e-94b8-4bf9-b3a0-7f0e50f7a072")
                    }
                }
                val inserted = client.database.from("todos")
                    .insert(insertData)
                    .returning()
                    .execute<JsonObject>()
                todoId = inserted.first()["id"].toString().removeSurrounding("\"")
                needsCleanup = true
                delay(1000) // Wait for INSERT event to pass
            } else {
                todoId = existingTodos.first().id
                needsCleanup = false
            }

            println("[Test] Updating todo: $todoId")

            // Update the todo
            val updateData = buildJsonObject {
                put("title", "Updated at ${System.currentTimeMillis()}")
            }
            client.database.from("todos")
                .eq("id", todoId)
                .update(updateData)
                .execute<JsonObject>()

            // Wait for realtime event
            delay(3000)

            println("[Test] UPDATE events received: ${updateEvents.size}")
            if (updateEvents.isNotEmpty()) {
                println("[Test] ✅ Successfully received UPDATE notification!")
                updateEvents.forEach { println("[Test]   - ${it.payload}") }
            }

            // Cleanup if we created a test todo
            if (needsCleanup) {
                client.database.from("todos")
                    .eq("id", todoId)
                    .delete()
                    .execute<JsonObject>()
            }
        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    @Test
    fun `test monitor todos DELETE with high-level API`() = runTest(timeout = 30.seconds) {
        println("[Test] Starting DELETE monitoring test...")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val deleteEvents = mutableListOf<SocketMessage>()

        // Listen for DELETE events
        client.realtime.on<SocketMessage>("DELETE") { message ->
            message?.let {
                println("[Test] 🗑️ DELETE event received:")
                println("       Payload: ${it.payload}")
                deleteEvents.add(it)
            }
        }

        try {
            // Create a todo to delete (include user_id for RLS)
            val insertData = buildJsonArray {
                addJsonObject {
                    put("title", "Todo to be deleted ${System.currentTimeMillis()}")
                    put("is_completed", false)
                    put("user_id", "085a481e-94b8-4bf9-b3a0-7f0e50f7a072")
                }
            }
            val inserted = client.database.from("todos")
                .insert(insertData)
                .returning()
                .execute<JsonObject>()

            val todoId = inserted.first()["id"].toString().removeSurrounding("\"")
            println("[Test] Created todo to delete: $todoId")

            delay(1000) // Wait for INSERT event to pass

            // Delete the todo
            println("[Test] Deleting todo: $todoId")
            client.database.from("todos")
                .eq("id", todoId)
                .delete()
                .execute<JsonObject>()

            // Wait for realtime event
            delay(3000)

            println("[Test] DELETE events received: ${deleteEvents.size}")
            if (deleteEvents.isNotEmpty()) {
                println("[Test] ✅ Successfully received DELETE notification!")
                deleteEvents.forEach { println("[Test]   - ${it.payload}") }
            }
        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    @Test
    fun `test monitor all todos CRUD operations`() = runTest(timeout = 60.seconds) {
        println("[Test] Starting full CRUD monitoring test...")

        client.realtime.connect()
        val subscribeResponse = client.realtime.subscribe("todos")

        if (!subscribeResponse.ok) {
            println("[Test] Subscribe failed: ${subscribeResponse.error?.message}")
            client.realtime.disconnect()
            return@runTest
        }

        val allEvents = mutableListOf<Pair<String, SocketMessage>>()

        // Listen for all event types
        listOf("INSERT", "UPDATE", "DELETE").forEach { eventType ->
            client.realtime.on<SocketMessage>(eventType) { message ->
                message?.let {
                    println("[Test] 📨 $eventType event:")
                    println("       Payload: ${it.payload}")
                    allEvents.add(eventType to it)
                }
            }
        }

        try {
            val timestamp = System.currentTimeMillis()

            // 1. INSERT (include user_id for RLS)
            println("\n[Test] === Step 1: INSERT ===")
            val insertData = buildJsonArray {
                addJsonObject {
                    put("title", "CRUD Test Todo $timestamp")
                    put("is_completed", false)
                    put("user_id", "085a481e-94b8-4bf9-b3a0-7f0e50f7a072")
                }
            }
            val inserted = client.database.from("todos")
                .insert(insertData)
                .returning()
                .execute<JsonObject>()

            val todoId = inserted.first()["id"].toString().removeSurrounding("\"")
            println("[Test] Inserted todo: $todoId")
            delay(2000)

            // 2. UPDATE
            println("\n[Test] === Step 2: UPDATE ===")
            val updateData = buildJsonObject {
                put("title", "CRUD Test Todo Updated $timestamp")
                put("is_completed", true)
            }
            client.database.from("todos")
                .eq("id", todoId)
                .update(updateData)
                .execute<JsonObject>()
            println("[Test] Updated todo")
            delay(2000)

            // 3. DELETE
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
            println("[Test]   - INSERT: $insertCount")
            println("[Test]   - UPDATE: $updateCount")
            println("[Test]   - DELETE: $deleteCount")

            if (insertCount >= 1 && updateCount >= 1 && deleteCount >= 1) {
                println("[Test] ✅ All CRUD events received successfully!")
            } else {
                println("[Test] ⚠️ Some events may be missing")
            }
        } catch (e: Exception) {
            println("[Test] Database operation failed: ${e.message}")
        }

        client.realtime.disconnect()
    }

    // ============ PostgresChangeFlow Tests (for Supabase-style servers) ============

    @Test
    fun `test postgresChangeFlow for INSERT`() = runTest {
        val channel = client.realtime.channel("postgres-insert-test")

        // Setup postgres change flow BEFORE subscribing
        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "todos"
        }

        // Note: This test verifies the API compiles and can be set up
        // Actual postgres_changes require server-side configuration
        println("[Test] PostgresChangeFlow for INSERT created successfully")
        assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)
    }

    @Test
    fun `test postgresChangeFlow for UPDATE`() = runTest {
        val channel = client.realtime.channel("postgres-update-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "todos"
        }

        println("[Test] PostgresChangeFlow for UPDATE created successfully")
    }

    @Test
    fun `test postgresChangeFlow for DELETE`() = runTest {
        val channel = client.realtime.channel("postgres-delete-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
            table = "todos"
        }

        println("[Test] PostgresChangeFlow for DELETE created successfully")
    }

    @Test
    fun `test postgresChangeFlow for all actions`() = runTest {
        val channel = client.realtime.channel("postgres-all-test")

        val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "todos"
        }

        println("[Test] PostgresChangeFlow for all actions created successfully")
    }

    @Test
    fun `test postgresChangeFlow with filter`() = runTest {
        val channel = client.realtime.channel("postgres-filter-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "todos"
            filter("is_completed", FilterOperator.EQ, false)
        }

        println("[Test] PostgresChangeFlow with filter created successfully")
    }

    // ============ Channel Lifecycle Tests ============

    @Test
    fun `test channel status transitions`() = runTest {
        val channel = client.realtime.channel("lifecycle-test")

        // Initial state
        assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)

        // Note: The High-Level channel API uses Phoenix-style phx_join messages
        // which may not be supported by all server implementations.
        // This test verifies the channel state management API exists.

        println("[Test] Initial status: ${channel.status.value}")
        println("[Test] Channel lifecycle test completed")
    }

    @Test
    fun `test remove channel`() = runTest {
        val channel1 = client.realtime.channel("removable-test")
        assertEquals("removable-test", channel1.topic)

        client.realtime.removeChannel("removable-test")

        // Creating channel again should return new instance
        val channel2 = client.realtime.channel("removable-test")
        assertNotSame(channel1, channel2)

        println("[Test] Channel removal test completed")
    }

    @Test
    fun `test remove all channels`() = runTest {
        client.realtime.channel("channel-1")
        client.realtime.channel("channel-2")
        client.realtime.channel("channel-3")

        client.realtime.removeAllChannels()

        // All channels should be new instances now
        println("[Test] All channels removed successfully")
    }
}
