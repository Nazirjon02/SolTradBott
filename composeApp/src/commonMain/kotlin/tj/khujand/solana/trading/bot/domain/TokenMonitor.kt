package tj.khujand.solana.trading.bot.domain

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import tj.khujand.solana.trading.bot.crypto.Signer
import tj.khujand.solana.trading.bot.crypto.createSignerFromSeedPhrase
import tj.khujand.solana.trading.bot.crypto.signTransactionBase64
import tj.khujand.solana.trading.bot.network.*
import tj.khujand.solana.trading.bot.util.AppSettings
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import tj.khujand.solana.trading.bot.util.formatNumber
import kotlin.math.absoluteValue

// ════════════════════════════════════════════════════════════════════════════════
// ТОКЕН В МОНИТОРИНГЕ (активная позиция)
// ════════════════════════════════════════════════════════════════════════════════
@Serializable
data class MonitoredToken(
    // ──── ОСНОВНАЯ ИНФОРМАЦИЯ ────
    val tokenPair: TokenPair,                      // Пара с DexScreener (адрес, цена, капа, ликвидность)
    val foundTime: Long = Clock.System.now().toEpochMilliseconds(),  // Время обнаружения токена
    val ageToken: String = "0.0",                  // Возраст токена (для отображения)
    
    // ──── ВХОД В ПОЗИЦИЮ ────
    val entryPrice: Double = 0.0,                  // ⭐ Цена входа (USD)
    var currentPrice: String = "0.0",              // Текущая цена (обновляется каждый тик)
    var priceChangePercent: Double = 0.0,          // ⭐ Изменение цены в % от входа
    var profitUsd: Double = 0.0,                   // ⭐ Текущая прибыль/убыток USD (может быть отриц.)
    var status: TokenStatus = TokenStatus.MONITORING,  // Статус: MONITORING / STOPPED_TP / STOPPED_SL
    
    // ──── ОТСЛЕЖИВАНИЕ МАКСИМУМОВ (для trailing) ────
    var sessionHighPrice: Double = 0.0,            // ⭐ Максимальная цена с момента входа (для trailing по цене)
    var entryMarketCap: Double = 0.0,              // Market Cap на момент входа
    var peakMarketCap: Double = 0.0,               // ⭐ Максимальная Market Cap (для trailing по капе)
    var lastMarketCap: Double = 0.0,               // Последняя известная Market Cap
    
    // ──── ИНВЕСТИЦИИ И КОЛИЧЕСТВО ────
    var investedUsd: Double = DemoAccountManager.DEMO_TRADE_AMOUNT,  // Инвестировано USD (demo=$100, real=настройка)
    var tokenAmountRaw: Long = 0L,                 // ⭐ Количество токенов (raw, без decimals) - для реальных свапов
    var buyTxId: String = "",                      // ID транзакции покупки (если Jupiter)
    var buySolLamports: Long = 0L,                 // Сколько SOL потрачено на покупку (lamports)
    
    // ──── ЧАСТИЧНЫЕ ПРОДАЖИ (Stages / Aggressive) ────
    var realizedProfitUsd: Double = 0.0,           // ⭐ Реализованная прибыль USD (сумма всех частичных выходов)
    var remainingPositionPct: Double = 100.0,      // ⭐ Оставшаяся позиция в % (100% → 70% → 40% → и т.д.)
    var exitStage1Done: Boolean = false,           // Флаг: Stage 1 выполнен
    var exitStage2Done: Boolean = false,           // Флаг: Stage 2 выполнен
    var exitStage3Done: Boolean = false,           // Флаг: Stage 3 выполнен
    var exitStage4Done: Boolean = false,           // Флаг: Stage 4 выполнен (или Aggressive фиксация)
    
    // ──── ДЕМО vs РЕАЛЬНЫЕ СДЕЛКИ ────
    var demoBuyApplied: Boolean = false,           // ⭐ true = демо-покупка (виртуальная), false = реальная через Jupiter
    /** Последняя ошибка продажи Jupiter (пока позиция в мониторинге и выход не прошёл) */
    var jupiterSellLastError: String = "",
    var jupiterSellLastErrorAtMs: Long = 0L
)

// ════════════════════════════════════════════════════════════════════════════════
// СТАТУС ТОКЕНА
// ════════════════════════════════════════════════════════════════════════════════
@Serializable
enum class TokenStatus {
    MONITORING,    // ⭐ В активном мониторинге (позиция открыта)
    STOPPED_TP,    // ⭐ Закрыт по тейк-профиту (завершён успешно)
    STOPPED_SL     // ⭐ Закрыт по стоп-лоссу (убыток или защитный выход)
}

// ════════════════════════════════════════════════════════════════════════════════
// МОНИТОР ТОКЕНОВ - центральный класс для управления позициями
// ════════════════════════════════════════════════════════════════════════════════
class TokenMonitor {
    private  val CACHE_KEY_TOKENS = "cached_monitored_tokens"
    private val CLOSED_TOKENS_KEY = "closed_tokens_v1"

    private var allowNewTokenDiscovery = true  // ⭐ Флаг: разрешён ли поиск новых токенов

    private val api = DexScreenerApi()
    private val jupiterApi = JupiterApi()
    private var aiAnalyzer: AiAnalyzer? = null  // ⭐ AI-анализатор (создаётся при необходимости)
    private var monitorJob: Job? = null
    // @Volatile — изменение видно всем потокам немедленно (loop-check + cancel из другого потока)
    @Volatile private var isMonitoring = false
    private var monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateSemaphore = Semaphore(FilterSettings().maxParallelUpdates.coerceAtLeast(1))
    private val listMutex = Mutex()
    private val closedTokenAddresses = mutableSetOf<String>()
    private var rpcClient: SolanaRpcClient? = null
    private var cachedSigner: Signer? = null
    private var signerFingerprint: String = ""
    private var rpcFingerprint: String = ""
    private var consecutiveLosses: Int = 0
    private var dailyRealizedPnlUsd: Double = 0.0
    private val tokenUpdateFailureCount = mutableMapOf<String, Int>()
    private val MAX_UPDATE_FAILURES = 3
    private var dailyPnlEpochDay: String = currentUtcDateId()
    private var peakTrackedEquityUsd: Double = 0.0

    // ─── Circuit breaker ──────────────────────────────────────────────────────
    private var apiErrorStreak = 0
    private val CIRCUIT_BREAKER_THRESHOLD = 5
    private val CIRCUIT_BREAKER_PAUSE_MS = 5 * 60 * 1_000L
    private var circuitBreakerOpenUntilMs = 0L
    private var cooldownUntilEpochMs: Long = 0L
    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    // Список отслеживаемых токенов
    private val _monitoredTokens = mutableStateListOf<MonitoredToken>()
    val monitoredTokens: List<MonitoredToken> get() = _monitoredTokens

    // Callbacks
    private var onTokenClosedCallback: (MonitoredToken) -> Unit = {}

    // Настройки по умолчанию
    var filterSettings = FilterSettings()
        set(value) {
            field = value
            refreshCachedDependencies()
            updateSemaphore = Semaphore(value.maxParallelUpdates.coerceAtLeast(1))
            // Пересоздаём AI analyzer при изменении настроек
            if (value.useAiAnalysis && value.aiApiKey.isNotBlank()) {
                aiAnalyzer = AiAnalyzer(value)
            } else {
                aiAnalyzer?.close()
                aiAnalyzer = null
            }
        }

