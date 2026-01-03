package io.insforge.storage.models

import kotlinx.serialization.Serializable

// ============ Bucket Models ============

@Serializable
data class BucketInfo(
    val name: String,
    val public: Boolean,
    val createdAt: String
)

@Serializable
data class CreateBucketRequest(
    val bucketName: String,
    val isPublic: Boolean = true
)

@Serializable
data class CreateBucketResponse(
    val message: String,
    val bucketName: String
)

@Serializable
data class UpdateBucketResponse(
    val message: String,
    val bucket: String,
    val isPublic: Boolean
)

@Serializable
data class DeleteBucketResponse(
    val message: String
)

// ============ File Models ============

@Serializable
data class StoredFile(
    val bucket: String,
    val key: String,
    val size: Int,
    val mimeType: String? = null,
    val uploadedAt: String,
    val url: String
)

@Serializable
data class ListFilesResponse(
    val data: List<StoredFile>,
    val pagination: Pagination,
    val nextActions: String? = null
)

@Serializable
data class Pagination(
    val offset: Int,
    val limit: Int,
    val total: Int
)

@Serializable
data class DeleteFileResponse(
    val message: String
)

// ============ Upload Strategy ============

@Serializable
data class UploadStrategyRequest(
    val filename: String,
    val contentType: String? = null,
    val size: Int? = null
)

@Serializable
data class UploadStrategy(
    val method: String, // "presigned" or "direct"
    val uploadUrl: String,
    val fields: Map<String, String>? = null, // For presigned POST
    val key: String,
    val confirmRequired: Boolean,
    val confirmUrl: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class ConfirmUploadRequest(
    val size: Int,
    val contentType: String? = null,
    val etag: String? = null
)

// ============ Download Strategy ============

@Serializable
data class DownloadStrategy(
    val method: String, // "presigned" or "direct"
    val url: String,
    val expiresAt: String? = null,
    val headers: Map<String, String>? = null
)
