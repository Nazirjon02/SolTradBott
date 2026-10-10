package tj.khujand.solana.trading.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import tj.khujand.solana.trading.bot.domain.dars.DarsConfig
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.domain.dars.MarketContext
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Экран «Анализ» и график реально рисуются — на настоящем Skia, без окна (как в MRX).
 *
 * Обычные тесты проверяют арифметику разметки, но не отрисовку: в MRX первая версия графика
 * падала на `coerceIn` в карточке, зажатой до нескольких пикселей, и ни один тест этого не
 * видел. Здесь рендерим карточку, график в тесных размерах и экран целиком и проверяем, что
 * ничего не бросает и картинка не пустая. PNG складываются в `build/analysis-render/` —
 * на них удобно посмотреть глазами после правок вёрстки.
 */
class AnalysisRenderTest {

    private val cfg = DarsConfig(
        higherTf = "hour", higherTfAggregate = 1,
        entryTf = "minute", entryTfAggregate = 5,
        candleLimit = 200,
        swingPivotPct = 1.5,
        requireHtfTrend = true,
        dominanceRatio = 1.5,
        minCorrectionLenPct = 70.0,
        rejectAtResistance = true,
        resistanceProximityPct = 2.0,
        minLegs = 4,
        failClosed = false,
        useImpulseCorrection = true,
        useTrendLevels = true,
        useFalseBreakout = true,
        useTriangle = true,
    )

    /**
     * Мемкоин за ~0.00001$: боковик, импульс, коррекция, импульс, коррекция и бычий бар.
     * Шум меньше порога свинга (1.5%), чтобы структура читалась так, как задумана. Первый
     * импульс крутой (бар > 1.5%): ZigZag в [LegSegmenter] до первого направления сдвигает
     * стартовую точку на каждом баре и пологий старт не распознаёт.
     */
    private fun memeCandles(): List<Candle> {
        val closes = mutableListOf<Double>()
        var p = 0.00001
        fun leg(bars: Int, totalPct: Double) {
            val step = Math.pow(1 + totalPct / 100.0, 1.0 / bars)
            repeat(bars) { p *= step; closes += p }
        }
        repeat(40) { i -> closes += p * (1 + 0.003 * sin(i * 1.7)) }
        leg(10, 35.0); leg(8, -10.0); leg(8, 26.0); leg(7, -9.0); leg(1, 2.5)
        return closes.mapIndexed { i, close ->
            val prev = if (i == 0) close else closes[i - 1]
            val up = close >= prev
            val wiggle = 1 + 0.002 * (1 + sin(i * 2.3))
            Candle(
                openTimeMs = 1_760_000_000_000L + i * 300_000L,
                open = prev,
                high = maxOf(prev, close) * wiggle,
                low = minOf(prev, close) / wiggle,
                close = close,
                volume = if (up) 9_000.0 + 3_000 * sin(i * 0.9) else 2_500.0 + 800 * sin(i * 1.3),
            )
        }
    }

    private fun htf(): List<Candle> = (0..29).map { i ->
        val close = 0.000007 + i * (0.000011 / 29.0)
        Candle(1_760_000_000_000L - (30 - i) * 3_600_000L, close * 0.99, close * 1.01, close * 0.985, close, 50_000.0)
    }

    private val market = MarketContext(
        priceUsd = 0.0000135, changeM5Percent = 1.4, changeH1Percent = 6.2,
        liquidityUsd = 84_000.0, marketCap = 1_350_000.0, volumeH1Usd = 41_000.0,
        buysH1 = 312, sellsH1 = 198, tokenAgeMinutes = 2_900,
    )

    private fun candidate(pool: String, symbol: String) = TokenCandidate(
        mint = "$pool-mint", symbol = symbol, name = symbol, pairAddress = pool, dexId = "raydium",
        priceUsd = 0.0000135, liquidityUsd = 84_000.0, marketCap = 1_350_000.0, volumeH1Usd = 41_000.0,
        buysH1 = 312, sellsH1 = 198, priceChangeM5 = 1.4, priceChangeH1 = 6.2,
        tokenAgeMinutes = 2_900, score = 70.0, scannedAt = 0L,
    )

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = SurfaceCard)) {
                Column(Modifier.fillMaxSize().background(BgDark)) { content() }
            }
        }
        val bytes = try {
            scene.render() // первый кадр запускает эффекты, второй рисует их результат
            scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
        val dir = File("build/analysis-render").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(bytes)
        return bytes
    }

    @Test
    fun expandedCardWithChartRenders() {
        val candles = memeCandles()
        val a = MarketAnalysis.analyze("PEPE2", candles, htf(), market, cfg, mint = "",
            now = candles.last().openTimeMs + 120_000L)
        assertNotNull(a.chart, "Для рендера нужна разметка: ${a.blockers}")
        val png = render("card-expanded-phone", 780, 2600) {
            Column(Modifier.padding(16.dp)) {
                CoinAnalysisCard(a = a, candles = candles, startExpanded = true)
            }
        }
        assertTrue(png.size > 10_000, "Карточка отрисовалась пустой (${png.size} байт)")
    }

    /** График в заведомо тесных размерах не должен бросать (coerceIn на пустом диапазоне). */
    @Test
    fun chartSurvivesTinySizes() {
        val candles = memeCandles()
        val a = MarketAnalysis.analyze("PEPE2", candles, htf(), market, cfg, now = candles.last().openTimeMs + 120_000L)
        listOf(8 to 8, 60 to 30, 200 to 60).forEach { (w, h) ->
            render("chart-${w}x$h", w, h) { CandleChart(candles, a.chart, height = 4.dp, caption = "x", lastBarForming = true) }
        }
        render("chart-desktop", 1520, 600) {
            CandleChart(candles, a.chart, height = 220.dp, caption = a.chart?.let { setupCaption(it) })
        }
    }

    /** Экран целиком в обзоре: пульс, вкладки, фазы, карточки — из скана, который держит сервис. */
    @Test
    fun overviewScreenRenders() = runBlocking {
        val good = memeCandles()
        val service = MarketAnalyzerService(candleSource = { pool, tf, _, _ ->
            when {
                tf == "hour" -> htf()
                pool == "good" -> good
                else -> good.map { it.copy(close = it.open) } // боковик
            }
        })
        val list = listOf(candidate("good", "PEPE2"), candidate("flat", "FLATCAT"), candidate("flat2", "SLEEPY"))
        service.startScan(list, cfg)
        withTimeout(10_000) { while (service.scan.value?.finished != true) delay(20) }

        val png = render("screen-overview-phone", 780, 1700) {
            AnalysisScreen(candidates = list, analyzer = service, darsConfig = cfg, settingsStore = null)
        }
        assertTrue(png.size > 10_000, "Экран отрисовался пустым (${png.size} байт)")
        service.close()
    }
}
