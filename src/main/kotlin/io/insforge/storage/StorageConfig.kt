package io.insforge.storage

/**
 * Configuration for the Storage module
 */
class StorageConfig {
    /**
     * Default bucket to use (optional)
     */
    var defaultBucket: String? = null

    /**
     * Maximum file size for uploads (in bytes)
     */
    var maxFileSize: Long = 100 * 1024 * 1024 // 100MB

    /**
     * Enable automatic content type detection
     */
    var autoDetectContentType: Boolean = true
}
