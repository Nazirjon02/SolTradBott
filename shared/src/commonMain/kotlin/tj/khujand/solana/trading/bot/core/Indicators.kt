package tj.khujand.solana.trading.bot.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import tj.khujand.solana.trading.bot.exchange.dex.Candle

object Indicators {

    fun ema(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()
        val k = 2.0 / (period + 1)
        val result = mutableListOf(prices.take(period).average())
        for (i in period until prices.size) {
            result.add(prices[i] * k + result.last() * (1 - k))
        }
        return result
    }

    fun rsi(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period + 1) return emptyList()
        val changes = prices.zipWithNext { a, b -> b - a }
        val result = mutableListOf<Double>()
        // Окно ВКЛЮЧАЕТ изменение с индексом i: иначе последнее движение цены
        // не попадало в расчёт и RSI отставал ровно на одну свечу.
        for (i in period - 1 until changes.size) {
            val window = changes.subList(i - period + 1, i + 1)
            val gains = window.filter { it > 0 }.average().let { if (it.isNaN()) 0.0 else it }
            val losses = window.filter { it < 0 }.map { -it }.average().let { if (it.isNaN()) 0.0 else it }
            val rs = if (losses == 0.0) 100.0 else gains / losses
            result.add(100.0 - 100.0 / (1 + rs))
        }
        return result
    }

    data class MacdResult(
        val macdLine: List<Double>,
        val signalLine: List<Double>,
        val histogram: List<Double>
    )

    fun macd(prices: List<Double>, fast: Int, slow: Int, signal: Int): MacdResult {
        val emaFast = ema(prices, fast)
        val emaSlow = ema(prices, slow)
        if (emaFast.isEmpty() || emaSlow.isEmpty()) return MacdResult(emptyList(), emptyList(), emptyList())
        val diff = emaFast.size - emaSlow.size
        val macdLine = emaSlow.indices.map { emaFast[it + diff] - emaSlow[it] }
        val signalLine = ema(macdLine, signal)
        val macdOffset = macdLine.size - signalLine.size
        val histogram = signalLine.indices.map { macdLine[it + macdOffset] - signalLine[it] }
        return MacdResult(macdLine, signalLine, histogram)
    }

    data class BollingerResult(
        val upper: List<Double>,
        val middle: List<Double>,
        val lower: List<Double>
    )

    fun bollingerBands(prices: List<Double>, period: Int, deviation: Double): BollingerResult {
        val upper = mutableListOf<Double>()
        val middle = mutableListOf<Double>()
        val lower = mutableListOf<Double>()
        // Как и в rsi: окно включает текущую цену prices[i], иначе полосы отстают на бар.
        for (i in period - 1 until prices.size) {
            val window = prices.subList(i - period + 1, i + 1)
            val sma = window.average()
            val std = sqrt(window.map { (it - sma).pow(2) }.average())
            middle.add(sma)
            upper.add(sma + deviation * std)
            lower.add(sma - deviation * std)
        }
        return BollingerResult(upper, middle, lower)
    }

    fun atr(candles: List<Candle>, period: Int): List<Double> {
        val trueRanges = candles.zipWithNext { prev, curr ->
            maxOf(
                curr.high - curr.low,
                abs(curr.high - prev.close),
                abs(curr.low - prev.close)
            )
        }
        return ema(trueRanges, period)
    }
}
