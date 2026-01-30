package tj.khujand.solana.trading.bot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    // ✅ УЛУЧШЕННЫЙ МЕТОД: Получение НОВЫХ токенов Solana
    suspend fun getNewTokens(settings: FilterSettings): List<TokenPair> {
        val allTokens = mutableListOf<TokenPair>()

        try {
            println("🔍 Поиск новых токенов Solana...")

            // СТРАТЕГИЯ 1: Поиск по популярным парам Solana
            val searchQueries = listOf(
                "Raydium",
                "Orca",
                "Meteora",
                "Pump.fan",
                "PumpSwap",
                "Bags",
            )
            for (query in searchQueries) {
                try {
                    println("🔍 Поиск по запросу: $query")

                    val url = "https://api.dexscreener.com/latest/dex/search"
                    val response: DexScreenerResponse = client.get(url) {
                        parameter("q", query)
                    }.body()

                    println("📊 Получено пар для '$query': ${response.pairs.size}")

                    // Фильтруем только Solana токены
                    val solanaTokens = response.pairs.filter { it.chainId == "solana" }
                    allTokens.addAll(solanaTokens)

                    // Задержка между запросами (rate limit: 300/min)
                    delay(500)

                } catch (e: Exception) {
                    println("⚠️ Ошибка при поиске '$query': ${e.message}")
                }
            }

            println("🔗 Всего Solana пар найдено: ${allTokens.size}")

            // Убираем дубликаты
            val uniqueTokens = allTokens.distinctBy { it.pairAddress }
            println("🔗 Уникальных пар: ${uniqueTokens.size}")

            // Применяем фильтры для поиска НОВЫХ токенов
            val filtered = uniqueTokens.filter { token ->
                if (token.pairAddress.isNullOrEmpty()) return@filter false
                if (token.baseToken?.address.isNullOrEmpty()) return@filter false
                if (token.baseToken?.symbol.isNullOrEmpty()) return@filter false

                // 1. ⏰ ГЛАВНЫЙ ФИЛЬТР: Проверка возраста (НОВЫЕ токены)
                val ageOk =if (token.pairCreatedAt != null && token.pairCreatedAt > 0) {
                    val ageHours = (Clock.System.now().toEpochMilliseconds() - token.pairCreatedAt) / (1000 * 60 * 60)
                    val isNew = ageHours <= settings.maxAgeHours
                    if (isNew) {
                        println("🆕 НОВЫЙ: ${token.baseToken?.symbol} (возраст: ${ageHours}ч)")
                    }
                    isNew
                } else {
                    // Если нет даты создания - пропускаем (не можем определить новизну)
                    println("⚠️ ${token.baseToken?.symbol}: нет даты создания пары")
                    false
                }

                if (!ageOk) return@filter false

                // 2. 💰 Проверка объема (должен быть > minVolumeUSD)
                val volumeOk = token.volume?.h24 ?: 0.0 >= settings.minVolumeUSD

                // 3. 💧 Проверка ликвидности
                val liquidityOk = token.liquidity?.usd ?: 0.0 >= settings.minLiquidityUSD

                // 4. 🚨 Проверка на скам (если включено)
                val notScam = if (settings.excludeRugPull) {
                    !isPotentialScam(token)
                } else true

                volumeOk && liquidityOk && notScam
            }

            println("✅ После фильтрации (только НОВЫЕ): ${filtered.size}")

            // Сортируем по времени создания (самые новые первыми)
            return filtered.sortedByDescending { it.pairCreatedAt }

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
            val response: DexScreenerResponse = client.get(url).body()

            response.pairs.firstOrNull()?.also {
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
}
