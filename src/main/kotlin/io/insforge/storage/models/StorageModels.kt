package io.insforge.storage.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============ Bucket Models ============

/**
 * Bucket information
 */
@Serializable
data class Bucket(
    val name: String,
    @SerialName("isPublic")
    val isPublic: Boolean = true
)

/**
 * Response when listing buckets
 */
@Serializable
data class ListBucketsResponse(
    val buckets: List<String>
)

/**
 * Request to create a bucket
 */
@Serializable
data class CreateBucketRequest(
    val bucketName: String,
    val isPublic: Boolean = true
)

/**
 * Response when creating a bucket
 */
@Serializable
data class CreateBucketResponse(
    val message: String,
    val bucket: String? = null,
    val bucketName: String? = null
)

/**
 * Request to update a bucket
 */
@Serializable
data class UpdateBucketRequest(
    val isPublic: Boolean
)

/**
 * Response when updating a bucket
 */
@Serializable
data class UpdateBucketResponse(
    val message: String,
    val bucket: String,
    val isPublic: Boolean
)

/**
 * Response when deleting a bucket
 */
@Serializable
data class DeleteBucketResponse(
    val message: String
)

// ============ File/Object Models ============

/**
 * Stored file/object information
 */
@Serializable
data class StoredFile(
    val bucket: String,
    val key: String,
    val size: Long,
    val mimeType: String? = null,
    val uploadedAt: String,
    val url: String
)

/**
 * Response when listing files in a bucket
 */
@Serializable
data class ListFilesResponse(
    val data: List<StoredFile>,
    val pagination: Pagination,
    val nextActions: String? = null
)

/**
 * Pagination information
 */
@Serializable
data class Pagination(
    val offset: Int,
    val limit: Int,
    val total: Int
)

/**
 * Response when deleting a file
 */
@Serializable
data class DeleteFileResponse(
    val message: String
)

// ============ Upload Strategy ============

/**
 * Request to get upload strategy
 */
@Serializable
data class UploadStrategyRequest(
    val filename: String,
    val contentType: String? = null,
    val size: Long? = null
)

/**
 * Upload strategy response - determines how to upload files
 *
 * For S3 backend: Returns presigned POST URL with form fields
 * For local storage: Returns direct upload endpoint
 */
@Serializable
data class UploadStrategy(
    /** Upload method: "presigned" for S3, "direct" for local storage */
    val method: UploadMethod,
    /** URL to upload the file to */
    val uploadUrl: String,
    /** Form fields for presigned POST (S3 only) */
    val fields: Map<String, String>? = null,
    /** Generated unique key for the file */
    val key: String,
    /** Whether upload confirmation is required (true for S3) */
    val confirmRequired: Boolean,
    /** URL to confirm the upload (if confirmRequired is true) */
    val confirmUrl: String? = null,
    /** Expiration time for presigned URL (S3 only) */
    val expiresAt: String? = null
)

/**
 * Upload method enum
 */
@Serializable
enum class UploadMethod {
    @SerialName("presigned")
    PRESIGNED,
    @SerialName("direct")
    DIRECT
}

/**
 * Request to confirm a presigned upload
 */
@Serializable
data class ConfirmUploadRequest(
    val size: Long,
    val contentType: String? = null,
    val etag: String? = null
)

// ============ Download Strategy ============

/**
 * Request to get download strategy
 */
@Serializable
data class DownloadStrategyRequest(
    val expiresIn: Int? = null
)

/**
 * Download strategy response - determines how to download files
 *
 * For S3 public bucket: Direct URL
 * For S3 private bucket: Presigned URL with expiration
 * For local storage: Direct endpoint
 */
@Serializable
data class DownloadStrategy(
    /** Download method: "presigned" or "direct" */
    val method: DownloadMethod,
    /** URL to download the file from */
    val url: String,
    /** Expiration time for presigned URLs (only for private S3 buckets) */
    val expiresAt: String? = null,
    /** Optional headers to include in the download request */
    val headers: Map<String, String>? = null
)

/**
 * Download method enum
 */
@Serializable
enum class DownloadMethod {
    @SerialName("presigned")
    PRESIGNED,
    @SerialName("direct")
    DIRECT
}

// ============ Upload Options ============

/**
 * Options for file upload
 */
class UploadOptions {
    /** Content type of the file (auto-detected if not specified) */
    var contentType: String? = null

    /** Whether to upsert the file if it already exists */
    var upsert: Boolean = false

    /** Custom metadata for the file */
    var metadata: Map<String, String>? = null
}

/**
 * Result of a file upload operation
 */
data class FileUploadResponse(
    /** The bucket containing the file */
    val bucket: String,
    /** The key/path of the uploaded file */
    val key: String,
    /** Size of the uploaded file in bytes */
    val size: Long,
    /** MIME type of the file */
    val mimeType: String?,
    /** URL to access the file */
    val url: String
)

// ============ List Options ============

/**
 * Filter options for listing files in a bucket
 */
class BucketListFilter {
    /** Filter objects by key prefix */
    var prefix: String? = null

    /** Maximum number of results to return (1-1000, default 100) */
    var limit: Int = 100

    /** Offset for pagination */
    var offset: Int = 0

    /** Sort column */
    var sortBy: String? = null

    /** Sort order */
    var sortOrder: SortOrder = SortOrder.ASC
}

/**
 * Sort order enum
 */
enum class SortOrder {
    ASC, DESC
}

// ============ Signed URL ============

/**
 * Signed URL response
 */
data class SignedUrl(
    val signedUrl: String,
    val path: String,
    val expiresAt: String? = null
)

/**
 * Signed upload URL response
 */
data class SignedUploadUrl(
    val url: String,
    val path: String,
    val token: String,
    val expiresAt: String? = null
)
