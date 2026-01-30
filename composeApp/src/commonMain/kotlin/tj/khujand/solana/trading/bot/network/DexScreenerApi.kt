package tj.khujand.solana.trading.bot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.delay
import kotlin.time.Clock

// МОДЕЛИ ДАННЫХ (перенесем сюда чтобы все в одном месте)
@Serializable
data class DexScreenerResponse(
    val pairs: List<TokenPair> = emptyList()
)

@Serializable
data class TokenPair(
    val chainId: String? = null,
    val dexId: String? = null,
    val pairAddress: String? = null,
    val baseToken: BaseToken? = null,
    val quoteToken: QuoteToken? = null,
    val priceNative: String? = null,
    val priceUsd: String? = null,
    val volume: Volume? = null,
    val liquidity: Liquidity? = null,
    val pairCreatedAt: Long? = null,
    val marketCap: Double? = null,
    val fdv: Double? = null,
    val txns: Txns? = null
)

@Serializable
data class BaseToken(
    val address: String? = null,
    val name: String? = null,
    val symbol: String? = null
)

@Serializable
data class QuoteToken(
    val address: String? = null,
    val name: String? = null,
    val symbol: String? = null
)

@Serializable
data class Volume(
    val h24: Double? = null,
    val h6: Double? = null,
    val h1: Double? = null,
    val m5: Double? = null
)

@Serializable
data class Liquidity(
    val usd: Double? = null
)

@Serializable
data class Txns(
    val h24: BuySell? = null,
    val h6: BuySell? = null,
    val h1: BuySell? = null,
    val m5: BuySell? = null
)

@Serializable
data class BuySell(
    val buys: Int? = null,
    val sells: Int? = null
)

@Serializable
data class TokenProfile(
    val chainId: String? = null,
    val tokenAddress: String? = null
)

// НАСТРОЙКИ ФИЛЬТРОВ
@Serializable
data class FilterSettings(
    val minVolumeUSD: Double = 5000.0,
    val maxAgeHours: Int = 24,
    val minLiquidityUSD: Double = 10000.0,
    val chains: List<String> = listOf("solana"),
    val excludeRugPull: Boolean = true,
    val checkHolders: Boolean = false,
    val maxTokensToMonitor: Int = 10 // 👈 Новое поле: максимум токенов
)

// API КЛИЕНТ
class DexScreenerApi {

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

        install(Logging) {
            level = LogLevel.HEADERS
        }
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val maxProfileTokens = 25
    private val minRequestDelayMs = 250L
    private val maxRetries = 3

