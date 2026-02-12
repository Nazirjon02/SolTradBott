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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val txns: Txns? = null,
    val priceChange: PriceChange? = null,
    val info: TokenInfo? = null
)

@Serializable
data class PriceChange(
    val m5: Double? = null,
    val h1: Double? = null,
    val h24: Double? = null
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
data class TokenInfo(
    val websites: List<TokenLink>? = null,
    val socials: List<TokenSocial>? = null
)

@Serializable
data class TokenLink(
    val label: String? = null,
    val url: String? = null
)

@Serializable
data class TokenSocial(
    val type: String? = null,
    val url: String? = null
)

@Serializable
data class TokenProfile(
    val chainId: String? = null,
    val tokenAddress: String? = null
)

@Serializable
data class TokenBoost(
    val chainId: String? = null,
    val tokenAddress: String? = null,
    val createdAt: Long? = null
)

// ════════════════════════════════════════════════════════════════════════════════
// НАСТРОЙКИ ФИЛЬТРОВ И ТОРГОВЛИ
// ════════════════════════════════════════════════════════════════════════════════
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class FilterSettings(
    // ─────────────────────────────────────────────────────────────────────────────
    // ОБЩИЕ НАСТРОЙКИ
    // ─────────────────────────────────────────────────────────────────────────────
    val minVolumeUSD: Double = 5000.0,              // Минимальный объём USD (старая настройка, не используется)
    val maxAgeHours: Int = 24,                      // Максимальный возраст токена в часах (старая настройка)
    val minLiquidityUSD: Double = 10000.0,          // Минимальная ликвидность USD (старая настройка)
    val chains: List<String> = listOf("solana"),   // Список блокчейнов для мониторинга (только Solana)
    val excludeRugPull: Boolean = true,             // Исключать потенциальные скам-токены
    val checkHolders: Boolean = false,              // Проверять количество холдеров (не используется)
    val maxTokensToMonitor: Int = 6,                // ⭐ ВАЖНО: максимум токенов в мониторинге одновременно
    
    // ─────────────────────────────────────────────────────────────────────────────
    // ФИЛЬТРЫ ДЛЯ ПЕРВИЧНОГО ОТБОРА (в DexScreener API)
    // ─────────────────────────────────────────────────────────────────────────────
    val liquidityMinUsd: Double = 200.0,            // Мин. ликвидность для первичного отбора
    val volumeH24MinUsd: Double = 1000.0,           // Мин. объём 24ч для первичного отбора
    val pairMaxAgeHours: Double = 1.0,              // Макс. возраст пары в часах (для поиска НОВЫХ токенов)
    val buysH1Min: Int = 1,                         // Мин. количество покупок за 1 час (для скоринга)
    val maxSellsToBuysRatioH1: Double = 1.2,        // Макс. соотношение продаж/покупок за 1ч (штраф в скоринге)
    val maxAbsPriceChangeH1Pct: Double = 250.0,     // Макс. изменение цены за 1ч в % (не используется активно)
    val maxTokensPerTick: Int = 2,                  // ⭐ Макс. токенов для добавления за один цикл поиска
    val minScoreAccept: Int = 10,                   // ⭐ Мин. очков скоринга для допуска токена (0-100)
    
    // 📈 ФИЛЬТР: Buys/Sells ratio за 5 минут (давление покупок)
    val useMinBuysToSellsRatioM5: Boolean = true,   // ⭐ Включить/выключить проверку Buys/Sells ratio
    val minBuysToSellsRatioM5: Double = 1.8,        // ⭐ Мин. соотношение buys/sells за 5 мин (1.8 = на 80% больше покупок)
    
    // 📈 ФИЛЬТР: Рост цены за 5 минут (памп-детектор)
    val useMinPriceChangeM5Pct: Boolean = true,     // ⭐ Включить/выключить проверку роста цены за 5м
    val minPriceChangeM5Pct: Double = 150.0,        // ⭐ Мин. рост цены за 5 мин в % (150 = +150% за 5 минут)
    
    // ─────────────────────────────────────────────────────────────────────────────
    // УСЛОВИЯ ВХОДА (Entry Criteria) - проверяются перед покупкой
    // ─────────────────────────────────────────────────────────────────────────────
    val entryMaxAgeMinutes: Int = 30,               // ⭐ Макс. возраст токена в минутах (для входа в позицию)
    val entryMinMarketCap: Double = 80_000.0,       // ⭐ Мин. Market Cap USD ($500-$200K, шаг $500)
    val entryMaxMarketCap: Double = 200_000.0,      // ⭐ Макс. Market Cap USD (диапазон входа)
    val entryMinLiquidity: Double = 5_000.0,        // ⭐ Мин. ликвидность USD (защита от низколиквидных токенов)
    val entryMinVolume: Double = 150_000.0,         // ⭐ Мин. объём торгов 24ч USD (если useVolumeH24 = true)
    val entryMinVolumeM5: Double = 30_000.0,        // ⭐ Мин. объём торгов 5 мин USD (если useVolumeM5 = true)
    val useVolumeH24: Boolean = false,              // Использовать проверку объёма 24ч (обычно false)
    val useVolumeM5: Boolean = true,                // ⭐ Использовать проверку объёма 5 мин (обычно true для новых токенов)
    val requireSocials: Boolean = true,             // ⭐ Требовать наличие соцсетей (Telegram/X)
    val requireWebsite: Boolean = true,             // ⭐ Требовать наличие сайта
    
    // ─────────────────────────────────────────────────────────────────────────────
    // СТРАТЕГИИ ВЫХОДА (Exit Strategy)
    // ─────────────────────────────────────────────────────────────────────────────
    val exitStrategy: String = "stages",            // ⭐ ВЫБОР СТРАТЕГИИ: "stages" или "aggressive"
    
    // 🔥 AGGRESSIVE MODE (один тейк-профит + trailing)
    val aggressiveTakeProfitPct: Double = 100.0,    // ⭐ При какой прибыли % делать фиксацию (20-300%, default 100%)
    val aggressiveSellPct: Double = 50.0,           // ⭐ Сколько % позиции продать (10-100%, default 50%; остальное trailing)
    
    // 📊 STAGES MODE (4 этапа по Market Cap)
    // Stage 1: первая фиксация
    val exitStage1Cap: Double = 200_000.0,          // ⭐ При достижении Market Cap $200K
    val exitStage1Pct: Double = 30.0,               // ⭐ Продать 30% позиции
    
    // Stage 2: вторая фиксация
    val exitStage2Cap: Double = 250_000.0,          // ⭐ При достижении Market Cap $250K
    val exitStage2Pct: Double = 30.0,               // ⭐ Продать ещё 30% (всего 60%)
    
    // Stage 3: третья фиксация
    val exitStage3Cap: Double = 300_000.0,          // ⭐ При достижении Market Cap $300K
    val exitStage3Pct: Double = 20.0,               // ⭐ Продать ещё 20% (всего 80%)
    
    // Stage 4: последняя фиксация
    val exitStage4Cap: Double = 350_000.0,          // ⭐ При достижении Market Cap $350K
    val exitStage4Pct: Double = 20.0,               // ⭐ Продать оставшиеся 20% (всего 100%)

    // ─────────────────────────────────────────────────────────────────────────────
    // ЗАЩИТНЫЕ МЕХАНИЗМЫ (автоматические, жёстко закодированы в TokenMonitor)
    // ─────────────────────────────────────────────────────────────────────────────
    // Stop Loss: -30% от входной Market Cap ИЛИ -25% по цене → принудительный выход
    // Trailing Stop: -35% от локального максимума Market Cap → выход (после Stage 1 / Aggressive)
    // Stage Pullback: -35% от пика цены после частичной фиксации → выход
    
    // ─────────────────────────────────────────────────────────────────────────────
    // JUPITER TRADING (реальные свапы через Jupiter Aggregator)
    // ─────────────────────────────────────────────────────────────────────────────
    val jupiterEnabled: Boolean = false,            // ⭐ ВАЖНО: false = DEMO (виртуальные сделки), true = РЕАЛЬНЫЕ свапы
    val jupiterApiKey: String = "",                 // Jupiter API ключ (опционально, для rate limits)
    val tradeUsdAmount: Double = 6.0,               // ⭐ Сумма USD на одну реальную покупку (если Jupiter включён)
    val slippageBps: Int = 50,                      // ⭐ Slippage в basis points (50 = 0.5%, 100 = 1%)
    val seedPhrase: String = "",                    // ⭐ SEED PHRASE (12/24 слова) для автоподписи транзакций
    val baseMint: String = "So11111111111111111111111111111111111111112",  // SOL mint адрес (базовая валюта для свапов)
    
    // ─────────────────────────────────────────────────────────────────────────────
    // SOLANA RPC (подключение к блокчейну)
    // ─────────────────────────────────────────────────────────────────────────────
    val rpcUrl: String = "https://api.mainnet-beta.solana.com",  // ⭐ Solana RPC URL (можно заменить на Helius/QuickNode)
    val rpcTimeoutSeconds: Int = 12,                // Таймаут RPC запросов в секундах
    
    // ─────────────────────────────────────────────────────────────────────────────
    // 🤖 AI ANALYSIS (умный анализ токенов перед входом)
    // ─────────────────────────────────────────────────────────────────────────────
    val useAiAnalysis: Boolean = false,             // ⭐ Включить AI-анализ токенов
    val aiProvider: String = "groq",                // ⭐ Провайдер AI: "groq" (бесплатный!), "claude", "openai"
    val aiApiKey: String = "",                      // ⭐ API ключ (groq.com, anthropic.com, openai.com)
    val aiModel: String = "llama-3.1-8b-instant",   // ⭐ Модель: llama-3.1-8b-instant / llama-3.3-70b-versatile (Groq) / claude-3-5-sonnet / gpt-4o-mini
    val minAiScore: Int = 60,                       // ⭐ Мин. AI score для входа (0-100)
    val maxAiRugRisk: String = "MEDIUM",            // ⭐ Макс. допустимый rug risk: LOW/MEDIUM/HIGH
    val aiTimeoutSeconds: Int = 15                  // Таймаут AI запроса в секундах
)
// ════════════════════════════════════════════════════════════════════════════════

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
    private val minRequestDelayMs = 700L
    private val maxRetries = 3
    private var useBoostsNext = true

    suspend fun getTokenPriceUsd(chainId: String, tokenAddress: String): Double? {
        val url = "https://api.dexscreener.com/tokens/v1/$chainId/$tokenAddress"
        val jsonElement = getJsonElementWithRetry(url) ?: return null
        val pairs = parsePairsFromJson(jsonElement)
        val priceStr = pairs.firstOrNull()?.priceUsd
        return priceStr?.toDoubleOrNull()
    }

    // ✅ УЛУЧШЕННЫЙ МЕТОД: Получение НОВЫХ токенов Solana (выбор API через настройки)
    suspend fun getNewTokens(settings: FilterSettings): List<TokenPair> {
        val allTokens = mutableListOf<TokenPair>()

        try {
            val useBoostsNow = useBoostsNext
            useBoostsNext = !useBoostsNext

            // По очереди: boosts -> profiles -> boosts...
            if (useBoostsNow) {
                println("🔍 Поиск новых токенов через token-boosts...")
                val boostTokens = getLatestTokenBoosts(settings)
                if (boostTokens.isNotEmpty()) {
                    println("🧩 Найдено токенов через token-boosts: ${boostTokens.size}")
                    allTokens.addAll(boostTokens)
                } else {
                    println("⚠️ token-boosts не вернул данные или пусто")
                }
            } else {
                println("🔍 Поиск новых токенов через token-profiles...")
                val profileTokens = getLatestTokenProfilePairs(settings)
                if (profileTokens.isNotEmpty()) {
                    println("🧩 Найдено токенов через token-profiles: ${profileTokens.size}")
                    allTokens.addAll(profileTokens)
                } else {
                    println("⚠️ token-profiles не вернул данные или пусто")
                }
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

                // 1. 🔗 Проверка цепочки (только Solana)
                if (!settings.chains.contains("solana") && token.chainId != "solana") {
                    return@filter false
                }

                // 2. ⏰ Проверка возраста (НОВЫЕ токены)
                val ageOk = if (token.pairCreatedAt != null && token.pairCreatedAt > 0) {
                    val ageHours = (Clock.System.now().toEpochMilliseconds() - token.pairCreatedAt) / (1000.0 * 60.0 * 60.0)
                    val isNew = ageHours <= settings.pairMaxAgeHours
                    if (isNew) {
                        println("🆕 НОВЫЙ: ${token.baseToken?.symbol} (возраст: ${ageHours}ч)")
                    }
                    isNew
                } else {
                    println("⚠️ ${token.baseToken?.symbol}: нет даты создания пары, пропускаем фильтр возраста")
                    true
                }

                if (!ageOk) return@filter false

                // 3. 💰 Проверка объема (24h / 5m по флагам)
                val volumeH24Ok = if (settings.useVolumeH24) {
                    (token.volume?.h24 ?: 0.0) >= settings.entryMinVolume
                } else true
                val volumeM5Ok = if (settings.useVolumeM5) {
                    (token.volume?.m5 ?: 0.0) >= settings.entryMinVolumeM5
                } else true
                val volumeOk = volumeH24Ok && volumeM5Ok

                // 4. 💧 Проверка ликвидности (должна быть >= liquidityMinUsd)
                val liquidityOk = (token.liquidity?.usd ?: 0.0) >= settings.liquidityMinUsd

                // 5. 🚨 Проверка на скам (если включено)
                val notScam = if (settings.excludeRugPull) {
                    !isPotentialScam(token)
                } else true

                // 6. 📈 Buys/Sells ratio M5 > min (если фильтр включён)
                val buysM5 = token.txns?.m5?.buys ?: 0
                val sellsM5 = token.txns?.m5?.sells ?: 0
                val ratioOk = if (!settings.useMinBuysToSellsRatioM5) true else when {
                    sellsM5 == 0 -> buysM5 > 0
                    else -> (buysM5.toDouble() / sellsM5) >= settings.minBuysToSellsRatioM5
                }

                // 7. 📈 Price ↑ за 5 мин (если фильтр включён; если m5 нет в API — пропускаем)
                val pricePumpOk = if (!settings.useMinPriceChangeM5Pct) true
                else token.priceChange?.m5?.let { it >= settings.minPriceChangeM5Pct } ?: true

                volumeOk && liquidityOk && notScam && ratioOk && pricePumpOk
            }

            println("✅ После фильтрации (только НОВЫЕ): ${filtered.size}")

            // Ограничиваем количество токенов за один тик
            val limited = filtered.take(settings.maxTokensPerTick)

            // Сортируем по времени создания (самые новые первыми)
            return limited.sortedByDescending { it.pairCreatedAt ?: 0L }

        } catch (e: CancellationException) {
            throw e
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

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("❌ Ошибка получения деталей токена: ${e.message}")
            null
        }
    }

    // ✅ РАБОЧИЙ МЕТОД: Обновить цену токена
    suspend fun updateTokenPrice(token: TokenPair): TokenPair? {
        val pairAddress = token.pairAddress
        if (pairAddress.isNullOrEmpty()) return null

        return try {
            // Получаем обновленные данные
            val updated = getTokenDetails(pairAddress)
            updated?.let {
                println("📈 Цена обновлена: ${it.baseToken?.symbol} -> $${it.priceUsd}")
            }
            updated
        } catch (e: CancellationException) {
            throw e
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("⚠️ Ошибка при поиске '$query': ${e.message}")
            }
        }

        return searchTokens
    }

    // ✅ НОВЫЙ МЕТОД: Получение токенов через token-boosts/latest/v1 (оптимизировано с параллельными запросами)
    private suspend fun getLatestTokenBoosts(settings: FilterSettings): List<TokenPair> {
        val url = "https://api.dexscreener.com/token-boosts/latest/v1"
        val jsonElement = getJsonElementWithRetry(url) ?: return emptyList()
        
        // Парсим массив токенов из ответа
        val boosts = parseTokenBoostsFromJson(jsonElement)
        
        // Фильтруем только Solana токены
        val solanaTokens = boosts.filter { it.chainId == "solana" && !it.tokenAddress.isNullOrBlank() }
        
        if (solanaTokens.isEmpty()) return emptyList()

        // ✅ ОПТИМИЗАЦИЯ: Параллельные запросы для получения деталей токенов
        return coroutineScope {
            solanaTokens.map { boost ->
                async {
                    val tokenAddress = boost.tokenAddress ?: return@async emptyList<TokenPair>()
                    try {
                        val tokenPairs = getTokenPairsForAddress("solana", tokenAddress)
                        delay(minRequestDelayMs) // Задержка для rate limiting
                        tokenPairs
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("⚠️ Ошибка получения пар для $tokenAddress: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
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

        // ✅ ОПТИМИЗАЦИЯ: Параллельные запросы для получения деталей токенов
        return coroutineScope {
            recentTokens.map { (chainId, tokenAddress) ->
                async {
                    try {
                        val tokenPairs = getTokenPairsForAddress(chainId, tokenAddress)
                        delay(minRequestDelayMs) // Задержка для rate limiting
                        tokenPairs
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("⚠️ Ошибка получения пар для $chainId/$tokenAddress: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                val isNetworkError = errorMessage.contains("Unable to resolve", ignoreCase = true) ||
                        errorMessage.contains("No address associated", ignoreCase = true) ||
                        errorMessage.contains("Network", ignoreCase = true) ||
                        errorMessage.contains("timeout", ignoreCase = true)
                
                if (isNetworkError) {
                    println("🌐 Сетевая ошибка (попытка ${attempt + 1}/$maxRetries): $errorMessage")
                } else {
                    println("⚠️ Ошибка запроса (попытка ${attempt + 1}/$maxRetries): $errorMessage")
                }
                
                attempt++
                if (attempt < maxRetries) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                } else {
                    println("❌ Все попытки исчерпаны для $url")
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
    
    private fun parseTokenBoostsFromJson(jsonElement: JsonElement): List<TokenBoost> {
        val array = when (jsonElement) {
            is JsonArray -> jsonElement
            is JsonObject -> jsonElement["data"]?.jsonArray ?: jsonElement["boosts"]?.jsonArray ?: jsonElement["tokens"]?.jsonArray
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<TokenBoost>(element) }.getOrNull()
        }
    }
}
