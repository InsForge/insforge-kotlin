package dev.insforge.realtime

import kotlinx.serialization.Serializable

/**
 * Filter configuration for Postgres change subscriptions.
 *
 * Example:
 * ```kotlin
 * channel.postgresChangeFlow<PostgresAction.Insert>("public") {
 *     table = "messages"
 *     filter = "user_id=eq.123"
 * }
 * ```
 *
 * Available filter operators:
 * - `eq` - Equal to: `id=eq.1`
 * - `neq` - Not equal to: `status=neq.deleted`
 * - `lt` - Less than: `age=lt.18`
 * - `lte` - Less than or equal: `age=lte.65`
 * - `gt` - Greater than: `price=gt.100`
 * - `gte` - Greater than or equal: `quantity=gte.10`
 * - `in` - In list: `status=in.(active,pending)`
 */
@ChannelDsl
class PostgresChangeFilter internal constructor(
    internal val event: String,
    internal val schema: String
) {
    /**
     * Table name to listen for changes
     */
    var table: String? = null

    /**
     * Row-level filter expression.
     *
     * Format: `column=operator.value`
     *
     * Examples:
     * - `id=eq.1` - Where id equals 1
     * - `status=neq.deleted` - Where status is not 'deleted'
     * - `price=gt.100` - Where price is greater than 100
     * - `name=in.(foo,bar,baz)` - Where name is in the list
     */
    var filter: String? = null

    /**
     * Build the filter using a type-safe builder.
     *
     * Example:
     * ```kotlin
     * channel.postgresChangeFlow<PostgresAction.Update>("public") {
     *     table = "products"
     *     filter("price", FilterOperator.GT, 100)
     * }
     * ```
     */
    fun filter(column: String, operator: FilterOperator, value: Any) {
        filter = "$column=${operator.value}.$value"
    }

    /**
     * Build the filter for IN operator with multiple values.
     *
     * Example:
     * ```kotlin
     * channel.postgresChangeFlow<PostgresAction.Update>("public") {
     *     table = "products"
     *     filterIn("status", listOf("active", "pending", "review"))
     * }
     * ```
     */
    fun filterIn(column: String, values: List<Any>) {
        val valueList = values.joinToString(",")
        filter = "$column=in.($valueList)"
    }

    internal fun buildConfig(): PostgresChangeConfig {
        return PostgresChangeConfig(
            event = event,
            schema = schema,
            table = table,
            filter = filter
        )
    }
}

/**
 * Filter operators for Postgres change subscriptions
 */
enum class FilterOperator(val value: String) {
    /** Equal to */
    EQ("eq"),
    /** Not equal to */
    NEQ("neq"),
    /** Less than */
    LT("lt"),
    /** Less than or equal to */
    LTE("lte"),
    /** Greater than */
    GT("gt"),
    /** Greater than or equal to */
    GTE("gte")
}

/**
 * Internal configuration for Postgres change subscription
 */
@Serializable
internal data class PostgresChangeConfig(
    val event: String,
    val schema: String,
    val table: String?,
    val filter: String?
) {
    /**
     * Generate a unique key for this configuration
     */
    fun toKey(): String {
        return "postgres_changes:$schema:${table ?: "*"}:$event:${filter ?: "*"}"
    }
}
