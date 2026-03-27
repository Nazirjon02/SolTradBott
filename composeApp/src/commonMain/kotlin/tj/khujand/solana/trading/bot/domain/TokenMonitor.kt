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
    var demoBuyApplied: Boolean = false            // ⭐ true = демо-покупка (виртуальная), false = реальная через Jupiter
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
    private var isMonitoring = false
    private var updateSemaphore = Semaphore(FilterSettings().maxParallelUpdates.coerceAtLeast(1))
    private val listMutex = Mutex()
    private val closedTokenAddresses = mutableSetOf<String>()
    private var rpcClient: SolanaRpcClient? = null
    private var cachedSigner: Signer? = null
    private var signerFingerprint: String = ""
    private var rpcFingerprint: String = ""
    private var consecutiveLosses: Int = 0
    private var dailyRealizedPnlUsd: Double = 0.0
    private var dailyPnlEpochDay: String = currentUtcDateId()
    private var peakTrackedEquityUsd: Double = 0.0
    private var cooldownUntilEpochMs: Long = 0L
    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    // Список отслеживаемых токенов
    private val _monitoredTokens = mutableStateListOf<MonitoredToken>()
    val monitoredTokens: List<MonitoredToken> get() = _monitoredTokens

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
        onRequestStateChanged: (Boolean) -> Unit = {},    // Callback: статус запроса к API (true=загрузка)
        onError: (String) -> Unit = {}             // Callback: произошла ошибка
    ) {
        if (isMonitoring) {
            println("⚠️ Мониторинг уже запущен")
            return
        }

        isMonitoring = true
        allowNewTokenDiscovery = true  // 🔴 Сбрасываем флаг
        println("🚀 Запуск мониторинга с фильтрами:")
        println("   - Возраст токена: <= ${filterSettings.entryMaxAgeMinutes} мин")
        println("   - Market Cap: ${filterSettings.entryMinMarketCap.toInt()} - ${filterSettings.entryMaxMarketCap.toInt()} USD")
        println("   - Ликвидность: >= ${filterSettings.entryMinLiquidity.toInt()} USD")
        println("   - Объем 24ч: >= ${filterSettings.entryMinVolume.toInt()} USD")
        println("   - Соц. сети и сайт: обязательны")

        monitorJob = CoroutineScope(Dispatchers.Default).launch {
            var cycleCount = 0
            while (isMonitoring) {
                var requestedThisCycle = false
                try {
                    cycleCount++
                    // Поиск новых токенов — раз в 5 циклов (чтобы цены обновлялись чаще)
                    val runPhase1 = allowNewTokenDiscovery && (
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

                                // 5. Добавляем токен
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
                                    updateTokenPrice(monitoredToken, updatedPair, newPrice, onUpdate)
                                    println("✅ ${monitoredToken.tokenPair.baseToken?.symbol}: цена обновлена до $${newPrice}")
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println("❌ Ошибка обновления токена ${monitoredToken.tokenPair.baseToken?.symbol}: ${e.message}")
                        } finally {
                            delay(filterSettings.updateDelayMs.toLong().coerceAtLeast(0L))
                        }
                    }
                }
            }

            updateJobs.awaitAll()
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
    
    // ✅ НОВЫЙ МЕТОД: Ручное закрытие токена (фиксация прибыли/убытка)
    suspend fun closeTokenManually(pairAddress: String, isProfit: Boolean = true) {
        val index = _monitoredTokens.indexOfFirst { it.tokenPair.pairAddress == pairAddress }
        if (index != -1) {
            val token = _monitoredTokens[index]
            if (token.status == TokenStatus.MONITORING) {
                if (filterSettings.jupiterEnabled && token.tokenAmountRaw > 0L) {
                    val percentToSell = token.remainingPositionPct.coerceIn(0.0, 100.0)
                    val sellResult = sellTokenPercent(
                        token = token,
                        amountBase = token.tokenAmountRaw,
                        percent = percentToSell,
                        marketCap = token.lastMarketCap
                    )
                    if (sellResult == null) {
                        println("⚠️ Ручное закрытие: продажа через Jupiter не удалась, токен остается в мониторинге")
                        return
                    }
                }

                // Определяем статус на основе текущей прибыли
                val newStatus = if (isProfit || token.profitUsd >= 0) {
                    TokenStatus.STOPPED_TP
                } else {
                    TokenStatus.STOPPED_SL
                }
                
                // ⭐ Обнуляем позицию полностью при закрытии
                val updatedToken = token.copy(
                    status = newStatus,
                    remainingPositionPct = 0.0,
                    tokenAmountRaw = 0L
                )
                
                // Сохраняем в историю и помечаем токен как закрытый (без повторного добавления)
                addClosedToken(updatedToken)

                // ✅ Автоматически удаляем из списка мониторинга
                _monitoredTokens.removeAt(index)
                checkAndResumeDiscovery() // Проверяем, можно ли возобновить поиск

                saveTokensToCache()

                println("✅ Токен ${token.tokenPair.baseToken?.symbol} закрыт вручную и удален из мониторинга: ${if (isProfit) "Profit" else "Loss"}")
            }
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
                val sellResult = sellTokenPercent(
                    token = token,
                    amountBase = token.tokenAmountRaw,
                    percent = percentToSell,
                    marketCap = token.lastMarketCap
                )
                if (sellResult == null) {
                    println("⚠️ Очистка: продажа через Jupiter не удалась, токен остается в мониторинге")
                    tokensToKeep.add(token)
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
        monitorJob?.cancel()
        monitorJob = null
        println("✅ Мониторинг остановлен")
    }

    fun isMonitoringActive(): Boolean {
        return isMonitoring
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
    ): SellResult? {
        if (!filterSettings.jupiterEnabled) return null
        if (percent <= 0) return null
        val signer = getSigner() ?: run {
            println("❌ Jupiter sell: seed phrase empty or invalid")
            return null
        }
        val inputMint = token.tokenPair.baseToken?.address ?: return null
        val sellAmount = (amountBase * (percent / 100.0)).toLong().coerceAtLeast(1L)

        val quote = jupiterApi.getQuote(
            inputMint = inputMint,
            outputMint = filterSettings.baseMint,
            amount = sellAmount,
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
            println("⚠️ Jupiter sell tx not confirmed: $txId")
            return null
        }

        val outAmountStr = quote["outAmount"]?.jsonPrimitive?.content
        val outAmount = outAmountStr?.toLongOrNull() ?: 0L
        val solPrice = getSolPriceUsdWithFallback() ?: 0.0
        val costLamports = (token.buySolLamports * (percent / 100.0)).toLong()
        val profitUsd = ((outAmount - costLamports).toDouble() / 1_000_000_000.0) * solPrice

        println("✅ Jupiter sell: in=${sellAmount} raw, out=${outAmount} lamports, tx=$txId (${percent.toInt()}%)")
        return SellResult(outLamports = outAmount, profitUsd = profitUsd)
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
        val entry = token.entryPrice
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

        // ════════════════════════════════════════════════════════════════════════════════
        // 🔥 AGGRESSIVE MODE: одна фиксация по % прибыли, остальное trailing
        // ════════════════════════════════════════════════════════════════════════════════
        // Срабатывает ОДИН РАЗ при достижении заданного % прибыли по цене
        if (isAggressive && !stage1Done && priceChangePercent >= filterSettings.aggressiveTakeProfitPct) {
            val pct = filterSettings.aggressiveSellPct.coerceIn(1.0, 100.0)
            val sellResult = if (filterSettings.jupiterEnabled) {
                sellTokenPercent(token, tokenAmountRaw, pct, marketCap)
            } else null
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
            val sellResult = if (filterSettings.jupiterEnabled) {
                sellTokenPercent(token, tokenAmountRaw, pct, marketCap)
            } else null
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
            val sellResult = if (filterSettings.jupiterEnabled) {
                sellTokenPercent(token, tokenAmountRaw, pct, marketCap)
            } else null
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
            val sellResult = if (filterSettings.jupiterEnabled) {
                sellTokenPercent(token, tokenAmountRaw, pct, marketCap)
            } else null
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
            val sellResult = if (filterSettings.jupiterEnabled) {
                sellTokenPercent(token, tokenAmountRaw, pct, marketCap)
            } else null
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

        val forcedExit = forcedExitByStopLoss || forcedExitByTrailing || forcedExitByStagePullback
        var forcedExitExecuted = !forcedExit

        // ──── ПРИНУДИТЕЛЬНЫЙ ВЫХОД (продажа остатка) ────
        if (forcedExit) {
            val percentToSell = remainingPct.coerceAtMost(100.0)
            if (filterSettings.jupiterEnabled && tokenAmountRaw > 0) {
                val sellResult = sellTokenPercent(token, tokenAmountRaw, percentToSell, marketCap)
                if (sellResult != null) {
                    realizedProfitUsd += sellResult.profitUsd
                    tokenAmountRaw = 0L
                    remainingPct = 0.0
                } else {
                    forcedExitExecuted = false
                }
            } else {
                remainingPct = 0.0
            }
            when {
                forcedExitByStopLoss -> println("🛑 Forced exit: -30% от входной капы")
                forcedExitByTrailing -> println("🛑 Forced exit: -35% от локального максимума")
                forcedExitByStagePullback -> println("🛑 Forced exit: -35% после Stage")
            }
        }

        val positionFullyClosed = remainingPct <= 0.0

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
            positionFullyClosed -> TokenStatus.STOPPED_TP
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
            exitStage4Done = stage4Done
        )

        listMutex.withLock {
            val index = _monitoredTokens.indexOfFirst {
                it.tokenPair.pairAddress == token.tokenPair.pairAddress
            }

            if (index != -1) {
                // 💾 Сохраняем в историю если достигнут TP или SL
                if (newStatus != TokenStatus.MONITORING) {
                    val skipFinalHistory = closedByFullStageExit && !forcedExit
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
            val activeOnly = cached.filter { it.status == TokenStatus.MONITORING }
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

    // Закрытие ресурсов
    fun close() {
        stopMonitoring()
        api.close()
        jupiterApi.close()
        rpcClient?.close()
        println("🔌 Ресурсы закрыты")
    }
}