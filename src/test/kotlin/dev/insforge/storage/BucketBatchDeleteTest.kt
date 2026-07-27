package dev.insforge.storage

import dev.insforge.createInsforgeClient
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.storage.models.DeleteObjectStatus
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit tests for [BucketApi.delete] batch deletion using a MockEngine.
 *
 * The multi-path overload must issue a single DELETE request to the batch
 * endpoint (`.../objects` with a `keys` body) instead of one request per key.
 */
class BucketBatchDeleteTest {

    private val jsonContent = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `delete with single path uses the single-object endpoint`() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()

        val engine = MockEngine { request ->
            requests.add(request.method to request.url.encodedPath)
            respond("""{"message":"Object deleted"}""", HttpStatusCode.OK, jsonContent)
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        client.storage["docs"].delete("files/report.pdf")

        assertEquals(1, requests.size)
        assertEquals(HttpMethod.Delete, requests[0].first)
        assertEquals("/api/storage/buckets/docs/objects/files/report.pdf", requests[0].second)
    }

    @Test
    fun `delete with multiple paths issues one batch request and returns per-key results`() = runTest {
        val requests = mutableListOf<Triple<HttpMethod, String, String>>()

        val engine = MockEngine { request ->
            requests.add(Triple(request.method, request.url.encodedPath, String(request.body.toByteArray())))
            respond(
                """
                {
                  "results": [
                    {"key": "a.pdf", "status": "deleted"},
                    {"key": "missing.pdf", "status": "notFound"},
                    {"key": "locked.pdf", "status": "failed", "message": "Delete denied"}
                  ]
                }
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonContent
            )
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        val response = client.storage["docs"].delete(listOf("a.pdf", "missing.pdf", "locked.pdf"))

        // One request total — not one per key
        assertEquals(1, requests.size)
        assertEquals(HttpMethod.Delete, requests[0].first)
        assertEquals("/api/storage/buckets/docs/objects", requests[0].second)

        val body = Json.parseToJsonElement(requests[0].third).jsonObject
        val keys = body["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a.pdf", "missing.pdf", "locked.pdf"), keys)

        // Server results are surfaced unchanged, one per key
        assertEquals(3, response.results.size)
        assertEquals(DeleteObjectStatus.DELETED, response.results[0].status)
        assertNull(response.results[0].message)
        assertEquals(DeleteObjectStatus.NOT_FOUND, response.results[1].status)
        assertEquals(DeleteObjectStatus.FAILED, response.results[2].status)
        assertEquals("Delete denied", response.results[2].message)
    }

    @Test
    fun `delete with vararg paths delegates to the batch endpoint`() = runTest {
        val requests = mutableListOf<String>()

        val engine = MockEngine { request ->
            requests.add(request.url.encodedPath)
            respond(
                """{"results":[{"key":"a.txt","status":"deleted"},{"key":"b.txt","status":"deleted"}]}""",
                HttpStatusCode.OK,
                jsonContent
            )
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        val response = client.storage["docs"].delete("a.txt", "b.txt")

        assertEquals(listOf("/api/storage/buckets/docs/objects"), requests)
        assertEquals(2, response.results.size)
    }

    @Test
    fun `batch delete surfaces request errors and does not split oversized batches`() = runTest {
        var requestCount = 0
        var lastBody: String? = null

        val engine = MockEngine { request ->
            requestCount++
            lastBody = String(request.body.toByteArray())
            respond(
                """{"statusCode":400,"error":"STORAGE_ERROR","message":"Cannot delete more than 1000 objects at once"}""",
                HttpStatusCode.BadRequest,
                jsonContent
            )
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        val paths = (0 until 1001).map { "file-$it.txt" }

        val exception = assertFailsWith<InsforgeHttpException> {
            client.storage["docs"].delete(paths)
        }

        assertEquals(400, exception.statusCode)
        assertEquals("Cannot delete more than 1000 objects at once", exception.message)
        // The SDK must not silently split the batch into multiple requests
        assertEquals(1, requestCount)
        val keys = Json.parseToJsonElement(lastBody!!).jsonObject["keys"]!!.jsonArray
        assertEquals(1001, keys.size)
    }
}
