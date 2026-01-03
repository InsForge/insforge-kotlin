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
class ApiResponseTest {

    private val baseURL = "https://pg6afqz9.us-east.insforge.app"
    private val anonKey = "ik_ca177fcf1e2e72e8d1e0c2c23dbe3b79"

    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
    }

    @Test
    fun `capture auth public config response`() = runTest {
        val response = httpClient.get("$baseURL/api/auth/public-config") {
            header("x-api-key", anonKey)
        }
        println("=== Auth Public Config ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture storage list buckets response`() = runTest {
        val response = httpClient.get("$baseURL/api/storage/buckets") {
            header("x-api-key", anonKey)
        }
        println("=== Storage List Buckets ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture database list tables response`() = runTest {
        val response = httpClient.get("$baseURL/api/database/tables") {
            header("x-api-key", anonKey)
        }
        println("=== Database List Tables ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture functions list response`() = runTest {
        val response = httpClient.get("$baseURL/api/functions") {
            header("x-api-key", anonKey)
        }
        println("=== Functions List ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture ai models response`() = runTest {
        val response = httpClient.get("$baseURL/api/ai/models") {
            header("x-api-key", anonKey)
        }
        println("=== AI Models ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture ai configurations response`() = runTest {
        val response = httpClient.get("$baseURL/api/ai/configurations") {
            header("x-api-key", anonKey)
        }
        println("=== AI Configurations ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture ai usage summary response`() = runTest {
        val response = httpClient.get("$baseURL/api/ai/usage/summary") {
            header("x-api-key", anonKey)
        }
        println("=== AI Usage Summary ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture ai usage records response`() = runTest {
        val response = httpClient.get("$baseURL/api/ai/usage") {
            header("x-api-key", anonKey)
        }
        println("=== AI Usage Records ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture realtime channels response`() = runTest {
        val response = httpClient.get("$baseURL/api/realtime/channels") {
            header("x-api-key", anonKey)
        }
        println("=== Realtime Channels ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture database records response`() = runTest {
        val response = httpClient.get("$baseURL/api/database/records/users") {
            header("x-api-key", anonKey)
        }
        println("=== Database Records (users) ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture function create response`() = runTest {
        val response = httpClient.post("$baseURL/api/functions") {
            header("x-api-key", anonKey)
            header("Content-Type", "application/json")
            setBody("""{"name":"Test API","code":"export default async function(req) { return new Response('ok'); }","status":"draft"}""")
        }
        println("=== Function Create ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }

    @Test
    fun `capture function get response`() = runTest {
        val response = httpClient.get("$baseURL/api/functions/hello") {
            header("x-api-key", anonKey)
        }
        println("=== Function Get ===")
        println("Status: ${response.status}")
        println("Body: ${response.bodyAsText()}")
    }
}
