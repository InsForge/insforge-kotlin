package io.insforge.storage

import io.insforge.InsforgeClient
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.storage.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

/**
 * API for interacting with a specific storage bucket.
 *
 * Provides operations for uploading, downloading, and managing files within a bucket.
 * Automatically handles both direct uploads (local storage) and presigned URL uploads (S3).
 *
 * Example usage:
 * ```kotlin
 * val bucket = client.storage["avatars"]
 *
 * // Upload a file
 * val result = bucket.upload("user123.jpg", imageBytes) {
 *     contentType = "image/jpeg"
 * }
 *
 * // Download a file
 * val bytes = bucket.download("user123.jpg")
 *
 * // List files
 * val files = bucket.list {
 *     prefix = "users/"
 *     limit = 50
 * }
 * ```
 */
interface BucketApi {

    /**
     * The ID/name of the bucket
     */
    val bucketId: String

    /**
     * The InsforgeClient instance
     */
    val insforgeClient: InsforgeClient

    // ============ Upload Operations ============

    /**
     * Upload a file to the bucket with the specified path.
     *
     * This method automatically determines the best upload strategy:
     * - For local storage: Direct upload to the server
     * - For S3: Gets presigned URL, uploads to S3, then confirms
     *
     * @param path The path/key where the file will be stored
     * @param data The file data as ByteArray
     * @param options Additional upload options (content type, upsert, metadata)
     * @return FileUploadResponse containing the uploaded file information
     * @throws IllegalArgumentException if data is empty
     * @throws InsforgeHttpException on API errors
     */
    suspend fun upload(
        path: String,
        data: ByteArray,
        options: UploadOptions.() -> Unit = {}
    ): FileUploadResponse

    /**
     * Upload a file to the bucket with auto-generated key.
     *
     * The server will generate a unique key based on the filename.
     *
     * @param filename Original filename (used for key generation and content type detection)
     * @param data The file data as ByteArray
     * @param options Additional upload options
     * @return FileUploadResponse containing the uploaded file information
     */
    suspend fun uploadWithAutoKey(
        filename: String,
        data: ByteArray,
        options: UploadOptions.() -> Unit = {}
    ): FileUploadResponse

    // ============ Download Operations ============

    /**
     * Download a file from the bucket.
     *
     * This method automatically handles:
     * - Direct downloads for local storage and public S3 buckets
     * - Presigned URL downloads for private S3 buckets
     *
     * @param path The path/key of the file to download
     * @return The file data as ByteArray
     * @throws InsforgeHttpException if file not found or access denied
     */
    suspend fun download(path: String): ByteArray

    /**
     * Get the download URL for a file.
     *
     * For public buckets, returns a direct URL.
     * For private buckets, returns a presigned URL with expiration.
     *
     * @param path The path/key of the file
     * @param expiresIn URL expiration time in seconds (default 3600)
     * @return DownloadStrategy containing the URL and method
     */
    suspend fun getDownloadUrl(path: String, expiresIn: Int = 3600): DownloadStrategy

    /**
     * Create a signed download URL that expires after the specified duration.
     *
     * @param path The path/key of the file
     * @param expiresIn URL expiration time in seconds
     * @return The signed URL string
     */
    suspend fun createSignedUrl(path: String, expiresIn: Int = 3600): String

    // ============ Delete Operations ============

    /**
     * Delete a file from the bucket.
     *
     * @param path The path/key of the file to delete
     * @throws InsforgeHttpException if file not found
     */
    suspend fun delete(path: String)

    /**
     * Delete multiple files from the bucket.
     *
     * @param paths The paths/keys of the files to delete
     */
    suspend fun delete(paths: Collection<String>)

    /**
     * Delete multiple files from the bucket.
     *
     * @param paths The paths/keys of the files to delete
     */
    suspend fun delete(vararg paths: String) = delete(paths.toList())

    // ============ List Operations ============

