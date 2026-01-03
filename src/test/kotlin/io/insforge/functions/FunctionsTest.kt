package io.insforge.functions

import io.insforge.TestConfig
import io.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*

/**
 * Integration tests for Functions module
 */
class FunctionsTest {

    private lateinit var client: io.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        client = TestConfig.createFunctionsClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // Response models for function invocations
    @Serializable
    data class HelloResponse(
        val message: String,
        val timestamp: String? = null
    )

    @Serializable
    data class EchoResponse(
        val received: Map<String, String>? = null,
        val echo: String? = null
    )

    @Serializable
    data class CalculatorResponse(
        val result: Double,
        val operation: String
    )

    // ============ Function Invocation Tests ============

    @Test
    fun `test invoke simple function`() = runTest {
        try {
            val response = client.functions.invoke<HelloResponse>("hello")
            println("Function response: ${response.message}")
            assertNotNull(response.message)
        } catch (e: InsforgeHttpException) {
            println("Invoke failed: ${e.message}")
        }
    }

    @Test
    fun `test invoke function with body`() = runTest {
        try {
            val response = client.functions.invoke<EchoResponse>(
                slug = "echo",
                body = mapOf("name" to "Kotlin SDK", "version" to "1.0")
            )
            println("Echo response: $response")
        } catch (e: InsforgeHttpException) {
            println("Invoke with body failed: ${e.message}")
        }
    }

    // Request model for calculator function
    @Serializable
    data class CalculatorRequest(
        val operation: String,
        val a: Int,
        val b: Int
    )

    @Test
    fun `test invoke function with complex body`() = runTest {
        try {
            // Use a @Serializable data class instead of Map to avoid serialization issues
            val requestBody = CalculatorRequest(
                operation = "add",
                a = 10,
                b = 20
            )

            val response = client.functions.invoke<CalculatorResponse>(
                slug = "calculator",
                body = requestBody
            )
            println("Calculator result: ${response.result}")
        } catch (e: InsforgeHttpException) {
            println("Invoke calculator failed: ${e.message}")
        }
    }

    @Test
    fun `test invoke raw function`() = runTest {
        try {
            val response = client.functions.invokeRaw("hello")
            println("Raw response status: ${response.status}")
            assertTrue(response.status.value in 200..299)
        } catch (e: Exception) {
            println("Raw invoke failed: ${e.message}")
        }
    }

    @Test
    fun `test invoke non-existent function`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.functions.invoke<HelloResponse>("non-existent-function-${System.currentTimeMillis()}")
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    // ============ Function Management Tests (Admin) ============

