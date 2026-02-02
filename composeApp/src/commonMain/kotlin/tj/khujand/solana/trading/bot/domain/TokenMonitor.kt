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
    var sessionHighPrice: Double = 0.0    // максимум цены с момента добавления (для авто-стопа)
)

@Serializable
enum class TokenStatus {
    MONITORING,    // В мониторинге
    STOPPED_TP,    // Остановлен по тейк-профиту (+30%)
    STOPPED_SL     // Остановлен по стоп-лоссу (-25%)
}

class TokenMonitor {
    private  val CACHE_KEY_TOKENS = "cached_monitored_tokens"

    private var allowNewTokenDiscovery = true  // 🔴 Добавляем флаг

    private val api = DexScreenerApi()
    private var rpcClient: SolanaRpcClient? = null
    private var monitorJob: Job? = null
    private var isMonitoring = false
    private val maxParallelUpdates = 3
    private val updateSemaphore = Semaphore(maxParallelUpdates)
    private val listMutex = Mutex()

    // Список отслеживаемых токенов
    private val _monitoredTokens = mutableStateListOf<MonitoredToken>()
    val monitoredTokens: List<MonitoredToken> get() = _monitoredTokens

    // Кулдаун: pairAddress -> timestamp до которого токен нельзя снова добавлять (после TP/SL)
    private val cooldownUntil = mutableMapOf<String, Long>()

    // Счётчик: сколько раз каждый токен уже был закрыт (TP/SL). Лимит повторных входов задаётся в настройках.
    private val closedCount = mutableMapOf<String, Int>()

    // Настройки по умолчанию
    var filterSettings = FilterSettings()

    // 🔄 Запуск автоматического мониторинга
    fun startMonitoring(
        intervalSeconds: Int = 30,
        onNewTokenFound: (MonitoredToken) -> Unit = {},
        onTokenUpdated: (MonitoredToken) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isMonitoring) {
            println("⚠️ Мониторинг уже запущен")
            return
        }

        isMonitoring = true
        allowNewTokenDiscovery = true  // 🔴 Сбрасываем флаг
        println("🚀 Запуск мониторинга с фильтрами:")
        println("   - Минимальная ликвидность: ${filterSettings.liquidityMinUsd} USD")
        println("   - Минимальный объем 24ч: ${filterSettings.volumeH24MinUsd} USD")
        println("   - Максимальный возраст пары: ${filterSettings.pairMaxAgeHours} часов")
        println("   - Минимум покупок H1: ${filterSettings.buysH1Min}")
        println("   - Максимальное соотношение продаж/покупок: ${filterSettings.maxSellsToBuysRatioH1}")
        println("   - Минимальный балл для принятия: ${filterSettings.minScoreAccept}")

