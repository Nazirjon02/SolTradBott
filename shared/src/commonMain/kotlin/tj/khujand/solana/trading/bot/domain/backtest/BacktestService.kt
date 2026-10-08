package tj.khujand.solana.trading.bot.domain.backtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.exchange.dex.GtPoolList

/** Что грузить и как исполнять. */
data class BacktestRequest(
    val source: GtPoolList = GtPoolList.TRENDING,
    val coins: Int = 20,
    val days: Int = 7,
    val settings: BacktestSettings = BacktestSettings(),
)

/** Состояние бэктеста для UI. */
sealed interface BacktestState {
    data object Idle : BacktestState
    data class Loading(val message: String, val done: Int, val total: Int) : BacktestState
    data class Running(val done: Int, val total: Int) : BacktestState
    data class Done(val report: BacktestReport, val strategyId: String) : BacktestState
    data class Failed(val message: String) : BacktestState
}

/**
 * Запуск бэктеста в фоне (переживает уход с экрана). Загруженная история держится в памяти:
 * повторный прогон с изменёнными параметрами стратегии на тех же данных — без сети и мгновенно.
 * Данные перезагружаются, только если изменились источник, число монет, период или таймфреймы.
 */
class BacktestService(private val loader: BacktestDataLoader = BacktestDataLoader()) {

    private data class DatasetKey(
        val source: GtPoolList,
        val coins: Int,
        val days: Int,
        val entryTf: String,
        val higherTf: String?,
    )

    private class Dataset(val key: DatasetKey, val series: List<CoinSeries>)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow<BacktestState>(BacktestState.Idle)
    val state: StateFlow<BacktestState> = _state.asStateFlow()

    /** Последний запрос — экран восстанавливает настройки после перехода в форму стратегии. */
    var lastRequest: BacktestRequest = BacktestRequest()
        private set

    private var job: Job? = null
    private var dataset: Dataset? = null

    private fun keyOf(config: StrategyConfig, r: BacktestRequest) =
        DatasetKey(r.source, r.coins, r.days, config.timeframe, config.backtestHigherTf())

    /** История под такой запрос уже в памяти — перезапуск без загрузки. */
    fun hasData(config: StrategyConfig, request: BacktestRequest): Boolean =
        dataset?.key == keyOf(config, request)

    /** Оценка числа запросов к GeckoTerminal (страницы списка + свечи по монетам). */
    fun estimatedRequests(config: StrategyConfig, request: BacktestRequest): Int =
        (request.coins + POOLS_PER_PAGE - 1) / POOLS_PER_PAGE +
            request.coins * loader.requestsPerCoin(config.timeframe, config.backtestHigherTf(), request.days)

    fun start(config: StrategyConfig, request: BacktestRequest) {
        lastRequest = request
        job?.cancel()
        job = scope.launch {
            try {
                val key = keyOf(config, request)
                val series = dataset?.takeIf { it.key == key }?.series
                    ?: load(key).also { dataset = Dataset(key, it) }
                _state.value = BacktestState.Running(0, series.size)
                val report = Backtester.run(config, series, request.settings) { done, total ->
                    _state.value = BacktestState.Running(done, total)
                }
                _state.value = BacktestState.Done(report, config.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BacktestState.Failed(e.message ?: "ошибка бэктеста")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = BacktestState.Idle
    }

    private suspend fun load(key: DatasetKey): List<CoinSeries> {
        _state.value = BacktestState.Loading("Список монет…", 0, key.coins)
        val coins = loader.loadCoins(key.source, key.coins)
        if (coins.isEmpty()) error("GeckoTerminal не вернул монеты — проверьте сеть или повторите через минуту")
        return coins.mapIndexed { i, coin ->
            _state.value = BacktestState.Loading("Свечи ${coin.symbol}", i, coins.size)
            loader.loadSeries(coin, key.entryTf, key.higherTf, key.days)
        }
    }

    private companion object {
        /** Пулов на странице списка GeckoTerminal. */
        const val POOLS_PER_PAGE = 20
    }
}
