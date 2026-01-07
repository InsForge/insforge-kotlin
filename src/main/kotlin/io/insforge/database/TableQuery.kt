package io.insforge.database

import io.insforge.InsforgeClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Count algorithm types for database queries.
 *
 * Similar to PostgREST/Supabase count options.
 */
enum class CountType {
    /**
     * Exact count - performs a full table scan.
     * Most accurate but slowest for large tables.
     */
    EXACT,

    /**
     * Planned count - uses PostgreSQL's query planner estimate.
     * Fast but may be inaccurate, especially after bulk operations.
     */
    PLANNED,

    /**
     * Estimated count - uses statistics from pg_class.
     * Fastest but least accurate.
     */
    ESTIMATED
}

/**
 * Query builder for database tables
 */
class TableQuery @PublishedApi internal constructor(
    @PublishedApi internal val client: InsforgeClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val tableName: String
) {
    @PublishedApi internal val filters = mutableMapOf<String, String>()
    @PublishedApi internal var selectColumns: String? = null
    @PublishedApi internal var orderBy: String? = null
    @PublishedApi internal var limitValue: Int? = null
    @PublishedApi internal var offsetValue: Int? = null
    private var operation: Operation = Operation.SELECT

    private enum class Operation {
        SELECT, INSERT, UPDATE, DELETE
    }

    /**
     * Select specific columns
     */
    fun select(columns: String = "*"): TableQuery {
        this.selectColumns = columns
        this.operation = Operation.SELECT
        return this
    }

    /**
     * Equal filter
     */
    fun eq(column: String, value: Any): TableQuery {
        filters[column] = "eq.$value"
        return this
    }

    /**
     * Not equal filter
     */
    fun neq(column: String, value: Any): TableQuery {
        filters[column] = "neq.$value"
        return this
    }

    /**
     * Greater than filter
     */
    fun gt(column: String, value: Any): TableQuery {
        filters[column] = "gt.$value"
        return this
    }

    /**
     * Greater than or equal filter
     */
    fun gte(column: String, value: Any): TableQuery {
        filters[column] = "gte.$value"
        return this
    }

    /**
     * Less than filter
     */
    fun lt(column: String, value: Any): TableQuery {
        filters[column] = "lt.$value"
        return this
    }

    /**
     * Less than or equal filter
     */
    fun lte(column: String, value: Any): TableQuery {
        filters[column] = "lte.$value"
        return this
    }

    /**
     * LIKE filter (case-sensitive pattern matching)
     */
    fun like(column: String, pattern: String): TableQuery {
        filters[column] = "like.$pattern"
        return this
    }

    /**
     * ILIKE filter (case-insensitive pattern matching)
     */
    fun ilike(column: String, pattern: String): TableQuery {
        filters[column] = "ilike.$pattern"
        return this
    }

    /**
     * IN filter (value in list)
     */
    fun `in`(column: String, values: List<Any>): TableQuery {
        filters[column] = "in.(${values.joinToString(",")})"
        return this
    }

    /**
     * IS NULL filter
     */
    fun isNull(column: String): TableQuery {
        filters[column] = "is.null"
        return this
    }

    /**
     * Order by column
     */
    fun order(column: String, ascending: Boolean = true): TableQuery {
        this.orderBy = "$column.${if (ascending) "asc" else "desc"}"
        return this
    }

    /**
     * Limit number of results
     */
    fun limit(count: Int): TableQuery {
        this.limitValue = count
        return this
    }

    /**
     * Skip number of results
     */
    fun offset(count: Int): TableQuery {
        this.offsetValue = count
        return this
    }

    /**
     * Pagination using range (inclusive).
     *
     * Example: `.range(0, 9)` returns the first 10 records (rows 0-9).
     *
     * @param from Starting index (0-based, inclusive)
     * @param to Ending index (inclusive)
     */
    fun range(from: Int, to: Int): TableQuery {
        this.offsetValue = from
        this.limitValue = to - from + 1
        return this
    }

    /**
     * Execute SELECT query
     */
    suspend inline fun <reified T> execute(): List<T> {
        val response = client.httpClient.get("$baseUrl/records/$tableName") {
            selectColumns?.let { parameter("select", it) }
            orderBy?.let { parameter("order", it) }
            limitValue?.let { parameter("limit", it) }
            offsetValue?.let { parameter("offset", it) }
            filters.forEach { (column, filter) ->
                parameter(column, filter)
            }
        }

        val database = client.plugin<Database>(Database.key)
        return database.handleResponse(response)
    }

    /**
     * Insert records - accepts JsonArray for proper serialization
     */
    fun insert(records: JsonArray): InsertQuery {
        return InsertQuery(client, baseUrl, tableName, records)
    }

    /**
     * Insert records from a list - converts to JsonArray
     * Note: Items must be @Serializable or JsonObject
     */
    inline fun <reified T> insertTyped(records: List<T>): InsertQuery {
        val jsonArray = Json.encodeToJsonElement(records) as JsonArray
        return InsertQuery(client, baseUrl, tableName, jsonArray)
    }

    /**
     * Update records matching filters - accepts JsonObject for proper serialization
     */
    fun update(data: JsonObject): UpdateQuery {
        return UpdateQuery(client, baseUrl, tableName, filters, data)
    }

    /**
     * Update records with a map - converts to JsonObject
     */
    fun update(data: Map<String, JsonElement>): UpdateQuery {
        return UpdateQuery(client, baseUrl, tableName, filters, JsonObject(data))
    }

    /**
     * Delete records matching filters
     */
    fun delete(): DeleteQuery {
        return DeleteQuery(client, baseUrl, tableName, filters)
    }

    /**
     * Count records matching filters.
     *
     * Example usage:
     * ```kotlin
     * // Count all records
     * val count = client.database.from("users").select().count()
     *
     * // Count with filter
     * val activeCount = client.database.from("users")
     *     .select()
     *     .eq("active", true)
     *     .count()
     *
     * // Count with specific algorithm
     * val estimatedCount = client.database.from("users")
     *     .select()
     *     .count(CountType.ESTIMATED)
     * ```
     *
     * @param countType The count algorithm to use (default: EXACT)
     * @return The count of matching records
     */
    suspend fun count(countType: CountType = CountType.EXACT): Long {
        val response = client.httpClient.get("$baseUrl/records/$tableName") {
            // Select nothing (just count)
            parameter("select", "count")
            // Request count in header
            header("Prefer", "count=${countType.name.lowercase()}")
            // Apply filters
            filters.forEach { (column, filter) ->
                parameter(column, filter)
            }
            // Limit to 0 rows since we only want the count
            parameter("limit", 0)
        }

        val database = client.plugin<Database>(Database.key)

        // Try to get count from Content-Range header first (PostgREST style)
        val contentRange = response.headers["Content-Range"]
        if (contentRange != null) {
            // Format: "0-0/123" or "*/123" where 123 is the total count
            val totalCount = contentRange.substringAfterLast("/").toLongOrNull()
            if (totalCount != null) {
                return totalCount
            }
        }

        // Try to get count from X-Total-Count header
        val totalCountHeader = response.headers["X-Total-Count"]
        if (totalCountHeader != null) {
            return totalCountHeader.toLongOrNull() ?: 0L
        }

        // Fallback: parse response body if it contains count
        val bodyText = response.bodyAsText()
        if (bodyText.isNotBlank()) {
            try {
                val json = Json.parseToJsonElement(bodyText)
                // Handle array response with count field
                if (json is JsonArray && json.isNotEmpty()) {
                    val firstElement = json[0]
                    if (firstElement is JsonObject) {
                        firstElement["count"]?.let { countElement ->
                            return when (countElement) {
                                is JsonPrimitive -> countElement.longOrNull ?: 0L
                                else -> 0L
                            }
                        }
                    }
                }
                // Handle object response with count field
                if (json is JsonObject) {
                    json["count"]?.let { countElement ->
                        return when (countElement) {
                            is JsonPrimitive -> countElement.longOrNull ?: 0L
                            else -> 0L
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }

        return 0L
    }
}

/**
 * Insert query builder
 */
class InsertQuery @PublishedApi internal constructor(
    @PublishedApi internal val client: InsforgeClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val tableName: String,
    @PublishedApi internal val records: JsonArray
) {
    @PublishedApi internal var returnRepresentation = false

    /**
     * Return inserted records in response
     */
    fun returning(): InsertQuery {
        returnRepresentation = true
        return this
    }

    /**
     * Execute insert
     */
    suspend inline fun <reified T> execute(): List<T> {
        val response = client.httpClient.post("$baseUrl/records/$tableName") {
            contentType(ContentType.Application.Json)
            if (returnRepresentation) {
                header("Prefer", "return=representation")
            }
            setBody(records)
        }

        val database = client.plugin<Database>(Database.key)
        return database.handleResponse(response)
    }
}

/**
 * Update query builder
 */
class UpdateQuery @PublishedApi internal constructor(
    @PublishedApi internal val client: InsforgeClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val tableName: String,
    @PublishedApi internal val filters: Map<String, String>,
    @PublishedApi internal val data: JsonObject
) {
    @PublishedApi internal var returnRepresentation = false

    /**
     * Return updated records in response
     */
    fun returning(): UpdateQuery {
        returnRepresentation = true
        return this
    }

    /**
     * Execute update
     */
    suspend inline fun <reified T> execute(): List<T> {
        val response = client.httpClient.patch("$baseUrl/records/$tableName") {
            contentType(ContentType.Application.Json)
            if (returnRepresentation) {
                header("Prefer", "return=representation")
            }
            filters.forEach { (column, filter) ->
                parameter(column, filter)
            }
            setBody(data)
        }

        val database = client.plugin<Database>(Database.key)
        return database.handleResponse(response)
    }
}

/**
 * Delete query builder
 */
class DeleteQuery @PublishedApi internal constructor(
    @PublishedApi internal val client: InsforgeClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val tableName: String,
    @PublishedApi internal val filters: Map<String, String>
) {
    @PublishedApi internal var returnRepresentation = false

    /**
     * Return deleted records in response
     */
    fun returning(): DeleteQuery {
        returnRepresentation = true
        return this
    }

    /**
     * Execute delete
     */
    suspend inline fun <reified T> execute(): List<T> {
        val response = client.httpClient.delete("$baseUrl/records/$tableName") {
            if (returnRepresentation) {
                header("Prefer", "return=representation")
            }
            filters.forEach { (column, filter) ->
                parameter(column, filter)
            }
        }

        val database = client.plugin<Database>(Database.key)
        return database.handleResponse(response)
    }
}
