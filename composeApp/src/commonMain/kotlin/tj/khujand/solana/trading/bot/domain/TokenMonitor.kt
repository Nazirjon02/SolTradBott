package tj.khujand.solana.trading.bot.domain

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import tj.khujand.solana.trading.bot.network.*
import tj.khujand.solana.trading.bot.util.AppSettings
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// Создаем недостающие модели прямо здесь
@Serializable
data class MonitoredToken(
    val tokenPair: TokenPair,
    val foundTime: Long = Clock.System.now().toEpochMilliseconds(),
    val ageToken: String = "0.0",
    val entryPrice: Double = 0.0,
    var currentPrice: String = "0.0",
    var priceChangePercent: Double = 0.0,
    var profitUsd: Double = 0.0,          // 👈 ВАЖНО
    var status: TokenStatus = TokenStatus.MONITORING,
    var sessionHighPrice: Double = 0.0,    // максимум цены с момента добавления
    var entryMarketCap: Double = 0.0,
    var peakMarketCap: Double = 0.0,
    var lastMarketCap: Double = 0.0,
    var remainingPositionPct: Double = 100.0,
    var exitStage1Done: Boolean = false,
    var exitStage2Done: Boolean = false,
    var exitStage3Done: Boolean = false,
    var exitStage4Done: Boolean = false
)

@Serializable
enum class TokenStatus {
    MONITORING,    // В мониторинге
    STOPPED_TP,    // Остановлен по тейк-профиту (+30%)
    STOPPED_SL     // Остановлен по стоп-лоссу (-25%)
}

class TokenMonitor {
    private  val CACHE_KEY_TOKENS = "cached_monitored_tokens"
    private val CLOSED_TOKENS_KEY = "closed_tokens_v1"

    private var allowNewTokenDiscovery = true  // 🔴 Добавляем флаг

    private val api = DexScreenerApi()
    private var monitorJob: Job? = null
    private var isMonitoring = false
    private val maxParallelUpdates = 2
    private val updateSemaphore = Semaphore(maxParallelUpdates)
    private val listMutex = Mutex()
    private val closedTokenAddresses = mutableSetOf<String>()

    // Список отслеживаемых токенов
    private val _monitoredTokens = mutableStateListOf<MonitoredToken>()
    val monitoredTokens: List<MonitoredToken> get() = _monitoredTokens

    // Настройки по умолчанию
    var filterSettings = FilterSettings()

    init {
        restoreClosedTokens()
    }

