package io.insforge.functions

import io.insforge.InsforgeClient
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.functions.models.*
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.InsforgePluginProvider
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Functions module for InsForge (Serverless functions in Deno runtime)
 *
 * Install this module in your Insforge client:
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(Functions)
 * }
 *
 * // Invoke a function
 * val result = client.functions.invoke<MyResponse>("hello-world", mapOf("name" to "John"))
 *
 * // Admin: Create function
 * client.functions.createFunction(
 *     name = "Hello World",
 *     code = "export default async function(req) { return new Response('Hello') }"
 * )
 * ```
 */
class Functions internal constructor(
    @PublishedApi internal val client: InsforgeClient,
    private val config: FunctionsConfig
) : InsforgePlugin<FunctionsConfig> {

    override val key: String = Functions.key

    @PublishedApi internal val baseUrl = "${client.baseURL}/api/functions"
    @PublishedApi internal val executeBaseUrl = "${client.baseURL}/functions"

    // ============ Function Execution ============

    /**
     * Invoke a function
     *
     * @param slug Function slug identifier
     * @param body Request body (will be JSON serialized)
     * @return Response from the function
     */
    suspend inline fun <reified T> invoke(slug: String, body: Any? = null): T {
        val response = client.httpClient.post("$executeBaseUrl/$slug") {
            contentType(ContentType.Application.Json)
            body?.let { setBody(it) }
        }
        return handleResponse(response)
    }

    /**
     * Invoke a function with raw response
     *
     * @param slug Function slug identifier
     * @param body Request body (will be JSON serialized)
     * @return Raw HTTP response
     */
    suspend fun invokeRaw(slug: String, body: Any? = null): HttpResponse {
        return client.httpClient.post("$executeBaseUrl/$slug") {
            contentType(ContentType.Application.Json)
            body?.let { setBody(it) }
        }
    }

    // ============ Function Management (Admin) ============

    /**
     * List all functions (admin only)
     */
    suspend fun listFunctions(): List<FunctionMetadata> {
        val response = client.httpClient.get(baseUrl)
        val result = handleResponse<ListFunctionsResponse>(response)
        return result.functions
    }

    /**
     * Get specific function details (admin only)
     *
     * @param slug Function slug
     */
    suspend fun getFunction(slug: String): FunctionDetails {
        val response = client.httpClient.get("$baseUrl/$slug")
        return handleResponse(response)
    }

    /**
     * Create a new function (admin only)
     *
     * @param name Display name for the function
     * @param code JavaScript/TypeScript code
     * @param slug URL-friendly identifier (auto-generated from name if not provided)
     * @param description Optional description
     * @param status Initial status (draft or active)
     */
    suspend fun createFunction(
        name: String,
        code: String,
        slug: String? = null,
        description: String? = null,
        status: String = "active"
    ): CreateFunctionResponse {
        val response = client.httpClient.post(baseUrl) {
            contentType(ContentType.Application.Json)
            setBody(CreateFunctionRequest(
                name = name,
                code = code,
                slug = slug,
                description = description,
                status = status
            ))
        }
        return handleResponse(response)
    }

    /**
     * Update an existing function (admin only)
     *
     * @param slug Function slug
     * @param name New display name
     * @param code Updated function code
     * @param description Updated description
     * @param status Updated status
     */
    suspend fun updateFunction(
        slug: String,
        name: String? = null,
        code: String? = null,
        description: String? = null,
        status: String? = null
    ): UpdateFunctionResponse {
        val response = client.httpClient.put("$baseUrl/$slug") {
            contentType(ContentType.Application.Json)
            setBody(UpdateFunctionRequest(
                name = name,
                code = code,
                description = description,
                status = status
            ))
        }
        return handleResponse(response)
    }

    /**
     * Delete a function (admin only)
     *
     * @param slug Function slug
     */
    suspend fun deleteFunction(slug: String): DeleteFunctionResponse {
        val response = client.httpClient.delete("$baseUrl/$slug")
        return handleResponse(response)
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
            Json.decodeFromString<io.insforge.exceptions.ErrorResponse>(errorBody)
        } catch (e: Exception) {
            throw InsforgeHttpException(
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

    companion object : InsforgePluginProvider<FunctionsConfig, Functions> {
        override val key: String = "functions"

        override fun createConfig(configure: FunctionsConfig.() -> Unit): FunctionsConfig {
            return FunctionsConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: FunctionsConfig): Functions {
            return Functions(client, config)
        }
    }
}

/**
 * Extension property for accessing Functions module
 */
val InsforgeClient.functions: Functions
    get() = plugin(Functions.key)