    init {
        restoreClosedTokens()
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 🔄 ЗАПУСК АВТОМАТИЧЕСКОГО МОНИТОРИНГА
    // ════════════════════════════════════════════════════════════════════════════════
    // Цикл мониторинга выполняется каждые N секунд (по умолчанию 30 сек):
    // 1. Поиск новых токенов через DexScreener (если есть свободные слоты)
    // 2. Обновление цен для всех активных позиций
    // 3. Проверка условий выхода (Stage/Aggressive/StopLoss/Trailing)
    // 4. Автоматические покупки/продажи (если Jupiter включён)
    // ════════════════════════════════════════════════════════════════════════════════
    fun startMonitoring(
        intervalSeconds: Int = 30,                 // ⭐ Интервал обновления в секундах (30 сек по умолчанию)
        onNewTokenFound: (MonitoredToken) -> Unit = {},   // Callback: новый токен найден и добавлен
        onTokenUpdated: (MonitoredToken) -> Unit = {},    // Callback: токен обновлён (цена/прибыль изменились)
        onTokenClosed: (MonitoredToken) -> Unit = {},     // Callback: токен закрыт (TP или SL)
        onRequestStateChanged: (Boolean) -> Unit = {},    // Callback: статус запроса к API (true=загрузка)
        onError: (String) -> Unit = {}             // Callback: произошла ошибка
    ) {
        if (isMonitoring) {
            println("⚠️ Мониторинг уже запущен")
            return
        }

        isMonitoring = true
        allowNewTokenDiscovery = true
        onTokenClosedCallback = onTokenClosed
        println("🚀 Запуск мониторинга с фильтрами:")
        println("   - Возраст токена: <= ${filterSettings.entryMaxAgeMinutes} мин")
        println("   - Market Cap: ${filterSettings.entryMinMarketCap.toInt()} - ${filterSettings.entryMaxMarketCap.toInt()} USD")
        println("   - Ликвидность: >= ${filterSettings.entryMinLiquidity.toInt()} USD")
        println("   - Объем 24ч: >= ${filterSettings.entryMinVolume.toInt()} USD")
        println("   - Соц. сети и сайт: обязательны")

        monitorScope.cancel()
        monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startSniperIfEnabled(onNewTokenFound)
        monitorJob = monitorScope.launch {
            var cycleCount = 0
            while (isMonitoring) {
                var requestedThisCycle = false
                try {
                    cycleCount++

                    // ── Circuit breaker check ──────────────────────────────────
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    if (circuitBreakerOpenUntilMs > nowMs) {
                        val remainSec = (circuitBreakerOpenUntilMs - nowMs) / 1000
                        println("⛔ Circuit breaker открыт, ждём ${remainSec}с...")
                        delay(10_000)
                        continue
                    }
                    // 🕐 Проверяем торговые часы: если не в активном окне — поиск новых токенов запрещён
                    val withinTradingHours = isWithinTradingHours()
                    if (filterSettings.tradingHoursEnabled && !withinTradingHours) {
                        val hourNow = ((Clock.System.now().toEpochMilliseconds() / 3_600_000L) % 24).toInt()
                        println("🕐 Trading hours: текущий час UTC=$hourNow вне окна [${filterSettings.tradingHoursStartUtcHour}:00–${filterSettings.tradingHoursEndUtcHour}:00], поиск новых токенов пропущен")
                    }

                    // Поиск новых токенов — раз в 5 циклов (чтобы цены обновлялись чаще)
                    val runPhase1 = withinTradingHours && allowNewTokenDiscovery && (
                        cycleCount == 1 ||
                        _monitoredTokens.isEmpty() ||
                        cycleCount % filterSettings.discoveryEveryNCycles.coerceAtLeast(1) == 0
                    )
                    if (runPhase1) {
                        println("🔄 Цикл мониторинга (поиск новых + цены)...")
                    }

                    // ✅ ФАЗА 1: Поиск НОВЫХ токенов (реже, чтобы не тормозить обновление цен)
                    if (runPhase1 && allowNewTokenDiscovery) {
                        if (!requestedThisCycle) {
                            onRequestStateChanged(true)
                            requestedThisCycle = true
                        }
                        println("📡 Запрос новых токенов...")
                        try {
                            val newTokens = api.getNewTokens(filterSettings)
                            println("📊 Получено токенов: ${newTokens.size}")

                            if (newTokens.isEmpty()) {
                                println("⚠️ Нет новых токенов, проверьте фильтры или подключение к интернету")
                                // Не вызываем onError для пустого результата - это нормально
                            }

                            // Фильтрация по условиям входа
                            val filteredTokens = filterTokensByEntryRules(newTokens)
                            println("✅ После фильтрации: ${filteredTokens.size}")

                            // 🔴 КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: проверяем лимит ПЕРЕД добавлением
                            var addedCount = 0
                        for (token in filteredTokens) {
                            val pairAddress = token.pairAddress
                            if (pairAddress.isNullOrBlank()) {
                                println("⚠️ Пропуск токена: нет pairAddress (${token.baseToken?.symbol})")
                                continue
                            }
                            val tokenKey = token.baseToken?.address ?: pairAddress

                            // 1. Проверяем дубликаты
                            val exists = _monitoredTokens.any {
                                it.tokenPair.pairAddress == pairAddress
                            }

                            // 2. Не добавляем токен повторно после TP/SL
                            if (closedTokenAddresses.contains(tokenKey)) {
                                println("⚠️ Пропуск токена ${token.baseToken?.symbol}: уже закрыт ранее")
                                continue
                            }

                            if (!exists) {
                                // 3. Проверяем лимит токенов
                                if (monitoringCount() >= filterSettings.maxTokensToMonitor) {
                                    println("⏸️ Достигнут лимит ${filterSettings.maxTokensToMonitor} токенов. Поиск приостановлен.")
                                    allowNewTokenDiscovery = false
                                    break  // 🛑 Прерываем добавление новых
                                }
                                if (!canOpenNewPosition()) {
                                    println("🛡️ Пропуск ${token.baseToken?.symbol}: сработали портфельные risk-limits")
                                    continue
                                }

                                // 4. 🤖 AI-АНАЛИЗ ТОКЕНА (если включён)
                                if (filterSettings.useAiAnalysis && aiAnalyzer != null) {
                                    try {
                                        val ageMs = token.pairCreatedAt?.let { 
                                            Clock.System.now().toEpochMilliseconds() - if (it < 1_000_000_000_000L) it * 1000 else it
                                        } ?: 0L
                                        val ageMinutes = (ageMs / (1000.0 * 60.0)).toLong()
                                        
                                        println("🤖 AI-анализ токена ${token.baseToken?.symbol}...")
                                        val aiResult = aiAnalyzer!!.analyzeToken(token, ageMinutes)
                                        
                                        if (aiResult != null) {
                                            println("═══════════════════════════════════════════════════════════")
                                            println("🤖 AI ANALYSIS RESULT: ${token.baseToken?.symbol}")
                                            println("═══════════════════════════════════════════════════════════")
                                            println("📊 SCORE: ${aiResult.score}/100")
                                            println("🚨 RUG_RISK: ${aiResult.rugRisk}")
                                            println("📈 MOMENTUM_PHASE: ${aiResult.momentumPhase}")
                                            println("🎯 ENTRY_SIGNAL: ${aiResult.entrySignal}")
                                            println("🎲 CONFIDENCE: ${formatNumber(aiResult.confidence)}")
                                            println("❌ RED_FLAGS: ${aiResult.redFlags}")
                                            println("✅ GREEN_FLAGS: ${aiResult.greenFlags}")
                                            println("💡 REASON: ${aiResult.reason}")
                                            println("⏰ OPTIMAL_ENTRY: ${aiResult.optimalEntryCap}")
                                            println("🎯 PREDICTED_PEAK: ${aiResult.predictedPeakCap}")
                                            println("═══════════════════════════════════════════════════════════")
                                            println("📜 RAW AI RESPONSE:")
                                            println(aiResult.rawResponse)
                                            println("═══════════════════════════════════════════════════════════")
                                            
                                            // Проверка фильтров AI
                                            val rugRiskOk = when (aiResult.rugRisk) {
                                                "LOW" -> true
                                                "MEDIUM" -> filterSettings.maxAiRugRisk != "LOW"
                                                "HIGH" -> filterSettings.maxAiRugRisk == "HIGH"
                                                "CRITICAL" -> false
                                                else -> true
                                            }
                                            
                                            val scoreOk = aiResult.score >= filterSettings.minAiScore
                                            val signalOk = aiResult.entrySignal !in listOf("AVOID", "STRONG_AVOID")
                                            
                                            if (!rugRiskOk) {
                                                println("❌ AI: отклонён по RUG_RISK (${aiResult.rugRisk} > ${filterSettings.maxAiRugRisk})")
                                                continue
                                            }
                                            if (!scoreOk) {
                                                println("❌ AI: отклонён по SCORE (${aiResult.score} < ${filterSettings.minAiScore})")
                                                continue
                                            }
                                            if (!signalOk) {
                                                println("❌ AI: отклонён по SIGNAL (${aiResult.entrySignal})")
                                                continue
                                            }
                                            
                                            println("✅ AI: токен прошёл анализ!")
                                        } else {
                                            if (filterSettings.aiFailClosed) {
                                                println("🛑 AI fail-closed: анализ не удался, токен отклонен")
                                                continue
                                            }
                                            println("⚠️ AI: анализ не удался, пропускаем AI-фильтр")
                                        }
                                    } catch (e: Exception) {
                                        if (filterSettings.aiFailClosed) {
                                            println("🛑 AI fail-closed: ошибка анализа (${e.message}), токен отклонен")
                                            continue
                                        }
                                        println("⚠️ AI анализ ошибка: ${e.message}, пропускаем AI-фильтр")
                                    }
                                }

                                if (!passesOnChainAuthorityChecks(token)) {
                                    println("🛑 On-chain checks failed: ${token.baseToken?.symbol}")
                                    continue
                                }

                                // 5а. Rug-check (опционально)
                                if (filterSettings.rugCheckEnabled) {
                                    val mintAddr = token.baseToken?.address
                                    if (mintAddr != null) {
                                        val rugResult = RugCheckApi.check(
                                            mintAddress    = mintAddr,
                                            maxScoreAllowed = filterSettings.rugCheckMaxScore,
                                            failClosed     = filterSettings.rugCheckFailClosed,
                                        )
                                        if (!rugResult.passed) {
                                            println("🛑 RugCheck FAIL ${token.baseToken?.symbol}: score=${rugResult.score} risks=${rugResult.topRisks}")
                                            continue
                                        }
                                        if (rugResult.level == tj.khujand.solana.trading.bot.network.RugRiskLevel.WARN) {
                                            println("⚠️ RugCheck WARN ${token.baseToken?.symbol}: score=${rugResult.score} risks=${rugResult.topRisks}")
                                        }
                                    }
                                }

                                // 5б. Добавляем токен
                                val price = parsePrice(token.priceUsd)
                                if (price > 0) {
                                    val entryCap = token.marketCap ?: 0.0
                                    val buyResult = if (filterSettings.jupiterEnabled) {
                                        buyTokenIfEnabled(token)
                                    } else null
                                    if (filterSettings.jupiterEnabled && buyResult == null) {
                                        println("⚠️ Покупка через Jupiter не удалась: ${token.baseToken?.symbol}")
                                        continue
                                    }
                                    val monitoredToken = MonitoredToken(
                                        tokenPair = token,
                                        entryPrice = price,
                                        currentPrice = token.priceUsd.toString(),
                                        ageToken = token.pairCreatedAt.toString(),
                                        sessionHighPrice = price,
                                        entryMarketCap = entryCap,
                                        peakMarketCap = entryCap,
                                        lastMarketCap = token.marketCap ?: 0.0,
                                        investedUsd = if (filterSettings.jupiterEnabled) {
                                            filterSettings.tradeUsdAmount
                                        } else {
                                            DemoAccountManager.DEMO_TRADE_AMOUNT
                                        },
                                        tokenAmountRaw = buyResult?.outAmountRaw ?: 0L,
                                        buyTxId = buyResult?.txId ?: "",
                                        buySolLamports = buyResult?.inLamports ?: 0L,
                                        remainingPositionPct = 100.0,
                                        demoBuyApplied = !filterSettings.jupiterEnabled
                                    )

                                    if (!filterSettings.jupiterEnabled) {
                                        DemoAccountManager.applyDemoBuy()
                                    }

                                    _monitoredTokens.add(monitoredToken)
                                    onNewTokenFound(monitoredToken)
                                    addedCount++
                                    println("➕ Добавлен: ${token.baseToken?.symbol ?: "Unknown"} ($${price}) [${_monitoredTokens.size}/${filterSettings.maxTokensToMonitor}]")
                                } else {
                                    println("⚠️ Пропуск токена ${token.baseToken?.symbol}: некорректная цена (${token.priceUsd})")
                                }
                            } else {
                                println("⚠️ Пропуск токена ${token.baseToken?.symbol}: уже в мониторинге")
                            }
                        }

                            if (addedCount > 0) {
                                println("🎯 Добавлено новых токенов: $addedCount")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val errorMsg = e.message ?: "Unknown error"
                            val isNetworkError = errorMsg.contains("Unable to resolve", ignoreCase = true) ||
                                    errorMsg.contains("No address associated", ignoreCase = true) ||
                                    errorMsg.contains("Network", ignoreCase = true)
                            
                            if (isNetworkError) {
                                println("🌐 Проблема с сетью при поиске токенов: $errorMsg")
                                onError("Проблема с подключением к интернету. Проверьте соединение.")
                            } else {
                                println("⚠️ Ошибка при поиске токенов: $errorMsg")
                                onError("Ошибка поиска токенов: $errorMsg")
                            }
                        }
                    } else if (!allowNewTokenDiscovery) {
                        println("⏸️ Поиск новых токенов приостановлен (лимит: ${filterSettings.maxTokensToMonitor})")
                    }

                    // ✅ ФАЗА 2: Обновление цен ВСЕХ токенов — каждый цикл (чаще обновление цен)
                    if (_monitoredTokens.isNotEmpty()) {
                        if (!requestedThisCycle) {
                            onRequestStateChanged(true)
                            requestedThisCycle = true
                        }
                        if (!runPhase1) println("🔄 Цикл мониторинга (только цены)...")
                        updateMonitoredTokensParallel(onTokenUpdated)
                    }

                    refreshDiscoveryFlag()

                    // Успешный цикл — сбрасываем streak
                    apiErrorStreak = 0

                    // Ожидание перед следующим циклом
                    println("⏳ Ожидание $intervalSeconds сек...")
                    delay(intervalSeconds * 1000L)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Unknown error"
                    val isNetworkError = errorMsg.contains("Unable to resolve", ignoreCase = true) ||
                            errorMsg.contains("No address associated", ignoreCase = true) ||
                            errorMsg.contains("Network", ignoreCase = true)

                    apiErrorStreak++
                    if (apiErrorStreak >= CIRCUIT_BREAKER_THRESHOLD) {
                        circuitBreakerOpenUntilMs = Clock.System.now().toEpochMilliseconds() + CIRCUIT_BREAKER_PAUSE_MS
                        apiErrorStreak = 0
                        println("⛔ Circuit breaker: $CIRCUIT_BREAKER_THRESHOLD ошибок подряд → пауза 5 мин")
                        onError("Circuit breaker: слишком много ошибок API, пауза 5 мин")
                    }

                    if (isNetworkError) {
                        println("🌐 Сетевая ошибка в цикле мониторинга: $errorMsg")
                        onError("Проблема с подключением к интернету. Проверьте соединение.")
                    } else {
                        println("❌ Ошибка мониторинга: $errorMsg")
                        onError("Ошибка мониторинга: $errorMsg")
                    }

                    // Увеличиваем задержку при сетевых ошибках
                    delay(if (isNetworkError) 30000 else 15000)
                } finally {
                    if (requestedThisCycle) {
                        onRequestStateChanged(false)
                    }
                }
            }
            println("⏹️ Цикл мониторинга завершен")
        }
        monitorJob?.invokeOnCompletion {
            isMonitoring = false
            runCatching { onRequestStateChanged(false) }
        }
    }

    // 🔴 НОВЫЙ МЕТОД: параллельное обновление токенов
    private suspend fun updateMonitoredTokensParallel(onUpdate: (MonitoredToken) -> Unit) {
        println("📈 Параллельное обновление цен для ${_monitoredTokens.size} токенов...")

        coroutineScope {
            // Создаем копию списка для безопасной итерации (на случай удаления токенов)
            val tokensToUpdate = _monitoredTokens.filter { it.status == TokenStatus.MONITORING }
            
            val updateJobs = tokensToUpdate.map { monitoredToken ->
                async {
                    updateSemaphore.withPermit {
                        val pairKey = monitoredToken.tokenPair.pairAddress
                            ?: monitoredToken.tokenPair.baseToken?.address
                            ?: return@withPermit

                        try {
                            // Проверяем, что токен все еще в списке
                            val currentIndex = _monitoredTokens.indexOfFirst { 
                                it.tokenPair.pairAddress == monitoredToken.tokenPair.pairAddress 
                            }
                            if (currentIndex == -1) {
                                println("⚠️ Токен ${monitoredToken.tokenPair.baseToken?.symbol} уже удален, пропускаем обновление")
                                return@withPermit
                            }
                            
                            val updatedPair = api.updateTokenPrice(monitoredToken.tokenPair)
                            if (updatedPair != null) {
                                val newPrice = parsePrice(updatedPair.priceUsd)
                                if (newPrice > 0) {
                                    tokenUpdateFailureCount.remove(pairKey)
                                    updateTokenPrice(monitoredToken, updatedPair, newPrice, onUpdate)
                                    println("✅ ${monitoredToken.tokenPair.baseToken?.symbol}: цена обновлена до $${newPrice}")
                                } else {
                                    handleUpdateFailure(monitoredToken, pairKey, "некорректная цена (${updatedPair.priceUsd})", onUpdate)
                                }
                            } else {
                                handleUpdateFailure(monitoredToken, pairKey, "API вернул null", onUpdate)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            handleUpdateFailure(monitoredToken, pairKey, e.message ?: "unknown error", onUpdate)
                        } finally {
                            delay(filterSettings.updateDelayMs.toLong().coerceAtLeast(0L))
                        }
                    }
                }
            }

            updateJobs.awaitAll()
        }
    }

    private suspend fun handleUpdateFailure(
        token: MonitoredToken,
        key: String,
        reason: String,
        onUpdate: (MonitoredToken) -> Unit
    ) {
        val failures = (tokenUpdateFailureCount[key] ?: 0) + 1
        tokenUpdateFailureCount[key] = failures
        println("⚠️ ${token.tokenPair.baseToken?.symbol} ошибка обновления ($reason), попытка $failures/$MAX_UPDATE_FAILURES")
        if (failures >= MAX_UPDATE_FAILURES) {
            println("🛑 ${token.tokenPair.baseToken?.symbol}: превышен лимит ошибок, принудительное закрытие как SL")
            tokenUpdateFailureCount.remove(key)
            forceCloseOnUpdateFailure(token, onUpdate)
        }
    }

    private suspend fun forceCloseOnUpdateFailure(
        token: MonitoredToken,
        onUpdate: (MonitoredToken) -> Unit
    ) {
        var tokenAmountRaw = token.tokenAmountRaw
        var realizedProfitUsd = token.realizedProfitUsd

        // Пытаемся продать через Jupiter ДО захвата мьютекса
        if (filterSettings.jupiterEnabled && tokenAmountRaw > 0L) {
            val pct = token.remainingPositionPct.coerceIn(0.0, 100.0)
            val outcome = try {
                sellTokenPercent(token, tokenAmountRaw, pct, token.lastMarketCap)
            } catch (e: Exception) {
                JupiterSellOutcome(null, e.message ?: "Ошибка продажи")
            }
            val sellResult = outcome.result
            if (sellResult != null) {
                realizedProfitUsd += sellResult.profitUsd
                tokenAmountRaw = 0L
                println("✅ Jupiter sell выполнен при принудительном закрытии ${token.tokenPair.baseToken?.symbol}")
            } else {
                println("⚠️ Jupiter sell не удался при принудительном закрытии ${token.tokenPair.baseToken?.symbol}: ${outcome.failureReason ?: "—"}, закрываем без продажи")
            }
        }

        // Для демо-режима: пересчитываем P&L по последней известной цене,
        // чтобы закрытый токен не показывал 0$ вместо реального убытка
        if (!filterSettings.jupiterEnabled) {
            val entry = token.entryPrice
            val lastPrice = parsePrice(token.currentPrice)
            if (entry > 0 && lastPrice > 0) {
                val priceChangePct = ((lastPrice - entry) / entry) * 100.0
                val investment = token.investedUsd.coerceAtLeast(0.0)
                val remainingLoss = investment * priceChangePct / 100.0 * (token.remainingPositionPct.coerceIn(0.0, 100.0) / 100.0)
                realizedProfitUsd += remainingLoss
            }
        }

        listMutex.withLock {
            val index = _monitoredTokens.indexOfFirst {
                it.tokenPair.pairAddress == token.tokenPair.pairAddress
            }
            if (index == -1) return@withLock

            val finalProfitUsd = realizedProfitUsd
            val closedToken = token.copy(
                status = TokenStatus.STOPPED_SL,
                remainingPositionPct = 0.0,
                tokenAmountRaw = tokenAmountRaw,
                realizedProfitUsd = realizedProfitUsd,
                profitUsd = finalProfitUsd
            )
            addClosedToken(closedToken)
            _monitoredTokens.removeAt(index)
            checkAndResumeDiscovery()
            saveTokensToCache()
            onUpdate(closedToken)
            println("🗑️ Токен ${token.tokenPair.baseToken?.symbol} принудительно закрыт (SL) из-за ошибок обновления")
        }
    }

    // 🔴 ДОБАВИТЬ: метод для возобновления поиска (например, при удалении токена)
    fun checkAndResumeDiscovery() {
        if (monitoringCount() < filterSettings.maxTokensToMonitor) {
            allowNewTokenDiscovery = true
            println("🔍 Поиск новых токенов возобновлен [${_monitoredTokens.size}/${filterSettings.maxTokensToMonitor}]")
        }
    }

    // 🔴 ОБНОВИТЬ: метод удаления токена
    fun removeToken(pairAddress: String) {
        val removed = _monitoredTokens.removeAll { it.tokenPair.pairAddress == pairAddress }
        if (removed) {
            checkAndResumeDiscovery()  // 🔴 Проверяем, можно ли возобновить поиск
        }
    }
    
    /** Индекс позиции по адресу пары или mint базового токена (pairAddress иногда пустой в данных API). */
    private fun monitoredIndexByPairOrBase(address: String): Int {
        if (address.isBlank()) return -1
        return _monitoredTokens.indexOfFirst { t ->
            val p = t.tokenPair.pairAddress
            val b = t.tokenPair.baseToken?.address
            (!p.isNullOrBlank() && p == address) || (!b.isNullOrBlank() && b == address)
        }
    }

    // ✅ Ручное закрытие токена (фиксация прибыли/убытка) — всегда пишет историю при успехе
    suspend fun closeTokenManually(pairOrBaseAddress: String, isProfit: Boolean = true) {
        if (pairOrBaseAddress.isBlank()) {
            println("⚠️ Ручное закрытие: пустой адрес пары/токена")
            return
        }

        val token = listMutex.withLock {
            val index = monitoredIndexByPairOrBase(pairOrBaseAddress)
            if (index == -1) {
                println("⚠️ Ручное закрытие: позиция не найдена ($pairOrBaseAddress)")
                return@withLock null
            }
            val t = _monitoredTokens[index]
            if (t.status != TokenStatus.MONITORING) {
                println("⚠️ Ручное закрытие: токен уже не в мониторинге")
                return@withLock null
            }
            t
        } ?: return

        var realizedProfitUsd = token.realizedProfitUsd
        val finalProfitUsd: Double

        if (filterSettings.jupiterEnabled && token.tokenAmountRaw > 0L) {
            val percentToSell = token.remainingPositionPct.coerceIn(0.0, 100.0)
            val outcome = try {
                sellTokenPercent(
                    token = token,
                    amountBase = token.tokenAmountRaw,
                    percent = percentToSell,
                    marketCap = token.lastMarketCap
                )
            } catch (e: Exception) {
                JupiterSellOutcome(null, e.message ?: "Ошибка продажи")
            }
            val sellResult = outcome.result
            if (sellResult == null) {
                val reason = outcome.failureReason ?: "Продажа Jupiter не выполнена"
                println("⚠️ Ручное закрытие: $reason, токен остается в мониторинге")
                listMutex.withLock {
                    val ix = monitoredIndexByPairOrBase(pairOrBaseAddress)
                    if (ix != -1) {
                        val now = Clock.System.now().toEpochMilliseconds()
                        _monitoredTokens[ix] = _monitoredTokens[ix].copy(
                            jupiterSellLastError = reason,
                            jupiterSellLastErrorAtMs = now
                        )
                    }
                }
                saveTokensToCache()
                return
            }
            realizedProfitUsd += sellResult.profitUsd
            finalProfitUsd = realizedProfitUsd
        } else if (filterSettings.jupiterEnabled) {
            // Реал без остатка raw (редко) — берём накопленный P&L из последнего тика
            finalProfitUsd = token.profitUsd
            realizedProfitUsd = token.realizedProfitUsd
        } else {
            // Демо: как в updateTokenPrice — реализованное + нереализованное на остаток позиции
            val entry = if (token.entryPrice > 0) token.entryPrice else parsePrice(token.currentPrice)
            val newPrice = parsePrice(token.currentPrice)
            val priceChangePercent =
                if (entry > 0) ((newPrice - entry) / entry) * 100 else 0.0
            val investment = token.investedUsd.coerceAtLeast(0.0)
            val remainingPct = token.remainingPositionPct.coerceIn(0.0, 100.0)
            val unrealizedOnRemain =
                investment * priceChangePercent / 100.0 * (remainingPct / 100.0)
            realizedProfitUsd = token.realizedProfitUsd + unrealizedOnRemain
            finalProfitUsd = realizedProfitUsd
        }

        val newStatus = if (isProfit || finalProfitUsd >= 0) {
            TokenStatus.STOPPED_TP
        } else {
            TokenStatus.STOPPED_SL
        }

        val exitPct = if (token.entryPrice > 0) {
            val np = parsePrice(token.currentPrice)
            ((np - token.entryPrice) / token.entryPrice) * 100
        } else {
            token.priceChangePercent
        }

        val updatedToken = token.copy(
            status = newStatus,
            remainingPositionPct = 0.0,
            tokenAmountRaw = 0L,
            profitUsd = finalProfitUsd,
            realizedProfitUsd = realizedProfitUsd,
            priceChangePercent = exitPct
        )

        listMutex.withLock {
            val index = monitoredIndexByPairOrBase(pairOrBaseAddress)
            if (index == -1) {
                println("⚠️ Ручное закрытие: токен исчез до записи результата — история не сохранена")
                return@withLock
            }
            val current = _monitoredTokens[index]
            val same =
                current.foundTime == token.foundTime &&
                    (current.tokenPair.pairAddress ?: "") == (token.tokenPair.pairAddress ?: "") &&
                    (current.tokenPair.baseToken?.address ?: "") == (token.tokenPair.baseToken?.address ?: "")
            if (!same || current.status != TokenStatus.MONITORING) {
                println("⚠️ Ручное закрытие: позиция изменилась параллельно, пропуск")
                return@withLock
            }

            addClosedToken(updatedToken)
            _monitoredTokens.removeAt(index)
            checkAndResumeDiscovery()
            saveTokensToCache()
            println("✅ Токен ${token.tokenPair.baseToken?.symbol} закрыт вручную: ${if (newStatus == TokenStatus.STOPPED_TP) "TP" else "SL"}, P&L \$${formatNumber(finalProfitUsd)}")
        }
    }

    // 🔴 ОБНОВИТЬ: метод очистки всех токенов (закрывает все сделки перед удалением)
    suspend fun clearAllTokens(): Int {
        println("🗑️ Очистка всех токенов...")
        
        // 1. Закрываем все активные токены (сохраняем в историю P&L)
        val activeTokens = _monitoredTokens.filter { it.status == TokenStatus.MONITORING }
        val tokensToKeep = mutableListOf<MonitoredToken>()
        activeTokens.forEach { token ->
            if (filterSettings.jupiterEnabled && token.tokenAmountRaw > 0L) {
                val percentToSell = token.remainingPositionPct.coerceIn(0.0, 100.0)
                val outcome = try {
                    sellTokenPercent(
                        token = token,
                        amountBase = token.tokenAmountRaw,
                        percent = percentToSell,
                        marketCap = token.lastMarketCap
                    )
                } catch (e: Exception) {
                    JupiterSellOutcome(null, e.message ?: "Ошибка продажи")
                }
                val sellResult = outcome.result
                if (sellResult == null) {
                    val reason = outcome.failureReason ?: "Продажа Jupiter не выполнена"
                    println("⚠️ Очистка: $reason, токен остается в мониторинге")
                    val now = Clock.System.now().toEpochMilliseconds()
                    tokensToKeep.add(
                        token.copy(
                            jupiterSellLastError = reason,
                            jupiterSellLastErrorAtMs = now
                        )
                    )
                    return@forEach
                }
                val realized = token.realizedProfitUsd + sellResult.profitUsd
                val updatedProfitUsd = realized
                val closedToken = token.copy(
                    status = if (updatedProfitUsd >= 0) TokenStatus.STOPPED_TP else TokenStatus.STOPPED_SL,
                    remainingPositionPct = 0.0,
                    tokenAmountRaw = 0L,
                    realizedProfitUsd = realized,
                    profitUsd = updatedProfitUsd
                )
                addClosedToken(closedToken)
                println("💾 Токен ${token.tokenPair.baseToken?.symbol} закрыт и сохранен в историю")
            } else {
                val isProfit = token.profitUsd >= 0
                val closedToken = token.copy(
                    status = if (isProfit) TokenStatus.STOPPED_TP else TokenStatus.STOPPED_SL
                )
                addClosedToken(closedToken)
                println("💾 Токен ${token.tokenPair.baseToken?.symbol} закрыт и сохранен в историю")
            }
        }
        
        // 2. Очищаем список мониторинга
        val tokensCount = _monitoredTokens.size
        _monitoredTokens.clear()
        if (tokensToKeep.isNotEmpty()) {
            _monitoredTokens.addAll(tokensToKeep)
        }
        allowNewTokenDiscovery = true  // 🔴 Сбрасываем флаг
        
        // 3. Очищаем кеш мониторинга
        AppSettings.remove(CACHE_KEY_TOKENS)
        
        println("✅ Очищено:")
        println("   - Токенов в мониторинге: $tokensCount")
        println("   - Закрыто и сохранено в историю: ${activeTokens.size - tokensToKeep.size}")
        if (tokensToKeep.isNotEmpty()) {
            println("   - Остались в мониторинге (sell failed): ${tokensToKeep.size}")
        }
        println("   - Кеш мониторинга")
        println("   ⚠️ История P&L НЕ очищена (используйте Clear в экране P&L)")
        println("   ⚠️ Закрытые токены не будут добавлены снова")
        println("   ⚠️ Настройки фильтров НЕ затронуты")
        return tokensToKeep.size
    }

    // ⏹️ Остановка мониторинга
    fun stopMonitoring() {
        println("⏹️ Остановка мониторинга...")
        isMonitoring = false
        monitorScope.cancel()
        monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sniperJob = null
        monitorJob = null
        println("✅ Мониторинг остановлен")
    }

    fun isMonitoringActive(): Boolean = isMonitoring

    // ─── Sniper Mode — отдельный быстрый цикл поиска ультра-новых токенов ────
    private var sniperJob: Job? = null

    private fun startSniperIfEnabled(onNewTokenFound: (MonitoredToken) -> Unit) {
        if (!filterSettings.sniperEnabled) return
        sniperJob?.cancel()
        sniperJob = monitorScope.launch {
            println("🎯 Sniper Mode запущен (maxAge=${filterSettings.sniperMaxAgeSeconds}с)")
            while (isMonitoring) {
                try {
                    if (!allowNewTokenDiscovery) { delay(filterSettings.sniperIntervalMs); continue }
                    val maxAgeMinutes = filterSettings.sniperMaxAgeSeconds / 60.0
                    val sniperSettings = filterSettings.copy(
                        entryMaxAgeMinutes  = maxOf(1, filterSettings.sniperMaxAgeSeconds / 60),
                        entryMinLiquidity   = filterSettings.sniperMinLiquidityUsd,
                        entryMinVolume      = 0.0,
                        entryMinVolumeM5    = 0.0,
                        requireSocials      = false,
                        requireWebsite      = false,
                        discoveryEveryNCycles = 1,
                    )
                    val tokens = api.getNewTokens(sniperSettings)
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    val fresh = tokens.filter { t ->
                        val createdAt = t.pairCreatedAt ?: return@filter false
                        val ageMs = nowMs - (if (createdAt < 1_000_000_000_000L) createdAt * 1000L else createdAt)
                        ageMs in 0..(filterSettings.sniperMaxAgeSeconds * 1000L)
                    }
                    fresh.forEach { token ->
                        val alreadyMonitored = _monitoredTokens.any {
                            it.tokenPair.pairAddress == token.pairAddress
                        }
                        val alreadyClosed = closedTokenAddresses.contains(token.pairAddress)
                        val hasSlot = _monitoredTokens.size < filterSettings.maxTokensToMonitor
                        if (!alreadyMonitored && !alreadyClosed && hasSlot) {
                            val price = parsePrice(token.priceUsd)
                            if (price > 0) {
                                val monitoredToken = MonitoredToken(
                                    tokenPair            = token,
                                    entryPrice           = price,
                                    currentPrice         = token.priceUsd.toString(),
                                    sessionHighPrice     = price,
                                    entryMarketCap       = token.marketCap ?: 0.0,
                                    peakMarketCap        = token.marketCap ?: 0.0,
                                    lastMarketCap        = token.marketCap ?: 0.0,
                                    investedUsd          = DemoAccountManager.DEMO_TRADE_AMOUNT,
                                    remainingPositionPct = 100.0,
                                    demoBuyApplied       = true,
                                )
                                if (!filterSettings.jupiterEnabled) DemoAccountManager.applyDemoBuy()
                                _monitoredTokens.add(monitoredToken)
                                onNewTokenFound(monitoredToken)
                                println("🎯 Sniper: ${token.baseToken?.symbol} age=${(nowMs - (token.pairCreatedAt?.let { if (it < 1_000_000_000_000L) it * 1000L else it } ?: nowMs)) / 1000}с")
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { println("🎯 Sniper ошибка: ${e.message}") }
                delay(filterSettings.sniperIntervalMs)
            }
        }
    }

    fun manualClose(token: MonitoredToken) {
        val idx = _monitoredTokens.indexOfFirst {
            it.tokenPair.pairAddress == token.tokenPair.pairAddress
        }
        if (idx == -1) return
        val closed = _monitoredTokens[idx].copy(status = TokenStatus.STOPPED_SL)
        _monitoredTokens[idx] = closed
        addClosedToken(closed)
        _monitoredTokens.removeAt(idx)
        refreshDiscoveryFlag()
        println("🛑 Ручное закрытие: ${token.tokenPair.baseToken?.symbol}")
    }

    // 🔍 Фильтрация токенов по условиям входа
    private suspend fun filterTokensByEntryRules(tokens: List<TokenPair>): List<TokenPair> {
        return coroutineScope {
            tokens.map { token ->
                async {
                    // Базовые проверки
                    val symbol = token.baseToken?.symbol ?: ""
                    val price = token.priceUsd ?: ""
                    if (symbol.isBlank() || price.isBlank()) return@async null

                    // Проверка на скам (если включено)
                    if (filterSettings.excludeRugPull && isPotentialScam(token)) return@async null

                    // Проверка условий входа
                    if (!matchesEntryCriteria(token)) return@async null

                    println("✅ Токен ${token.baseToken?.symbol} принят по условиям входа")
                    token
                }
            }.awaitAll().filterNotNull()
        }
    }
    
    // 🔍 Старая фильтрация (для обратной совместимости)
    private fun filterTokens(tokens: List<TokenPair>): List<TokenPair> {
        return tokens.filter { token ->
            val symbol = token.baseToken?.symbol ?: ""
            val price = token.priceUsd ?: ""

            // Базовые проверки
            val hasData = symbol.isNotBlank() && price.isNotBlank()
            val volumeOk = token.volume?.h24 ?: 0.0 >= filterSettings.minVolumeUSD
            val liquidityOk = token.liquidity?.usd ?: 0.0 >= filterSettings.minLiquidityUSD

            // Проверка возраста
            val ageOk = checkTokenAge(token)

            // Проверка на скам
            val notScam = if (filterSettings.excludeRugPull) {
                !isPotentialScam(token)
            } else true

            hasData && volumeOk && liquidityOk && ageOk && notScam
        }
    }

    private fun monitoringCount(): Int {
        return _monitoredTokens.count { it.status == TokenStatus.MONITORING }
    }

    private fun currentOpenExposureUsd(): Double {
        return _monitoredTokens
            .filter { it.status == TokenStatus.MONITORING }
            .sumOf { it.investedUsd * (it.remainingPositionPct.coerceIn(0.0, 100.0) / 100.0) }
    }

    private fun canOpenNewPosition(): Boolean {
        resetDailyCountersIfNeeded()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        if (cooldownUntilEpochMs > nowMs) return false
        if (dailyRealizedPnlUsd <= -filterSettings.maxDailyLossUsd.absoluteValue) return false
        if (consecutiveLosses >= filterSettings.maxConsecutiveLosses.coerceAtLeast(1)) return false

        val openExposure = currentOpenExposureUsd()
        val nextPositionUsd = if (filterSettings.jupiterEnabled) {
            filterSettings.tradeUsdAmount
        } else {
            DemoAccountManager.DEMO_TRADE_AMOUNT
        }
        if (openExposure + nextPositionUsd > filterSettings.maxTotalExposureUsd) return false

        val equityEstimate = if (filterSettings.jupiterEnabled) {
            dailyRealizedPnlUsd
        } else {
            // Demo баланс уже включает реализованный PnL, не учитываем его повторно.
            DemoAccountManager.getBalance()
        }
        if (equityEstimate > peakTrackedEquityUsd) {
            peakTrackedEquityUsd = equityEstimate
        }
        if (peakTrackedEquityUsd > 0) {
            val drawdownPct = ((peakTrackedEquityUsd - equityEstimate) / peakTrackedEquityUsd) * 100.0
            if (drawdownPct >= filterSettings.maxDrawdownPct) return false
        }
        return true
    }

    private suspend fun passesOnChainAuthorityChecks(token: TokenPair): Boolean {
        if (!filterSettings.requireRevokedMintAuthority && !filterSettings.requireRevokedFreezeAuthority) return true
        val mintAddress = token.baseToken?.address ?: return false
        val mintInfo = getRpcClient().getSplMintInfo(mintAddress) ?: return false
        val score = TokenScoring.calculateScore(token, mintInfo, filterSettings)
        if (score.hardRejectReasons.isNotEmpty()) return false
        if (filterSettings.requireRevokedMintAuthority && mintInfo.hasMintAuthority) return false
        if (filterSettings.requireRevokedFreezeAuthority && mintInfo.hasFreezeAuthority) return false
        return true
    }

    private fun resetDailyCountersIfNeeded() {
        val day = currentUtcDateId()
        if (day == dailyPnlEpochDay) return
        dailyPnlEpochDay = day
        dailyRealizedPnlUsd = 0.0
        consecutiveLosses = 0
        cooldownUntilEpochMs = 0L
    }

    private fun currentUtcDateId(): String {
        val epochDay = Clock.System.now().toEpochMilliseconds() / 86_400_000L
        return epochDay.toString()
    }

    private fun updateRiskStateAfterClose(closedToken: MonitoredToken) {
        resetDailyCountersIfNeeded()
        val realized = closedToken.profitUsd
        dailyRealizedPnlUsd += realized
        if (realized < 0) {
            consecutiveLosses += 1
        } else {
            consecutiveLosses = 0
        }
        if (consecutiveLosses >= filterSettings.maxConsecutiveLosses.coerceAtLeast(1)) {
            val cooldownMs = filterSettings.cooldownMinutesAfterLossLimit.coerceAtLeast(1) * 60_000L
            cooldownUntilEpochMs = Clock.System.now().toEpochMilliseconds() + cooldownMs
        }
    }

    private fun addClosedToken(token: MonitoredToken, skipHistory: Boolean = false) {
        updateRiskStateAfterClose(token)
        if (token.demoBuyApplied) {
            DemoAccountManager.applyCloseResult(token.profitUsd)
        }
        if (!skipHistory) {
            TokenHistoryManager.saveToHistory(token)
        }
        val tokenKey = token.tokenPair.baseToken?.address ?: token.tokenPair.pairAddress
        if (!tokenKey.isNullOrBlank()) {
            closedTokenAddresses.add(tokenKey)
            saveClosedTokens()
        }
        runCatching { onTokenClosedCallback(token) }
    }

    private fun saveClosedTokens() {
        AppSettings.putObject(
            CLOSED_TOKENS_KEY,
            closedTokenAddresses.toList()
        )
    }

    private fun restoreClosedTokens() {
        val cached = AppSettings.getObjectSafe(
            CLOSED_TOKENS_KEY,
            emptyList<String>()
        )
        closedTokenAddresses.clear()
        closedTokenAddresses.addAll(cached)
    }

    private fun refreshDiscoveryFlag() {
        allowNewTokenDiscovery = monitoringCount() < filterSettings.maxTokensToMonitor
    }

    private fun matchesEntryCriteria(token: TokenPair): Boolean {
        val createdAtMs = getPairCreatedAtMs(token.pairCreatedAt) ?: return false
        val ageMinutes = (Clock.System.now().toEpochMilliseconds() - createdAtMs) / (1000.0 * 60.0)
        if (ageMinutes > filterSettings.entryMaxAgeMinutes) return false

        val marketCap = token.marketCap ?: return false
        if (marketCap < filterSettings.entryMinMarketCap || marketCap > filterSettings.entryMaxMarketCap) return false

        val liquidity = token.liquidity?.usd ?: return false
        if (liquidity < filterSettings.entryMinLiquidity) return false

        val volumeH24 = token.volume?.h24 ?: 0.0
        if (filterSettings.useVolumeH24 && volumeH24 < filterSettings.entryMinVolume) return false

        val volumeM5 = token.volume?.m5 ?: 0.0
        if (filterSettings.useVolumeM5 && volumeM5 < filterSettings.entryMinVolumeM5) return false

        val hasWebsite = token.info?.websites?.any { !it.url.isNullOrBlank() } == true
        val hasSocials = token.info?.socials?.any { !it.url.isNullOrBlank() } == true
        if (filterSettings.requireWebsite && !hasWebsite) return false
        if (filterSettings.requireSocials && !hasSocials) return false

        // Buys/Sells ratio M5 > min (если фильтр включён)
        if (filterSettings.useMinBuysToSellsRatioM5) {
            val buysM5 = token.txns?.m5?.buys ?: 0
            val sellsM5 = token.txns?.m5?.sells ?: 0
            val ratioOk = when {
                sellsM5 == 0 -> buysM5 > 0
                else -> (buysM5.toDouble() / sellsM5) >= filterSettings.minBuysToSellsRatioM5
            }
            if (!ratioOk) return false
        }

        // Price ↑ за 5 мин (если фильтр включён; при отсутствии m5 в API — не отсекаем)
        if (filterSettings.useMinPriceChangeM5Pct) {
            token.priceChange?.m5?.let { if (it < filterSettings.minPriceChangeM5Pct) return false }
        }

        return true
    }

    private fun getPairCreatedAtMs(pairCreatedAt: Long?): Long? {
        if (pairCreatedAt == null || pairCreatedAt <= 0) return null
        return if (pairCreatedAt < 1_000_000_000_000L) pairCreatedAt * 1000 else pairCreatedAt
    }

    private data class BuyResult(
        val outAmountRaw: Long,
        val inLamports: Long,
        val txId: String
    )

    private data class SellResult(
        val outLamports: Long,
        val profitUsd: Double
    )

    /** Результат попытки продажи через Jupiter (для UI / Telegram при неудаче) */
    private data class JupiterSellOutcome(
        val result: SellResult?,
        val failureReason: String? = null
    )

    private fun refreshCachedDependencies() {
        val normalizedPhrase = filterSettings.seedPhrase.trim().replace("\\s+".toRegex(), " ")
        val signerKey = normalizedPhrase
        if (signerKey != signerFingerprint) {
            cachedSigner = null
            signerFingerprint = signerKey
        }

        val rpcKey = "${filterSettings.rpcUrl}|${filterSettings.rpcTimeoutSeconds}|${filterSettings.rpcConfirmTimeoutMs}"
        if (rpcKey != rpcFingerprint) {
            rpcClient?.close()
            rpcClient = null
            rpcFingerprint = rpcKey
        }
    }

    private fun getSigner(): Signer? {
        val phrase = filterSettings.seedPhrase.trim()
        if (phrase.isBlank()) return null
        val words = phrase.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size != 12 && words.size != 24) return null
        val key = phrase.replace("\\s+".toRegex(), " ")
        if (signerFingerprint != key) {
            cachedSigner = null
            signerFingerprint = key
        }
        if (cachedSigner == null) {
            cachedSigner = try {
                createSignerFromSeedPhrase(phrase)
            } catch (e: Exception) {
                println("❌ Signer error: ${e.message}")
                null
            }
        }
        return cachedSigner
    }

    private fun getRpcClient(): SolanaRpcClient {
        refreshCachedDependencies()
        val current = rpcClient
        return if (current == null) {
            SolanaRpcClient(
                rpcUrl = filterSettings.rpcUrl,
                timeoutSeconds = filterSettings.rpcTimeoutSeconds,
                confirmTimeoutMs = filterSettings.rpcConfirmTimeoutMs
            ).also { rpcClient = it }
        } else current
    }

    private suspend fun buyTokenIfEnabled(token: TokenPair): BuyResult? {
        if (!filterSettings.jupiterEnabled) return null
        val signer = getSigner() ?: run {
            println("❌ Jupiter buy: seed phrase empty or invalid")
            return null
        }
        val outputMint = token.baseToken?.address ?: return null
        val solPriceUsd = getSolPriceUsdWithFallback() ?: return null
        val solAmount = filterSettings.tradeUsdAmount / solPriceUsd
        val lamports = (solAmount * 1_000_000_000L).toLong().coerceAtLeast(1L)
        val balance = getRpcClient().getBalanceLamports(signer.publicKeyBase58()) ?: 0L
        val feeBuffer = filterSettings.minFeeBufferLamports.coerceAtLeast(100_000L)
        if (balance < lamports + feeBuffer) {
            println("⚠️ Недостаточно SOL: balance=$balance, need=${lamports + feeBuffer}")
            return null
        }

        val quote = jupiterApi.getQuote(
            inputMint = filterSettings.baseMint,
            outputMint = outputMint,
            amount = lamports,
            slippageBps = filterSettings.slippageBps,
            apiKey = filterSettings.jupiterApiKey
        ) ?: return null

        val swap = jupiterApi.getSwap(
            quote = quote,
            userPublicKey = signer.publicKeyBase58(),
            apiKey = filterSettings.jupiterApiKey,
            priorityFeeMode = filterSettings.jupiterPriorityFeeMode
        ) ?: return null
        val unsignedTx = swap.swapTransaction ?: return null
        val signedTx = signTransactionBase64(unsignedTx, signer) ?: return null
        val txId = getRpcClient().sendTransaction(signedTx) ?: return null
        if (!getRpcClient().confirmTransaction(txId)) {
            println("⚠️ Jupiter buy tx not confirmed: $txId")
            return null
        }

        val outAmountStr = quote["outAmount"]?.jsonPrimitive?.content
        val inAmountStr = quote["inAmount"]?.jsonPrimitive?.content
        val outAmount = outAmountStr?.toLongOrNull() ?: 0L
        val inAmount = inAmountStr?.toLongOrNull() ?: lamports
        println("✅ Jupiter buy: in=${inAmount} lamports, out=${outAmount} raw, mint=${outputMint}")
        return BuyResult(outAmountRaw = outAmount, inLamports = inAmount, txId = txId)
    }

    private suspend fun sellTokenPercent(
        token: MonitoredToken,
        amountBase: Long,
        percent: Double,
        marketCap: Double
    ): JupiterSellOutcome {
        if (!filterSettings.jupiterEnabled) return JupiterSellOutcome(null, null)
        if (percent <= 0) return JupiterSellOutcome(null, null)
        val signer = getSigner() ?: run {
            println("❌ Jupiter sell: seed phrase empty or invalid")
            return JupiterSellOutcome(null, "Кошелёк недоступен (seed)")
        }
        val inputMint = token.tokenPair.baseToken?.address
            ?: return JupiterSellOutcome(null, "Нет mint токена")
        val sellAmount = (amountBase * (percent / 100.0)).toLong().coerceAtLeast(1L)

        val quote = jupiterApi.getQuote(
            inputMint = inputMint,
            outputMint = filterSettings.baseMint,
            amount = sellAmount,
            slippageBps = filterSettings.slippageBps,
            apiKey = filterSettings.jupiterApiKey
        ) ?: return JupiterSellOutcome(null, "Нет quote (ликвидность/slippage)")

        val swap = jupiterApi.getSwap(
            quote = quote,
            userPublicKey = signer.publicKeyBase58(),
            apiKey = filterSettings.jupiterApiKey,
            priorityFeeMode = filterSettings.jupiterPriorityFeeMode
        ) ?: return JupiterSellOutcome(null, "Ошибка сборки swap")
        val unsignedTx = swap.swapTransaction ?: return JupiterSellOutcome(null, "Пустая транзакция swap")
        val signedTx = signTransactionBase64(unsignedTx, signer) ?: return JupiterSellOutcome(null, "Подпись транзакции")
        val txId = getRpcClient().sendTransaction(signedTx) ?: return JupiterSellOutcome(null, "Отправка в сеть")
        if (!getRpcClient().confirmTransaction(txId)) {
            println("⚠️ Jupiter sell tx not confirmed: $txId")
            return JupiterSellOutcome(null, "Транзакция не подтверждена")
        }

        val outAmountStr = quote["outAmount"]?.jsonPrimitive?.content
        val outAmount = outAmountStr?.toLongOrNull() ?: 0L
        val solPrice = getSolPriceUsdWithFallback() ?: 0.0
        val costLamports = (token.buySolLamports * (percent / 100.0)).toLong()
        val profitUsd = ((outAmount - costLamports).toDouble() / 1_000_000_000.0) * solPrice

        println("✅ Jupiter sell: in=${sellAmount} raw, out=${outAmount} lamports, tx=$txId (${percent.toInt()}%)")
        return JupiterSellOutcome(SellResult(outLamports = outAmount, profitUsd = profitUsd), null)
    }

    suspend fun testSwap(): String {
        if (!filterSettings.jupiterEnabled) return "Jupiter trading is OFF"
        val signer = getSigner() ?: return "Seed phrase invalid or empty"
        val solPriceUsd = getSolPriceUsdWithFallback() ?: return "Failed to fetch SOL price"
        val solAmount = filterSettings.tradeUsdAmount / solPriceUsd
        val lamports = (solAmount * 1_000_000_000L).toLong().coerceAtLeast(1L)
        val balance = getRpcClient().getBalanceLamports(signer.publicKeyBase58()) ?: 0L
        if (balance < lamports + filterSettings.minFeeBufferLamports.coerceAtLeast(100_000L)) return "Insufficient SOL balance"

        val quoteResult = jupiterApi.getQuoteDebug(
            inputMint = filterSettings.baseMint,
            outputMint = usdcMint,
            amount = lamports,
            slippageBps = filterSettings.slippageBps,
            apiKey = filterSettings.jupiterApiKey
        )
        val quote = quoteResult.quote ?: return "Quote failed: ${quoteResult.error ?: "Unknown error"} (amount=${lamports}, slippageBps=${filterSettings.slippageBps})"

        val swap = jupiterApi.getSwap(
            quote = quote,
            userPublicKey = signer.publicKeyBase58(),
            apiKey = filterSettings.jupiterApiKey,
            priorityFeeMode = filterSettings.jupiterPriorityFeeMode
        ) ?: return "Swap build failed"
        val unsignedTx = swap.swapTransaction ?: return "Swap transaction empty"
        val signedTx = signTransactionBase64(unsignedTx, signer) ?: return "Sign failed"
        val txId = getRpcClient().sendTransaction(signedTx) ?: return "Send failed"
        if (!getRpcClient().confirmTransaction(txId)) return "Send failed: tx not confirmed"

        return "Test swap sent: $txId"
    }

    private suspend fun getSolPriceUsdWithFallback(): Double? {
        val jupPrice = jupiterApi.getSolPriceUsd(filterSettings.jupiterApiKey)
        if (jupPrice != null) return jupPrice

        val dexPrice = api.getTokenPriceUsd("solana", filterSettings.baseMint)
        if (dexPrice != null) {
            println("⚠️ Jupiter price failed, using DexScreener price")
            return dexPrice
        }

        val fallback = 100.0
        println("⚠️ Jupiter & Dex price failed, using fixed SOL price=$fallback")
        return fallback
    }

    // ⏰ Проверка возраста токена
    private fun checkTokenAge(token: TokenPair): Boolean {
        return getPairCreatedAtMs(token.pairCreatedAt)?.let { createdAt ->
            val ageHours = (Clock.System.now().toEpochMilliseconds() - createdAt) / (1000.0 * 60.0 * 60.0)
            ageHours <= filterSettings.pairMaxAgeHours
        } ?: true // Если даты нет, пропускаем
    }

    // 🚨 Проверка на потенциальный скам
    private fun isPotentialScam(token: TokenPair): Boolean {
        // Простые проверки:

        // 1. Слишком большая разница между FDV и ликвидностью
        val fdv = token.fdv ?: 0.0
        val liquidity = token.liquidity?.usd ?: 0.0
        if (liquidity > 0) {
            val fdvToLiquidityRatio = fdv / liquidity
            if (fdvToLiquidityRatio > 100) {
                println("🚨 Скам: FDV/ликвидность = $fdvToLiquidityRatio > 100")
                return true
            }
        }

        // 2. Слишком много продаж vs покупок
        val sells = token.txns?.h24?.sells ?: 0
        val buys = token.txns?.h24?.buys ?: 0
        if (buys > 0) {
            val sellBuyRatio = sells.toDouble() / buys
            if (sellBuyRatio > 3) {
                println("🚨 Скам: продаж/покупок = $sellBuyRatio > 3")
                return true
            }
        }

        // 3. Слишком маленькая цена (подозрительно)
        val price = parsePrice(token.priceUsd)
        if (price < 0.00000001) { // Меньше 0.00000001$
            println("🚨 Скам: цена слишком мала: $price")
            return true
        }

        return false
    }

    // 📈 Обновление цен отслеживаемых токенов (РЕАЛЬНЫЙ API)
    private suspend fun updateMonitoredTokens(onUpdate: (MonitoredToken) -> Unit) {
        println("📈 Обновление цен для ${_monitoredTokens.size} токенов...")

        for (monitoredToken in _monitoredTokens.filter { it.status == TokenStatus.MONITORING }) {
            try {
                // ✅ РЕАЛЬНЫЙ ЗАПРОС К API
                val updatedPair = api.updateTokenPrice(monitoredToken.tokenPair)
                if (updatedPair != null) {
                    val newPrice = parsePrice(updatedPair.priceUsd)
                    if (newPrice > 0) {
                        updateTokenPrice(monitoredToken, updatedPair, newPrice, onUpdate)
                        println("✅ ${monitoredToken.tokenPair.baseToken?.symbol}: цена обновлена до $${newPrice}")
                    } else {
                        println("⚠️ ${monitoredToken.tokenPair.baseToken?.symbol}: некорректная цена")
                    }
                } else {
                    println("⚠️ ${monitoredToken.tokenPair.baseToken?.symbol}: не удалось получить обновление")
                }

                // Задержка между запросами (чтобы не превысить rate limit)
                // DexScreener API: 300 requests/minute = ~5 requests/second
                delay(1000) // 1 секунда между запросами

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("❌ Ошибка обновления токена ${monitoredToken.tokenPair.baseToken?.symbol}: ${e.message}")
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 🔄 ОБНОВЛЕНИЕ ЦЕНЫ И ПРОВЕРКА УСЛОВИЙ ВЫХОДА
    // ════════════════════════════════════════════════════════════════════════════════
    // Вызывается каждый тик для каждого активного токена:
    // 1. Обновляет текущую цену и считает прибыль
    // 2. Проверяет условия частичных продаж (Stage 1-4 или Aggressive)
    // 3. Проверяет условия принудительного выхода (Stop Loss, Trailing, Pullback)
    // 4. Если Jupiter включён — выполняет реальные свапы
    // ════════════════════════════════════════════════════════════════════════════════
    private suspend fun updateTokenPrice(
        token: MonitoredToken,
        updatedPair: TokenPair,
        newPrice: Double,
        onUpdate: (MonitoredToken) -> Unit
    ) {
        // ──── РАСЧЁТ ПРИБЫЛИ ────
        // Если entryPrice=0 (старый кеш), используем сохранённый currentPrice как fallback
        val entry = if (token.entryPrice > 0) token.entryPrice else parsePrice(token.currentPrice)
        val prevHigh = if (token.sessionHighPrice > 0) token.sessionHighPrice else entry
        val newSessionHigh = maxOf(prevHigh, newPrice)

        val priceChangePercent =
            if (entry > 0) ((newPrice - entry) / entry) * 100 else 0.0  // ⭐ Прибыль в % от входа

        val investment = token.investedUsd.coerceAtLeast(0.0)
        val priceBasedProfitUsd = investment * priceChangePercent / 100  // ⭐ Прибыль в USD (для демо)

        // ──── ОБНОВЛЕНИЕ СОСТОЯНИЯ ────
        val marketCap = updatedPair.marketCap ?: token.lastMarketCap
        var remainingPct = token.remainingPositionPct  // ⭐ Оставшаяся позиция в %
        var tokenAmountRaw = token.tokenAmountRaw
        var stage1Done = token.exitStage1Done
        var stage2Done = token.exitStage2Done
        var stage3Done = token.exitStage3Done
        var stage4Done = token.exitStage4Done
        var realizedProfitUsd = token.realizedProfitUsd  // ⭐ Реализованная прибыль (сумма частичных выходов)
        var closedByFullStageExit = false
        val entryCap = token.entryMarketCap
        val newPeakMarketCap = if (marketCap > token.peakMarketCap) marketCap else token.peakMarketCap

        val isAggressive = filterSettings.exitStrategy == "aggressive"

        var jupiterSellLastError = token.jupiterSellLastError
        var jupiterSellLastErrorAtMs = token.jupiterSellLastErrorAtMs
        fun applyJupiterSellOutcome(outcome: JupiterSellOutcome) {
            if (!filterSettings.jupiterEnabled) return
            when {
                outcome.result != null -> {
                    jupiterSellLastError = ""
                    jupiterSellLastErrorAtMs = 0L
                }
                outcome.failureReason != null -> {
                    jupiterSellLastError = outcome.failureReason
                    jupiterSellLastErrorAtMs = Clock.System.now().toEpochMilliseconds()
                }
            }
        }
        suspend fun tryJupiterSell(amountBase: Long, percent: Double, mc: Double): JupiterSellOutcome =
            try {
                sellTokenPercent(token, amountBase, percent, mc)
            } catch (e: Exception) {
                JupiterSellOutcome(null, e.message ?: "Ошибка Jupiter")
            }

        // ════════════════════════════════════════════════════════════════════════════════
        // 🔥 AGGRESSIVE MODE: одна фиксация по % прибыли, остальное trailing
        // ════════════════════════════════════════════════════════════════════════════════
        // Срабатывает ОДИН РАЗ при достижении заданного % прибыли по цене
        if (isAggressive && !stage1Done && priceChangePercent >= filterSettings.aggressiveTakeProfitPct) {
            val pct = filterSettings.aggressiveSellPct.coerceIn(1.0, 100.0)
            val jupiterOutcome = if (filterSettings.jupiterEnabled) {
                tryJupiterSell(tokenAmountRaw, pct, marketCap)
            } else {
                JupiterSellOutcome(null, null)
            }
            if (filterSettings.jupiterEnabled) applyJupiterSellOutcome(jupiterOutcome)
            val sellResult = jupiterOutcome.result
            if (!filterSettings.jupiterEnabled || sellResult != null) {
                remainingPct -= pct
                tokenAmountRaw = (tokenAmountRaw - (tokenAmountRaw * (pct / 100.0)).toLong()).coerceAtLeast(0L)
                if (remainingPct <= 0.0) closedByFullStageExit = true
                stage1Done = true
                println("✅ Aggressive: фиксация ${pct.toInt()}% при +${priceChangePercent.toInt()}%")
                val partialProfitUsd = sellResult?.profitUsd ?: (priceBasedProfitUsd * (pct / 100.0))
                TokenHistoryManager.savePartialExit(
                    token,
                    "Aggressive",
                    pct,
                    marketCap,
                    newPrice,
                    partialProfitUsd,
                    isRealTrade = !token.demoBuyApplied,
                    isSwapSuccess = !filterSettings.jupiterEnabled || sellResult != null
                )
                // ⭐ Обновляем realizedProfitUsd для демо И Jupiter режимов
                realizedProfitUsd += partialProfitUsd
            }
        }

        if (!isAggressive && !stage1Done && marketCap >= filterSettings.exitStage1Cap) {
            val pct = filterSettings.exitStage1Pct
            val jupiterOutcome = if (filterSettings.jupiterEnabled) {
                tryJupiterSell(tokenAmountRaw, pct, marketCap)
            } else {
                JupiterSellOutcome(null, null)
            }
            if (filterSettings.jupiterEnabled) applyJupiterSellOutcome(jupiterOutcome)
            val sellResult = jupiterOutcome.result
            if (!filterSettings.jupiterEnabled || sellResult != null) {
                remainingPct -= pct
                tokenAmountRaw = (tokenAmountRaw - (tokenAmountRaw * (pct / 100.0)).toLong()).coerceAtLeast(0L)
                if (remainingPct <= 0.0) closedByFullStageExit = true
                stage1Done = true
                println("✅ Этап 1: фиксация ${pct.toInt()}% при Market Cap ${filterSettings.exitStage1Cap.toInt()}")
                val partialProfitUsd = sellResult?.profitUsd ?: (priceBasedProfitUsd * (pct / 100.0))
                TokenHistoryManager.savePartialExit(
                    token,
                    "Stage 1",
                    pct,
                    marketCap,
                    newPrice,
                    partialProfitUsd,
                    isRealTrade = !token.demoBuyApplied,
                    isSwapSuccess = !filterSettings.jupiterEnabled || sellResult != null
                )
                // ⭐ Обновляем realizedProfitUsd для демо И Jupiter режимов
                realizedProfitUsd += partialProfitUsd
            }
        }
        if (!isAggressive && !stage2Done && marketCap >= filterSettings.exitStage2Cap) {
            val pct = filterSettings.exitStage2Pct
            val jupiterOutcome = if (filterSettings.jupiterEnabled) {
                tryJupiterSell(tokenAmountRaw, pct, marketCap)
            } else {
                JupiterSellOutcome(null, null)
            }
            if (filterSettings.jupiterEnabled) applyJupiterSellOutcome(jupiterOutcome)
            val sellResult = jupiterOutcome.result
            if (!filterSettings.jupiterEnabled || sellResult != null) {
                remainingPct -= pct
                tokenAmountRaw = (tokenAmountRaw - (tokenAmountRaw * (pct / 100.0)).toLong()).coerceAtLeast(0L)
                if (remainingPct <= 0.0) closedByFullStageExit = true
                stage2Done = true
                println("✅ Этап 2: фиксация ${pct.toInt()}% при Market Cap ${filterSettings.exitStage2Cap.toInt()}")
                val partialProfitUsd = sellResult?.profitUsd ?: (priceBasedProfitUsd * (pct / 100.0))
                TokenHistoryManager.savePartialExit(
                    token,
                    "Stage 2",
                    pct,
                    marketCap,
                    newPrice,
                    partialProfitUsd,
                    isRealTrade = !token.demoBuyApplied,
                    isSwapSuccess = !filterSettings.jupiterEnabled || sellResult != null
                )
                // ⭐ Обновляем realizedProfitUsd для демо И Jupiter режимов
                realizedProfitUsd += partialProfitUsd
            }
        }
        if (!isAggressive && !stage3Done && marketCap >= filterSettings.exitStage3Cap) {
            val pct = filterSettings.exitStage3Pct
            val jupiterOutcome = if (filterSettings.jupiterEnabled) {
                tryJupiterSell(tokenAmountRaw, pct, marketCap)
            } else {
                JupiterSellOutcome(null, null)
            }
            if (filterSettings.jupiterEnabled) applyJupiterSellOutcome(jupiterOutcome)
            val sellResult = jupiterOutcome.result
            if (!filterSettings.jupiterEnabled || sellResult != null) {
                remainingPct -= pct
                tokenAmountRaw = (tokenAmountRaw - (tokenAmountRaw * (pct / 100.0)).toLong()).coerceAtLeast(0L)
                if (remainingPct <= 0.0) closedByFullStageExit = true
                stage3Done = true
                println("✅ Этап 3: фиксация ${pct.toInt()}% при Market Cap ${filterSettings.exitStage3Cap.toInt()}")
                val partialProfitUsd = sellResult?.profitUsd ?: (priceBasedProfitUsd * (pct / 100.0))
                TokenHistoryManager.savePartialExit(
                    token,
                    "Stage 3",
                    pct,
                    marketCap,
                    newPrice,
                    partialProfitUsd,
                    isRealTrade = !token.demoBuyApplied,
                    isSwapSuccess = !filterSettings.jupiterEnabled || sellResult != null
                )
                // ⭐ Обновляем realizedProfitUsd для демо И Jupiter режимов
                realizedProfitUsd += partialProfitUsd
            }
        }
        if (!isAggressive && !stage4Done && marketCap >= filterSettings.exitStage4Cap) {
            val pct = filterSettings.exitStage4Pct
            val jupiterOutcome = if (filterSettings.jupiterEnabled) {
                tryJupiterSell(tokenAmountRaw, pct, marketCap)
            } else {
                JupiterSellOutcome(null, null)
            }
            if (filterSettings.jupiterEnabled) applyJupiterSellOutcome(jupiterOutcome)
            val sellResult = jupiterOutcome.result
            if (!filterSettings.jupiterEnabled || sellResult != null) {
                remainingPct -= pct
                tokenAmountRaw = (tokenAmountRaw - (tokenAmountRaw * (pct / 100.0)).toLong()).coerceAtLeast(0L)
                if (remainingPct <= 0.0) closedByFullStageExit = true
                stage4Done = true
                println("✅ Этап 4: фиксация ${pct.toInt()}% при Market Cap ${filterSettings.exitStage4Cap.toInt()}")
                val partialProfitUsd = sellResult?.profitUsd ?: (priceBasedProfitUsd * (pct / 100.0))
                TokenHistoryManager.savePartialExit(
                    token,
                    "Stage 4",
                    pct,
                    marketCap,
                    newPrice,
                    partialProfitUsd,
                    isRealTrade = !token.demoBuyApplied,
                    isSwapSuccess = !filterSettings.jupiterEnabled || sellResult != null
                )
                // ⭐ Обновляем realizedProfitUsd для демо И Jupiter режимов
                realizedProfitUsd += partialProfitUsd
            }
        }

        if (remainingPct < 0) remainingPct = 0.0
        // Числовая «пыль» в % позиции: иначе позиция может остаться MONITORING без записи в историю
        if (remainingPct > 0 && remainingPct < 1e-6) remainingPct = 0.0

        // Остаток после частичных выходов (Aggressive / Stages), до принудительного выхода.
        // Нужен для skipFinalHistory: не смешивать с forcedExit — SL/trailing/time могут быть true
        // на том же тике, когда позиция уже полностью распродана стадиями.
        val remainingAfterStages = remainingPct

        // ════════════════════════════════════════════════════════════════════════════════
        // 🛡️ ЗАЩИТНЫЕ МЕХАНИЗМЫ (Stop Loss, Trailing Stop, Pullback)
        // ════════════════════════════════════════════════════════════════════════════════
        // Эти условия проверяются ВСЕГДА и закрывают остаток позиции принудительно
        
        // ⭐ Trailing включается:
        // - В Aggressive: только ПОСЛЕ первой фиксации (stage1Done)
        // - В Stages: после Stage 1 ИЛИ при достижении Stage 1 Cap
        val trailingEnabled = if (isAggressive) stage1Done else (stage1Done || marketCap >= filterSettings.exitStage1Cap)
        
        val stopLossCapFactor = 1.0 - (filterSettings.stopLossByMarketCapPct / 100.0)
        val stopLossPricePct = -filterSettings.stopLossByPricePct
        val trailingFactor = 1.0 - (filterSettings.trailingStopPct / 100.0)
        val stagePullbackFactor = 1.0 - (filterSettings.stagePullbackPct / 100.0)

        // ⭐ 1. STOP LOSS: настраиваемый порог от входной капы и по цене
        val forcedExitByStopLoss =
            (entryCap > 0 && marketCap <= entryCap * stopLossCapFactor) || priceChangePercent <= stopLossPricePct
        
        // ⭐ 2. TRAILING STOP: -35% от локального максимума Market Cap
        // Срабатывает только если trailing включён (после Stage 1 / Aggressive)
        val forcedExitByTrailing =
            trailingEnabled && newPeakMarketCap > 0 && marketCap <= newPeakMarketCap * trailingFactor
        
        // ⭐ 3. STAGE PULLBACK: -35% от пика цены после частичной фиксации
        // Если цена упала на 35% от максимума ПОСЛЕ любого Stage
        val forcedExitByStagePullback =
            (stage1Done || stage2Done || stage3Done || stage4Done) &&
                newPrice <= prevHigh * stagePullbackFactor

        // ⭐ 4. TIME-BASED EXIT: если первая цель не достигнута за N минут — выходим
        // Срабатывает только если ни один stage/aggressive ещё не выполнен (позиция без движения)
        val timeBasedExitTriggered = if (filterSettings.useTimeBasedExit && !stage1Done) {
            val holdingMs = Clock.System.now().toEpochMilliseconds() - token.foundTime
            val holdingMinutes = holdingMs / 60_000.0
            holdingMinutes >= filterSettings.timeBasedExitMinutes
        } else false

        if (timeBasedExitTriggered) {
            println("⏱️ Time-based exit: ${token.tokenPair.baseToken?.symbol} — нет прогресса ${filterSettings.timeBasedExitMinutes} мин")
        }

        val forcedExit = forcedExitByStopLoss || forcedExitByTrailing || forcedExitByStagePullback || timeBasedExitTriggered
        var forcedExitExecuted = !forcedExit

        // ──── ПРИНУДИТЕЛЬНЫЙ ВЫХОД (продажа остатка) ────
        if (forcedExit) {
            val percentToSell = remainingPct.coerceAtMost(100.0)
            when {
                percentToSell <= 0.0 -> {
                    // Позиция уже полностью продана через частичные выходы — просто фиксируем закрытие
                    forcedExitExecuted = true
                }
                filterSettings.jupiterEnabled && tokenAmountRaw > 0 -> {
                    val forcedOutcome = tryJupiterSell(tokenAmountRaw, percentToSell, marketCap)
                    applyJupiterSellOutcome(forcedOutcome)
                    val sellResult = forcedOutcome.result
                    if (sellResult != null) {
                        realizedProfitUsd += sellResult.profitUsd
                        tokenAmountRaw = 0L
                        remainingPct = 0.0
                        forcedExitExecuted = true  // ✅ продажа прошла — закрываем
                    } else {
                        forcedExitExecuted = false  // Jupiter sell не удался — попробуем в следующем тике
                    }
                }
                else -> {
                    // Demo-режим: нет реального свапа — фиксируем P&L по текущей цене
                    val remainingLoss = investment * priceChangePercent / 100.0 * (remainingPct / 100.0)
                    realizedProfitUsd += remainingLoss
                    remainingPct = 0.0
                    forcedExitExecuted = true  // ✅ демо-выход всегда успешен
                }
            }
            when {
                forcedExitByStopLoss -> println("🛑 Forced exit: SL по капе/цене")
                forcedExitByTrailing -> println("🛑 Forced exit: trailing stop от максимума")
                forcedExitByStagePullback -> println("🛑 Forced exit: pullback после Stage/Aggressive")
                timeBasedExitTriggered -> println("⏱️ Forced exit: time-based (${filterSettings.timeBasedExitMinutes} мин без прогресса)")
            }
        }

        val positionFullyClosed = remainingPct <= 1e-6

        // ──── ВЫЧИСЛЯЕМ ФИНАЛЬНЫЙ ПРОФИТ ────
        val finalProfitUsd = if (filterSettings.jupiterEnabled) {
            // Для real mode показываем реализованный PnL + оценку по открытому остатку
            val unrealizedFromPrice = priceBasedProfitUsd * (remainingPct / 100.0)
            realizedProfitUsd + unrealizedFromPrice
        } else {
            val remainingProfit = investment * priceChangePercent / 100 * (remainingPct / 100.0)
            realizedProfitUsd + remainingProfit
        }

        // ──── ОПРЕДЕЛЕНИЕ СТАТУСА ────
        val newStatus = when {
            forcedExit && !forcedExitExecuted -> TokenStatus.MONITORING
            forcedExit -> if (finalProfitUsd >= 0) TokenStatus.STOPPED_TP else TokenStatus.STOPPED_SL
            positionFullyClosed -> if (finalProfitUsd >= 0) TokenStatus.STOPPED_TP else TokenStatus.STOPPED_SL
            else -> TokenStatus.MONITORING
        }

        val updatedToken = token.copy(
            tokenPair = updatedPair,
            currentPrice = newPrice.toString(),
            priceChangePercent = priceChangePercent,
            profitUsd = finalProfitUsd,
            status = newStatus,
            sessionHighPrice = newSessionHigh,
            peakMarketCap = newPeakMarketCap,
            lastMarketCap = marketCap,
            tokenAmountRaw = tokenAmountRaw,
            realizedProfitUsd = realizedProfitUsd,
            remainingPositionPct = remainingPct,
            exitStage1Done = stage1Done,
            exitStage2Done = stage2Done,
            exitStage3Done = stage3Done,
            exitStage4Done = stage4Done,
            jupiterSellLastError = jupiterSellLastError,
            jupiterSellLastErrorAtMs = jupiterSellLastErrorAtMs
        )

        listMutex.withLock {
            val index = _monitoredTokens.indexOfFirst {
                it.tokenPair.pairAddress == token.tokenPair.pairAddress
            }

            if (index != -1) {
                // 💾 Сохраняем в историю если достигнут TP или SL
                if (newStatus != TokenStatus.MONITORING) {
                    // Пропускаем финальную строку saveToHistory, только если весь объём уже ушёл
                    // частичными записями (savePartialExit); иначе — одна полная запись по сделке.
                    val skipFinalHistory =
                        closedByFullStageExit && remainingAfterStages <= 1e-6
                    addClosedToken(updatedToken, skipHistory = skipFinalHistory)
                    // ✅ Автоматически удаляем из списка мониторинга
                    _monitoredTokens.removeAt(index)
                    println("🗑️ Токен ${token.tokenPair.baseToken?.symbol} удален из мониторинга (${if (newStatus == TokenStatus.STOPPED_TP) "TP HIT" else "SL HIT"})")
                    checkAndResumeDiscovery() // Проверяем, можно ли возобновить поиск
                } else {
                    _monitoredTokens[index] = updatedToken
                }
                onUpdate(updatedToken)
                saveTokensToCache()
            }
        }
    }

    private fun saveTokensToCache() {
        AppSettings.putObject(
            CACHE_KEY_TOKENS,
            _monitoredTokens.toList()
        )
        println("💾 Токены сохранены в кеш (${_monitoredTokens.size})")
    }

    fun restoreFromCache() {
        val cached = AppSettings.getObjectSafe(
            CACHE_KEY_TOKENS,
            emptyList<MonitoredToken>()
        )

        if (cached.isNotEmpty()) {
            val activeOnly = cached
                .filter { it.status == TokenStatus.MONITORING }
                .map { token ->
                    // Если entryPrice=0 (старый кеш без этого поля или повреждённые данные),
                    // восстанавливаем из currentPrice чтобы P&L не был заморожен на 0$
                    if (token.entryPrice <= 0.0) {
                        val fallback = token.currentPrice.toDoubleOrNull() ?: 0.0
                        if (fallback > 0.0) {
                            println("⚠️ Токен ${token.tokenPair.baseToken?.symbol}: entryPrice=0, сброс baseline → $fallback")
                            token.copy(entryPrice = fallback, profitUsd = 0.0, priceChangePercent = 0.0)
                        } else token
                    } else token
                }
            _monitoredTokens.clear()
            _monitoredTokens.addAll(activeOnly)
            allowNewTokenDiscovery =
                monitoringCount() < filterSettings.maxTokensToMonitor

            if (activeOnly.size != cached.size) {
                AppSettings.putObject(CACHE_KEY_TOKENS, activeOnly)
            }
            println("♻️ Восстановлено токенов из кеша: ${activeOnly.size}")
        }
    }


    // 🎯 Проверка условий для остановки мониторинга
    private fun checkStopConditions(token: MonitoredToken) {
        when {
            token.priceChangePercent >= 30 -> {
                token.status = TokenStatus.STOPPED_TP
                println("🎯 ТЕЙК-ПРОФИТ: ${token.tokenPair.baseToken?.symbol} +${formatNumber(token.priceChangePercent, 2)}%")
            }
            token.priceChangePercent <= -25 -> {
                token.status = TokenStatus.STOPPED_SL
                println("💥 СТОП-ЛОСС: ${token.tokenPair.baseToken?.symbol} ${formatNumber(token.priceChangePercent, 2)}%")
            }
        }
    }


    // Парсинг цены (может быть в формате "1.5e-7")
    private fun parsePrice(priceStr: String?): Double {
        return try {
            priceStr?.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }



    private fun abs(value: Double): Double {
        return if (value < 0) -value else value
    }

    // 🕐 Проверка: попадаем ли в разрешённые часы торговли (UTC)
    private fun isWithinTradingHours(): Boolean {
        if (!filterSettings.tradingHoursEnabled) return true
        val hourOfDay = ((Clock.System.now().toEpochMilliseconds() / 3_600_000L) % 24).toInt()
        val start = filterSettings.tradingHoursStartUtcHour.coerceIn(0, 23)
        val end = filterSettings.tradingHoursEndUtcHour.coerceIn(0, 23)
        return if (start <= end) {
            hourOfDay in start until end
        } else {
            // Диапазон переходит через полночь (напр. 22:00 – 06:00)
            hourOfDay >= start || hourOfDay < end
        }
    }

    // Закрытие ресурсов
    fun close() {
        stopMonitoring()
        api.close()
        jupiterApi.close()
        rpcClient?.close()
        println("🔌 Ресурсы закрыты")
    }
}