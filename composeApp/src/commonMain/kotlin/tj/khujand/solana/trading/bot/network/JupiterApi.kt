package tj.khujand.solana.trading.bot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.delay

@Serializable
data class JupiterPriceResponse(
    val data: JsonObject? = null
)

@Serializable
data class JupiterSwapRequest(
    val quoteResponse: JsonObject,
    val userPublicKey: String,
    val wrapAndUnwrapSol: Boolean = true,
    val dynamicComputeUnitLimit: Boolean = true,
    val prioritizationFeeLamports: String = "auto"
)

@Serializable
data class JupiterSwapResponse(
    val swapTransaction: String? = null
)

class JupiterApi {
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
        }
        install(ContentNegotiation) {
            json(Json {
                isLenient = true
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val baseUrl = "https://api.jup.ag"
    private val maxRetries = 3
    private val logBodyLimit = 600

    suspend fun getSolPriceUsd(apiKey: String? = null): Double? {
        val url = "$baseUrl/price/v3"
        return try {
            val response = getWithRetry(url, apiKey) {
                parameter("ids", "SOL")
            } ?: return null
            val text = response.bodyAsText()
            val element = json.parseToJsonElement(text).jsonObject
            element["So11111111111111111111111111111111111111112"]
                ?.jsonObject
                ?.get("usdPrice")
                ?.jsonPrimitive
                ?.content
                ?.toDoubleOrNull()
        } catch (e: Exception) {
            println("❌ Jupiter price error: ${e.message}")
            null
        }
    }

    suspend fun getQuote(
        inputMint: String,
        outputMint: String,
        amount: Long,
        slippageBps: Int,
        apiKey: String? = null
    ): JsonObject? {
        val url = "$baseUrl/swap/v1/quote"
        return try {
            val response = getWithRetry(url, apiKey) {
                parameter("inputMint", inputMint)
                parameter("outputMint", outputMint)
                parameter("amount", amount)
                parameter("slippageBps", slippageBps)
            } ?: return null
            response.body()
        } catch (e: Exception) {
            println("❌ Jupiter quote error: ${e.message}")
            null
        }
    }

    data class QuoteDebugResult(
        val quote: JsonObject? = null,
        val error: String? = null
    )

    suspend fun getQuoteDebug(
        inputMint: String,
        outputMint: String,
        amount: Long,
        slippageBps: Int,
        apiKey: String? = null
    ): QuoteDebugResult {
        val url = "$baseUrl/swap/v1/quote"
        return try {
            logApi("GET $url inputMint=$inputMint outputMint=$outputMint amount=$amount slippageBps=$slippageBps")
            val response = client.get(url) {
                parameter("inputMint", inputMint)
                parameter("outputMint", outputMint)
                parameter("amount", amount)
                parameter("slippageBps", slippageBps)
                applyApiKey(this, apiKey)
            }
            val status = response.status
            val text = response.bodyAsText()
            if (!status.isSuccess()) {
                val trimmed = text.take(logBodyLimit)
                logApi("GET $url -> HTTP ${status.value} body=$trimmed")
                QuoteDebugResult(
                    quote = null,
                    error = "HTTP ${status.value}: $trimmed"
                )
            } else {
                val element: JsonElement = json.parseToJsonElement(text)
                logApi("GET $url -> ${status.value} ok")
                QuoteDebugResult(quote = element.jsonObject, error = null)
            }
        } catch (e: Exception) {
            logApi("GET $url failed: ${e.message}")
            QuoteDebugResult(
                quote = null,
                error = e.message ?: "Unknown error"
            )
        }
    }

    suspend fun getSwap(
        quote: JsonObject,
        userPublicKey: String,
        apiKey: String? = null
    ): JupiterSwapResponse? {
        val url = "$baseUrl/swap/v1/swap"
        return try {
            val response = postWithRetry(url, JupiterSwapRequest(
                quoteResponse = quote,
                userPublicKey = userPublicKey
            ), apiKey) ?: return null
            response.body()
        } catch (e: Exception) {
            println("❌ Jupiter swap error: ${e.message}")
            null
        }
    }

    fun close() {
        client.close()
    }

    private suspend fun getWithRetry(
        url: String,
        apiKey: String? = null,
        builder: HttpRequestBuilder.() -> Unit
    ): HttpResponse? {
        var attempt = 0
        var backoffMs = 500L
        while (attempt < maxRetries) {
            try {
                logApi("GET $url attempt=${attempt + 1}")
                val response = client.get(url) {
                    applyApiKey(this, apiKey)
                    builder()
                }
                val status = response.status
                if (status.value == 429 || status.value >= 500) {
                    logApi("GET $url -> HTTP ${status.value}, retrying")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                    attempt++
                    continue
                }
                if (!status.isSuccess()) {
                    val body = response.bodyAsText().take(logBodyLimit)
                    logApi("GET $url -> HTTP ${status.value} body=$body")
                    return null
                }
                logApi("GET $url -> ${status.value} ok")
                return response
            } catch (e: Exception) {
                attempt++
                logApi("GET $url failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                }
            }
        }
        return null
    }

    private suspend fun postWithRetry(
        url: String,
        body: Any,
        apiKey: String? = null
    ): HttpResponse? {
        var attempt = 0
        var backoffMs = 500L
        while (attempt < maxRetries) {
            try {
                logApi("POST $url attempt=${attempt + 1}")
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applyApiKey(this, apiKey)
                }
                val status = response.status
                if (status.value == 429 || status.value >= 500) {
                    logApi("POST $url -> HTTP ${status.value}, retrying")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                    attempt++
                    continue
                }
                if (!status.isSuccess()) {
                    val responseBody = response.bodyAsText().take(logBodyLimit)
                    logApi("POST $url -> HTTP ${status.value} body=$responseBody")
                    return null
                }
                logApi("POST $url -> ${status.value} ok")
                return response
            } catch (e: Exception) {
                attempt++
                logApi("POST $url failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                }
            }
        }
        return null
    }

    private fun logApi(message: String) {
        println("🪐 Jupiter: $message")
    }

    private fun applyApiKey(builder: HttpRequestBuilder, apiKey: String?) {
        if (!apiKey.isNullOrBlank()) {
            builder.headers.append("x-api-key", apiKey)
        }
    }
}
