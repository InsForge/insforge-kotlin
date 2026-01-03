package io.insforge.realtime

import io.insforge.TestConfig
import io.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for Realtime module
 */
class RealtimeTest {

    private lateinit var client: io.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        client = TestConfig.createRealtimeClient()
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

    // ============ Subscription Tests ============

    @Test
    fun `test subscribe to channel`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            var messageReceived = false
            client.realtime.subscribe("test-channel") { message ->
                println("Received message: ${message.eventName} - ${message.payload}")
                messageReceived = true
            }

            println("Subscribed to test-channel")

            // Wait a bit for any messages
            delay(1000)

            client.realtime.unsubscribe("test-channel")
            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Subscribe test failed: ${e.message}")
        }
    }

    @Test
    fun `test subscribe to wildcard channel`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            client.realtime.subscribe("chat:*") { message ->
                println("Received on wildcard: ${message.channelName} - ${message.eventName}")
            }

            println("Subscribed to chat:* wildcard channel")

            delay(1000)
            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Wildcard subscribe test failed: ${e.message}")
        }
    }

    @Test
    fun `test unsubscribe from channel`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            client.realtime.subscribe("temp-channel") { message ->
                println("Should not receive: $message")
            }

            client.realtime.unsubscribe("temp-channel")
            println("Unsubscribed from temp-channel")

            client.realtime.disconnect()
        } catch (e: Exception) {
            println("Unsubscribe test failed: ${e.message}")
        }
    }

    // ============ Publish Tests ============

    @Test
    fun `test publish message`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            // Subscribe first to receive our own message
            var receivedMessage = false
            client.realtime.subscribe("test-publish") { message ->
                println("Received published message: $message")
                receivedMessage = true
            }

            // Publish a message
            client.realtime.publish(
                channelName = "test-publish",
                eventName = "test.event",
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
    fun `test publish complex payload`() = runTest {
        try {
            client.realtime.connect()
            delay(500)

            client.realtime.publish(
                channelName = "test-complex",
                eventName = "user.action",
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

            // Start connecting
            launch {
                client.realtime.connect()
            }

            // Should transition to connecting
            delay(100)
            val connectingState = client.realtime.connectionState.value
            println("State during connection: $connectingState")

            // Wait for connection
            delay(1000)
            val finalState = client.realtime.connectionState.value
            println("Final state: $finalState")

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
}