    /**
     * List files in the bucket.
     *
     * @param filter Filter options (prefix, limit, offset, sorting)
     * @return List of StoredFile objects
     */
    suspend fun list(filter: BucketListFilter.() -> Unit = {}): List<StoredFile>

    /**
     * Check if a file exists in the bucket.
     *
     * @param path The path/key of the file
     * @return true if the file exists, false otherwise
     */
    suspend fun exists(path: String): Boolean

    // ============ Upload Strategy ============

    /**
     * Get the upload strategy for a file.
     *
     * Use this to manually handle the upload process instead of using [upload].
     *
     * @param filename Original filename
     * @param contentType MIME type of the file
     * @param size File size in bytes
     * @return UploadStrategy containing upload URL and method
     */
    suspend fun getUploadStrategy(
        filename: String,
        contentType: String? = null,
        size: Long? = null
    ): UploadStrategy

    /**
     * Confirm a presigned upload after uploading to S3.
     *
     * This is only needed when using presigned uploads (S3 backend).
     *
     * @param objectKey The key returned from getUploadStrategy
     * @param size File size in bytes
     * @param contentType MIME type of the file
     * @param etag S3 ETag of the uploaded object (optional)
     * @return StoredFile containing the confirmed file information
     */
    suspend fun confirmUpload(
        objectKey: String,
        size: Long,
        contentType: String? = null,
        etag: String? = null
    ): StoredFile

    companion object {
        /**
         * Header for upsert operations
         */
        const val UPSERT_HEADER = "x-upsert"

        /**
         * Header for custom metadata
         */
        const val METADATA_HEADER = "x-metadata"
    }
}

/**
 * Internal implementation of BucketApi
 */
