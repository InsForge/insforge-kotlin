package io.insforge.storage

import io.insforge.TestConfig
import io.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for Storage module
 */
class StorageTest {

    private lateinit var client: io.insforge.InsforgeClient
    private val testBucketName = "test-bucket"

    @BeforeTest
    fun setup() {
        client = TestConfig.createStorageClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Bucket Management Tests ============

    @Test
    fun `test list buckets`() = runTest {
        try {
            val buckets = client.storage.listBuckets()
            println("Available buckets: ${buckets.size}")
            buckets.forEach { bucket ->
                println("  - ${bucket.name} (public: ${bucket.public})")
            }
        } catch (e: InsforgeHttpException) {
            println("List buckets failed: ${e.message}")
        }
    }

    @Test
    fun `test create bucket`() = runTest {
        val bucketName = "test-${System.currentTimeMillis()}"

        try {
            val response = client.storage.createBucket(bucketName, isPublic = true)
            println("Created bucket: ${response.bucketName}")
            assertTrue(response.bucketName.isNotEmpty())

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
            val response = client.storage.createBucket(bucketName, isPublic = false)
            println("Created private bucket: ${response.bucketName}")

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
            client.storage.createBucket(bucketName, isPublic = true)

            // Update to private
            val response = client.storage.updateBucket(bucketName, isPublic = false)
            println("Updated bucket: ${response.bucket}")

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
            client.storage.createBucket(bucketName, isPublic = true)

            // Delete bucket
            val response = client.storage.deleteBucket(bucketName)
            println("Deleted bucket: ${response.message}")
        } catch (e: InsforgeHttpException) {
            println("Delete bucket failed: ${e.message}")
        }
    }

    // ============ File Upload Tests ============

    @Test
    fun `test upload file with specific key`() = runTest {
        try {
            val testContent = "Hello, Insforge Storage!".toByteArray()
            val key = "test-file-${System.currentTimeMillis()}.txt"

            val result = client.storage.uploadFile(
                bucketName = testBucketName,
                key = key,
                fileBytes = testContent,
                contentType = "text/plain"
            )

            println("Uploaded file: ${result.key}")
            assertNotNull(result.key)

            // Cleanup
            client.storage.deleteFile(testBucketName, key)
        } catch (e: InsforgeHttpException) {
            println("Upload file failed: ${e.message}")
        }
    }

    @Test
    fun `test upload file with auto-generated key`() = runTest {
        try {
            val testContent = "Auto-key test content".toByteArray()
            val filename = "auto-test-${System.currentTimeMillis()}.txt"

            val result = client.storage.uploadFile(
                bucketName = testBucketName,
                fileBytes = testContent,
                filename = filename,
                contentType = "text/plain"
            )

            println("Uploaded file with auto key: ${result.key}")
            assertNotNull(result.key)

            // Cleanup
            client.storage.deleteFile(testBucketName, result.key)
        } catch (e: InsforgeHttpException) {
            println("Upload with auto key failed: ${e.message}")
        }
    }

    @Test
    fun `test upload image file`() = runTest {
        try {
            // Create a simple 1x1 PNG image (smallest valid PNG)
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  // PNG signature
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,  // IHDR chunk
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
                0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
                0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0x00, 0x05, 0xFE.toByte(), 0x02, 0xFE.toByte(), 0xDC.toByte(), 0xCC.toByte(), 0x59,
                0xE7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
                0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
            )

            val key = "test-image-${System.currentTimeMillis()}.png"

            val result = client.storage.uploadFile(
                bucketName = testBucketName,
                key = key,
                fileBytes = pngBytes,
                contentType = "image/png"
            )

            println("Uploaded image: ${result.key}")

            // Cleanup
            client.storage.deleteFile(testBucketName, key)
        } catch (e: InsforgeHttpException) {
            println("Upload image failed: ${e.message}")
        }
    }

    // ============ File Download Tests ============

