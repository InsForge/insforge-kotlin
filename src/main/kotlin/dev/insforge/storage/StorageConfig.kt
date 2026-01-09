package dev.insforge.storage

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the Storage module
 */
class StorageConfig {
    /**
     * Default bucket to use when not specified in operations
     */
    var defaultBucket: String? = null

    /**
     * Timeout for file transfer operations (upload/download)
     * Default: 120 seconds
     */
    var transferTimeout: Duration = 120.seconds

    /**
     * Maximum file size for uploads (in bytes)
     * Default: 100MB
     */
    var maxFileSize: Long = 100 * 1024 * 1024

    /**
     * Enable automatic content type detection based on file extension
     * Default: true
     */
    var autoDetectContentType: Boolean = true

    /**
     * Custom content type mappings (extension -> content type)
     * These take precedence over built-in mappings
     */
    var customContentTypes: Map<String, String> = emptyMap()
}

/**
 * Builder for bucket configuration when creating/updating buckets
 */
class BucketBuilder {
    /**
     * Whether the bucket should be publicly accessible
     */
    var isPublic: Boolean? = null

    /**
     * Allowed MIME types for uploads (null means all types allowed)
     */
    var allowedMimeTypes: List<String>? = null

    /**
     * Maximum file size limit in bytes (null means no limit)
     */
    var fileSizeLimit: Long? = null
}
