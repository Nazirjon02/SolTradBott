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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Модель для SPL Mint информации
 */
@Serializable
data class SplMintInfo(
    val hasMintAuthority: Boolean,
    val hasFreezeAuthority: Boolean,
    val decimals: Int,
    val supply: Long
)

/**
 * Модель для RPC ответа
 */
@Serializable
data class RpcResponse(
    val jsonrpc: String? = null,
    val id: Int? = null,
    val result: RpcResult? = null,
    val error: RpcError? = null
)

@Serializable
data class RpcResult(
    val value: AccountInfo? = null
)

@Serializable
data class AccountInfo(
    val data: List<String>? = null,
    val executable: Boolean? = null,
    val lamports: Long? = null,
    val owner: String? = null,
    val rentEpoch: String? = null // Используем String для больших чисел (18446744073709551615)
)

@Serializable
data class RpcError(
    val code: Int? = null,
    val message: String? = null
)

/**
 * Клиент для работы с Solana RPC
 */
class SolanaRpcClient(
    private val rpcUrl: String = "https://api.mainnet-beta.solana.com",
    private val timeoutSeconds: Int = 12,
    private val maxParallel: Int = 8
) {
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutSeconds * 1000L
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

    private val semaphore = Semaphore(maxParallel)
    private val maxRetries = 3

    /**
     * Получить информацию о SPL Mint токене
     */
    suspend fun getSplMintInfo(mintAddress: String): SplMintInfo? {
        return semaphore.withPermit {
            try {
                val response = getAccountInfoWithRetry(mintAddress) ?: return null
                val accountInfo = response.result?.value ?: return null
                val data = accountInfo.data ?: return null

                if (data.isEmpty()) return null

                // Декодируем base64 данные
                val base64Data = data[0]
                return parseSplMintFromBase64(base64Data)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("❌ Ошибка получения SPL Mint для $mintAddress: ${e.message}")
                null
            }
        }
    }

    /**
     * Парсинг SPL Mint из base64 данных
     * Формат: mintAuthorityOption(u32), mintAuthority(32), supply(u64), decimals(u8), isInit(u8), freezeAuthorityOption(u32), freezeAuthority(32)
     */
    private fun parseSplMintFromBase64(base64Data: String): SplMintInfo? {
        return try {
            // Декодируем base64
            val bytes = decodeBase64(base64Data) ?: return null

            if (bytes.size < 82) {
                println("⚠️ Недостаточно данных для парсинга SPL Mint (${bytes.size} байт)")
                return null
            }

            var offset = 0

            // mintAuthorityOption (u32, 4 байта)
            val mintAuthorityOption = bytesToUInt32(bytes, offset)
            offset += 4

            // mintAuthority (32 байта, пропускаем если option = 0)
            if (mintAuthorityOption != 0u) {
                offset += 32
            }

            // supply (u64, 8 байт)
            val supply = bytesToUInt64(bytes, offset)
            offset += 8

            // decimals (u8, 1 байт)
            val decimals = bytes[offset].toInt() and 0xFF
            offset += 1

            // isInit (u8, 1 байт) - пропускаем
            offset += 1

            // freezeAuthorityOption (u32, 4 байта)
            val freezeAuthorityOption = bytesToUInt32(bytes, offset)

            val hasMintAuthority = mintAuthorityOption != 0u
            val hasFreezeAuthority = freezeAuthorityOption != 0u

            SplMintInfo(
                hasMintAuthority = hasMintAuthority,
                hasFreezeAuthority = hasFreezeAuthority,
                decimals = decimals,
                supply = supply.toLong()
            )

        } catch (e: Exception) {
            println("❌ Ошибка парсинга SPL Mint: ${e.message}")
            null
        }
    }
    
    /**
     * Простая реализация base64 декодирования (работает на всех платформах)
     */
    private fun decodeBase64(data: String): ByteArray? {
        return try {
            val base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val padding = '='
            
            val input = data.trim()
            if (input.isEmpty()) return null
            
            val paddingCount = input.count { it == padding }
            val outputLength = (input.length * 3) / 4 - paddingCount
            val result = ByteArray(outputLength)
            
            var inputIndex = 0
            var outputIndex = 0
            
            while (inputIndex < input.length) {
                val char1 = base64Chars.indexOf(input[inputIndex++])
                val char2 = if (inputIndex < input.length) base64Chars.indexOf(input[inputIndex++]) else -1
                val char3 = if (inputIndex < input.length) base64Chars.indexOf(input[inputIndex++]) else -1
                val char4 = if (inputIndex < input.length) base64Chars.indexOf(input[inputIndex++]) else -1
                
                if (char1 == -1 || char2 == -1) break
                
                result[outputIndex++] = ((char1 shl 2) or (char2 shr 4)).toByte()
                
                if (char3 != -1) {
                    result[outputIndex++] = (((char2 and 0x0F) shl 4) or (char3 shr 2)).toByte()
                    
                    if (char4 != -1) {
                        result[outputIndex++] = (((char3 and 0x03) shl 6) or char4).toByte()
                    }
                }
            }
            
            result
        } catch (e: Exception) {
            println("❌ Ошибка декодирования base64: ${e.message}")
            null
        }
    }

    /**
     * Конвертация байтов в u32 (little-endian)
     */
    private fun bytesToUInt32(bytes: ByteArray, offset: Int): UInt {
        return ((bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)).toUInt()
    }

    /**
     * Конвертация байтов в u64 (little-endian)
     */
    private fun bytesToUInt64(bytes: ByteArray, offset: Int): ULong {
        var result = 0UL
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toInt() and 0xFF).toULong() shl (i * 8))
        }
        return result
    }

    /**
     * Получить информацию об аккаунте через RPC с повторными попытками
     */
    private suspend fun getAccountInfoWithRetry(mintAddress: String): RpcResponse? {
        var attempt = 0
        var backoffMs = 500L

        while (attempt < maxRetries) {
            try {
                // Правильный формат JSON для Solana RPC
                val requestJson = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "getAccountInfo",
                    "params": [
                        "$mintAddress",
                        {
                            "encoding": "base64",
                            "commitment": "confirmed"
                        }
                    ]
                }
                """.trimIndent()

                val response = client.post(rpcUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }

                val status = response.status
                if (status.value == 429 || status.value >= 500) {
                    println("⏳ Rate limit/Server error ${status.value}, retry in ${backoffMs}ms...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                    attempt++
                    continue
                }

                if (!status.isSuccess()) {
                    println("⚠️ Ошибка HTTP ${status.value} для RPC запроса")
                    return null
                }

                val responseText = response.bodyAsText()
                return json.decodeFromString<RpcResponse>(responseText)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("⚠️ Ошибка RPC запроса: ${e.message}")
                attempt++
                if (attempt < maxRetries) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                }
            }
        }

        return null
    }

    suspend fun getBalanceLamports(publicKey: String): Long? {
        return semaphore.withPermit {
            try {
                val requestJson = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "getBalance",
                    "params": [
                        "$publicKey",
                        { "commitment": "confirmed" }
                    ]
                }
                """.trimIndent()

                val response = client.post(rpcUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }

                val status = response.status
                if (status.value == 429 || status.value >= 500) {
                    println("⏳ RPC getBalance error ${status.value}")
                    return@withPermit null
                }

                if (!status.isSuccess()) {
                    println("⚠️ RPC getBalance HTTP ${status.value}")
                    return@withPermit null
                }

                val responseText = response.bodyAsText()
                val jsonElement = json.parseToJsonElement(responseText)
                val value = jsonElement.jsonObject["result"]
                    ?.jsonObject
                    ?.get("value")
                    ?.jsonPrimitive
                    ?.content
                    ?.toLongOrNull()
                value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("⚠️ getBalance error: ${e.message}")
                null
            }
        }
    }

    suspend fun sendTransaction(base64Tx: String): String? {
        return semaphore.withPermit {
            try {
                val requestJson = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "sendTransaction",
                    "params": [
                        "$base64Tx",
                        {
                            "encoding": "base64",
                            "skipPreflight": false,
                            "commitment": "confirmed"
                        }
                    ]
                }
                """.trimIndent()

                val response = client.post(rpcUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }

                val status = response.status
                if (status.value == 429 || status.value >= 500) {
                    println("⏳ RPC sendTransaction error ${status.value}")
                    return@withPermit null
                }

                if (!status.isSuccess()) {
                    println("⚠️ RPC sendTransaction HTTP ${status.value}")
                    return@withPermit null
                }

                val responseText = response.bodyAsText()
                val jsonElement = json.parseToJsonElement(responseText)
                val result = jsonElement.jsonObject["result"]?.jsonPrimitive?.content
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("⚠️ sendTransaction error: ${e.message}")
                null
            }
        }
    }

    fun close() {
        client.close()
    }
}