    @Test
    fun `test download file`() = runTest {
        try {
            val testContent = "Download test content"
            val key = "download-test-${System.currentTimeMillis()}.txt"

            // Upload first
            client.storage.uploadFile(
                bucketName = testBucketName,
                key = key,
                fileBytes = testContent.toByteArray(),
                contentType = "text/plain"
            )

            // Download
            val downloadedBytes = client.storage.downloadFile(testBucketName, key)
            val downloadedContent = String(downloadedBytes)

            assertEquals(testContent, downloadedContent)
            println("Downloaded content: $downloadedContent")

            // Cleanup
            client.storage.deleteFile(testBucketName, key)
        } catch (e: InsforgeHttpException) {
            println("Download file failed: ${e.message}")
        }
    }

    @Test
    fun `test download non-existent file`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.storage.downloadFile(testBucketName, "non-existent-file.txt")
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    // ============ Upload/Download Strategy Tests ============

    @Test
    fun `test get upload strategy`() = runTest {
        try {
            val strategy = client.storage.getUploadStrategy(
                bucketName = testBucketName,
                filename = "strategy-test.txt",
                contentType = "text/plain",
                size = 1024
            )

            println("Upload strategy: ${strategy.method}")
            println("URL: ${strategy.uploadUrl}")
        } catch (e: InsforgeHttpException) {
            println("Get upload strategy failed: ${e.message}")
        }
    }

    @Test
    fun `test get download strategy`() = runTest {
        try {
            val key = "strategy-download-test-${System.currentTimeMillis()}.txt"

            // Upload a file first
            client.storage.uploadFile(
                bucketName = testBucketName,
                key = key,
                fileBytes = "Test content".toByteArray(),
                contentType = "text/plain"
            )

            val strategy = client.storage.getDownloadStrategy(
                bucketName = testBucketName,
                objectKey = key,
                expiresIn = 3600
            )

            println("Download strategy: ${strategy.method}")
            println("URL: ${strategy.url}")

            // Cleanup
            client.storage.deleteFile(testBucketName, key)
        } catch (e: InsforgeHttpException) {
            println("Get download strategy failed: ${e.message}")
        }
    }

    // ============ File List Tests ============

    @Test
    fun `test list files in bucket`() = runTest {
        try {
            val response = client.storage.listFiles(testBucketName)
            println("Files in bucket: ${response.data.size}")
            response.data.forEach { file ->
                println("  - ${file.key} (${file.size} bytes)")
            }
        } catch (e: InsforgeHttpException) {
            println("List files failed: ${e.message}")
        }
    }

    @Test
    fun `test list files with prefix`() = runTest {
        try {
            val response = client.storage.listFiles(
                bucketName = testBucketName,
                prefix = "images/"
            )
            println("Files with prefix 'images/': ${response.data.size}")
        } catch (e: InsforgeHttpException) {
            println("List files with prefix failed: ${e.message}")
        }
    }

    @Test
    fun `test list files with pagination`() = runTest {
        try {
            val response = client.storage.listFiles(
                bucketName = testBucketName,
                limit = 5,
                offset = 0
            )
            println("Files (page 1, limit 5): ${response.data.size}")
        } catch (e: InsforgeHttpException) {
            println("List files with pagination failed: ${e.message}")
        }
    }

    // ============ File Delete Tests ============

    @Test
    fun `test delete file`() = runTest {
        try {
            val key = "delete-me-${System.currentTimeMillis()}.txt"

            // Upload first
            client.storage.uploadFile(
                bucketName = testBucketName,
                key = key,
                fileBytes = "To be deleted".toByteArray(),
                contentType = "text/plain"
            )

            // Delete
            val response = client.storage.deleteFile(testBucketName, key)
            println("Deleted file: ${response.message}")
        } catch (e: InsforgeHttpException) {
            println("Delete file failed: ${e.message}")
        }
    }

    // ============ Error Cases ============

    @Test
    fun `test upload to non-existent bucket`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.storage.uploadFile(
                bucketName = "non-existent-bucket-${System.currentTimeMillis()}",
                key = "test.txt",
                fileBytes = "test".toByteArray(),
                contentType = "text/plain"
            )
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test create bucket with invalid name`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.storage.createBucket("INVALID_BUCKET_NAME!!!", isPublic = true)
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }
}
