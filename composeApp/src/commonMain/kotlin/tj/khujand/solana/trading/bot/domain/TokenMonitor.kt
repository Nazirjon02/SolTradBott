package tj.khujand.solana.trading.bot.domain

import kotlinx.coroutines.*
import tj.khujand.solana.trading.bot.network.*
import kotlin.time.Clock

// Создаем недостающие модели прямо здесь
data class MonitoredToken(
    val tokenPair: TokenPair,
    val foundTime: Long = Clock.System.now().toEpochMilliseconds(),
    val entryPrice: Double = 0.0,
    var currentPrice: String = "0.0",
    var priceChangePercent: Double = 0.0,
    var profitUsd: Double = 0.0,          // 👈 ВАЖНО
    var status: TokenStatus = TokenStatus.MONITORING
)

enum class TokenStatus {
    MONITORING,    // В мониторинге
    STOPPED_TP,    // Остановлен по тейк-профиту (+30%)
    STOPPED_SL     // Остановлен по стоп-лоссу (-25%)
}

class TokenMonitor {

    private val api = DexScreenerApi()
    private var monitorJob: Job? = null
    private var isMonitoring = false

    // Список отслеживаемых токенов
    private val _monitoredTokens = mutableListOf<MonitoredToken>()
    val monitoredTokens: List<MonitoredToken> get() = _monitoredTokens

    // Настройки по умолчанию
    var filterSettings = FilterSettings()

    // 🔄 Запуск автоматического мониторинга
    fun startMonitoring(
        intervalSeconds: Int = 30, // ⚠️ Увеличен интервал для соблюдения rate limits API
        onNewTokenFound: (MonitoredToken) -> Unit = {},
        onTokenUpdated: (MonitoredToken) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isMonitoring) {
            println("⚠️ Мониторинг уже запущен")
            return
        }

        isMonitoring = true
        println("🚀 Запуск мониторинга с фильтрами: $filterSettings")

        monitorJob = CoroutineScope(Dispatchers.Default).launch {
            while (isMonitoring) {
                try {
                    println("🔄 Цикл мониторинга начат...")

                    // 1. Получаем новые токены
                    println("📡 Запрос новых токенов...")
                    val newTokens = api.getNewTokens(filterSettings)
                    println("📊 Получено токенов: ${newTokens.size}")

                    if (newTokens.isEmpty()) {
                        println("⚠️ Нет новых токенов, проверьте фильтры")
                        onError("Нет новых токенов, проверьте фильтры (объем > ${filterSettings.minVolumeUSD})")
                    }

                    // 2. Фильтруем (уже сделано в API, но проверяем еще раз)
                    val filteredTokens = filterTokens(newTokens)
                    println("✅ После фильтрации: ${filteredTokens.size}")

                    // 3. Добавляем новые токены в мониторинг
                    var addedCount = 0
                    for (token in filteredTokens) {
                        // Проверяем, не отслеживаем ли уже этот токен
                        val exists = _monitoredTokens.any {
                            it.tokenPair.pairAddress == token.pairAddress
                        }

                        if (!exists) {
                            val price = parsePrice(token.priceUsd)
                            if (price > 0) { // Только токены с ценой
                                val monitoredToken = MonitoredToken(
                                    tokenPair = token,
                                    entryPrice = price,
                                    currentPrice = token.priceUsd.toString()
                                )

                                _monitoredTokens.add(monitoredToken)
                                onNewTokenFound(monitoredToken)
                                addedCount++
                                println("➕ Добавлен: ${token.baseToken?.symbol ?: "Unknown"} ($${price})")
                            }
                        }
                    }

                    if (addedCount > 0) {
                        println("🎯 Добавлено новых токенов: $addedCount")
                    }

                    // 4. Обновляем цены существующих токенов (если они есть)
                    if (_monitoredTokens.isNotEmpty()) {
                        updateMonitoredTokens(onTokenUpdated)
                    }

                    // 5. Ждем перед следующим циклом
                    println("⏳ Ожидание $intervalSeconds секунд...")
                    delay(intervalSeconds * 1000L)

                } catch (e: Exception) {
                    val errorMsg = "Ошибка мониторинга: ${e.message}"
                    println("❌ $errorMsg")
                    onError(errorMsg)
                    delay(15000) // Большая задержка при ошибке
                }
            }
            println("⏹️ Цикл мониторинга завершен")
        }
    }

    // ⏹️ Остановка мониторинга
    fun stopMonitoring() {
        println("⏹️ Остановка мониторинга...")
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        println("✅ Мониторинг остановлен")
    }

    // 🔍 Фильтрация токенов по критериям (ДОПОЛНИТЕЛЬНАЯ)
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

    // ⏰ Проверка возраста токена
    private fun checkTokenAge(token: TokenPair): Boolean {
        return token.pairCreatedAt?.let { createdAt ->
            val ageHours = (Clock.System.now().toEpochMilliseconds() - createdAt) / (1000 * 60 * 60)
            ageHours <= filterSettings.maxAgeHours
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

            } catch (e: Exception) {
                println("❌ Ошибка обновления токена ${monitoredToken.tokenPair.baseToken?.symbol}: ${e.message}")
            }
        }
    }

    // 🔄 Обновление цены токена
    private fun updateTokenPrice(
        token: MonitoredToken,
        newPrice: Double,
        onUpdate: (MonitoredToken) -> Unit
    ) {
        val entry = token.entryPrice

        token.currentPrice = newPrice.toString()

        if (entry > 0) {
            token.priceChangePercent = ((newPrice - entry) / entry) * 100

            // 💰 прибыль если купили на 100$
            val investment = 100.0
            token.profitUsd = investment * token.priceChangePercent / 100
        } else {
            token.priceChangePercent = 0.0
            token.profitUsd = 0.0
        }

        checkStopConditions(token)
        onUpdate(token)
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

    // 🗑️ Очистка всех токенов
    fun clearAllTokens() {
        println("🗑️ Очистка всех токенов (было: ${_monitoredTokens.size})")
        _monitoredTokens.clear()
    }

    // 🗑️ Удалить конкретный токен
    fun removeToken(pairAddress: String) {
        _monitoredTokens.removeAll { it.tokenPair.pairAddress == pairAddress }
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