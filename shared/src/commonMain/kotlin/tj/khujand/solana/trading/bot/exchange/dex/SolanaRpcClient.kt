package tj.khujand.solana.trading.bot.exchange.dex

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/** Программа Token-2022 (у обычных SPL-токенов владелец mint — TokenkegQ…). */
const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

/** Token-2022: база mint добита до размера token-аккаунта (165 байт), за ней байт типа аккаунта и TLV. */
private const val ACCOUNT_TYPE_OFFSET = 165
private const val ACCOUNT_TYPE_MINT = 1
private const val MINT_EXTENSIONS_START = ACCOUNT_TYPE_OFFSET + 1

/** getSignatureStatuses принимает не больше 256 подписей за запрос. */
private const val MAX_SIGNATURES_PER_REQUEST = 256

/**
 * Модель для SPL Mint информации
 *
 * @param tokenProgram программа-владелец mint-аккаунта; null — неизвестна.
 * @param extensions типы расширений Token-2022 (ExtensionType из spl-token-2022); пусто у SPL Token.
 */
@Serializable
data class SplMintInfo(
    val hasMintAuthority: Boolean,
    val hasFreezeAuthority: Boolean,
    val decimals: Int,
    val supply: Long,
    val tokenProgram: String? = null,
    val extensions: List<Int> = emptyList(),
)

/** Итог отправки транзакции в RPC. */
sealed interface SendResult {
    /** Узел принял транзакцию (исполнится ли — покажет [SolanaRpcClient.awaitSignature]). */
    data class Sent(val signature: String) : SendResult

    /** Узел явно отверг (preflight: нет SOL, slippage, ошибка программы) — в сеть она не ушла. */
    data class Rejected(val reason: String) : SendResult

    /** Ответа нет (сеть, таймаут, 5xx): транзакция могла уйти — итог узнаём по подписи. */
    data object Unknown : SendResult
}

/** Итог ожидания подписи. */
enum class TxOutcome {
    /** Исполнена успешно (confirmed/finalized без ошибки). */
    CONFIRMED,

    /** Попала в блок с ошибкой (например, превышен slippage свопа) — средства не ушли. */
    FAILED,