    // 🔄 Запуск автоматического мониторинга
    fun startMonitoring(
        intervalSeconds: Int = 30,
        onNewTokenFound: (MonitoredToken) -> Unit = {},
        onTokenUpdated: (MonitoredToken) -> Unit = {},
        onRequestStateChanged: (Boolean) -> Unit = {},
        onError: (String) -> Unit = {}
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
                        cycleCount % 5 == 0
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

                                // 4. Добавляем токен
                                val price = parsePrice(token.priceUsd)
                                if (price > 0) {
                                    val entryCap = token.marketCap ?: 0.0
                                    val monitoredToken = MonitoredToken(
                                        tokenPair = token,
                                        entryPrice = price,
                                        currentPrice = token.priceUsd.toString(),
                                        ageToken = token.pairCreatedAt.toString(),
                                        sessionHighPrice = price,
                                        entryMarketCap = entryCap,
                                        peakMarketCap = entryCap,
                                        lastMarketCap = token.marketCap ?: 0.0,
                                        remainingPositionPct = 100.0
                                    )

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
                            delay(80)
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
    fun closeTokenManually(pairAddress: String, isProfit: Boolean = true) {
        val index = _monitoredTokens.indexOfFirst { it.tokenPair.pairAddress == pairAddress }
        if (index != -1) {
            val token = _monitoredTokens[index]
            if (token.status == TokenStatus.MONITORING) {
                // Определяем статус на основе текущей прибыли
                val newStatus = if (isProfit || token.profitUsd >= 0) {
                    TokenStatus.STOPPED_TP
                } else {
                    TokenStatus.STOPPED_SL
                }
                
                val updatedToken = token.copy(status = newStatus)
                
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
    fun clearAllTokens() {
        println("🗑️ Очистка всех токенов...")
        
        // 1. Закрываем все активные токены (сохраняем в историю P&L)
        val activeTokens = _monitoredTokens.filter { it.status == TokenStatus.MONITORING }
        activeTokens.forEach { token ->
            val isProfit = token.profitUsd >= 0
            val closedToken = token.copy(
                status = if (isProfit) TokenStatus.STOPPED_TP else TokenStatus.STOPPED_SL
            )
            addClosedToken(closedToken)
            println("💾 Токен ${token.tokenPair.baseToken?.symbol} закрыт и сохранен в историю")
        }
        
        // 2. Очищаем список мониторинга
        val tokensCount = _monitoredTokens.size
        _monitoredTokens.clear()
        allowNewTokenDiscovery = true  // 🔴 Сбрасываем флаг
        
        // 3. Очищаем кеш мониторинга
        AppSettings.remove(CACHE_KEY_TOKENS)
        
        println("✅ Очищено:")
        println("   - Токенов в мониторинге: $tokensCount")
        println("   - Закрыто и сохранено в историю: ${activeTokens.size}")
        println("   - Кеш мониторинга")
        println("   ⚠️ История P&L НЕ очищена (используйте Clear в экране P&L)")
        println("   ⚠️ Закрытые токены не будут добавлены снова")
        println("   ⚠️ Настройки фильтров НЕ затронуты")
    }

    // ⏹️ Остановка мониторинга
    fun stopMonitoring() {
        println("⏹️ Остановка мониторинга...")
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        println("✅ Мониторинг остановлен")
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

    private fun addClosedToken(token: MonitoredToken, skipHistory: Boolean = false) {
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

        val volumeH24 = token.volume?.h24 ?: return false
        if (volumeH24 < filterSettings.entryMinVolume) return false

        val hasWebsite = token.info?.websites?.any { !it.url.isNullOrBlank() } == true
        val hasSocials = token.info?.socials?.any { !it.url.isNullOrBlank() } == true
        if (filterSettings.requireWebsite && !hasWebsite) return false
        if (filterSettings.requireSocials && !hasSocials) return false

        return true
    }

    private fun getPairCreatedAtMs(pairCreatedAt: Long?): Long? {
        if (pairCreatedAt == null || pairCreatedAt <= 0) return null
        return if (pairCreatedAt < 1_000_000_000_000L) pairCreatedAt * 1000 else pairCreatedAt
    }

    // ⏰ Проверка возраста токена
    private fun checkTokenAge(token: TokenPair): Boolean {
        return token.pairCreatedAt?.let { createdAt ->
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

    // 🔄 Обновление цены токена (suspend для безопасного доступа к списку при параллельном обновлении)
    private suspend fun updateTokenPrice(
        token: MonitoredToken,
        updatedPair: TokenPair,
        newPrice: Double,
        onUpdate: (MonitoredToken) -> Unit
    ) {
        val entry = token.entryPrice
        val prevHigh = if (token.sessionHighPrice > 0) token.sessionHighPrice else entry
        val newSessionHigh = maxOf(prevHigh, newPrice)

        val priceChangePercent =
            if (entry > 0) ((newPrice - entry) / entry) * 100 else 0.0

        val investment = 100.0
        val profitUsd = investment * priceChangePercent / 100

        val marketCap = updatedPair.marketCap ?: token.lastMarketCap
        var remainingPct = token.remainingPositionPct
        var stage1Done = token.exitStage1Done
        var stage2Done = token.exitStage2Done
        var stage3Done = token.exitStage3Done
        var stage4Done = token.exitStage4Done
        val entryCap = token.entryMarketCap
        val newPeakMarketCap = if (marketCap > token.peakMarketCap) marketCap else token.peakMarketCap

        if (!stage1Done && marketCap >= filterSettings.exitStage1Cap) {
            remainingPct -= filterSettings.exitStage1Pct
            stage1Done = true
            println("✅ Этап 1: фиксация ${filterSettings.exitStage1Pct.toInt()}% при Market Cap ${filterSettings.exitStage1Cap.toInt()}")
            TokenHistoryManager.savePartialExit(token, "Stage 1", filterSettings.exitStage1Pct, marketCap, newPrice)
        }
        if (!stage2Done && marketCap >= filterSettings.exitStage2Cap) {
            remainingPct -= filterSettings.exitStage2Pct
            stage2Done = true
            println("✅ Этап 2: фиксация ${filterSettings.exitStage2Pct.toInt()}% при Market Cap ${filterSettings.exitStage2Cap.toInt()}")
            TokenHistoryManager.savePartialExit(token, "Stage 2", filterSettings.exitStage2Pct, marketCap, newPrice)
        }
        if (!stage3Done && marketCap >= filterSettings.exitStage3Cap) {
            remainingPct -= filterSettings.exitStage3Pct
            stage3Done = true
            println("✅ Этап 3: фиксация ${filterSettings.exitStage3Pct.toInt()}% при Market Cap ${filterSettings.exitStage3Cap.toInt()}")
            TokenHistoryManager.savePartialExit(token, "Stage 3", filterSettings.exitStage3Pct, marketCap, newPrice)
        }
        if (!stage4Done && marketCap >= filterSettings.exitStage4Cap) {
            remainingPct -= filterSettings.exitStage4Pct
            stage4Done = true
            println("✅ Этап 4: фиксация ${filterSettings.exitStage4Pct.toInt()}% при Market Cap ${filterSettings.exitStage4Cap.toInt()}")
            TokenHistoryManager.savePartialExit(token, "Stage 4", filterSettings.exitStage4Pct, marketCap, newPrice)
        }

        if (remainingPct < 0) remainingPct = 0.0

        val trailingEnabled = stage2Done || marketCap >= filterSettings.exitStage2Cap
        val forcedExitByStopLoss = entryCap > 0 && marketCap <= entryCap * 0.70
        val forcedExitByBreakEven = stage1Done && entryCap > 0 && marketCap < entryCap
        val forcedExitByTrailing = trailingEnabled && newPeakMarketCap > 0 && marketCap <= newPeakMarketCap * 0.80

        val forcedExit = forcedExitByStopLoss || forcedExitByBreakEven || forcedExitByTrailing
        val closedByStage4 = stage4Done || remainingPct <= 0.0

        val newStatus = when {
            forcedExit -> TokenStatus.STOPPED_SL
            closedByStage4 -> TokenStatus.STOPPED_TP
            else -> TokenStatus.MONITORING
        }

        if (forcedExit) {
            remainingPct = 0.0
            when {
                forcedExitByStopLoss -> println("🛑 Forced exit: -30% от входной капы")
                forcedExitByBreakEven -> println("🛑 Forced exit: Break-even после Stage 1")
                forcedExitByTrailing -> println("🛑 Forced exit: -20% от локального максимума")
            }
        }

        val updatedToken = token.copy(
            tokenPair = updatedPair,
            currentPrice = newPrice.toString(),
            priceChangePercent = priceChangePercent,
            profitUsd = profitUsd,
            status = newStatus,
            sessionHighPrice = newSessionHigh,
            peakMarketCap = newPeakMarketCap,
            lastMarketCap = marketCap,
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
                    addClosedToken(updatedToken, skipHistory = closedByStage4)
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
            _monitoredTokens.clear()
            _monitoredTokens.addAll(cached)
            allowNewTokenDiscovery =
                monitoringCount() < filterSettings.maxTokensToMonitor

            println("♻️ Восстановлено токенов из кеша: ${cached.size}")
        }
    }


    // 🎯 Проверка условий для остановки мониторинга
    private fun checkStopConditions(token: MonitoredToken) {
        when {
            token.priceChangePercent >= 30 -> {
                token.status = TokenStatus.STOPPED_TP
                println("🎯 ТЕЙК-ПРОФИТ: ${token.tokenPair.baseToken?.symbol} +${token.priceChangePercent.format(2)}%")
            }
            token.priceChangePercent <= -25 -> {
                token.status = TokenStatus.STOPPED_SL
                println("💥 СТОП-ЛОСС: ${token.tokenPair.baseToken?.symbol} ${token.priceChangePercent.format(2)}%")
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



    // Вспомогательная функция для форматирования чисел
    private fun Double.format(decimals: Int): String {
        return decimals.toString()
    }

    private fun abs(value: Double): Double {
        return if (value < 0) -value else value
    }

    // Закрытие ресурсов
    fun close() {
        stopMonitoring()
        api.close()
        println("🔌 Ресурсы закрыты")
    }
}