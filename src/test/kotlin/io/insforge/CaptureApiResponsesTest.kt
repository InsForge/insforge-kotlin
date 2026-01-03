package io.insforge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Test to capture raw API responses for model adjustment
 */
class CaptureApiResponsesTest {

    private val baseURL = "https://pg6afqz9.us-east.insforge.app"
    private val anonKey = "ik_ca177fcf1e2e72e8d1e0c2c23dbe3b79"

    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
    }

    @Test
    fun `capture function update response`() = runTest {
        val timestamp = System.currentTimeMillis()

        // Create a function first
        val createResponse = httpClient.post("$baseURL/api/functions") {
            header("x-api-key", anonKey)
            header("Content-Type", "application/json")
            setBody("""{"name":"Capture Test","code":"export default async function(req) { return new Response('ok'); }","status":"draft","slug":"capture-test-$timestamp"}""")
        }
        println("=== Function Create ===")
        println("Status: ${createResponse.status}")
        val createBody = createResponse.bodyAsText()
        println("Body: $createBody")

        // Extract slug from response
        val slugMatch = Regex("\"slug\":\"([^\"]+)\"").find(createBody)
        val slug = slugMatch?.groupValues?.get(1)

        if (slug != null) {
            // Update the function
            val updateResponse = httpClient.put("$baseURL/api/functions/$slug") {
                header("x-api-key", anonKey)
                header("Content-Type", "application/json")
                setBody("""{"name":"Capture Test Updated","status":"active"}""")
            }
            println("=== Function Update ===")
            println("Status: ${updateResponse.status}")
            println("Body: ${updateResponse.bodyAsText()}")

            // Cleanup
            httpClient.delete("$baseURL/api/functions/$slug") {
                header("x-api-key", anonKey)
            }
        }
    }
}