    /** За отведённое время статуса нет: могла не дойти, а могла исполниться позже. */
    TIMEOUT,
}

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
    private val maxParallel: Int = 8,
    private val confirmTimeoutMs: Int = 35_000
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

    private suspend fun postRpcWithRetry(requestJson: String, operation: String): HttpResponse? {
        var attempt = 0
        var backoffMs = 500L
        while (attempt < maxRetries) {
            try {
                val response = client.post(rpcUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }
                val status = response.status
                if (status.value == 429 || status.value >= 500) {
                    println("⏳ RPC $operation ${status.value}, retry in ${backoffMs}ms...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                    attempt++
                    continue
                }
                if (!status.isSuccess()) {
                    println("⚠️ RPC $operation HTTP ${status.value}")
                    return null
                }
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt < maxRetries) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                } else {
                    println("⚠️ RPC $operation error: ${e.message}")
                }
            }
        }
        return null
    }

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
                return parseSplMintFromBase64(base64Data)?.copy(tokenProgram = accountInfo.owner)

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
     * COption в SPL фиксированного размера: 32 байта ключа есть и при option = 0 (заполнены нулями).
     * Token-2022 начинается с той же 82-байтной базы — расширения идут после неё: база добита
     * нулями до 165 байт, байт 165 — тип аккаунта (1 = mint), дальше TLV: тип u16, длина u16, данные.
     */
    internal fun parseSplMintFromBase64(base64Data: String): SplMintInfo? {
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

            // mintAuthority (32 байта, есть всегда — даже когда authority отозвана)
            offset += 32

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
                supply = supply.toLong(),
                extensions = parseToken2022Extensions(bytes),
            )

        } catch (e: Exception) {
            println("❌ Ошибка парсинга SPL Mint: ${e.message}")
            null
        }
    }
    
    /** Типы TLV-расширений Token-2022 после базового mint; у обычного SPL-токена (82 байта) — пусто. */
    private fun parseToken2022Extensions(bytes: ByteArray): List<Int> {
        if (bytes.size <= MINT_EXTENSIONS_START || bytes[ACCOUNT_TYPE_OFFSET].toInt() != ACCOUNT_TYPE_MINT) {
            return emptyList()
        }
        val types = mutableListOf<Int>()
        var offset = MINT_EXTENSIONS_START
        while (offset + 4 <= bytes.size) {
            val type = bytesToUInt16(bytes, offset)
            val length = bytesToUInt16(bytes, offset + 2)
            if (type == 0) break // Uninitialized — дальше только добивка
            types += type
            offset += 4 + length
        }
        return types
    }

    private fun bytesToUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

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

                val response = postRpcWithRetry(requestJson, "getAccountInfo") ?: return null

                val status = response.status
                if (!status.isSuccess()) return null

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

                val response = postRpcWithRetry(requestJson, "getBalance") ?: return@withPermit null

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

    /**
     * Суммарный баланс SPL-токена на кошельке в raw-единицах.
     * null — RPC недоступен/ошибка, 0 — токена на кошельке нет.
     */
    suspend fun getTokenBalanceRaw(owner: String, mint: String): Long? {
        return semaphore.withPermit {
            try {
                val requestJson = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "getTokenAccountsByOwner",
                    "params": [
                        "$owner",
                        { "mint": "$mint" },
                        { "encoding": "jsonParsed", "commitment": "confirmed" }
                    ]
                }
                """.trimIndent()

                val response = postRpcWithRetry(requestJson, "getTokenAccountsByOwner")
                    ?: return@withPermit null

                val jsonElement = json.parseToJsonElement(response.bodyAsText())
                val accounts = jsonElement.jsonObject["result"]
                    ?.jsonObject
                    ?.get("value")
                    ?.jsonArray
                    ?: return@withPermit null

                var total = 0L
                for (acc in accounts) {
                    val amount = acc.jsonObject["account"]?.jsonObject
                        ?.get("data")?.jsonObject
                        ?.get("parsed")?.jsonObject
                        ?.get("info")?.jsonObject
                        ?.get("tokenAmount")?.jsonObject
                        ?.get("amount")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    total += amount
                }
                total
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("⚠️ getTokenAccountsByOwner error: ${e.message}")
                null
            }
        }
    }

    /**
     * Отправка подписанной транзакции. Различаем «узел отверг» (в сеть не ушла — можно считать
     * неудачей) и «ответа нет» (могла уйти — итог только по подписи), иначе потерянный ответ
     * выглядел как отказ, и бот мог повторить уже исполненную покупку.
     */
    suspend fun sendTransaction(base64Tx: String): SendResult {
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

                val response = postRpcWithRetry(requestJson, "sendTransaction")
                    ?: return@withPermit SendResult.Unknown

                val responseText = response.bodyAsText()
                val body = json.parseToJsonElement(responseText).jsonObject
                val result = body["result"]?.jsonPrimitive?.content
                if (result != null) return@withPermit SendResult.Sent(result)
                // Отказ preflight-симуляции (нет SOL на комиссию, slippage и т.п.) приходит
                // как JSON-RPC error при HTTP 200 — без лога причина терялась бесследно.
                val reason = body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: responseText.take(300)
                println("⚠️ sendTransaction отклонена RPC: $reason")
                SendResult.Rejected(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("⚠️ sendTransaction error: ${e.message}")
                SendResult.Unknown
            }
        }
    }

    /**
     * Статусы подписей одним запросом: true — исполнена, false — упала с ошибкой, null — сеть её
     * не знает или она ещё не подтверждена. null вместо карты — RPC недоступен (решения не принимаем).
     */
    suspend fun getSignatureStatuses(signatures: List<String>): Map<String, Boolean?>? {
        if (signatures.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Boolean?>()
        for (chunk in signatures.chunked(MAX_SIGNATURES_PER_REQUEST)) {
            val list = chunk.joinToString(",") { "\"$it\"" }
            val requestJson = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "getSignatureStatuses",
                    "params": [
                        [$list],
                        { "searchTransactionHistory": true }
                    ]
                }
            """.trimIndent()
            val response = postRpcWithRetry(requestJson, "getSignatureStatuses") ?: return null
            val values = try {
                json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]
                    ?.jsonObject?.get("value")?.jsonArray
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            chunk.forEachIndexed { i, sig -> result[sig] = signatureOutcome(values.getOrNull(i) as? JsonObject) }
        }
        return result
    }

    /** Ждёт итог подписи до таймаута подтверждения (см. [TxOutcome]). */
    suspend fun awaitSignature(signature: String): TxOutcome {
        val startedMs = Clock.System.now().toEpochMilliseconds()
        var backoffMs = 500L
        while (Clock.System.now().toEpochMilliseconds() - startedMs < confirmTimeoutMs) {
            val requestJson = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "getSignatureStatuses",
                    "params": [
                        ["$signature"],
                        { "searchTransactionHistory": true }
                    ]
                }
            """.trimIndent()
            val response = postRpcWithRetry(requestJson, "getSignatureStatuses")
            if (response != null) {
                // Пока узел не видел подпись, value = [null]: `.jsonObject` на JsonNull бросал
                // исключение, и оно вылетало из подтверждения уже ОТПРАВЛЕННОЙ транзакции —
                // покупка исполнялась, а сделка не записывалась. Сбой разбора = «ещё ждём».
                val value = try {
                    json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]
                        ?.jsonObject
                        ?.get("value")
                        ?.jsonArray
                        ?.firstOrNull() as? JsonObject
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                when (signatureOutcome(value)) {
                    true -> return TxOutcome.CONFIRMED
                    false -> {
                        println("❌ Транзакция $signature исполнилась с ошибкой: ${value?.get("err")}")
                        return TxOutcome.FAILED
                    }
                    null -> Unit // ещё не подтверждена — ждём
                }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(3000L)
        }
        return TxOutcome.TIMEOUT
    }

    fun close() {
        client.close()
    }
}

/**
 * Итог по статусу подписи из `getSignatureStatuses`: true — исполнена успешно, false — упала
 * (ошибка программы, например превышен slippage свопа), null — ещё не подтверждена.
 *
 * Ошибку проверяем ДО статуса: упавшая транзакция тоже попадает в блок и получает
 * confirmationStatus "confirmed"/"finalized". Раньше такой своп считался успешным — бот
 * записывал выход из позиции, хотя токены оставались на кошельке.
 */
internal fun signatureOutcome(status: JsonObject?): Boolean? {
    if (status == null) return null
    val err = status["err"]
    if (err != null && err != JsonNull) return false
    val confirmation = (status["confirmationStatus"] as? JsonPrimitive)?.content
    return if (confirmation == "confirmed" || confirmation == "finalized") true else null
}