    // ✅ УЛУЧШЕННЫЙ МЕТОД: Получение НОВЫХ токенов Solana
    suspend fun getNewTokens(settings: FilterSettings): List<TokenPair> {
        val allTokens = mutableListOf<TokenPair>()

        try {
            println("🔍 Поиск новых токенов...")

            // СТРАТЕГИЯ 1: Последние токены из token-profiles/latest
            val profileTokens = getLatestTokenProfilePairs(settings)
            if (profileTokens.isNotEmpty()) {
                println("🧩 Найдено пар через token-profiles: ${profileTokens.size}")
                allTokens.addAll(profileTokens)
            } else {
                println("⚠️ token-profiles не вернул данные или пусто")
            }

            // СТРАТЕГИЯ 2: Поиск по популярным DEX/платформам
            val searchTokens = getSearchTokens(settings)
            if (searchTokens.isNotEmpty()) {
                println("🧩 Найдено пар через search: ${searchTokens.size}")
                allTokens.addAll(searchTokens)
            }

            println("🔗 Всего пар найдено: ${allTokens.size}")

            // Убираем дубликаты
            val uniqueTokens = allTokens.distinctBy { it.pairAddress ?: it.baseToken?.address }
            println("🔗 Уникальных пар: ${uniqueTokens.size}")

            // Применяем фильтры для поиска НОВЫХ токенов
            val filtered = uniqueTokens.filter { token ->
                if (token.pairAddress.isNullOrEmpty()) return@filter false
                if (token.baseToken?.address.isNullOrEmpty()) return@filter false
                if (token.baseToken?.symbol.isNullOrEmpty()) return@filter false

                // 1. 🔗 Проверка цепочки (если список пустой - пропускаем)
                val chainOk = if (settings.chains.isEmpty()) {
                    true
                } else {
                    settings.chains.contains(token.chainId)
                }

                if (!chainOk) return@filter false

                // 2. ⏰ ГЛАВНЫЙ ФИЛЬТР: Проверка возраста (НОВЫЕ токены)
                val ageOk = if (token.pairCreatedAt != null && token.pairCreatedAt > 0) {
                    val ageHours = (Clock.System.now().toEpochMilliseconds() - token.pairCreatedAt) / (1000 * 60 * 60)
                    val isNew = ageHours <= settings.maxAgeHours
                    if (isNew) {
                        println("🆕 НОВЫЙ: ${token.baseToken?.symbol} (возраст: ${ageHours}ч)")
                    }
                    isNew
                } else {
                    // Если нет даты создания - разрешаем, но логируем
                    println("⚠️ ${token.baseToken?.symbol}: нет даты создания пары, пропускаем фильтр возраста")
                    true
                }

                if (!ageOk) return@filter false

                // 3. 💰 Проверка объема (должен быть > minVolumeUSD)
                val volumeOk = token.volume?.h24 ?: 0.0 >= settings.minVolumeUSD

                // 4. 💧 Проверка ликвидности
                val liquidityOk = token.liquidity?.usd ?: 0.0 >= settings.minLiquidityUSD

                // 5. 🚨 Проверка на скам (если включено)
                val notScam = if (settings.excludeRugPull) {
                    !isPotentialScam(token)
                } else true

                volumeOk && liquidityOk && notScam
            }

            println("✅ После фильтрации (только НОВЫЕ): ${filtered.size}")

            // Сортируем по времени создания (самые новые первыми)
            return filtered.sortedByDescending { it.pairCreatedAt ?: 0L }

        } catch (e: Exception) {
            println("❌ Ошибка получения токенов: ${e.message}")
            e.printStackTrace()
        }

        return emptyList()
    }


    // ✅ РАБОЧИЙ МЕТОД: Получить информацию о токене
    suspend fun getTokenDetails(pairAddress: String): TokenPair? {
        return try {
            println("📡 Запрос информации для пары: $pairAddress")

            // Используем endpoint для конкретной пары
            // Формат: /latest/dex/pairs/{chainId}/{pairAddress}
            val url = "https://api.dexscreener.com/latest/dex/pairs/solana/$pairAddress"
            val jsonElement = getJsonElementWithRetry(url) ?: return null
            val pairs = parsePairsFromJson(jsonElement)

            pairs.firstOrNull()?.also {
                println("✅ Информация получена: ${it.baseToken?.symbol}")
            }

        } catch (e: Exception) {
            println("❌ Ошибка получения деталей токена: ${e.message}")
            null
        }
    }

    // ✅ РАБОЧИЙ МЕТОД: Обновить цену токена
    suspend fun updateTokenPrice(token: TokenPair): TokenPair? {
        if (token.pairAddress.isNullOrEmpty()) return null

        return try {
            // Получаем обновленные данные
            val updated = getTokenDetails(token.pairAddress!!)
            updated?.let {
                println("📈 Цена обновлена: ${it.baseToken?.symbol} -> $${it.priceUsd}")
            }
            updated
        } catch (e: Exception) {
            println("⚠️ Ошибка обновления цены: ${e.message}")
            null
        }
    }


    // 🚨 Проверка на потенциальный скам
    private fun isPotentialScam(token: TokenPair): Boolean {
        // Простые проверки:

        // 1. Слишком большая разница между FDV и ликвидностью
        if (token.fdv == null || token.liquidity?.usd == null) return true
        val fdvToLiquidityRatio = token.fdv / token.liquidity.usd

        if (fdvToLiquidityRatio > 100) return true

        // 2. Слишком много продаж vs покупок
        val sells = token.txns?.h24?.sells ?: 0
        val buys = token.txns?.h24?.buys ?: 0
        if (sells > 0 && buys > 0 && sells.toDouble() / buys > 3) return true

        // 3. Слишком маленькая ликвидность при большом объеме
        val volumeToLiquidityRatio = (token.volume?.h24 ?: 0.0) / token.liquidity.usd
        if (volumeToLiquidityRatio > 10) return true

        // 4. Нет ликвидности или она заблокирована слишком слабо
        if ((token.liquidity?.usd ?: 0.0) < 3000) return true

        // 5. Нет покупок вообще
        val buys5m = token.txns?.m5?.buys ?: 0
        if (buys5m == 0) return true

        return false
    }