        monitorJob = CoroutineScope(Dispatchers.Default).launch {
            var cycleCount = 0
            while (isMonitoring) {
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
                        println("📡 Запрос новых токенов...")
                        try {
                            val newTokens = api.getNewTokens(filterSettings)
                            println("📊 Получено токенов: ${newTokens.size}")

                            if (newTokens.isEmpty()) {
                                println("⚠️ Нет новых токенов, проверьте фильтры или подключение к интернету")
                                // Не вызываем onError для пустого результата - это нормально
                            }

                            // Фильтрация с проверкой SPL Mint и подсчетом очков
                            val filteredTokens = filterTokensWithScoring(newTokens)
                            println("✅ После фильтрации: ${filteredTokens.size}")

                            // 🔴 КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: проверяем лимит ПЕРЕД добавлением
                            var addedCount = 0
                            for (token in filteredTokens) {
                            val pairAddress = token.pairAddress ?: continue
                            // 1. Проверяем дубликаты
                            val exists = _monitoredTokens.any {
                                it.tokenPair.pairAddress == pairAddress
                            }

                            // 2. Пропускаем токены в кулдауне (недавно закрыты по TP/SL)
                            if (isInCooldown(pairAddress)) {
                                continue
                            }

                            // 3. Проверяем лимит повторных входов после TP/SL
                            if (!canReenterAfterClose(pairAddress)) {
                                continue
                            }

                            if (!exists) {
                                // 4. Проверяем лимит токенов
                                if (monitoringCount() >= filterSettings.maxTokensToMonitor) {
                                    println("⏸️ Достигнут лимит ${filterSettings.maxTokensToMonitor} токенов. Поиск приостановлен.")
                                    allowNewTokenDiscovery = false
                                    break  // 🛑 Прерываем добавление новых
                                }

                                // 5. Добавляем токен
                                val price = parsePrice(token.priceUsd)
                                if (price > 0) {
                                    val monitoredToken = MonitoredToken(
                                        tokenPair = token,
                                        entryPrice = price,
                                        currentPrice = token.priceUsd.toString(),
                                        ageToken = token.pairCreatedAt.toString(),
                                        sessionHighPrice = price
                                    )

                                    _monitoredTokens.add(monitoredToken)
                                    onNewTokenFound(monitoredToken)
                                    addedCount++
                                    println("➕ Добавлен: ${token.baseToken?.symbol ?: "Unknown"} ($${price}) [${_monitoredTokens.size}/${filterSettings.maxTokensToMonitor}]")
                                }
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
                            
                            val updatedToken = api.updateTokenPrice(monitoredToken.tokenPair)
                            if (updatedToken != null) {
                                val newPrice = parsePrice(updatedToken.priceUsd)
                                if (newPrice > 0) {
                                    updateTokenPrice(monitoredToken, newPrice, onUpdate)
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
                
                // Сохраняем в историю
                TokenHistoryManager.saveToHistory(updatedToken)
                token.tokenPair.pairAddress?.let {
                    putCooldown(it)
                    incrementClosedCount(it)
                }

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
        
        // 1. Закрываем все активные токены (сохраняем в историю P&L и добавляем в кулдаун)
        val activeTokens = _monitoredTokens.filter { it.status == TokenStatus.MONITORING }
        activeTokens.forEach { token ->
            token.tokenPair.pairAddress?.let {
                putCooldown(it)
                incrementClosedCount(it)
            }
            val isProfit = token.profitUsd >= 0
            val closedToken = token.copy(
                status = if (isProfit) TokenStatus.STOPPED_TP else TokenStatus.STOPPED_SL
            )
            TokenHistoryManager.saveToHistory(closedToken)
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
        println("   ⚠️ Кулдаун НЕ очищен (закрытые токены не будут добавлены снова до истечения времени)")
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

    // 🔍 Фильтрация токенов по критериям с проверкой SPL Mint и подсчетом очков
    private suspend fun filterTokensWithScoring(tokens: List<TokenPair>): List<TokenPair> {
        return coroutineScope {
            tokens.map { token ->
                async {
                    // Базовые проверки
                    val symbol = token.baseToken?.symbol ?: ""
                    val price = token.priceUsd ?: ""
                    if (symbol.isBlank() || price.isBlank()) return@async null

                    // Проверка возраста
                    if (!checkTokenAge(token)) return@async null

                    // Проверка на скам
                    if (filterSettings.excludeRugPull && isPotentialScam(token)) {
                        return@async null
                    }

                    // Проверка базовых фильтров
                    val volumeH24 = token.volume?.h24 ?: 0.0
                    val liquidity = token.liquidity?.usd ?: 0.0
                    
                    if (volumeH24 < filterSettings.volumeH24MinUsd) {
                        println("❌ Токен ${token.baseToken?.symbol} отклонен: объем $volumeH24 < ${filterSettings.volumeH24MinUsd}")
                        return@async null
                    }
                    if (liquidity < filterSettings.liquidityMinUsd) {
                        println("❌ Токен ${token.baseToken?.symbol} отклонен: ликвидность $liquidity < ${filterSettings.liquidityMinUsd}")
                        return@async null
                    }

                    // Проверка покупок H1
                    val buysH1 = token.txns?.h1?.buys ?: 0
                    if (buysH1 < filterSettings.buysH1Min) return@async null

                    // Проверка соотношения продаж/покупок H1
                    val sellsH1 = token.txns?.h1?.sells ?: 0
                    if (buysH1 > 0) {
                        val sellBuyRatio = sellsH1.toDouble() / buysH1
                        if (sellBuyRatio > filterSettings.maxSellsToBuysRatioH1) return@async null
                    }

                    // Получаем SPL Mint информацию
                    val mintAddress = token.baseToken?.address
                    val mintInfo = if (!mintAddress.isNullOrBlank()) {
                        val rpc = rpcClient ?: SolanaRpcClient(
                            rpcUrl = filterSettings.rpcUrl,
                            timeoutSeconds = filterSettings.rpcTimeoutSeconds
                        ).also { rpcClient = it }
                        rpc.getSplMintInfo(mintAddress)
                    } else null

                    // Подсчитываем очки
                    val scoreResult = TokenScoring.calculateScore(token, mintInfo, filterSettings)

                    // Проверяем условие принятия
                    if (!scoreResult.isAccepted) {
                        println("❌ Токен ${token.baseToken?.symbol} отклонен: score=${scoreResult.totalScore}, reasons=${scoreResult.reasons}, hardReject=${scoreResult.hardRejectReasons}")
                        return@async null
                    }

                    println("✅ Токен ${token.baseToken?.symbol} принят: score=${scoreResult.totalScore}, reasons=${scoreResult.reasons}")
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

    /** Добавить токен в кулдаун после TP/SL (не добавлять снова в мониторинг до истечения времени). */
    private fun putCooldown(pairAddress: String) {
        val minutes = filterSettings.cooldownMinutes
        if (minutes <= 0) return
        val untilMs = Clock.System.now().toEpochMilliseconds() + minutes * 60L * 1000L
        cooldownUntil[pairAddress] = untilMs
        println("⏳ Кулдаун: ${pairAddress.take(8)}... не добавлять в мониторинг ${minutes} мин")
    }

    /** Проверка: токен ещё в кулдауне? Очищает истёкшие записи. */
    private fun isInCooldown(pairAddress: String): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val expired = cooldownUntil.filter { it.value <= now }.keys.toList()
        expired.forEach { cooldownUntil.remove(it) }
        val until = cooldownUntil[pairAddress] ?: return false
        return now < until
    }

    /** Увеличить счётчик закрытий по TP/SL для пары (вызывать при каждом закрытии). */
    private fun incrementClosedCount(pairAddress: String) {
        closedCount[pairAddress] = (closedCount[pairAddress] ?: 0) + 1
        println("📊 Закрытий по TP/SL для пары: ${closedCount[pairAddress]} (лимит повторов: ${filterSettings.maxReentriesAfterClose})")
    }

    /** Можно ли снова добавить токен в мониторинг после TP/SL (лимит повторных входов). */
    private fun canReenterAfterClose(pairAddress: String): Boolean {
        val count = closedCount[pairAddress] ?: 0
        val allowed = count <= filterSettings.maxReentriesAfterClose
        if (!allowed) {
            println("⛔ Токен $pairAddress пропущен: уже закрыт ${count} раз(а), лимит повторов = ${filterSettings.maxReentriesAfterClose}")
        }
        return allowed
    }

    private fun refreshDiscoveryFlag() {
        allowNewTokenDiscovery = monitoringCount() < filterSettings.maxTokensToMonitor
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
                val updatedToken = api.updateTokenPrice(monitoredToken.tokenPair)
                if (updatedToken != null) {
                    val newPrice = parsePrice(updatedToken.priceUsd)
                    if (newPrice > 0) {
                        updateTokenPrice(monitoredToken, newPrice, onUpdate)
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

        var newStatus = when {
            priceChangePercent >= 30 -> TokenStatus.STOPPED_TP
            priceChangePercent <= -25 -> TokenStatus.STOPPED_SL
            else -> TokenStatus.MONITORING
        }

        // Авто-стоп при резком падении (если ещё в мониторинге)
        if (newStatus == TokenStatus.MONITORING && filterSettings.autoStopEnabled && filterSettings.autoStopDropPercent > 0) {
            val reference = if (filterSettings.autoStopFromPeak) newSessionHigh else entry
            if (reference > 0 && newPrice <= reference * (1 - filterSettings.autoStopDropPercent / 100.0)) {
                newStatus = TokenStatus.STOPPED_SL
                println("🛑 Авто-стоп: ${token.tokenPair.baseToken?.symbol} упал на ${filterSettings.autoStopDropPercent}% от ${if (filterSettings.autoStopFromPeak) "пика" else "входа"} ($$newPrice <= $reference)")
            }
        }

        val updatedToken = token.copy(
            currentPrice = newPrice.toString(),
            priceChangePercent = priceChangePercent,
            profitUsd = profitUsd,
            status = newStatus,
            sessionHighPrice = newSessionHigh
        )

        listMutex.withLock {
            val index = _monitoredTokens.indexOfFirst {
                it.tokenPair.pairAddress == token.tokenPair.pairAddress
            }

            if (index != -1) {
                // 💾 Сохраняем в историю если достигнут TP или SL
                if (newStatus != TokenStatus.MONITORING) {
                    TokenHistoryManager.saveToHistory(updatedToken)
                    token.tokenPair.pairAddress?.let {
                        putCooldown(it)
                        incrementClosedCount(it)
                    }
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
        rpcClient?.close()
        println("🔌 Ресурсы закрыты")
    }
}