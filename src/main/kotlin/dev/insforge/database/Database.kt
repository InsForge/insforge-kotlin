package dev.insforge.database

import dev.insforge.InsforgeClient
import dev.insforge.InsforgeClientBuilder
import dev.insforge.database.models.*
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.plugins.InsforgePlugin
import dev.insforge.plugins.InsforgePluginProvider
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Database module for Insforge (PostgREST-style API)
 *
 * Install this module in your Insforge client:
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(Database)
 * }
 *
 * // Query records
 * val posts = client.database.from("posts")
 *     .select()
 *     .eq("status", "active")
 *     .execute<Post>()
 *
 * // Insert records
 * client.database.from("posts")
 *     .insert(listOf(newPost))
 *     .execute()
 * ```
 */
class Database internal constructor(
    private val client: InsforgeClient,
    private val config: DatabaseConfig
) : InsforgePlugin<DatabaseConfig> {

    override val key: String = Database.key

    private val baseUrl = "${client.baseURL}/api/database"

    /**
     * Start a query on a table
     *
     * @param tableName Name of the table to query
     */
    fun from(tableName: String): TableQuery {
        return TableQuery(client, baseUrl, tableName)
    }

    // ============ Table Management (Admin) ============

    /**
     * List all tables (admin only)
     */
    suspend fun listTables(): List<String> {
        val response = client.httpClient.get("$baseUrl/tables")
        return handleResponse(response)
    }

    /**
     * Get table schema (admin only)
     *
     * @param tableName Name of the table
     */
    suspend fun getTableSchema(tableName: String): TableSchema {
        val response = client.httpClient.get("$baseUrl/tables/$tableName")
        return handleResponse(response)
    }

    /**
     * Create a new table (admin only)
     *
     * @param tableName Name of the new table
     * @param columns List of column definitions
     */
    suspend fun createTable(tableName: String, columns: List<ColumnDefinition>): CreateTableResponse {
        val response = client.httpClient.post("$baseUrl/tables") {
            contentType(ContentType.Application.Json)
            setBody(CreateTableRequest(tableName, columns))
        }
        return handleResponse(response)
    }

    /**
     * Update table schema (admin only)
     *
     * @param tableName Name of the table
     * @param operations Schema modification operations
     */
    suspend fun updateTableSchema(tableName: String, operations: TableSchemaUpdate): UpdateTableResponse {
        val response = client.httpClient.patch("$baseUrl/tables/$tableName") {
            contentType(ContentType.Application.Json)
            setBody(operations)
        }
        return handleResponse(response)
    }

    /**
     * Delete a table (admin only)
     *
     * @param tableName Name of the table to delete
     */
    suspend fun deleteTable(tableName: String): DeleteTableResponse {
        val response = client.httpClient.delete("$baseUrl/tables/$tableName")
        return handleResponse(response)
    }

    // ============ Helper Methods ============

    @PublishedApi
    internal suspend inline fun <reified T> handleResponse(response: HttpResponse): T {
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                // Check if body is empty (content length is 0 or null)
                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength == 0L || (contentLength == null && T::class == List::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    emptyList<Any>() as T
                } else {
                    response.body()
                }
            }
            HttpStatusCode.NoContent -> {
                // 204 No Content - return appropriate empty value based on type
                @Suppress("UNCHECKED_CAST")
                if (T::class == Unit::class) {
                    Unit as T
                } else {
                    // For List types (which is what all database queries return), return empty list
                    emptyList<Any>() as T
                }
            }
            else -> {
                val errorBody = response.bodyAsText()
                val error = try {
                    Json.decodeFromString<dev.insforge.exceptions.ErrorResponse>(errorBody)
                } catch (e: Exception) {
                    throw InsforgeHttpException(
                        statusCode = response.status.value,
                        error = "UNKNOWN_ERROR",
                        message = errorBody.ifEmpty { response.status.description }
                    )
                }

                throw InsforgeHttpException(
                    statusCode = error.statusCode,
                    error = error.error,
                    message = error.message,
                    nextActions = error.nextActions
                )
            }
        }
    }

    companion object : InsforgePluginProvider<DatabaseConfig, Database> {
        override val key: String = "database"

        override fun createConfig(configure: DatabaseConfig.() -> Unit): DatabaseConfig {
            return DatabaseConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: DatabaseConfig): Database {
            return Database(client, config)
        }
    }
}

/**
 * Extension property for accessing Database module
 */
val InsforgeClient.database: Database
    get() = plugin(Database.key)