internal class BucketApiImpl(
    override val bucketId: String,
    private val storage: Storage
) : BucketApi {

    override val insforgeClient: InsforgeClient
        get() = storage.client

    private val httpClient: HttpClient
        get() = insforgeClient.httpClient

    private val baseUrl: String
        get() = "${insforgeClient.baseURL}/api/storage/buckets/$bucketId"

    private val json = Json { ignoreUnknownKeys = true }

    // ============ Upload Operations ============

    override suspend fun upload(
        path: String,
        data: ByteArray,
        options: UploadOptions.() -> Unit
    ): FileUploadResponse {
        require(data.isNotEmpty()) { "The data to upload should not be empty" }

        val uploadOptions = UploadOptions().apply(options)
        val contentType = uploadOptions.contentType
            ?: detectContentType(path)
            ?: "application/octet-stream"

        // Get upload strategy first
        val strategy = getUploadStrategy(path, contentType, data.size.toLong())

        return when (strategy.method) {
            UploadMethod.DIRECT -> {
                // Direct upload to InsForge server
                uploadDirect(path, data, contentType, uploadOptions)
            }
            UploadMethod.PRESIGNED -> {
                // Upload to S3 using presigned URL, then confirm
                uploadPresigned(strategy, data, contentType, uploadOptions)
            }
        }
    }

    override suspend fun uploadWithAutoKey(
        filename: String,
        data: ByteArray,
        options: UploadOptions.() -> Unit
    ): FileUploadResponse {
        require(data.isNotEmpty()) { "The data to upload should not be empty" }

        val uploadOptions = UploadOptions().apply(options)
        val contentType = uploadOptions.contentType
            ?: detectContentType(filename)
            ?: "application/octet-stream"

        // Get upload strategy with filename for auto-key generation
        val strategy = getUploadStrategy(filename, contentType, data.size.toLong())

        return when (strategy.method) {
            UploadMethod.DIRECT -> {
                // Direct upload with auto-generated key
                uploadDirectAutoKey(filename, data, contentType, uploadOptions)
            }
            UploadMethod.PRESIGNED -> {
                // Upload to S3 using presigned URL, then confirm
                uploadPresigned(strategy, data, contentType, uploadOptions)
            }
        }
    }

    private suspend fun uploadDirect(
        path: String,
        data: ByteArray,
        contentType: String,
        options: UploadOptions
    ): FileUploadResponse {
        val response = httpClient.put("$baseUrl/objects/$path") {
            if (options.upsert) {
                header(BucketApi.UPSERT_HEADER, "true")
            }
            options.metadata?.let { metadata ->
                header(BucketApi.METADATA_HEADER, json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    metadata
                ))
            }
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", data, Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$path\"")
                    })
                }
            ))
        }

        val storedFile = handleResponse<StoredFile>(response)
        return FileUploadResponse(
            bucket = storedFile.bucket,
            key = storedFile.key,
            size = storedFile.size,
            mimeType = storedFile.mimeType,
            url = storedFile.url
        )
    }

    private suspend fun uploadDirectAutoKey(
        filename: String,
        data: ByteArray,
        contentType: String,
        options: UploadOptions
    ): FileUploadResponse {
        val response = httpClient.post("$baseUrl/objects") {
            if (options.upsert) {
                header(BucketApi.UPSERT_HEADER, "true")
            }
            options.metadata?.let { metadata ->
                header(BucketApi.METADATA_HEADER, json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    metadata
                ))
            }
            setBody(MultiPartFormDataContent(
                formData {
                    append("file", data, Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    })
                }
            ))
        }

        val storedFile = handleResponse<StoredFile>(response)
        return FileUploadResponse(
            bucket = storedFile.bucket,
            key = storedFile.key,
            size = storedFile.size,
            mimeType = storedFile.mimeType,
            url = storedFile.url
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun uploadPresigned(
        strategy: UploadStrategy,
        data: ByteArray,
        contentType: String,
        options: UploadOptions
    ): FileUploadResponse {
        // Upload to S3 using presigned POST
        val fields = strategy.fields ?: emptyMap()

        val s3Response = httpClient.post(strategy.uploadUrl) {
            setBody(MultiPartFormDataContent(
                formData {
                    // Add all presigned form fields
                    fields.forEach { (key, value) ->
                        append(key, value)
                    }
                    // Add Content-Type field
                    append("Content-Type", contentType)
                    // Add the file last (required for S3)
                    append("file", data, Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${strategy.key}\"")
                    })
                }
            ))
        }

        // Check S3 upload success (204 No Content or 200 OK)
        if (s3Response.status != HttpStatusCode.NoContent && s3Response.status != HttpStatusCode.OK) {
            throw InsforgeHttpException(
                statusCode = s3Response.status.value,
                error = "S3_UPLOAD_FAILED",
                message = "Failed to upload file to S3: ${s3Response.status}"
            )
        }

        // Extract ETag from S3 response if available
        val etag = s3Response.headers["ETag"]?.removeSurrounding("\"")

        // Confirm the upload with InsForge
        if (strategy.confirmRequired) {
            val storedFile = confirmUpload(
                objectKey = strategy.key,
                size = data.size.toLong(),
                contentType = contentType,
                etag = etag
            )
            return FileUploadResponse(
                bucket = storedFile.bucket,
                key = storedFile.key,
                size = storedFile.size,
                mimeType = storedFile.mimeType,
                url = storedFile.url
            )
        }

        // If no confirmation required, return basic info
        return FileUploadResponse(
            bucket = bucketId,
            key = strategy.key,
            size = data.size.toLong(),
            mimeType = contentType,
            url = strategy.uploadUrl
        )
    }

    // ============ Download Operations ============

    override suspend fun download(path: String): ByteArray {
        // First get download strategy to determine the best approach
        val strategy = getDownloadUrl(path)

        return when (strategy.method) {
            DownloadMethod.DIRECT -> {
                // Download directly from InsForge or public S3 URL
                val response = httpClient.get(strategy.url)
                if (response.status == HttpStatusCode.OK) {
                    response.body()
                } else {
                    throw handleError(response)
                }
            }
            DownloadMethod.PRESIGNED -> {
                // Download from presigned S3 URL
                val response = httpClient.get(strategy.url) {
                    strategy.headers?.forEach { (key, value) ->
                        header(key, value)
                    }
                }
                if (response.status == HttpStatusCode.OK) {
                    response.body()
                } else {
                    throw InsforgeHttpException(
                        statusCode = response.status.value,
                        error = "DOWNLOAD_FAILED",
                        message = "Failed to download file from presigned URL"
                    )
                }
            }
        }
    }

    override suspend fun getDownloadUrl(path: String, expiresIn: Int): DownloadStrategy {
        val response = httpClient.post("$baseUrl/objects/$path/download-strategy") {
            contentType(ContentType.Application.Json)
            setBody(DownloadStrategyRequest(expiresIn))
        }
        return handleResponse(response)
    }

    override suspend fun createSignedUrl(path: String, expiresIn: Int): String {
        val strategy = getDownloadUrl(path, expiresIn)
        return strategy.url
    }

    // ============ Delete Operations ============

    override suspend fun delete(path: String) {
        val response = httpClient.delete("$baseUrl/objects/$path")
        handleResponse<DeleteFileResponse>(response)
    }

    override suspend fun delete(paths: Collection<String>) {
        // Delete files one by one (API doesn't support batch delete)
        paths.forEach { path ->
            delete(path)
        }
    }

    // ============ List Operations ============

    override suspend fun list(filter: BucketListFilter.() -> Unit): List<StoredFile> {
        val options = BucketListFilter().apply(filter)
        val response = httpClient.get("$baseUrl/objects") {
            options.prefix?.let { parameter("prefix", it) }
            parameter("limit", options.limit)
            parameter("offset", options.offset)
            options.sortBy?.let { parameter("sortBy", it) }
        }
        val result = handleResponse<ListFilesResponse>(response)
        return result.data
    }

    override suspend fun exists(path: String): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/objects/$path") {
                // Use HEAD request if supported, otherwise GET
            }
            response.status == HttpStatusCode.OK
        } catch (e: InsforgeHttpException) {
            if (e.statusCode == 404) false else throw e
        }
    }

    // ============ Upload Strategy ============

    override suspend fun getUploadStrategy(
        filename: String,
        contentType: String?,
        size: Long?
    ): UploadStrategy {
        val response = httpClient.post("$baseUrl/upload-strategy") {
            contentType(ContentType.Application.Json)
            setBody(UploadStrategyRequest(filename, contentType, size))
        }
        return handleResponse(response)
    }

    override suspend fun confirmUpload(
        objectKey: String,
        size: Long,
        contentType: String?,
        etag: String?
    ): StoredFile {
        val response = httpClient.post("$baseUrl/objects/$objectKey/confirm-upload") {
            contentType(ContentType.Application.Json)
            setBody(ConfirmUploadRequest(size, contentType, etag))
        }
        return handleResponse(response)
    }

    // ============ Helper Methods ============

    private fun detectContentType(filename: String): String? {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return CONTENT_TYPE_MAP[extension]
    }

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

    companion object {
        private val CONTENT_TYPE_MAP = mapOf(
            // Images
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "bmp" to "image/bmp",
            // Documents
            "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // Text
            "txt" to "text/plain",
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "js" to "application/javascript",
            "json" to "application/json",
            "xml" to "application/xml",
            "csv" to "text/csv",
            "md" to "text/markdown",
            // Archives
            "zip" to "application/zip",
            "rar" to "application/vnd.rar",
            "7z" to "application/x-7z-compressed",
            "tar" to "application/x-tar",
            "gz" to "application/gzip",
            // Audio
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "m4a" to "audio/mp4",
            // Video
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "avi" to "video/x-msvideo",
            "mov" to "video/quicktime",
            "mkv" to "video/x-matroska"
        )
    }
}