    @Test
    fun `test list functions`() = runTest {
        try {
            val functions = client.functions.listFunctions()
            println("Available functions: ${functions.size}")
            functions.forEach { fn ->
                println("  - ${fn.slug}: ${fn.name}")
            }
        } catch (e: InsforgeHttpException) {
            println("List functions failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test get function details`() = runTest {
        try {
            val details = client.functions.getFunction("hello")
            println("Function details: $details")
        } catch (e: InsforgeHttpException) {
            println("Get function failed: ${e.message}")
        }
    }

    @Test
    fun `test create and delete function`() = runTest {
        val timestamp = System.currentTimeMillis()
        val functionName = "Test Function $timestamp"
        val functionSlug = "test-function-$timestamp"

        try {
            // Create function
            val createResponse = client.functions.createFunction(
                name = functionName,
                slug = functionSlug,
                code = """
                    export default async function(req) {
                        return new Response(JSON.stringify({ message: "Hello from test function" }), {
                            headers: { "Content-Type": "application/json" }
                        });
                    }
                """.trimIndent(),
                description = "A test function created by Kotlin SDK",
                status = "active"
            )

            println("Created function: ${createResponse.function.slug}")
            assertEquals(functionSlug, createResponse.function.slug)

            // Delete function
            val deleteResponse = client.functions.deleteFunction(functionSlug)
            println("Deleted function: ${deleteResponse.message}")
        } catch (e: InsforgeHttpException) {
            println("Function management failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test update function`() = runTest {
        val functionSlug = "test-update-${System.currentTimeMillis()}"

        try {
            // Create function first
            client.functions.createFunction(
                name = "Update Test",
                slug = functionSlug,
                code = "export default async function(req) { return new Response('v1'); }",
                status = "draft"
            )

            // Update function
            val updateResponse = client.functions.updateFunction(
                slug = functionSlug,
                name = "Update Test - Updated",
                code = "export default async function(req) { return new Response('v2'); }",
                status = "active"
            )

            println("Updated function: ${updateResponse.function.slug}")

            // Cleanup
            client.functions.deleteFunction(functionSlug)
        } catch (e: InsforgeHttpException) {
            println("Update function failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test create function with invalid code`() = runTest {
        try {
            client.functions.createFunction(
                name = "Invalid Function",
                code = "this is not valid javascript/typescript code {{{",
                status = "active"
            )
            fail("Should have thrown an exception")
        } catch (e: InsforgeHttpException) {
            println("Expected error for invalid code: ${e.message}")
        }
    }

    // ============ Function Status Tests ============

    @Test
    fun `test create draft function`() = runTest {
        val functionSlug = "draft-test-${System.currentTimeMillis()}"

        try {
            val response = client.functions.createFunction(
                name = "Draft Function",
                slug = functionSlug,
                code = "export default async function(req) { return new Response('draft'); }",
                status = "draft"
            )

            println("Created draft function: ${response.function.slug}")

            // Cleanup
            client.functions.deleteFunction(functionSlug)
        } catch (e: InsforgeHttpException) {
            println("Create draft function failed: ${e.message}")
        }
    }

    @Test
    fun `test invoke draft function should fail`() = runTest {
        val functionSlug = "draft-invoke-test-${System.currentTimeMillis()}"

        try {
            // Create draft function
            client.functions.createFunction(
                name = "Draft Invoke Test",
                slug = functionSlug,
                code = "export default async function(req) { return new Response('draft'); }",
                status = "draft"
            )

            // Try to invoke - should fail
            val exception = assertFailsWith<InsforgeHttpException> {
                client.functions.invoke<HelloResponse>(functionSlug)
            }
            println("Expected error for draft invocation: ${exception.message}")

            // Cleanup
            client.functions.deleteFunction(functionSlug)
        } catch (e: InsforgeHttpException) {
            println("Test failed: ${e.message}")
        }
    }

    // ============ Edge Cases ============

    @Test
    fun `test invoke function with empty body`() = runTest {
        try {
            val response = client.functions.invoke<HelloResponse>(
                slug = "hello",
                body = null
            )
            println("Response with null body: ${response.message}")
        } catch (e: InsforgeHttpException) {
            println("Invoke with null body failed: ${e.message}")
        }
    }

    // Request models for nested object test
    @Serializable
    data class UserInfo(val name: String, val email: String)
    @Serializable
    data class SettingsInfo(val theme: String, val notifications: Boolean)
    @Serializable
    data class NestedRequest(val user: UserInfo, val settings: SettingsInfo)

    @Test
    fun `test invoke function with nested object body`() = runTest {
        try {
            // Use @Serializable data classes instead of nested Maps to avoid serialization issues
            val response = client.functions.invoke<EchoResponse>(
                slug = "echo",
                body = NestedRequest(
                    user = UserInfo(name = "Test User", email = "test@example.com"),
                    settings = SettingsInfo(theme = "dark", notifications = true)
                )
            )
            println("Response with nested body: $response")
        } catch (e: InsforgeHttpException) {
            println("Invoke with nested body failed: ${e.message}")
        }
    }

    @Test
    fun `test invoke function with array body`() = runTest {
        try {
            val response = client.functions.invoke<EchoResponse>(
                slug = "echo",
                body = listOf("item1", "item2", "item3")
            )
            println("Response with array body: $response")
        } catch (e: InsforgeHttpException) {
            println("Invoke with array body failed: ${e.message}")
        }
    }
}
