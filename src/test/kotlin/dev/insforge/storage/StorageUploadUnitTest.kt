package dev.insforge.storage

import dev.insforge.createInsforgeClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Unit tests for storage upload semantics (standard PUT create-or-replace)
 * and client-side auto-key generation.
 *
 * All HTTP interactions go through a MockEngine — no external services needed.
 */
class StorageUploadUnitTest {

    private val jsonContent = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun storedFileJson(key: String) = """
        {
          "bucket": "docs",
          "key": "$key",
          "size": 3,
          "mimeType": "application/pdf",
          "uploadedAt": "2026-01-01T00:00:00.000Z",
          "url": "https://test.insforge.app/api/storage/buckets/docs/objects/$key"
        }
    """.trimIndent()

    private fun directStrategyJson(key: String) = """
        {
          "method": "direct",
          "uploadUrl": "https://test.insforge.app/api/storage/buckets/docs/objects/$key",
          "key": "$key",
          "confirmRequired": false
        }
    """.trimIndent()

    // ============ generateObjectKey ============

    @Test
    fun `generateObjectKey preserves extension and appends timestamp plus random suffix`() {
        val key = generateObjectKey("report.pdf")
        assertTrue(
            key.matches(Regex("""^report-\d+-[a-z0-9]{6}\.pdf$""")),
            "Unexpected key format: $key"
        )
    }

    @Test
    fun `generateObjectKey sanitizes special characters in the base name`() {
        val key = generateObjectKey("my photo (1)!.png")
        assertTrue(
            key.matches(Regex("""^my-photo--1---\d+-[a-z0-9]{6}\.png$""")),
            "Unexpected key format: $key"
        )
    }

    @Test
    fun `generateObjectKey truncates long base names to 32 characters`() {
        val key = generateObjectKey("a".repeat(100) + ".txt")
        val base = key.substringBefore("-")
        assertEquals("a".repeat(32), base)
        assertTrue(key.endsWith(".txt"))
    }

    @Test
    fun `generateObjectKey falls back to file base for empty filenames`() {
        val key = generateObjectKey("")
        assertTrue(
            key.matches(Regex("""^file-\d+-[a-z0-9]{6}$""")),
            "Unexpected key format: $key"
        )
    }

    @Test
    fun `generateObjectKey does not treat a leading dot as an extension`() {
        val key = generateObjectKey(".gitignore")
        assertTrue(
            key.matches(Regex("""^-gitignore-\d+-[a-z0-9]{6}$""")),
            "Unexpected key format: $key"
        )
    }

    @Test
    fun `generateObjectKey mints distinct keys for the same filename`() {
        val keys = (1..10).map { generateObjectKey("report.pdf") }.toSet()
        assertTrue(keys.size > 1, "Expected collision-free keys, got: $keys")
    }

    // ============ upload (standard PUT semantics) ============

    @Test
    fun `upload sends the exact key via the direct PUT route`() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()

        val engine = MockEngine { request ->
            requests.add(request.method to request.url.encodedPath)
            val path = request.url.encodedPath
            when {
                path.endsWith("/upload-strategy") ->
                    respond(directStrategyJson("report.pdf"), HttpStatusCode.OK, jsonContent)
                request.method == HttpMethod.Put && path.contains("/objects/") ->
                    respond(storedFileJson("report.pdf"), HttpStatusCode.OK, jsonContent)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        val result = client.storage["docs"].upload("report.pdf", "abc".toByteArray())

        assertEquals("report.pdf", result.key)
        val put = requests.single { it.first == HttpMethod.Put }
        assertEquals("/api/storage/buckets/docs/objects/report.pdf", put.second)

        client.close()
    }

    @Test
    fun `uploadWithAutoKey mints a unique key client-side and uploads via the standard PUT path`() = runTest {
        val strategyFilenames = mutableListOf<String>()
        val putPaths = mutableListOf<String>()
        val postObjectCalls = mutableListOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/upload-strategy") -> {
                    val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                    val filename = body["filename"]!!.jsonPrimitive.content
                    strategyFilenames.add(filename)
                    respond(directStrategyJson(filename), HttpStatusCode.OK, jsonContent)
                }
                request.method == HttpMethod.Put && path.contains("/objects/") -> {
                    putPaths.add(path)
                    respond(storedFileJson(path.substringAfterLast("/")), HttpStatusCode.OK, jsonContent)
                }
                request.method == HttpMethod.Post && path.endsWith("/objects") -> {
                    // The server-side key-minting endpoint is gone — nothing should land here.
                    postObjectCalls.add(path)
                    respond("", HttpStatusCode.NotFound)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        val bucket = client.storage["docs"]
        val result = bucket.uploadWithAutoKey("report.pdf", "abc".toByteArray())

        // Client-generated key: sanitized base + timestamp + random, preserving ext.
        val mintedKey = strategyFilenames.single()
        assertTrue(
            mintedKey.matches(Regex("""^report-\d+-[a-z0-9]+\.pdf$""")),
            "Expected client-minted key, got: $mintedKey"
        )
        // Uploads to the client-minted key via the standard PUT route.
        assertEquals("/api/storage/buckets/docs/objects/$mintedKey", putPaths.single())
        assertEquals(mintedKey, result.key)
        // The POST /objects auto-key endpoint is never used.
        assertTrue(postObjectCalls.isEmpty(), "uploadWithAutoKey must not POST to /objects")

        // Repeated uploads of the same file never overwrite each other.
        bucket.uploadWithAutoKey("report.pdf", "abc".toByteArray())
        assertEquals(2, strategyFilenames.toSet().size, "Keys must be collision-free: $strategyFilenames")

        client.close()
    }

    @Test
    fun `uploadWithAutoKey rejects empty data`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Storage)
        }

        assertFailsWith<IllegalArgumentException> {
            client.storage["docs"].uploadWithAutoKey("report.pdf", byteArrayOf())
        }

        client.close()
    }
}
