package dev.insforge.database

import dev.insforge.InsforgeClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

/**
 * Full-text search types supported by PostgREST.
 *
 * Each type maps to a different PostgreSQL text search function:
 * - [PLAIN] uses `plainto_tsquery` — converts plain text to a tsquery
 * - [PHRASE] uses `phraseto_tsquery` — matches exact phrases
 * - [WEBSEARCH] uses `websearch_to_tsquery` — supports Google-like search syntax
 * - [FULL] uses `to_tsquery` — expects raw tsquery syntax (e.g. `'fat' & 'cat'`)
 */
enum class TextSearchType(val value: String) {
    PLAIN("plfts"),
    PHRASE("phfts"),
    WEBSEARCH("wfts"),
    FULL("fts")
}

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

    // ============ Logical Operators ============

    /**
     * OR combined filter using PostgREST syntax.
     *
     * Example: `.or("age.lt.18,age.gt.65")` sends `?or=(age.lt.18,age.gt.65)`
     *
     * @param filters Comma-separated PostgREST filter expressions
     */
    fun or(filters: String): TableQuery {
        this.filters["or"] = "($filters)"
        return this
    }

    /**
     * Negate a filter using the PostgREST NOT operator.
     *
     * Example: `.not("status", "eq", "archived")` sends `?status=not.eq.archived`
     * Example: `.not("id", "in", listOf(1, 2))` sends `?id=not.in.(1,2)`
     * Example: `.not("deleted_at", "is", null)` sends `?deleted_at=not.is.null`
     *
     * @param column Column name
     * @param operator PostgREST operator (e.g. "eq", "like", "in", "is")
     * @param value Filter value (null becomes "null", collections formatted as PostgREST lists)
     */
    fun not(column: String, operator: String, value: Any?): TableQuery {
        val formatted = when {
            value == null -> "null"
            value is Collection<*> -> "(${value.joinToString(",")})"
            else -> "$value"
        }
        filters[column] = "not.$operator.$formatted"
        return this
    }

    // ============ Array / JSON Operators ============

    /**
     * Contains filter (PostgREST `cs` operator, PostgreSQL `@>`).
     *
     * For arrays: `.contains("tags", "{kotlin,android}")` sends `?tags=cs.{kotlin,android}`
     * For JSON: `.contains("metadata", """{"key":"val"}""")` sends `?metadata=cs.{"key":"val"}`
     *
     * @param column Column name (array or JSON type)
     * @param value Value to check containment against
     */
    fun contains(column: String, value: Any): TableQuery {
        filters[column] = "cs.$value"
        return this
    }

    /**
     * Contained-by filter (PostgREST `cd` operator, PostgreSQL `<@`).
     *
     * Example: `.containedBy("tags", "{kotlin,android,java}")` sends `?tags=cd.{kotlin,android,java}`
     *
     * @param column Column name (array or JSON type)
     * @param value Value to check containment against
     */
    fun containedBy(column: String, value: Any): TableQuery {
        filters[column] = "cd.$value"
        return this
    }

    // ============ Full-Text Search ============

    /**
     * Full-text search filter.
     *
     * Example: `.textSearch("content", "kotlin api")` sends `?content=plfts.kotlin api`
     * Example: `.textSearch("content", "'fat' & 'cat'", TextSearchType.FULL, "english")`
     *         sends `?content=fts(english).'fat' & 'cat'`
     *
     * @param column Column name (text or tsvector type)
     * @param query Search query
     * @param type Type of text search function to use (default: PLAIN)
     * @param config Optional PostgreSQL text search configuration (e.g. "english", "french")
     */
    fun textSearch(
        column: String,
        query: String,
        type: TextSearchType = TextSearchType.PLAIN,
        config: String? = null
    ): TableQuery {
        val configStr = config?.let { "($it)" } ?: ""
        filters[column] = "${type.value}${configStr}.$query"
        return this
    }

    // ============ Range Operators ============

    /**
     * Range overlap filter (PostgREST `ov` operator, PostgreSQL `&&`).
     *
     * Example: `.overlaps("schedule", "[2024-01-01,2024-12-31]")` sends `?schedule=ov.[2024-01-01,2024-12-31]`
     * For arrays: `.overlaps("tags", "{a,b}")` sends `?tags=ov.{a,b}`
     *
     * @param column Column name (range or array type)
     * @param value Range or array literal
     */
    fun overlaps(column: String, value: String): TableQuery {
        filters[column] = "ov.$value"
        return this
    }

    /**
     * Range adjacent filter (PostgREST `adj` operator, PostgreSQL `-|-`).
     *
     * Example: `.adjacent("range_col", "(1,10)")` sends `?range_col=adj.(1,10)`
     *
     * @param column Column name (range type)
     * @param value Range literal
     */
    fun adjacent(column: String, value: String): TableQuery {
        filters[column] = "adj.$value"
        return this
    }

    /**
     * Strictly-left-of range filter (PostgREST `sl` operator, PostgreSQL `<<`).
     *
     * @param column Column name (range type)
     * @param value Range literal
     */
    fun rangeLt(column: String, value: String): TableQuery {
        filters[column] = "sl.$value"
        return this
    }

    /**
     * Strictly-right-of range filter (PostgREST `sr` operator, PostgreSQL `>>`).
     *
     * @param column Column name (range type)
     * @param value Range literal
     */
    fun rangeGt(column: String, value: String): TableQuery {
        filters[column] = "sr.$value"
        return this
    }

    /**
     * Does-not-extend-to-the-right-of range filter (PostgREST `nxr` operator, PostgreSQL `&<`).
     *
     * @param column Column name (range type)
     * @param value Range literal
     */
    fun rangeLte(column: String, value: String): TableQuery {
        filters[column] = "nxr.$value"
        return this
    }

    /**
     * Does-not-extend-to-the-left-of range filter (PostgREST `nxl` operator, PostgreSQL `&>`).
     *
     * @param column Column name (range type)
     * @param value Range literal
     */
    fun rangeGte(column: String, value: String): TableQuery {
        filters[column] = "nxl.$value"
        return this
    }

    // ============ Generic Filter ============

    /**
     * Generic filter for any PostgREST operator.
     *
     * Use this as an escape hatch for operators not covered by named methods.
     *
     * Example: `.filter("id", "in", "(1,2,3)")` sends `?id=in.(1,2,3)`
     *
     * @param column Column name
     * @param operator PostgREST operator string
     * @param value Filter value
     */
    fun filter(column: String, operator: String, value: Any): TableQuery {
        filters[column] = "$operator.$value"
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
     * Execute SELECT query and deserialize results to the specified type.
     *
     * Note: T must be a @Serializable class. For untyped access, use [executeRaw].
     *
     * @param T The type to deserialize each row to (must be @Serializable)
     * @return List of deserialized records
     * @throws IllegalArgumentException if T is not a @Serializable type
     */
    suspend inline fun <reified T> execute(): List<T> {
        // Verify T is serializable at runtime with clear error message
        try {
            serializer<T>()
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Type '${T::class.simpleName}' is not @Serializable. " +
                "Either add @Serializable annotation to your data class, " +
                "or use executeRaw() for untyped/dynamic queries.",
                e
            )
        }

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
     * Execute SELECT query and return raw JSON array.
     *
     * Use this method when you need to work with dynamic/untyped data,
     * such as queries with joins that return nested objects.
     *
     * Example:
     * ```kotlin
     * val result = client.database
     *     .from("tweets")
     *     .select("id,content,profiles!tweets_user_id_fkey(username)")
     *     .executeRaw()
     *
     * result.forEach { element ->
     *     val obj = element.jsonObject
     *     val id = obj["id"]?.jsonPrimitive?.content
     *     val profile = obj["profiles"]?.jsonObject
     *     val username = profile?.get("username")?.jsonPrimitive?.content
     * }
     * ```
     *
     * @return JsonArray containing the query results
     */
    suspend fun executeRaw(): JsonArray {
        val response = client.httpClient.get("$baseUrl/records/$tableName") {
            selectColumns?.let { parameter("select", it) }
            orderBy?.let { parameter("order", it) }
            limitValue?.let { parameter("limit", it) }
            offsetValue?.let { parameter("offset", it) }
            filters.forEach { (column, filter) ->
                parameter(column, filter)
            }
        }

        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                val bodyText = response.bodyAsText()
                if (bodyText.isBlank()) {
                    JsonArray(emptyList())
                } else {
                    Json.parseToJsonElement(bodyText).jsonArray
                }
            }
            HttpStatusCode.NoContent -> JsonArray(emptyList())
            else -> {
                val errorBody = response.bodyAsText()
                throw Exception("Database query failed: ${response.status} - $errorBody")
            }
        }
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
     * Upsert records - insert or update on conflict.
     *
     * Performs an UPSERT operation: inserts the row if it doesn't exist,
     * or updates it if a row with the same conflict column(s) already exists.
     *
     * Example usage:
     * ```kotlin
     * // Upsert with default conflict column (usually primary key)
     * client.database.from("users")
     *     .upsert(userRecords)
     *     .execute<User>()
     *
     * // Upsert with specific conflict column
     * client.database.from("users")
     *     .upsert(userRecords) {
     *         onConflict = "email"
     *     }
     *     .returning()
     *     .execute<User>()
     *
     * // Upsert ignoring duplicates (no update, just skip)
     * client.database.from("users")
     *     .upsert(userRecords) {
     *         onConflict = "email"
     *         ignoreDuplicates = true
     *     }
     *     .execute<User>()
     * ```
     *
     * @param records The records to upsert as JsonArray
     * @param options Configuration for the upsert operation
     * @return UpsertQuery for further configuration
     */
    fun upsert(records: JsonArray, options: UpsertOptions.() -> Unit = {}): UpsertQuery {
        val upsertOptions = UpsertOptions().apply(options)
        return UpsertQuery(client, baseUrl, tableName, records, upsertOptions)
    }

    /**
     * Upsert records from a list - converts to JsonArray.
     *
     * @param records The records to upsert
     * @param options Configuration for the upsert operation
     * @return UpsertQuery for further configuration
     */
    inline fun <reified T> upsertTyped(records: List<T>, noinline options: UpsertOptions.() -> Unit = {}): UpsertQuery {
        val jsonArray = Json.encodeToJsonElement(records) as JsonArray
        return upsert(jsonArray, options)
    }

    /**
     * Upsert a single record.
     *
     * @param record The record to upsert
     * @param options Configuration for the upsert operation
     * @return UpsertQuery for further configuration
     */
    inline fun <reified T> upsertTyped(record: T, noinline options: UpsertOptions.() -> Unit = {}): UpsertQuery {
        return upsertTyped(listOf(record), options)
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
    initialFilters: Map<String, String>,
    @PublishedApi internal val data: JsonObject
) {
    @PublishedApi internal val filters = initialFilters.toMutableMap()
    @PublishedApi internal var returnRepresentation = false

    /**
     * Equal filter
     */
    fun eq(column: String, value: Any): UpdateQuery {
        filters[column] = "eq.$value"
        return this
    }

    /**
     * Not equal filter
     */
    fun neq(column: String, value: Any): UpdateQuery {
        filters[column] = "neq.$value"
        return this
    }

    /**
     * Greater than filter
     */
    fun gt(column: String, value: Any): UpdateQuery {
        filters[column] = "gt.$value"
        return this
    }

    /**
     * Greater than or equal filter
     */
    fun gte(column: String, value: Any): UpdateQuery {
        filters[column] = "gte.$value"
        return this
    }

    /**
     * Less than filter
     */
    fun lt(column: String, value: Any): UpdateQuery {
        filters[column] = "lt.$value"
        return this
    }

    /**
     * Less than or equal filter
     */
    fun lte(column: String, value: Any): UpdateQuery {
        filters[column] = "lte.$value"
        return this
    }

    /**
     * LIKE filter (case-sensitive pattern matching)
     */
    fun like(column: String, pattern: String): UpdateQuery {
        filters[column] = "like.$pattern"
        return this
    }

    /**
     * ILIKE filter (case-insensitive pattern matching)
     */
    fun ilike(column: String, pattern: String): UpdateQuery {
        filters[column] = "ilike.$pattern"
        return this
    }

    /**
     * IN filter (value in list)
     */
    fun `in`(column: String, values: List<Any>): UpdateQuery {
        filters[column] = "in.(${values.joinToString(",")})"
        return this
    }

    /**
     * IS NULL filter
     */
    fun isNull(column: String): UpdateQuery {
        filters[column] = "is.null"
        return this
    }

    /** OR combined filter. See [TableQuery.or]. */
    fun or(filters: String): UpdateQuery {
        this.filters["or"] = "($filters)"
        return this
    }

    /** Negate a filter. See [TableQuery.not]. */
    fun not(column: String, operator: String, value: Any?): UpdateQuery {
        val formatted = when {
            value == null -> "null"
            value is Collection<*> -> "(${value.joinToString(",")})"
            else -> "$value"
        }
        filters[column] = "not.$operator.$formatted"
        return this
    }

    /** Contains filter (`@>`). See [TableQuery.contains]. */
    fun contains(column: String, value: Any): UpdateQuery {
        filters[column] = "cs.$value"
        return this
    }

    /** Contained-by filter (`<@`). See [TableQuery.containedBy]. */
    fun containedBy(column: String, value: Any): UpdateQuery {
        filters[column] = "cd.$value"
        return this
    }

    /** Full-text search filter. See [TableQuery.textSearch]. */
    fun textSearch(column: String, query: String, type: TextSearchType = TextSearchType.PLAIN, config: String? = null): UpdateQuery {
        val configStr = config?.let { "($it)" } ?: ""
        filters[column] = "${type.value}${configStr}.$query"
        return this
    }

    /** Range overlap filter (`&&`). See [TableQuery.overlaps]. */
    fun overlaps(column: String, value: String): UpdateQuery {
        filters[column] = "ov.$value"
        return this
    }

    /** Range adjacent filter (`-|-`). See [TableQuery.adjacent]. */
    fun adjacent(column: String, value: String): UpdateQuery {
        filters[column] = "adj.$value"
        return this
    }

    /** Strictly-left-of range filter (`<<`). See [TableQuery.rangeLt]. */
    fun rangeLt(column: String, value: String): UpdateQuery {
        filters[column] = "sl.$value"
        return this
    }

    /** Strictly-right-of range filter (`>>`). See [TableQuery.rangeGt]. */
    fun rangeGt(column: String, value: String): UpdateQuery {
        filters[column] = "sr.$value"
        return this
    }

    /** Does-not-extend-to-the-right-of range filter (`&<`). See [TableQuery.rangeLte]. */
    fun rangeLte(column: String, value: String): UpdateQuery {
        filters[column] = "nxr.$value"
        return this
    }

    /** Does-not-extend-to-the-left-of range filter (`&>`). See [TableQuery.rangeGte]. */
    fun rangeGte(column: String, value: String): UpdateQuery {
        filters[column] = "nxl.$value"
        return this
    }

    /** Generic filter. See [TableQuery.filter]. */
    fun filter(column: String, operator: String, value: Any): UpdateQuery {
        filters[column] = "$operator.$value"
        return this
    }

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
    initialFilters: Map<String, String>
) {
    @PublishedApi internal val filters = initialFilters.toMutableMap()
    @PublishedApi internal var returnRepresentation = false

    /**
     * Equal filter
     */
    fun eq(column: String, value: Any): DeleteQuery {
        filters[column] = "eq.$value"
        return this
    }

    /**
     * Not equal filter
     */
    fun neq(column: String, value: Any): DeleteQuery {
        filters[column] = "neq.$value"
        return this
    }

    /**
     * Greater than filter
     */
    fun gt(column: String, value: Any): DeleteQuery {
        filters[column] = "gt.$value"
        return this
    }

    /**
     * Greater than or equal filter
     */
    fun gte(column: String, value: Any): DeleteQuery {
        filters[column] = "gte.$value"
        return this
    }

    /**
     * Less than filter
     */
    fun lt(column: String, value: Any): DeleteQuery {
        filters[column] = "lt.$value"
        return this
    }

    /**
     * Less than or equal filter
     */
    fun lte(column: String, value: Any): DeleteQuery {
        filters[column] = "lte.$value"
        return this
    }

    /**
     * LIKE filter (case-sensitive pattern matching)
     */
    fun like(column: String, pattern: String): DeleteQuery {
        filters[column] = "like.$pattern"
        return this
    }

    /**
     * ILIKE filter (case-insensitive pattern matching)
     */
    fun ilike(column: String, pattern: String): DeleteQuery {
        filters[column] = "ilike.$pattern"
        return this
    }

    /**
     * IN filter (value in list)
     */
    fun `in`(column: String, values: List<Any>): DeleteQuery {
        filters[column] = "in.(${values.joinToString(",")})"
        return this
    }

    /**
     * IS NULL filter
     */
    fun isNull(column: String): DeleteQuery {
        filters[column] = "is.null"
        return this
    }

    /** OR combined filter. See [TableQuery.or]. */
    fun or(filters: String): DeleteQuery {
        this.filters["or"] = "($filters)"
        return this
    }

    /** Negate a filter. See [TableQuery.not]. */
    fun not(column: String, operator: String, value: Any?): DeleteQuery {
        val formatted = when {
            value == null -> "null"
            value is Collection<*> -> "(${value.joinToString(",")})"
            else -> "$value"
        }
        filters[column] = "not.$operator.$formatted"
        return this
    }

    /** Contains filter (`@>`). See [TableQuery.contains]. */
    fun contains(column: String, value: Any): DeleteQuery {
        filters[column] = "cs.$value"
        return this
    }

    /** Contained-by filter (`<@`). See [TableQuery.containedBy]. */
    fun containedBy(column: String, value: Any): DeleteQuery {
        filters[column] = "cd.$value"
        return this
    }

    /** Full-text search filter. See [TableQuery.textSearch]. */
    fun textSearch(column: String, query: String, type: TextSearchType = TextSearchType.PLAIN, config: String? = null): DeleteQuery {
        val configStr = config?.let { "($it)" } ?: ""
        filters[column] = "${type.value}${configStr}.$query"
        return this
    }

    /** Range overlap filter (`&&`). See [TableQuery.overlaps]. */
    fun overlaps(column: String, value: String): DeleteQuery {
        filters[column] = "ov.$value"
        return this
    }

    /** Range adjacent filter (`-|-`). See [TableQuery.adjacent]. */
    fun adjacent(column: String, value: String): DeleteQuery {
        filters[column] = "adj.$value"
        return this
    }

    /** Strictly-left-of range filter (`<<`). See [TableQuery.rangeLt]. */
    fun rangeLt(column: String, value: String): DeleteQuery {
        filters[column] = "sl.$value"
        return this
    }

    /** Strictly-right-of range filter (`>>`). See [TableQuery.rangeGt]. */
    fun rangeGt(column: String, value: String): DeleteQuery {
        filters[column] = "sr.$value"
        return this
    }

    /** Does-not-extend-to-the-right-of range filter (`&<`). See [TableQuery.rangeLte]. */
    fun rangeLte(column: String, value: String): DeleteQuery {
        filters[column] = "nxr.$value"
        return this
    }

    /** Does-not-extend-to-the-left-of range filter (`&>`). See [TableQuery.rangeGte]. */
    fun rangeGte(column: String, value: String): DeleteQuery {
        filters[column] = "nxl.$value"
        return this
    }

    /** Generic filter. See [TableQuery.filter]. */
    fun filter(column: String, operator: String, value: Any): DeleteQuery {
        filters[column] = "$operator.$value"
        return this
    }

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

/**
 * Options for upsert operations.
 */
class UpsertOptions {
    /**
     * The column(s) to use for conflict detection.
     * If not specified, the primary key is used.
     *
     * For composite keys, use comma-separated column names: "col1,col2"
     */
    var onConflict: String? = null

    /**
     * If true, duplicate rows are ignored (no update performed).
     * If false (default), duplicate rows are updated with the new values.
     */
    var ignoreDuplicates: Boolean = false

    /**
     * If true, missing columns in the input will be set to their default values.
     * If false (default), missing columns will be set to NULL.
     */
    var defaultToNull: Boolean = true
}

/**
 * Upsert query builder
 */
class UpsertQuery @PublishedApi internal constructor(
    @PublishedApi internal val client: InsforgeClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val tableName: String,
    @PublishedApi internal val records: JsonArray,
    @PublishedApi internal val options: UpsertOptions
) {
    @PublishedApi internal var returnRepresentation = false

    /**
     * Return upserted records in response
     */
    fun returning(): UpsertQuery {
        returnRepresentation = true
        return this
    }

    /**
     * Execute upsert
     */
    suspend inline fun <reified T> execute(): List<T> {
        // Extract column names from the records for the columns parameter
        val columns = records
            .filterIsInstance<JsonObject>()
            .flatMap { it.keys }
            .distinct()

        val response = client.httpClient.post("$baseUrl/records/$tableName") {
            contentType(ContentType.Application.Json)

            // Build Prefer header for upsert
            val preferValues = mutableListOf<String>()

            // resolution=merge-duplicates (update) or resolution=ignore-duplicates
            if (options.ignoreDuplicates) {
                preferValues.add("resolution=ignore-duplicates")
            } else {
                preferValues.add("resolution=merge-duplicates")
            }

            // Return representation if requested
            if (returnRepresentation) {
                preferValues.add("return=representation")
            }

            header("Prefer", preferValues.joinToString(","))

            // Add columns parameter
            if (columns.isNotEmpty()) {
                parameter("columns", columns.joinToString(","))
            }

            // Add on_conflict parameter if specified
            options.onConflict?.let {
                parameter("on_conflict", it)
            }

            setBody(records)
        }

        val database = client.plugin<Database>(Database.key)
        return database.handleResponse(response)
    }
}
