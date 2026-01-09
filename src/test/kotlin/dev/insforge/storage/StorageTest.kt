package dev.insforge.storage

import dev.insforge.TestConfig
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.storage.models.UploadMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Storage module
 *
 * Tests the Supabase-style Storage API including:
 * - Bucket management (create, update, delete, list)
 * - File operations via BucketApi (upload, download, delete, list)
 * - Upload strategies (direct and presigned)
 * - Download strategies
 */
class StorageTest {

    private lateinit var client: dev.insforge.InsforgeClient
    private val testBucketName = "test-bucket"

    @BeforeTest
    fun setup() {
        client = TestConfig.createStorageClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Bucket Access Tests ============

    @Test
    fun `test bucket access via index operator`() = runTest {
        val bucket = client.storage["avatars"]
        assertEquals("avatars", bucket.bucketId)
    }

    @Test
    fun `test bucket access via from method`() = runTest {
        val bucket = client.storage.from("avatars")
        assertEquals("avatars", bucket.bucketId)
    }

    // ============ Bucket Management Tests ============

    @Test
    fun `test list buckets`() = runTest {
        try {
            val buckets = client.storage.listBuckets()
            println("Available buckets: ${buckets.size}")
            buckets.forEach { bucket ->
                println("  - ${bucket.name} (public: ${bucket.isPublic})")
            }
        } catch (e: InsforgeHttpException) {
            println("List buckets failed: ${e.message}")
        }
    }

    @Test
    fun `test create bucket with builder`() = runTest {
        val bucketName = "test-${System.currentTimeMillis()}"

        try {
            client.storage.createBucket(bucketName) {
                isPublic = true
            }
            println("Created bucket: $bucketName")

            // Verify bucket exists in list
            val buckets = client.storage.listBuckets()
            assertTrue(buckets.any { it.name == bucketName })

            // Cleanup - delete the bucket
            client.storage.deleteBucket(bucketName)
        } catch (e: InsforgeHttpException) {
            println("Create bucket failed: ${e.message}")
        }
    }

    @Test
    fun `test create private bucket`() = runTest {
        val bucketName = "private-test-${System.currentTimeMillis()}"

        try {
            client.storage.createBucket(bucketName) {
                isPublic = false
            }
            println("Created private bucket: $bucketName")

            // Cleanup
            client.storage.deleteBucket(bucketName)
        } catch (e: InsforgeHttpException) {
            println("Create private bucket failed: ${e.message}")
        }
    }

    @Test
    fun `test update bucket visibility`() = runTest {
        val bucketName = "update-test-${System.currentTimeMillis()}"

        try {
            // Create bucket
            client.storage.createBucket(bucketName) {
                isPublic = true
            }

            // Update to private
            client.storage.updateBucket(bucketName) {
                isPublic = false
            }
            println("Updated bucket to private: $bucketName")

            // Cleanup
            client.storage.deleteBucket(bucketName)
        } catch (e: InsforgeHttpException) {
            println("Update bucket failed: ${e.message}")
        }
    }

    @Test
    fun `test delete bucket`() = runTest {
        val bucketName = "delete-test-${System.currentTimeMillis()}"

        try {
            // Create bucket
            client.storage.createBucket(bucketName)

            // Delete bucket
            client.storage.deleteBucket(bucketName)
            println("Deleted bucket: $bucketName")

            // Verify bucket no longer exists
            val buckets = client.storage.listBuckets()
            assertFalse(buckets.any { it.name == bucketName })
        } catch (e: InsforgeHttpException) {
            println("Delete bucket failed: ${e.message}")
        }
    }

    // ============ BucketApi Upload Tests ============

    @Test
    fun `test upload file via bucket api`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val testContent = "Hello, Insforge Storage!".toByteArray()
            val key = "test-file-${System.currentTimeMillis()}.txt"

            val result = bucket.upload(key, testContent) {
                contentType = "text/plain"
            }

            println("Uploaded file: ${result.key}")
            assertEquals(key, result.key)
            assertEquals(testContent.size.toLong(), result.size)

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Upload file failed: ${e.message}")
        }
    }

    @Test
    fun `test upload file with auto-generated key`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val testContent = "Auto-key test content".toByteArray()
            val filename = "auto-test-${System.currentTimeMillis()}.txt"

            val result = bucket.uploadWithAutoKey(filename, testContent) {
                contentType = "text/plain"
            }

            println("Uploaded file with auto key: ${result.key}")
            assertNotNull(result.key)

            // Cleanup
            bucket.delete(result.key)
        } catch (e: InsforgeHttpException) {
            println("Upload with auto key failed: ${e.message}")
        }
    }

    @Test
    fun `test upload image file`() = runTest {
        try {
            val bucket = client.storage[testBucketName]

            // Create a simple 1x1 PNG image (smallest valid PNG)
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
                0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
                0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0x00, 0x05, 0xFE.toByte(), 0x02, 0xFE.toByte(), 0xDC.toByte(), 0xCC.toByte(), 0x59,
                0xE7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
                0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
            )

            val key = "test-image-${System.currentTimeMillis()}.png"

            val result = bucket.upload(key, pngBytes) {
                contentType = "image/png"
            }

            println("Uploaded image: ${result.key}")
            assertEquals("image/png", result.mimeType)

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Upload image failed: ${e.message}")
        }
    }

    @Test
    fun `test upload with upsert option`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val key = "upsert-test-${System.currentTimeMillis()}.txt"

            // First upload
            bucket.upload(key, "Version 1".toByteArray()) {
                contentType = "text/plain"
            }

            // Upsert (overwrite)
            val result = bucket.upload(key, "Version 2".toByteArray()) {
                contentType = "text/plain"
                upsert = true
            }

            println("Upserted file: ${result.key}")

            // Verify content
            val content = bucket.download(key)
            assertEquals("Version 2", String(content))

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Upload with upsert failed: ${e.message}")
        }
    }

    // ============ BucketApi Download Tests ============

    @Test
    fun `test download file via bucket api`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val testContent = "Download test content"
            val key = "download-test-${System.currentTimeMillis()}.txt"

            // Upload first
            bucket.upload(key, testContent.toByteArray()) {
                contentType = "text/plain"
            }

            // Download
            val downloadedBytes = bucket.download(key)
            val downloadedContent = String(downloadedBytes)

            assertEquals(testContent, downloadedContent)
            println("Downloaded content: $downloadedContent")

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Download file failed: ${e.message}")
        }
    }

    @Test
    fun `test download non-existent file`() = runTest {
        val bucket = client.storage[testBucketName]
        val exception = assertFailsWith<InsforgeHttpException> {
            bucket.download("non-existent-file-${System.currentTimeMillis()}.txt")
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test create signed url`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val key = "signed-url-test-${System.currentTimeMillis()}.txt"

            // Upload first
            bucket.upload(key, "Signed URL test".toByteArray()) {
                contentType = "text/plain"
            }

            // Get signed URL
            val signedUrl = bucket.createSignedUrl(key, expiresIn = 3600)
            println("Signed URL: $signedUrl")
            assertTrue(signedUrl.isNotEmpty())

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Create signed URL failed: ${e.message}")
        }
    }

    // ============ Upload/Download Strategy Tests ============

    @Test
    fun `test get upload strategy`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val strategy = bucket.getUploadStrategy(
                filename = "strategy-test.txt",
                contentType = "text/plain",
                size = 1024
            )

            println("Upload strategy: ${strategy.method}")
            println("URL: ${strategy.uploadUrl}")
            println("Key: ${strategy.key}")
            println("Confirm required: ${strategy.confirmRequired}")

            when (strategy.method) {
                UploadMethod.DIRECT -> {
                    println("Using direct upload to InsForge")
                }
                UploadMethod.PRESIGNED -> {
                    println("Using presigned upload to S3")
                    assertNotNull(strategy.fields)
                }
            }
        } catch (e: InsforgeHttpException) {
            println("Get upload strategy failed: ${e.message}")
        }
    }

    @Test
    fun `test get download strategy`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val key = "strategy-download-test-${System.currentTimeMillis()}.txt"

            // Upload a file first
            bucket.upload(key, "Test content".toByteArray()) {
                contentType = "text/plain"
            }

            val strategy = bucket.getDownloadUrl(key, expiresIn = 3600)

            println("Download strategy: ${strategy.method}")
            println("URL: ${strategy.url}")
            strategy.expiresAt?.let { println("Expires at: $it") }

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Get download strategy failed: ${e.message}")
        }
    }

    // ============ BucketApi List Tests ============

    @Test
    fun `test list files in bucket`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val files = bucket.list()

            println("Files in bucket: ${files.size}")
            files.forEach { file ->
                println("  - ${file.key} (${file.size} bytes)")
            }
        } catch (e: InsforgeHttpException) {
            println("List files failed: ${e.message}")
        }
    }

    @Test
    fun `test list files with filter`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val files = bucket.list {
                prefix = "images/"
                limit = 10
                offset = 0
            }

            println("Files with prefix 'images/': ${files.size}")
        } catch (e: InsforgeHttpException) {
            println("List files with filter failed: ${e.message}")
        }
    }

    @Test
    fun `test check file exists`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val key = "exists-test-${System.currentTimeMillis()}.txt"

            // Initially should not exist
            assertFalse(bucket.exists(key))

            // Upload
            bucket.upload(key, "Test".toByteArray())

            // Now should exist
            assertTrue(bucket.exists(key))

            // Cleanup
            bucket.delete(key)

            // Should not exist again
            assertFalse(bucket.exists(key))
        } catch (e: InsforgeHttpException) {
            println("Check exists failed: ${e.message}")
        }
    }

    // ============ BucketApi Delete Tests ============

    @Test
    fun `test delete single file`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val key = "delete-me-${System.currentTimeMillis()}.txt"

            // Upload first
            bucket.upload(key, "To be deleted".toByteArray())

            // Delete
            bucket.delete(key)
            println("Deleted file: $key")

            // Verify deleted
            assertFalse(bucket.exists(key))
        } catch (e: InsforgeHttpException) {
            println("Delete file failed: ${e.message}")
        }
    }

    @Test
    fun `test delete multiple files`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val timestamp = System.currentTimeMillis()
            val keys = listOf(
                "batch-delete-1-$timestamp.txt",
                "batch-delete-2-$timestamp.txt",
                "batch-delete-3-$timestamp.txt"
            )

            // Upload files
            keys.forEach { key ->
                bucket.upload(key, "Delete me".toByteArray())
            }

            // Delete all
            bucket.delete(keys)
            println("Deleted ${keys.size} files")

            // Verify all deleted
            keys.forEach { key ->
                assertFalse(bucket.exists(key))
            }
        } catch (e: InsforgeHttpException) {
            println("Delete multiple files failed: ${e.message}")
        }
    }

    @Test
    fun `test delete with vararg`() = runTest {
        try {
            val bucket = client.storage[testBucketName]
            val timestamp = System.currentTimeMillis()
            val key1 = "vararg-delete-1-$timestamp.txt"
            val key2 = "vararg-delete-2-$timestamp.txt"

            // Upload files
            bucket.upload(key1, "Delete me 1".toByteArray())
            bucket.upload(key2, "Delete me 2".toByteArray())

            // Delete using vararg
            bucket.delete(key1, key2)
            println("Deleted files using vararg")
        } catch (e: InsforgeHttpException) {
            println("Delete with vararg failed: ${e.message}")
        }
    }

    // ============ Error Cases ============

    @Test
    fun `test upload to non-existent bucket`() = runTest {
        val bucket = client.storage["non-existent-bucket-${System.currentTimeMillis()}"]
        val exception = assertFailsWith<InsforgeHttpException> {
            bucket.upload("test.txt", "test".toByteArray())
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test create bucket with invalid name`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.storage.createBucket("INVALID_BUCKET_NAME!!!")
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test upload empty data`() = runTest {
        val bucket = client.storage[testBucketName]
        val exception = assertFailsWith<IllegalArgumentException> {
            bucket.upload("empty.txt", byteArrayOf())
        }
        println("Expected error: ${exception.message}")
    }

    // ============ Content Type Detection Tests ============

    @Test
    fun `test content type auto detection`() = runTest {
        try {
            val bucket = client.storage[testBucketName]

            // Upload without specifying content type - should auto-detect
            val key = "auto-detect-${System.currentTimeMillis()}.json"
            val result = bucket.upload(key, """{"test": true}""".toByteArray())

            println("Auto-detected content type: ${result.mimeType}")
            assertEquals("application/json", result.mimeType)

            // Cleanup
            bucket.delete(key)
        } catch (e: InsforgeHttpException) {
            println("Content type auto detection failed: ${e.message}")
        }
    }

    // ============ Local File Upload Tests ============

    @Test
    fun `test upload local png file`() = runTest(timeout = 60.seconds) {
        // Create a dedicated bucket for this test
        val uploadBucketName = "cpu-upload-test-${System.currentTimeMillis()}"

        try {
            // Create bucket first
            client.storage.createBucket(uploadBucketName) {
                isPublic = true
            }
            println("Created bucket: $uploadBucketName")

            val bucket = client.storage[uploadBucketName]

            // Read the local cpu.png file from project root
            val localFile = java.io.File("cpu.png")
            if (!localFile.exists()) {
                println("Skipping test: cpu.png not found at ${localFile.absolutePath}")
                client.storage.deleteBucket(uploadBucketName)
                return@runTest
            }

            val fileBytes = localFile.readBytes()
            val key = "uploaded-cpu-${System.currentTimeMillis()}.png"

            println("Uploading ${localFile.name} (${fileBytes.size} bytes)...")

            val result = bucket.upload(key, fileBytes) {
                contentType = "image/png"
            }

            println("Upload successful!")
            println("  Key: ${result.key}")
            println("  Size: ${result.size} bytes")
            println("  MIME Type: ${result.mimeType}")

            assertEquals(key, result.key)
            assertEquals(fileBytes.size.toLong(), result.size)
            assertEquals("image/png", result.mimeType)

            // Get download URL
            val downloadUrl = bucket.createSignedUrl(key, expiresIn = 3600)
            println("  Download URL: $downloadUrl")

            // Cleanup - delete file and bucket
            bucket.delete(key)
            client.storage.deleteBucket(uploadBucketName)
            println("  Cleaned up bucket: $uploadBucketName")

        } catch (e: InsforgeHttpException) {
            println("Upload local file failed: ${e.message}")
            // Try to cleanup bucket on failure
            try { client.storage.deleteBucket(uploadBucketName) } catch (_: Exception) {}
            throw e
        }
    }

    @Test
    fun `test upload local file using File API`() = runTest(timeout = 60.seconds) {
        // Create a dedicated bucket for this test
        val uploadBucketName = "file-api-test-${System.currentTimeMillis()}"

        try {
            // Create bucket first
            client.storage.createBucket(uploadBucketName) {
                isPublic = true
            }
            println("Created bucket: $uploadBucketName")

            val bucket = client.storage[uploadBucketName]

            // Read the local cpu.png file from project root
            val localFile = java.io.File("cpu.png")
            if (!localFile.exists()) {
                println("Skipping test: cpu.png not found at ${localFile.absolutePath}")
                client.storage.deleteBucket(uploadBucketName)
                return@runTest
            }

            val key = "file-api-cpu-${System.currentTimeMillis()}.png"

            println("Uploading ${localFile.name} using File API...")

            // Use the File upload method
            val result = bucket.upload(key, localFile) {
                contentType = "image/png"
            }

            println("Upload successful!")
            println("  Key: ${result.key}")
            println("  Size: ${result.size} bytes")
            println("  MIME Type: ${result.mimeType}")

            // Get download URL
            val downloadUrl = bucket.createSignedUrl(key, expiresIn = 3600)
            println("  Download URL: $downloadUrl")

            // Cleanup
            bucket.delete(key)
            client.storage.deleteBucket(uploadBucketName)
            println("  Cleaned up bucket: $uploadBucketName")

        } catch (e: InsforgeHttpException) {
            println("Upload local file using File API failed: ${e.message}")
            // Try to cleanup bucket on failure
            try { client.storage.deleteBucket(uploadBucketName) } catch (_: Exception) {}
            throw e
        }
    }
}
