package io.insforge.storage

import io.insforge.InsforgeClient
import io.insforge.InsforgeClientBuilder
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.InsforgePluginProvider
import io.insforge.storage.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.json.Json

/**
 * Storage module for InsForge (S3-compatible object storage)
 *
 * Install this module in your Insforge client:
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(Storage)
 * }
 *
 * // Create bucket
 * client.storage.createBucket("avatars", isPublic = true)
 *
 * // Upload file
 * client.storage.uploadFile("avatars", "user123.jpg", fileBytes, "image/jpeg")
 *
 * // Download file
 * val bytes = client.storage.downloadFile("avatars", "user123.jpg")
 * ```
 */
class Storage internal constructor(
    private val client: InsforgeClient,
    private val config: StorageConfig
) : InsforgePlugin<StorageConfig> {

    override val key: String = Storage.key

    private val baseUrl = "${client.baseURL}/api/storage"

    // ============ Bucket Management ============

    /**
     * List all buckets
     */
    suspend fun listBuckets(): List<BucketInfo> {
        val response = client.httpClient.get("$baseUrl/buckets")
        return handleResponse(response)
    }

    /**
     * Create a new bucket
     */
    suspend fun createBucket(bucketName: String, isPublic: Boolean = true): CreateBucketResponse {
        val response = client.httpClient.post("$baseUrl/buckets") {
            contentType(ContentType.Application.Json)
            setBody(CreateBucketRequest(bucketName, isPublic))
        }
        return handleResponse(response)
    }

    /**
     * Update bucket visibility
     */
    suspend fun updateBucket(bucketName: String, isPublic: Boolean): UpdateBucketResponse {
        val response = client.httpClient.patch("$baseUrl/buckets/$bucketName") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("isPublic" to isPublic))
        }
        return handleResponse(response)
    }

    /**
     * Delete a bucket
     */
    suspend fun deleteBucket(bucketName: String): DeleteBucketResponse {
        val response = client.httpClient.delete("$baseUrl/buckets/$bucketName/objects")
        return handleResponse(response)
    }

    // ============ File Operations ============

    /**
     * Upload a file with specific key
     */
    suspend fun uploadFile(
        bucketName: String,
        key: String,
        fileBytes: ByteArray,
        contentType: String = "application/octet-stream"
    ): StoredFile {
        val response = client.httpClient.put("$baseUrl/buckets/$bucketName/objects/$key") {
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", fileBytes, Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$key\"")
                    })
                }
            ))
        }
        return handleResponse(response)
    }

    /**
     * Upload a file with auto-generated key
     */
    suspend fun uploadFile(
        bucketName: String,
        fileBytes: ByteArray,
        filename: String,
        contentType: String = "application/octet-stream"
    ): StoredFile {
        val response = client.httpClient.post("$baseUrl/buckets/$bucketName/objects") {
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", fileBytes, Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    })
                }
            ))
        }
        return handleResponse(response)
    }

    /**
     * Get upload strategy (presigned URL for S3 or direct upload)
     */
    suspend fun getUploadStrategy(
        bucketName: String,
        filename: String,
        contentType: String? = null,
        size: Int? = null
    ): UploadStrategy {
        val response = client.httpClient.post("$baseUrl/buckets/$bucketName/upload-strategy") {
            contentType(ContentType.Application.Json)
            setBody(UploadStrategyRequest(filename, contentType, size))
        }
        return handleResponse(response)
    }

    /**
     * Confirm presigned upload (for S3)
     */
    suspend fun confirmUpload(
        bucketName: String,
        objectKey: String,
        size: Int,
        contentType: String? = null,
        etag: String? = null
    ): StoredFile {
        val response = client.httpClient.post("$baseUrl/buckets/$bucketName/objects/$objectKey/confirm-upload") {
            contentType(ContentType.Application.Json)
            setBody(ConfirmUploadRequest(size, contentType, etag))
        }
        return handleResponse(response)
    }

    /**
     * Download a file
     */
    suspend fun downloadFile(bucketName: String, key: String): ByteArray {
        val response = client.httpClient.get("$baseUrl/buckets/$bucketName/objects/$key")
        return when (response.status) {
            HttpStatusCode.OK -> response.body()
            else -> throw handleError(response)
        }
    }

    /**
     * Get download strategy (presigned URL for S3 private or direct URL)
     */
    suspend fun getDownloadStrategy(
        bucketName: String,
        objectKey: String,
        expiresIn: Int = 3600
    ): DownloadStrategy {
        val response = client.httpClient.post("$baseUrl/buckets/$bucketName/objects/$objectKey/download-strategy") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("expiresIn" to expiresIn))
        }
        return handleResponse(response)
    }

    /**
     * Delete a file
     */
    suspend fun deleteFile(bucketName: String, key: String): DeleteFileResponse {
        val response = client.httpClient.delete("$baseUrl/buckets/$bucketName/objects/$key")
        return handleResponse(response)
    }

    /**
     * List files in a bucket
     */
    suspend fun listFiles(
        bucketName: String,
        prefix: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): ListFilesResponse {
        val response = client.httpClient.get("$baseUrl/buckets/$bucketName/objects") {
            prefix?.let { parameter("prefix", it) }
            parameter("limit", limit)
            parameter("offset", offset)
        }
        return handleResponse(response)
    }

    // ============ Helper Methods ============

    @PublishedApi
    internal suspend inline fun <reified T> handleResponse(response: HttpResponse): T {
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.NoContent -> {
                if (T::class == Unit::class) {
                    Unit as T
                } else {
                    response.body()
                }
            }
            else -> throw handleError(response)
        }
    }

    @PublishedApi
    internal suspend fun handleError(response: HttpResponse): InsforgeHttpException {
        val errorBody = response.bodyAsText()
        val error = try {
            Json.decodeFromString<io.insforge.exceptions.ErrorResponse>(errorBody)
        } catch (e: Exception) {
            throw InsforgeHttpException(
                statusCode = response.status.value,
                error = "UNKNOWN_ERROR",
                message = errorBody.ifEmpty { response.status.description }
            )
        }

        return InsforgeHttpException(
            statusCode = error.statusCode,
            error = error.error,
            message = error.message,
            nextActions = error.nextActions
        )
    }

    companion object : InsforgePluginProvider<StorageConfig, Storage> {
        override val key: String = "storage"

        override fun createConfig(configure: StorageConfig.() -> Unit): StorageConfig {
            return StorageConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: StorageConfig): Storage {
            return Storage(client, config)
        }
    }
}

/**
 * Extension property for accessing Storage module
 */
val InsforgeClient.storage: Storage
    get() = plugin(Storage.key)
