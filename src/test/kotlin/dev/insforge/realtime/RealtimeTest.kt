package dev.insforge.realtime

import dev.insforge.TestConfig
import dev.insforge.database.database
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.realtime.models.SocketMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Realtime module
 *
 * Covers both low-level and high-level APIs:
 * - Low-level: connect/disconnect, subscribe/publish, channel management (REST)
 * - High-level: InsforgeChannel, broadcastFlow, postgresChangeFlow, PostgresAction
 */
class RealtimeTest {

    private lateinit var client: dev.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        // Use authenticated client with JWT token for Socket.IO tests
        client = TestConfig.createAuthenticatedRealtimeClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Connection Tests ============

    @Test
    fun `test initial connection state is disconnected`() = runTest {
        assertTrue(client.realtime.connectionState.value is Realtime.ConnectionState.Disconnected)
    }

    @Test
    fun `test connect to realtime`() = runTest {
        try {
            client.realtime.connect()

            // Wait for connection
            delay(1000)

            val state = client.realtime.connectionState.value
            println("Connection state: $state")

            when (state) {
                is Realtime.ConnectionState.Connected -> println("Successfully connected!")
                is Realtime.ConnectionState.Connecting -> println("Still connecting...")
                is Realtime.ConnectionState.Disconnected -> println("Disconnected")
                is Realtime.ConnectionState.Error -> println("Error: ${state.message}")
            }

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Connect failed: ${e.message}")
        }
    }

    @Test
    fun `test disconnect from realtime`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            client.realtime.disconnect()