    fun close() {
        client.close()
    }

    private suspend fun getSearchTokens(settings: FilterSettings): List<TokenPair> {
        val searchQueries = listOf(
            "Raydium",
            "Orca",
            "Meteora",
            "Pump.fun",
            "PumpSwap",
            "Bags",
        )

        val searchTokens = mutableListOf<TokenPair>()
        for (query in searchQueries) {
            try {
                println("🔍 Поиск по запросу: $query")

                val url = "https://api.dexscreener.com/latest/dex/search"
                val jsonElement = getJsonElementWithRetry(url, mapOf("q" to query))
                if (jsonElement != null) {
                    val pairs = parsePairsFromJson(jsonElement)
                    val filteredByChain = if (settings.chains.isEmpty()) {
                        pairs
                    } else {
                        pairs.filter { settings.chains.contains(it.chainId) }
                    }
                    println("📊 Получено пар для '$query': ${filteredByChain.size}")
                    searchTokens.addAll(filteredByChain)
                }
            } catch (e: Exception) {
                println("⚠️ Ошибка при поиске '$query': ${e.message}")
            }
        }

        return searchTokens
    }

    private suspend fun getLatestTokenProfilePairs(settings: FilterSettings): List<TokenPair> {
        val url = "https://api.dexscreener.com/token-profiles/latest/v1"
        val jsonElement = getJsonElementWithRetry(url) ?: return emptyList()
        val profiles = parseTokenProfilesFromJson(jsonElement)

        val recentTokens = profiles.mapNotNull { profile ->
            val chainId = profile.chainId
            val tokenAddress = profile.tokenAddress
            if (chainId.isNullOrBlank() || tokenAddress.isNullOrBlank()) return@mapNotNull null
            if (settings.chains.isNotEmpty() && !settings.chains.contains(chainId)) return@mapNotNull null
            chainId to tokenAddress
        }.distinct().take(maxProfileTokens)

        if (recentTokens.isEmpty()) return emptyList()

        val pairs = mutableListOf<TokenPair>()
        for ((chainId, tokenAddress) in recentTokens) {
            val tokenPairs = getTokenPairsForAddress(chainId, tokenAddress)
            if (tokenPairs.isNotEmpty()) {
                pairs.addAll(tokenPairs)
            }
            delay(minRequestDelayMs)
        }

        return pairs
    }

    private suspend fun getTokenPairsForAddress(chainId: String, tokenAddress: String): List<TokenPair> {
        val url = "https://api.dexscreener.com/token-pairs/v1/$chainId/$tokenAddress"
        val jsonElement = getJsonElementWithRetry(url) ?: return emptyList()
        return parsePairsFromJson(jsonElement)
    }

    private suspend fun getJsonElementWithRetry(
        url: String,
        queryParams: Map<String, String> = emptyMap()
    ): JsonElement? {
        var attempt = 0
        var backoffMs = 500L

        while (attempt < maxRetries) {
            try {
                val response = client.get(url) {
                    queryParams.forEach { (key, value) -> parameter(key, value) }
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
                    println("⚠️ Ошибка HTTP ${status.value} для $url")
                    return null
                }

                return response.body()
            } catch (e: Exception) {
                println("⚠️ Ошибка запроса: ${e.message}")
                attempt++
                if (attempt < maxRetries) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                }
            }
        }

        return null
    }

    private fun parsePairsFromJson(jsonElement: JsonElement): List<TokenPair> {
        val array = when (jsonElement) {
            is JsonObject -> jsonElement["pairs"]?.jsonArray
            is JsonArray -> jsonElement
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<TokenPair>(element) }.getOrNull()
        }
    }

    private fun parseTokenProfilesFromJson(jsonElement: JsonElement): List<TokenProfile> {
        val array = when (jsonElement) {
            is JsonArray -> jsonElement
            is JsonObject -> jsonElement["data"]?.jsonArray ?: jsonElement["profiles"]?.jsonArray
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<TokenProfile>(element) }.getOrNull()
        }
    }
}
