package dev.insforge.storage

import dev.insforge.InsforgeClient
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.plugins.InsforgePlugin
import dev.insforge.plugins.InsforgePluginProvider
import dev.insforge.storage.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Storage module for InsForge (S3-compatible object storage)
 *
 * Provides bucket-based file storage similar to AWS S3 or Supabase Storage.
 * Supports both local storage and S3 backends transparently.
 *
 * ## Basic Usage
 *
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(Storage) {
 *         defaultBucket = "avatars"
 *         transferTimeout = 60.seconds
 *     }
 * }
 *
 * // Access a bucket
 * val bucket = client.storage["avatars"]
 * // or
 * val bucket = client.storage.from("avatars")
 *
 * // Upload a file
 * val result = bucket.upload("user123.jpg", imageBytes) {
 *     contentType = "image/jpeg"
 * }
 *
 * // Download a file
 * val bytes = bucket.download("user123.jpg")
 *
 * // Get download URL
 * val url = bucket.createSignedUrl("user123.jpg", expiresIn = 3600)
 *
 * // List files
 * val files = bucket.list {
 *     prefix = "users/"
 *     limit = 50
 * }
 *
 * // Delete a file
 * bucket.delete("user123.jpg")
 * ```
 *
 * ## Bucket Management (Admin)
 *
 * ```kotlin
 * // List all buckets
 * val buckets = client.storage.listBuckets()
 *
 * // Create a bucket
 * client.storage.createBucket("documents") {
 *     isPublic = false
 * }
 *
 * // Update bucket visibility
 * client.storage.updateBucket("documents") {
 *     isPublic = true
 * }
 *
 * // Delete a bucket
 * client.storage.deleteBucket("documents")
 * ```
 *
 * ## Upload Strategies
 *
 * The SDK automatically handles different upload strategies:
 * - **Direct upload**: For local storage backend, files are uploaded directly to InsForge
 * - **Presigned upload**: For S3 backend, the SDK gets a presigned URL, uploads to S3, then confirms
 *
 * You can also manually control the upload process:
 *
 * ```kotlin
 * // Get upload strategy
 * val strategy = bucket.getUploadStrategy("photo.jpg", "image/jpeg", fileSize)
 *
 * when (strategy.method) {
 *     UploadMethod.DIRECT -> {
 *         // Upload directly to InsForge
 *     }
 *     UploadMethod.PRESIGNED -> {
 *         // Upload to S3 using strategy.uploadUrl and strategy.fields
 *         // Then confirm: bucket.confirmUpload(strategy.key, size, contentType)
 *     }
 * }
 * ```
 */
class Storage internal constructor(
    internal val client: InsforgeClient,
    private val config: StorageConfig
) : InsforgePlugin<StorageConfig> {

    override val key: String = Storage.key

    private val baseUrl = "${client.baseURL}/api/storage"
    private val bucketCache = mutableMapOf<String, BucketApi>()

    // ============ Bucket Access ============

    /**
     * Get a BucketApi for interacting with a specific bucket.
     *
     * @param bucketId The name/ID of the bucket
     * @return BucketApi instance for the bucket
     */
    operator fun get(bucketId: String): BucketApi {
        return bucketCache.getOrPut(bucketId) {
            BucketApiImpl(bucketId, this)
        }
    }

    /**
     * Get a BucketApi for interacting with a specific bucket.
     *
     * @param bucketId The name/ID of the bucket
     * @return BucketApi instance for the bucket
     */
    fun from(bucketId: String): BucketApi = get(bucketId)

    // ============ Bucket Management (Admin) ============

    /**
     * List all buckets.
     *
     * @return List of Bucket objects
     */
    suspend fun listBuckets(): List<Bucket> {
        val response = client.httpClient.get("$baseUrl/buckets")
        return handleResponse<List<Bucket>>(response)
    }

    /**
     * Create a new bucket.
     *
     * @param bucketId The name/ID for the new bucket
     * @param builder Configuration for the bucket (public/private, allowed types, size limits)
     */
    suspend fun createBucket(bucketId: String, builder: BucketBuilder.() -> Unit = {}) {
        val bucketBuilder = BucketBuilder().apply(builder)
        val response = client.httpClient.post("$baseUrl/buckets") {
            contentType(ContentType.Application.Json)
            setBody(CreateBucketRequest(
                bucketName = bucketId,
                isPublic = bucketBuilder.isPublic ?: true
            ))
        }
        handleResponse<CreateBucketResponse>(response)
    }

    /**
     * Update an existing bucket.
     *
     * @param bucketId The name/ID of the bucket to update
     * @param builder New configuration for the bucket
     */
    suspend fun updateBucket(bucketId: String, builder: BucketBuilder.() -> Unit = {}) {
        val bucketBuilder = BucketBuilder().apply(builder)
        val response = client.httpClient.patch("$baseUrl/buckets/$bucketId") {
            contentType(ContentType.Application.Json)
            setBody(UpdateBucketRequest(
                isPublic = bucketBuilder.isPublic ?: true
            ))
        }
        handleResponse<UpdateBucketResponse>(response)
    }

    /**
     * Delete a bucket and all its contents.
     *
     * @param bucketId The name/ID of the bucket to delete
     */
    suspend fun deleteBucket(bucketId: String) {
        val response = client.httpClient.delete("$baseUrl/buckets/$bucketId")
        handleResponse<DeleteBucketResponse>(response)
        bucketCache.remove(bucketId)
    }

    /**
     * Empty a bucket (delete all files but keep the bucket).
     *
     * @param bucketId The name/ID of the bucket to empty
     */
    suspend fun emptyBucket(bucketId: String) {
        val bucket = get(bucketId)
        val files = bucket.list()
        files.forEach { file ->
            bucket.delete(file.key)
        }
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
            Json.decodeFromString<dev.insforge.exceptions.ErrorResponse>(errorBody)
        } catch (e: Exception) {
            return InsforgeHttpException(
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
 * Extension property for accessing Storage module.
 *
 * Usage:
 * ```kotlin
 * val bucket = client.storage["avatars"]
 * val files = bucket.list()
 * ```
 */
val InsforgeClient.storage: Storage
    get() = plugin(Storage.key)