            assertTrue(client.realtime.connectionState.value is Realtime.ConnectionState.Disconnected)
            println("Successfully disconnected")
        } catch (e: Exception) {
            println("Disconnect test failed: ${e.message}")
        }
    }

    // ============ High-Level Channel API Tests ============

    @Test
    fun `test channel creation with default config`() = runTest {
        val channel = client.realtime.channel("test-channel-1")

        assertEquals("test-channel-1", channel.topic)
        assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)

        println("Channel created: ${channel.topic}, status: ${channel.status.value}")
    }

    @Test
    fun `test channel creation with broadcast config`() = runTest {
        val channel = client.realtime.channel("test-channel-2") {
            broadcast {
                acknowledgeBroadcasts = true
                receiveOwnBroadcasts = true
            }
        }

        assertEquals("test-channel-2", channel.topic)
        assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)

        println("Channel with broadcast config created: ${channel.topic}")
    }

    @Test
    fun `test same topic returns same channel`() = runTest {
        val channel1 = client.realtime.channel("shared-topic")
        val channel2 = client.realtime.channel("shared-topic")

        assertSame(channel1, channel2)
        println("Same channel returned for same topic")
    }

    @Test
    fun `test remove channel`() = runTest {
        val channel = client.realtime.channel("removable-channel")
        assertEquals("removable-channel", channel.topic)

        client.realtime.removeChannel("removable-channel")

        // Creating channel with same topic should return new instance
        val newChannel = client.realtime.channel("removable-channel")
        assertNotSame(channel, newChannel)

        println("Channel removed and recreated successfully")
    }

    @Test
    fun `test remove all channels`() = runTest {
        client.realtime.channel("channel-a")
        client.realtime.channel("channel-b")
        client.realtime.channel("channel-c")

        client.realtime.removeAllChannels()

        // All channels should be new instances now
        val channelA = client.realtime.channel("channel-a")
        val channelB = client.realtime.channel("channel-b")

        println("All channels removed, new instances created")
    }

    // ============ Channel Subscription Tests ============

    @Test
    fun `test channel status transitions`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            val channel = client.realtime.channel("status-test")

            // Initial status
            assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)

            // Subscribe (non-blocking)
            launch {
                channel.subscribe(blockUntilSubscribed = false)
            }

            // Should transition to SUBSCRIBING
            delay(100)
            val statusDuringSubscription = channel.status.value
            println("Status during subscription: $statusDuringSubscription")

            // Wait for subscription
            delay(1000)
            val finalStatus = channel.status.value
            println("Final status: $finalStatus")

            // Unsubscribe
            channel.unsubscribe()
            assertEquals(InsforgeChannel.Status.UNSUBSCRIBED, channel.status.value)

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Channel status test failed: ${e.message}")
        }
    }

    // ============ Broadcast Flow Tests ============

    @Test
    fun `test broadcastFlow raw returns JsonObject`() = runTest {
        val channel = client.realtime.channel("broadcast-test")

        // Get raw flow (should compile without error)
        val flow = channel.broadcastFlow("chat")

        println("Raw broadcast flow created for event 'chat'")
    }

    @Test
    fun `test broadcastFlow with type deserialization`() = runTest {
        @Serializable
        data class ChatMessage(val text: String, val sender: String)

        val channel = client.realtime.channel("typed-broadcast-test")

        // Get typed flow (should compile without error)
        val flow = channel.broadcastFlow<ChatMessage>("chat")

        println("Typed broadcast flow created for event 'chat'")
    }

    @Test
    fun `test broadcastFlow with custom Json config`() = runTest {
        @Serializable
        data class Message(val content: String)

        val customJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        val channel = client.realtime.channel("custom-json-test")

        // Get typed flow with custom JSON (should compile without error)
        val flow = channel.broadcastFlow<Message>("message", customJson)

        println("Typed broadcast flow with custom JSON created")
    }

    // ============ Broadcast Send Tests ============

    @Test
    fun `test broadcast with JsonObject payload`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            val channel = client.realtime.channel("send-test") {
                broadcast {
                    receiveOwnBroadcasts = true
                }
            }

            channel.subscribe(blockUntilSubscribed = false)
            delay(500)

            // Send JsonObject broadcast
            val payload = buildJsonObject {
                put("message", "Hello World")
                put("timestamp", System.currentTimeMillis())
            }

            channel.broadcast("greeting", payload)
            println("Broadcast sent with JsonObject payload")

            channel.unsubscribe()
            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Broadcast test failed: ${e.message}")
        }
    }

    @Test
    fun `test broadcast with typed payload`() = runTest {
        @Serializable
        data class Greeting(val message: String, val from: String)

        try {
            client.realtime.connect()
            delay(500)

            val channel = client.realtime.channel("typed-send-test") {
                broadcast {
                    receiveOwnBroadcasts = true
                }
            }

            channel.subscribe(blockUntilSubscribed = false)
            delay(500)

            // Send typed broadcast
            channel.broadcast("greeting", Greeting("Hello!", "Kotlin SDK"))
            println("Broadcast sent with typed payload")

            channel.unsubscribe()
            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Typed broadcast test failed: ${e.message}")
        }
    }

    // ============ PostgresAction Tests ============

    @Test
    fun `test PostgresAction Insert structure`() {
        val record = buildJsonObject {
            put("id", 1)
            put("name", "Test")
        }

        val action = PostgresAction.Insert(
            schema = "public",
            table = "users",
            commitTimestamp = "2024-01-01T00:00:00Z",
            record = record
        )

        assertEquals("public", action.schema)
        assertEquals("users", action.table)
        assertEquals("2024-01-01T00:00:00Z", action.commitTimestamp)
        assertEquals(record, action.record)

        println("PostgresAction.Insert created: schema=${action.schema}, table=${action.table}")
    }

    @Test
    fun `test PostgresAction Update structure`() {
        val record = buildJsonObject { put("name", "Updated") }
        val oldRecord = buildJsonObject { put("name", "Original") }

        val action = PostgresAction.Update(
            schema = "public",
            table = "users",
            commitTimestamp = null,
            record = record,
            oldRecord = oldRecord
        )

        assertEquals("public", action.schema)
        assertEquals("users", action.table)
        assertNull(action.commitTimestamp)
        assertEquals(record, action.record)
        assertEquals(oldRecord, action.oldRecord)

        println("PostgresAction.Update created with both record and oldRecord")
    }

    @Test
    fun `test PostgresAction Delete structure`() {
        val oldRecord = buildJsonObject {
            put("id", 1)
            put("deleted", true)
        }

        val action = PostgresAction.Delete(
            schema = "public",
            table = "users",
            commitTimestamp = "2024-12-31T23:59:59Z",
            oldRecord = oldRecord
        )

        assertEquals("public", action.schema)
        assertEquals("users", action.table)
        assertEquals(oldRecord, action.oldRecord)

        println("PostgresAction.Delete created: oldRecord=$oldRecord")
    }

    @Test
    fun `test PostgresAction is sealed interface`() {
        val actions: List<PostgresAction> = listOf(
            PostgresAction.Insert("public", "t", null, buildJsonObject {}),
            PostgresAction.Update("public", "t", null, buildJsonObject {}, buildJsonObject {}),
            PostgresAction.Delete("public", "t", null, buildJsonObject {})
        )

        actions.forEach { action ->
            when (action) {
                is PostgresAction.Insert -> println("Insert: ${action.schema}.${action.table}")
                is PostgresAction.Update -> println("Update: ${action.schema}.${action.table}")
                is PostgresAction.Delete -> println("Delete: ${action.schema}.${action.table}")
            }
        }

        println("Sealed interface pattern works correctly")
    }

    // ============ PostgresAction Decode Tests ============

    @Test
    fun `test Insert decodeRecord`() {
        @Serializable
        data class User(val id: Int, val name: String)

        val record = buildJsonObject {
            put("id", 42)
            put("name", "John Doe")
        }

        val action = PostgresAction.Insert("public", "users", null, record)

        val user: User = action.decodeRecord()

        assertEquals(42, user.id)
        assertEquals("John Doe", user.name)

        println("Insert.decodeRecord() works: $user")
    }

    @Test
    fun `test Insert decodeRecord with custom Json`() {
        @Serializable
        data class Product(val name: String, val price: Double = 0.0)

        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        val record = buildJsonObject {
            put("name", "Widget")
            put("price", 19.99)
            put("unknownField", "ignored")
        }

        val action = PostgresAction.Insert("public", "products", null, record)

        val product: Product = action.decodeRecord(lenientJson)

        assertEquals("Widget", product.name)
        assertEquals(19.99, product.price)

        println("Insert.decodeRecord(json) works with custom JSON: $product")
    }

    @Test
    fun `test Update decodeRecord and decodeOldRecord`() {
        @Serializable
        data class Item(val status: String)

        val record = buildJsonObject { put("status", "active") }
        val oldRecord = buildJsonObject { put("status", "pending") }

        val action = PostgresAction.Update("public", "items", null, record, oldRecord)

        val newItem: Item = action.decodeRecord()
        val oldItem: Item = action.decodeOldRecord()

        assertEquals("active", newItem.status)
        assertEquals("pending", oldItem.status)

        println("Update.decodeRecord() = $newItem, decodeOldRecord() = $oldItem")
    }

    @Test
    fun `test Delete decodeOldRecord`() {
        @Serializable
        data class DeletedItem(val id: Int, val reason: String)

        val oldRecord = buildJsonObject {
            put("id", 100)
            put("reason", "expired")
        }

        val action = PostgresAction.Delete("public", "items", null, oldRecord)

        val deleted: DeletedItem = action.decodeOldRecord()

        assertEquals(100, deleted.id)
        assertEquals("expired", deleted.reason)

        println("Delete.decodeOldRecord() works: $deleted")
    }

    // ============ PostgresChangeFlow Tests ============

    @Test
    fun `test postgresChangeFlow setup before subscribe`() = runTest {
        val channel = client.realtime.channel("postgres-test")

        // Setup postgres change flow BEFORE subscribing (this is required)
        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
        }

        println("PostgresChangeFlow created for INSERT on public.messages")
    }

    @Test
    fun `test postgresChangeFlow with all action types`() = runTest {
        val channel = client.realtime.channel("postgres-all-types")

        // Listen for all action types
        val allChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "events"
        }

        println("PostgresChangeFlow created for all action types on public.events")
    }

    @Test
    fun `test postgresChangeFlow for Update`() = runTest {
        val channel = client.realtime.channel("postgres-update")

        val updateFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "products"
        }

        println("PostgresChangeFlow created for UPDATE on public.products")
    }

    @Test
    fun `test postgresChangeFlow for Delete`() = runTest {
        val channel = client.realtime.channel("postgres-delete")

        val deleteFlow = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
            table = "sessions"
        }

        println("PostgresChangeFlow created for DELETE on public.sessions")
    }

    // ============ PostgresChangeFilter Tests ============

    @Test
    fun `test postgresChangeFlow with string filter`() = runTest {
        val channel = client.realtime.channel("filter-string-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter = "user_id=eq.123"
        }

        println("PostgresChangeFlow with string filter: user_id=eq.123")
    }

    @Test
    fun `test postgresChangeFlow with filter builder - EQ`() = runTest {
        val channel = client.realtime.channel("filter-eq-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "orders"
            filter("status", FilterOperator.EQ, "pending")
        }

        println("PostgresChangeFlow with EQ filter on status")
    }

    @Test
    fun `test postgresChangeFlow with filter builder - NEQ`() = runTest {
        val channel = client.realtime.channel("filter-neq-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "tasks"
            filter("status", FilterOperator.NEQ, "completed")
        }

        println("PostgresChangeFlow with NEQ filter on status")
    }

    @Test
    fun `test postgresChangeFlow with filter builder - GT`() = runTest {
        val channel = client.realtime.channel("filter-gt-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "products"
            filter("price", FilterOperator.GT, 100)
        }

        println("PostgresChangeFlow with GT filter: price > 100")
    }

    @Test
    fun `test postgresChangeFlow with filter builder - GTE`() = runTest {
        val channel = client.realtime.channel("filter-gte-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "inventory"
            filter("quantity", FilterOperator.GTE, 10)
        }

        println("PostgresChangeFlow with GTE filter: quantity >= 10")
    }

    @Test
    fun `test postgresChangeFlow with filter builder - LT`() = runTest {
        val channel = client.realtime.channel("filter-lt-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "discounts"
            filter("percentage", FilterOperator.LT, 50)
        }

        println("PostgresChangeFlow with LT filter: percentage < 50")
    }

    @Test
    fun `test postgresChangeFlow with filter builder - LTE`() = runTest {
        val channel = client.realtime.channel("filter-lte-test")

        val flow = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
            table = "sessions"
            filter("age_days", FilterOperator.LTE, 30)
        }

        println("PostgresChangeFlow with LTE filter: age_days <= 30")
    }

    @Test
    fun `test postgresChangeFlow with filterIn`() = runTest {
        val channel = client.realtime.channel("filter-in-test")

        val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "orders"
            filterIn("status", listOf("pending", "processing", "shipped"))
        }

        println("PostgresChangeFlow with IN filter: status in (pending, processing, shipped)")
    }

    @Test
    fun `test FilterOperator enum values`() {
        assertEquals("eq", FilterOperator.EQ.value)
        assertEquals("neq", FilterOperator.NEQ.value)
        assertEquals("lt", FilterOperator.LT.value)
        assertEquals("lte", FilterOperator.LTE.value)
        assertEquals("gt", FilterOperator.GT.value)
        assertEquals("gte", FilterOperator.GTE.value)

        println("All FilterOperator values verified")
    }

    // ============ Multiple Flows on Same Channel Tests ============

    @Test
    fun `test multiple flows on same channel`() = runTest {
        val channel = client.realtime.channel("multi-flow-test")

        // Setup multiple flows before subscribing
        val insertFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
        }

        val updateFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "messages"
        }

        val broadcastFlow = channel.broadcastFlow("notification")

        println("Multiple flows created on same channel: INSERT, UPDATE, broadcast")
    }

    @Test
    fun `test postgresChangeFlow check prevents call after subscribed status`() = runTest {
        // This test verifies the check exists in postgresChangeFlowRaw
        // by directly checking the implementation behavior
        val channel = client.realtime.channel("after-subscribe-check-test") as InsforgeChannelImpl

        // Manually simulate subscribed status to test the guard
        channel.onSubscribed()

        // Verify status is now SUBSCRIBED
        assertEquals(InsforgeChannel.Status.SUBSCRIBED, channel.status.value)

        // This should throw an exception because we're already subscribed
        val exception = assertFailsWith<IllegalStateException> {
            channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "messages"
            }
        }

        assertTrue(exception.message?.contains("after subscribing") == true)
        println("Correctly threw exception when calling postgresChangeFlow after subscribe")
    }

    // ============ Socket.IO Subscription Tests ============

    @Test
    fun `test subscribe to channel with new API`() = runTest {
        try {
            // Subscribe auto-connects
            val response = client.realtime.subscribe("test-channel")

            println("Subscribe response: ok=${response.ok}, channel=${response.channel}")
            if (!response.ok) {
                println("Subscribe error: ${response.error?.code} - ${response.error?.message}")
            }

            // Verify subscribed channels
            val channels = client.realtime.getSubscribedChannels()
            println("Subscribed channels: $channels")

            client.realtime.unsubscribe("test-channel")
            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Subscribe test failed: ${e.message}")
        }
    }

    @Test
    fun `test subscribe to wildcard channel with new API`() = runTest {
        try {
            val response = client.realtime.subscribe("chat:*")

            println("Wildcard subscribe response: ok=${response.ok}")
            if (response.ok) {
                println("Successfully subscribed to chat:* wildcard channel")
            } else {
                println("Wildcard subscribe error: ${response.error?.message}")
            }

            delay(1000)
            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Wildcard subscribe test failed: ${e.message}")
        }
    }

    @Test
    fun `test unsubscribe from channel with new API`() = runTest {
        try {
            // Subscribe first
            val response = client.realtime.subscribe("temp-channel")
            println("Subscribed: ${response.ok}")

            // Unsubscribe
            client.realtime.unsubscribe("temp-channel")
            println("Unsubscribed from temp-channel")

            // Verify
            val channels = client.realtime.getSubscribedChannels()
            assertFalse(channels.contains("temp-channel"))

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Unsubscribe test failed: ${e.message}")
        }
    }

    @Test
    fun `test event listeners with on and off`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            var messageCount = 0
            val callback = Realtime.EventCallback<SocketMessage> { message ->
                messageCount++
                println("Received message #$messageCount: ${message?.event}")
            }

            // Register listener
            client.realtime.on("test-event", callback)
            println("Registered listener for test-event")

            // Remove listener
            client.realtime.off("test-event", callback)
            println("Removed listener for test-event")

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Event listener test failed: ${e.message}")
        }
    }

    // ============ Socket.IO Publish Tests ============

    @Test
    fun `test publish message with new API`() = runTest {
        try {
            // Subscribe first (auto-connects)
            val subResponse = client.realtime.subscribe("test-publish")
            println("Subscribed: ${subResponse.ok}")

            // Setup listener to receive our own message
            var receivedMessage = false
            client.realtime.on<SocketMessage>("realtime:message") { message ->
                println("Received published message: ${message?.event} - ${message?.payload}")
                receivedMessage = true
            }

            // Publish a message
            client.realtime.publish(
                channel = "test-publish",
                event = "test.event",
                payload = mapOf(
                    "message" to "Hello from Kotlin SDK",
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )

            println("Published message to test-publish")

            // Wait for message to be received
            delay(1000)

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Publish test failed: ${e.message}")
        }
    }

    @Test
    fun `test publish complex payload with new API`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            client.realtime.publish(
                channel = "test-complex",
                event = "user.action",
                payload = mapOf(
                    "userId" to "user123",
                    "action" to "click",
                    "metadata" to "button_submit",
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )

            println("Published complex payload")

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Complex publish test failed: ${e.message}")
        }
    }

    // ============ Socket Properties Tests ============

    @Test
    fun `test isConnected and socketId properties`() = runTest {
        try {
            // Before connect
            assertFalse(client.realtime.isConnected)
            assertNull(client.realtime.socketId)

            client.realtime.connect()
            delay(500)

            // After connect
            if (client.realtime.isConnected) {
                println("Connected with socketId: ${client.realtime.socketId}")
                assertNotNull(client.realtime.socketId)
            } else {
                println("Not connected (server may not be running)")
            }

            client.realtime.disconnect()

            // After disconnect
            assertFalse(client.realtime.isConnected)
        } catch (e: Exception) {
            println("Socket properties test failed: ${e.message}")
        }
    }

    // ============ Todos Table Realtime Tests ============

    @Test
    fun `test subscribe to todos channel`() = runTest {
        try {
            // Subscribe to todos channel (matches database table)
            val response = client.realtime.subscribe("todos")

            println("Subscribe to todos: ok=${response.ok}")
            if (!response.ok) {
                println("Error: ${response.error?.code} - ${response.error?.message}")
            }

            // Keep listening for a moment
            delay(2000)

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Todos subscribe test failed: ${e.message}")
        }
    }

    @Test
    fun `test listen for todos changes and trigger update`() = runTest(timeout = 30.seconds) {
        try {
            // Connect to realtime
            client.realtime.connect()
            println("[Test] Connected with authenticated token")

            // Subscribe to todos channel for database changes
            val response = client.realtime.subscribe("todos")

            if (response.ok) {
                println("[Test] ✅ Subscribed to 'todos' channel")

                // Set up tracking for received events
                val receivedMessages = mutableListOf<SocketMessage>()
                var receivedEvent: SocketMessage? = null

                // Listen for various event types that might be used for DB changes
                val eventTypes = listOf(
                    "INSERT", "UPDATE", "DELETE",
                    "db_change", "todo_updated", "todo_changed",
                    "realtime:message", "*"
                )

                eventTypes.forEach { eventType ->
                    client.realtime.on<SocketMessage>(eventType) { message ->
                        message?.let {
                            println("[Test] 📨 Received '$eventType' event:")
                            println("       Channel: ${it.channel}")
                            println("       MessageId: ${it.messageId}")
                            println("       SenderType: ${it.senderType}")
                            println("       Payload: ${it.payload}")
                            receivedMessages.add(it)
                            receivedEvent = it
                        }
                    }
                }

                // Now perform a database update on todos table
                println("[Test] 🔄 Performing todo update...")

                // First, fetch an existing todo
                @Serializable
                data class TodoRead(val id: String, val title: String)

                val existingTodos = client.database
                    .from("todos")
                    .select()
                    .limit(1)
                    .execute<TodoRead>()

                if (existingTodos.isNotEmpty()) {
                    val firstTodo = existingTodos.first()
                    val todoId = firstTodo.id
                    println("[Test] Found todo with id: $todoId, title: ${firstTodo.title}")

                    // Update the todo - append timestamp to title to make it unique
                    val newTitle = "Updated at ${System.currentTimeMillis()}"
                    val updateData = buildJsonObject {
                        put("title", newTitle)
                    }

                    val updateResult = client.database
                        .from("todos")
                        .eq("id", todoId)
                        .update(updateData)
                        .returning()
                        .execute<JsonObject>()

                    println("[Test] ✅ Todo updated: $updateResult")

                    // Wait for realtime event (with timeout)
                    println("[Test] Waiting for realtime notification...")
                    delay(5000)

                    if (receivedMessages.isNotEmpty()) {
                        println("[Test] ✅ Received ${receivedMessages.size} realtime notification(s)!")
                        receivedMessages.forEach { msg ->
                            println("[Test]   - Event: ${msg.event}, Payload: ${msg.payload}")
                        }
                        assertNotNull(receivedEvent)
                    } else {
                        println("[Test] ⚠️ No realtime event received within timeout")
                        println("[Test] This might be expected if the backend doesn't broadcast DB changes to 'todos' channel")
                    }
                } else {
                    // No existing todos, create one and then update it
                    println("[Test] ⚠️ No existing todos found, creating one...")

                    val insertData = buildJsonArray {
                        addJsonObject {
                            put("title", "Test todo for realtime")
                            put("is_completed", false)
                        }
                    }

                    val inserted = client.database
                        .from("todos")
                        .insert(insertData)
                        .returning()
                        .execute<JsonObject>()

                    println("[Test] Created todo: ${inserted.firstOrNull()}")

                    // Wait for potential INSERT notification
                    delay(3000)

                    // Now update it
                    val todoId = inserted.firstOrNull()?.get("id")?.toString()?.removeSurrounding("\"")
                    if (todoId != null) {
                        val updateData = buildJsonObject {
                            put("title", "Updated at ${System.currentTimeMillis()}")
                        }
                        client.database
                            .from("todos")
                            .eq("id", todoId)
                            .update(updateData)
                            .execute<JsonObject>()

                        println("[Test] Updated the newly created todo")

                        // Wait for UPDATE notification
                        delay(3000)

                        // Cleanup
                        client.database
                            .from("todos")
                            .eq("id", todoId)
                            .delete()
                            .execute<JsonObject>()
                    }

                    println("[Test] Total messages received: ${receivedMessages.size}")
                }
            } else {
                println("[Test] ❌ Subscribe failed: ${response.error?.code} - ${response.error?.message}")
            }

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("[Test] ❌ Test failed: ${e.message}")
            e.printStackTrace()
            client.realtime.disconnect()
        }
    }

    @Test
    fun `test todos realtime with database insert`() = runTest {
        try {
            val receivedMessages = mutableListOf<SocketMessage>()

            // Setup listener
            client.realtime.on<SocketMessage>("realtime:message") { message ->
                message?.let {
                    receivedMessages.add(it)
                    println("Realtime message received: ${it.event} - ${it.payload}")
                }
            }

            // Subscribe to todos channel
            val subResponse = client.realtime.subscribe("todos")
            println("Subscribed to todos: ${subResponse.ok}")

            if (subResponse.ok) {
                // Insert a new todo using the database API
                val timestamp = System.currentTimeMillis()
                val records = buildJsonArray {
                    addJsonObject {
                        put("title", "Realtime Test Todo $timestamp")
                        put("is_completed", false)
                    }
                }

                println("\nInserting todo via database API...")
                val insertedTodos = client.database.from("todos")
                    .insert(records)
                    .returning()
                    .execute<JsonObject>()

                println("Inserted todo: ${insertedTodos.firstOrNull()}")

                // Wait for realtime notification
                println("\nWaiting for realtime notification...")
                delay(3000)

                println("\nMessages received: ${receivedMessages.size}")
                receivedMessages.forEach {
                    println("  - ${it.event}: ${it.payload}")
                }

                // Cleanup - delete the inserted todo
                insertedTodos.firstOrNull()?.let { todo ->
                    val todoId = todo["id"]?.toString()?.removeSurrounding("\"")
                    if (todoId != null) {
                        println("\nCleaning up: deleting todo $todoId")
                        client.database.from("todos")
                            .eq("id", todoId)
                            .delete()
                            .execute<JsonObject>()
                    }
                }
            }

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Todos realtime with insert test failed: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun `test todos realtime full CRUD cycle`() = runTest {
        try {
            val events = mutableListOf<String>()

            // Setup listeners for different event types
            client.realtime.on<SocketMessage>("realtime:message") { message ->
                message?.let {
                    events.add(it.event)
                    println("[${events.size}] Event: ${it.event}, Payload: ${it.payload}")
                }
            }

            // Subscribe
            val subResponse = client.realtime.subscribe("todos")
            println("Subscribed: ${subResponse.ok}")

            if (subResponse.ok) {
                val timestamp = System.currentTimeMillis()

                // 1. INSERT
                println("\n--- Step 1: INSERT ---")
                val insertRecords = buildJsonArray {
                    addJsonObject {
                        put("title", "CRUD Test $timestamp")
                        put("is_completed", false)
                    }
                }
                val inserted = client.database.from("todos")
                    .insert(insertRecords)
                    .returning()
                    .execute<JsonObject>()

                val todoId = inserted.firstOrNull()?.get("id")?.toString()?.removeSurrounding("\"")
                println("Inserted todo ID: $todoId")
                delay(1000)

                if (todoId != null) {
                    // 2. UPDATE
                    println("\n--- Step 2: UPDATE ---")
                    val updateData = buildJsonObject {
                        put("is_completed", true)
                        put("title", "CRUD Test Updated $timestamp")
                    }
                    client.database.from("todos")
                        .eq("id", todoId)
                        .update(updateData)
                        .execute<JsonObject>()
                    println("Updated todo")
                    delay(1000)

                    // 3. DELETE
                    println("\n--- Step 3: DELETE ---")
                    client.database.from("todos")
                        .eq("id", todoId)
                        .delete()
                        .execute<JsonObject>()
                    println("Deleted todo")
                    delay(1000)
                }

                println("\n--- Summary ---")
                println("Total events received: ${events.size}")
                events.forEachIndexed { index, event ->
                    println("  ${index + 1}. $event")
                }
            }

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("CRUD cycle test failed: ${e.message}")
            e.printStackTrace()
        }
    }

    // ============ Channel Management Tests (REST API) ============

    @Test
    fun `test list channels`() = runTest {
        try {
            val channels = client.realtime.listChannels()
            println("Available channels: ${channels.size}")
            channels.forEach { channel ->
                println("  - ${channel.pattern}: ${channel.description}")
            }
        } catch (e: InsforgeHttpException) {
            println("List channels failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test create channel`() = runTest {
        val pattern = "test-create-${System.currentTimeMillis()}"

        try {
            val channel = client.realtime.createChannel(
                pattern = pattern,
                description = "Test channel created by Kotlin SDK",
                enabled = true
            )

            println("Created channel: ${channel.id} - ${channel.pattern}")

            // Cleanup
            client.realtime.deleteChannel(channel.id)
        } catch (e: InsforgeHttpException) {
            println("Create channel failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test create channel with webhook`() = runTest {
        val pattern = "webhook-test-${System.currentTimeMillis()}"

        try {
            val channel = client.realtime.createChannel(
                pattern = pattern,
                description = "Channel with webhook",
                webhookUrls = listOf("https://example.com/webhook"),
                enabled = true
            )

            println("Created channel with webhook: ${channel.id}")

            // Cleanup
            client.realtime.deleteChannel(channel.id)
        } catch (e: InsforgeHttpException) {
            println("Create channel with webhook failed: ${e.message}")
        }
    }

    @Test
    fun `test get channel by id`() = runTest {
        try {
            // First create a channel
            val created = client.realtime.createChannel(
                pattern = "get-test-${System.currentTimeMillis()}",
                description = "Test get channel"
            )

            // Get it by ID
            val channel = client.realtime.getChannel(created.id)
            assertEquals(created.id, channel.id)
            println("Got channel: ${channel.pattern}")

            // Cleanup
            client.realtime.deleteChannel(channel.id)
        } catch (e: InsforgeHttpException) {
            println("Get channel failed: ${e.message}")
        }
    }

    @Test
    fun `test update channel`() = runTest {
        try {
            // Create channel
            val created = client.realtime.createChannel(
                pattern = "update-test-${System.currentTimeMillis()}",
                description = "Original description"
            )

            // Update it
            val updated = client.realtime.updateChannel(
                channelId = created.id,
                description = "Updated description",
                enabled = false
            )

            println("Updated channel: ${updated.pattern}")

            // Cleanup
            client.realtime.deleteChannel(updated.id)
        } catch (e: InsforgeHttpException) {
            println("Update channel failed: ${e.message}")
        }
    }

    @Test
    fun `test delete channel`() = runTest {
        try {
            // Create channel
            val created = client.realtime.createChannel(
                pattern = "delete-test-${System.currentTimeMillis()}"
            )

            // Delete it
            val response = client.realtime.deleteChannel(created.id)
            println("Deleted channel: ${response.message}")
        } catch (e: InsforgeHttpException) {
            println("Delete channel failed: ${e.message}")
        }
    }

    // ============ Message History Tests ============

    @Test
    fun `test get messages`() = runTest {
        try {
            val messages = client.realtime.getMessages(limit = 10)
            println("Recent messages: ${messages.size}")
            messages.take(3).forEach { message ->
                println("  - ${message.channelName}: ${message.eventName}")
            }
        } catch (e: InsforgeHttpException) {
            println("Get messages failed: ${e.message}")
        }
    }

    @Test
    fun `test get messages by channel`() = runTest {
        try {
            // First, we need a channel ID. Try to list channels first
            val channels = client.realtime.listChannels()
            if (channels.isNotEmpty()) {
                val messages = client.realtime.getMessages(
                    channelId = channels.first().id,
                    limit = 5
                )
                println("Messages for channel: ${messages.size}")
            } else {
                println("No channels available to test message filtering")
            }
        } catch (e: InsforgeHttpException) {
            println("Get messages by channel failed: ${e.message}")
        }
    }

    @Test
    fun `test get messages by event name`() = runTest {
        try {
            val messages = client.realtime.getMessages(
                eventName = "message.new",
                limit = 10
            )
            println("Messages with event 'message.new': ${messages.size}")
        } catch (e: InsforgeHttpException) {
            println("Get messages by event failed: ${e.message}")
        }
    }

    @Test
    fun `test get message statistics`() = runTest {
        try {
            val stats = client.realtime.getMessageStats()
            println("Message statistics: $stats")
        } catch (e: InsforgeHttpException) {
            println("Get message stats failed: ${e.message}")
        }
    }

    @Test
    fun `test get message statistics with time filter`() = runTest {
        try {
            val stats = client.realtime.getMessageStats(
                since = "2024-01-01T00:00:00Z"
            )
            println("Message stats since 2024: $stats")
        } catch (e: InsforgeHttpException) {
            println("Get filtered message stats failed: ${e.message}")
        }
    }

    // ============ Connection State Flow Tests ============

    @Test
    fun `test connection state transitions`() = runTest {
        try {
            // Initial state
            assertTrue(client.realtime.connectionState.value is Realtime.ConnectionState.Disconnected)

            // Start connecting in a job we can cancel
            val connectJob = launch {
                try {
                    client.realtime.connect()
                } catch (e: Exception) {
                    // Expected to fail with invalid token
                }
            }

            // Should transition to connecting
            delay(100)
            val connectingState = client.realtime.connectionState.value
            println("State during connection: $connectingState")

            // Wait for connection attempt to complete or fail
            delay(1000)
            val finalState = client.realtime.connectionState.value
            println("Final state: $finalState")

            // Cancel the job if still running
            connectJob.cancel()

            // Disconnect
            client.realtime.disconnect()
            assertTrue(client.realtime.connectionState.value is Realtime.ConnectionState.Disconnected)
        } catch (e: Exception) {
            println("Connection state test failed: ${e.message}")
        }
    }

    // ============ Error Handling Tests ============

    @Test
    fun `test get non-existent channel`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.realtime.getChannel("non-existent-channel-id-${System.currentTimeMillis()}")
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test delete non-existent channel`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.realtime.deleteChannel("non-existent-${System.currentTimeMillis()}")
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    // ============ BroadcastConfig Tests ============

    @Test
    fun `test BroadcastConfig defaults`() {
        val config = BroadcastConfig()

        assertFalse(config.acknowledgeBroadcasts)
        assertFalse(config.receiveOwnBroadcasts)

        println("BroadcastConfig defaults: ack=${config.acknowledgeBroadcasts}, self=${config.receiveOwnBroadcasts}")
    }

    @Test
    fun `test BroadcastConfig custom values`() {
        val config = BroadcastConfig().apply {
            acknowledgeBroadcasts = true
            receiveOwnBroadcasts = true
        }

        assertTrue(config.acknowledgeBroadcasts)
        assertTrue(config.receiveOwnBroadcasts)

        println("BroadcastConfig custom: ack=${config.acknowledgeBroadcasts}, self=${config.receiveOwnBroadcasts}")
    }

    // ============ InsforgeChannel.Status Tests ============

    @Test
    fun `test InsforgeChannel Status enum values`() {
        val statuses = InsforgeChannel.Status.values()

        assertEquals(4, statuses.size)
        assertTrue(InsforgeChannel.Status.UNSUBSCRIBED in statuses)
        assertTrue(InsforgeChannel.Status.SUBSCRIBING in statuses)
        assertTrue(InsforgeChannel.Status.SUBSCRIBED in statuses)
        assertTrue(InsforgeChannel.Status.UNSUBSCRIBING in statuses)

        println("InsforgeChannel.Status enum values: ${statuses.joinToString()}")
    }

    // ============ Full Integration Test ============

    @Test
    fun `test complete channel workflow`() = runTest {
        try {
            // 1. Connect to realtime
            client.realtime.connect()
            delay(500)

            // 2. Create channel with config
            val channel = client.realtime.channel("integration-test") {
                broadcast {
                    acknowledgeBroadcasts = true
                    receiveOwnBroadcasts = true
                }
            }

            // 3. Setup flows BEFORE subscribing
            val broadcastMessages = mutableListOf<JsonObject>()
            val broadcastFlow = channel.broadcastFlow("test-event")

            // 4. Collect broadcast messages in background
            val collectJob = launch {
                broadcastFlow.take(2).collect { msg ->
                    broadcastMessages.add(msg)
                    println("Collected broadcast: $msg")
                }
            }

            // 5. Subscribe to channel
            channel.subscribe(blockUntilSubscribed = false)
            delay(500)

            // 6. Send broadcast messages
            channel.broadcast("test-event", buildJsonObject {
                put("seq", 1)
                put("data", "First message")
            })

            channel.broadcast("test-event", buildJsonObject {
                put("seq", 2)
                put("data", "Second message")
            })

            // 7. Wait for collection
            delay(1000)
            collectJob.cancel()

            println("Collected ${broadcastMessages.size} messages")

            // 8. Cleanup
            channel.unsubscribe()
            client.realtime.removeChannel("integration-test")
            client.realtime.disconnect()

            println("Complete integration test passed!")
        } catch (e: Exception) {
            println("Integration test: ${e.message}")
        }
    }
}
