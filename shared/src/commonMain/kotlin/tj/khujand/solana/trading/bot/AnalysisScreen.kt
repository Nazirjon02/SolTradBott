package tj.khujand.solana.trading.bot

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tj.khujand.solana.trading.bot.core.strategy.StrategyConfig
import tj.khujand.solana.trading.bot.core.strategy.StrategyType
import tj.khujand.solana.trading.bot.core.strategy.toDarsFilterSettings
import tj.khujand.solana.trading.bot.data.FavoriteCoin
import tj.khujand.solana.trading.bot.data.SettingsStore
import tj.khujand.solana.trading.bot.domain.dars.AnalysisProgress
import tj.khujand.solana.trading.bot.domain.dars.AnalysisTiming
import tj.khujand.solana.trading.bot.domain.dars.CoinAnalysis
import tj.khujand.solana.trading.bot.domain.dars.DarsConfig
import tj.khujand.solana.trading.bot.domain.dars.LegCharacter
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalysis
import tj.khujand.solana.trading.bot.domain.dars.MarketAnalyzerService
import tj.khujand.solana.trading.bot.domain.dars.MarketPhase
import tj.khujand.solana.trading.bot.domain.dars.Readiness
import tj.khujand.solana.trading.bot.domain.dars.TrendDirection
import tj.khujand.solana.trading.bot.exchange.dex.Candle
import tj.khujand.solana.trading.bot.exchange.dex.TokenCandidate
import tj.khujand.solana.trading.bot.util.formatDexTime
import tj.khujand.solana.trading.bot.util.formatLargeNumber
import tj.khujand.solana.trading.bot.util.formatLocalTime
import kotlin.math.abs
import kotlin.time.Clock

// Экран «Анализ» (дизайн из MRX). Два режима, между которыми переключает поиск:
//
//   • ОБЗОР — пульс скана, фильтры и монеты сканера по баллу. Ответ на «что сейчас интересно».
//   • ФОКУС — одна монета: только её разбор, без списка, фильтров и хода скана. Когда спросили
//     про конкретную монету, всё остальное на экране — шум.
//
// Весь экран — одна LazyColumn: шапка, поиск и фильтры уезжают вместе со списком, а не съедают
// половину высоты на длинном разборе. Ширина колонки ограничена [PageMaxWidth], иначе на
// десктопе строки растягиваются через весь монитор и перестают читаться.
//
// Сам скан живёт в [MarketAnalyzerService.scan], а не в экране: вкладка пересоздаётся при каждом
// переходе, а разбор десятков монет упирается в лимит GeckoTerminal и стоит минут.

/** Предел ширины колонки контента — дальше строки становятся нечитаемо длинными. */
private val PageMaxWidth = 760.dp

/** Слот страницы: держит ширину контента и общие поля — все блоки стоят по одной сетке. */
@Composable
private fun PageSlot(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = 16.dp),
            content = content
        )
    }
}

/** Конфиг Dars для анализатора: активная DARS-стратегия → её пороги; иначе — дефолты. */
internal fun List<StrategyConfig>.activeDarsConfig(): DarsConfig {
    val cfg = firstOrNull { it.type == StrategyType.DARS.name && it.isActive }
        ?: firstOrNull { it.type == StrategyType.DARS.name }
        ?: StrategyConfig(id = "analysis-default")
    return DarsConfig.from(cfg.toDarsFilterSettings())
}

/** Как отсортирован список. Скан всегда отдаёт монеты по баллу, сортировка живёт в UI. */
private enum class TopSort(val label: String) {
    SCORE("По баллу"),
    LIQUIDITY("По ликвидности"),
    VOLUME("По обороту за 1ч"),
    CHANGE("По росту за 1ч"),
}

private fun List<CoinAnalysis>.sortedFor(sort: TopSort): List<CoinAnalysis> = when (sort) {
    TopSort.SCORE -> sortedByDescending { it.score }
    TopSort.LIQUIDITY -> sortedByDescending { it.market.liquidityUsd }
    TopSort.VOLUME -> sortedByDescending { it.market.volumeH1Usd }
    TopSort.CHANGE -> sortedByDescending { it.market.changeH1Percent }
}

/** Что показывает обзор: свежий скан или сохранённые снимки избранного. */
private enum class OverviewTab { SCAN, FAVORITES }

/** Чип быстрого разбора: что написать на чипе и что искать (mint однозначнее тикера). */
private data class QuickPick(val label: String, val query: String)

/**
 * Экран «Анализ монет» — информационное «второе мнение» по методике Dars (не торговля).
 * Разбирает кандидатов сканера через [MarketAnalyzerService] и любую монету по mint/тикеру:
 * тренд, фаза, готовность, зона входа, график с разметкой и что мешает войти прямо сейчас.
 */
@Composable
fun AnalysisScreen(
    candidates: List<TokenCandidate>,
    analyzer: MarketAnalyzerService?,
    darsConfig: DarsConfig,
    settingsStore: SettingsStore? = null,
) {
    val scope = rememberCoroutineScope()
    val noScan = remember { MutableStateFlow<AnalysisProgress?>(null) }
    val progress by (analyzer?.scan ?: noScan).collectAsState()
    val results = progress?.best.orEmpty()
    val loading = progress?.finished == false

    // Фильтры обзора: по фазе и по готовности (null — все). Это выбор пользователя, а не
    // состояние загрузки, поэтому держатся между кадрами скана.
    var phaseFilter by remember { mutableStateOf<MarketPhase?>(null) }
    var readyFilter by remember { mutableStateOf<Readiness?>(null) }
    var sort by remember { mutableStateOf(TopSort.SCORE) }
    var tab by remember { mutableStateOf(OverviewTab.SCAN) }

    // Сколько монет разбирать за раз (Int.MAX_VALUE = все кандидаты).
    var limit by remember { mutableStateOf(25) }
    val batch = if (limit == Int.MAX_VALUE) candidates else candidates.take(limit)

    // Итог последнего действия (избранное) — сверху, гаснет через 4с.
    var banner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(banner) {
        if (banner != null) { delay(4000); banner = null }
    }

    // ── Избранное ── Самодостаточные снимки из БД: видны без скана и когда монета ушла из сканера.
    var favorites by remember { mutableStateOf(settingsStore?.getFavorites() ?: emptyList()) }
    val favoriteMints = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.mint } }
    fun toggleFavorite(a: CoinAnalysis) {
        val store = settingsStore ?: return
        if (a.mint.isBlank()) return
        val nowSaved = !store.isFavorite(a.mint)
        if (nowSaved) store.upsertFavorite(a.toFavorite()) else store.removeFavorite(a.mint)
        favorites = store.getFavorites()
        banner = if (nowSaved) "★ ${a.symbol}: разбор сохранён в избранное" else "☆ ${a.symbol}: убрана из избранного"
    }
    fun removeFavorite(mint: String) {
        val store = settingsStore ?: return
        store.removeFavorite(mint)
        favorites = store.getFavorites()
    }
    /** Свежий разбор — повод обновить снимки уже избранных монет (addedAt store сохраняет). */
    fun refreshFavorites(reports: List<CoinAnalysis>) {
        val store = settingsStore ?: return
        val favMints = store.getFavoriteMints()
        val fresh = reports.filter { it.mint.isNotBlank() && it.mint in favMints }
        if (fresh.isEmpty()) return
        fresh.forEach { store.upsertFavorite(it.toFavorite()) }
        favorites = store.getFavorites()
    }
    LaunchedEffect(progress?.finished, progress?.done) {
        val p = progress ?: return@LaunchedEffect
        if (p.finished) refreshFavorites(p.best)
    }

    // ── Поиск ── Скан закрывает «что сейчас интересно», но часто нужна конкретная монета:
    // её ищем по mint или тикеру — среди кандидатов сканера, а если там нет, через DexScreener.
    var query by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<CoinAnalysis?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchedLabel by remember { mutableStateOf<String?>(null) }
    var searchedQuery by remember { mutableStateOf<String?>(null) }
    // Недавние разборы — чипами под поиском. Живут в памяти экрана: подсказка, а не данные.
    var recent by remember { mutableStateOf<List<QuickPick>>(emptyList()) }

    fun runSearch(raw: String, label: String? = null) {
        val svc = analyzer ?: return
        val q = raw.trim().replace(" ", "")
        if (q.isBlank()) return
        scope.launch {
            searching = true
            searchError = null
            searchResult = null
            searchedQuery = q
            searchedLabel = label ?: q.let { if (it.length > 12) "${it.take(4)}…${it.takeLast(4)}" else it }
            try {
                val a = svc.analyzeQuery(q, darsConfig, candidates)
                if (a == null) {
                    searchError = "Не нашёл «$q» на Solana — проверьте тикер или адрес монеты"
                } else {
                    searchResult = a
                    searchedLabel = a.symbol
                    val pick = QuickPick(a.symbol, a.mint.ifBlank { q })
                    recent = (listOf(pick) + recent.filter { it.query != pick.query }).take(5)
                    refreshFavorites(listOf(a))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                searchError = "Не удалось разобрать «$q» — нет связи с DexScreener или GeckoTerminal"
            }
            searching = false
        }
    }

    fun clearSearch() {
        query = ""
        searchResult = null
        searchError = null
        searchedLabel = null
        searchedQuery = null
        searching = false
    }

    val focus = searching || searchResult != null || searchError != null

    val shownResults = remember(results, phaseFilter, readyFilter, sort) {
        results
            .filter {
                (phaseFilter == null || it.phase == phaseFilter) &&
                    (readyFilter == null || it.readiness == readyFilter)
            }
            .sortedFor(sort)
    }
    val tfLabel = "${analysisTfShort(darsConfig.higherTf, darsConfig.higherTfAggregate)} → " +
        analysisTfShort(darsConfig.entryTf, darsConfig.entryTfAggregate)
    val scaleLabel = "$tfLabel · Dars" +
        (results.firstOrNull()?.timing?.analyzedAt?.let { " · ${formatLocalTime(it)}" } ?: "")

    Box(Modifier.fillMaxSize().background(BgDark)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Шапка: обзор или фокус ──
            item(key = "top-bar") {
                PageSlot {
                    if (focus) {
                        val sr = searchResult
                        FocusBar(
                            symbol = searchedLabel ?: query,
                            iconUrl = sr?.iconUrl,
                            price = sr?.let { MarketAnalysis.fmtPrice(it.price) },
                            scale = sr?.let { "${it.timeframeLabel} · разбор ${formatLocalTime(it.timing.analyzedAt)}" },
                            busy = searching,
                            saved = if (settingsStore == null || sr == null || sr.mint.isBlank()) null
                                    else sr.mint in favoriteMints,
                            onToggleFav = { sr?.let { toggleFavorite(it) } },
                            onBack = { clearSearch() },
                            onRefresh = { searchedQuery?.let { runSearch(it, searchedLabel) } }
                        )
                    } else {
                        OverviewHeader(
                            subtitle = scaleLabel,
                            loading = loading,
                            progress = progress,
                            enabled = analyzer != null && batch.isNotEmpty(),
                            onRefresh = { analyzer?.startScan(batch, darsConfig) }
                        )
                    }
                }
            }

            banner?.let { msg ->
                item(key = "banner") {
                    PageSlot {
                        Surface(color = GreenDim, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(msg, color = Green, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                }
            }

            if (focus) {
                // ── ФОКУС: только запрошенная монета ──
                val sr = searchResult
                val se = searchError
                when {
                    searching -> item(key = "focus-loading") {
                        PageSlot { SearchStatusCard("Разбираю ${searchedLabel ?: query}… (DexScreener → свечи GeckoTerminal)") }
                    }
                    se != null -> {
                        item(key = "focus-error") { PageSlot { SearchStatusCard(se, isError = true) } }
                        item(key = "focus-back") { PageSlot { BackToTopButton { clearSearch() } } }
                    }
                    sr != null -> {
                        item(key = "focus-verdict") {
                            PageSlot {
                                // Балл сам по себе — число из ниоткуда, а «выше, чем у 7 из 10»
                                // сразу ставит монету в контекст скана.
                                val others = results.filter { it.mint != sr.mint }
                                FocusVerdict(
                                    a = sr,
                                    rank = if (others.size >= 3)
                                        "балл выше, чем у ${others.count { it.score < sr.score }} из ${others.size} монет скана"
                                    else null
                                )
                            }
                        }
                        item(key = "focus-card") {
                            PageSlot {
                                CoinAnalysisCard(
                                    a = sr,
                                    // Символ, цена, балл и звезда уже стоят в шапке фокуса и в вердикте.
                                    showHeader = false,
                                    candles = analyzer?.cachedCandles(sr.mint),
                                    // Монету открыли специально — сразу весь разбор.
                                    startExpanded = true,
                                    chartHeight = 220.dp,
                                )
                            }
                        }
                        item(key = "focus-back") { PageSlot { BackToTopButton { clearSearch() } } }
                    }
                }
            } else if (analyzer == null) {
                item(key = "no-runtime") {
                    PageSlot {
                        NoticeCard("🔌 Анализатор недоступен", "Рантайм бота не запущен — свечи загрузить нечем.")
                    }
                }
            } else {
                // ── ОБЗОР: поиск, вкладки, пульс, фильтры, список ──
                item(key = "search") {
                    PageSlot {
                        AnalysisSearchField(
                            query = query,
                            searching = searching,
                            onQueryChange = { query = it },
                            onClear = { clearSearch() },
                            onSearch = { runSearch(query) }
                        )
                        Spacer(Modifier.height(10.dp))
                        // Сначала недавние, следом лучшие по баллу сканера: ввод тикера руками —
                        // самое медленное действие на экране, а mint и вовсе не набрать.
                        val suggested = candidates.sortedByDescending { it.score }.take(5)
                            .map { QuickPick(it.symbol.ifBlank { it.name }, it.mint) }
                        QuickPickRow(recent, suggested) { pick -> query = pick.label; runSearch(pick.query, pick.label) }
                    }
                }

                item(key = "tabs") {
                    PageSlot {
                        SegmentedTabs(
                            left = "Скан · ${results.size}",
                            right = "⭐ Избранное · ${favorites.size}",
                            rightSelected = tab == OverviewTab.FAVORITES,
                            onSelect = { right -> tab = if (right) OverviewTab.FAVORITES else OverviewTab.SCAN }
                        )
                    }
                }

                if (tab == OverviewTab.FAVORITES) {
                    if (favorites.isEmpty()) {
                        item(key = "fav-empty") {
                            PageSlot {
                                NoticeCard("В избранном пусто", "Отметьте монету звездой ☆ на карточке разбора — снимок останется здесь, даже когда она уйдёт из сканера.")
                            }
                        }
                    } else {
                        item(key = "fav-hint") {
                            PageSlot {
                                Text(
                                    "Снимки последнего разбора каждой монеты. Обновляются сами, когда монета снова попадает в разбор.",
                                    fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp
                                )
                            }
                        }
                        val now = Clock.System.now().toEpochMilliseconds()
                        items(favorites, key = { "fav-${it.mint}" }) { f ->
                            PageSlot(Modifier.animateItem()) {
                                FavoriteCard(
                                    c = f, now = now,
                                    onRemove = { removeFavorite(f.mint) },
                                    onReanalyze = { runSearch(f.mint, f.symbol.ifBlank { null }) }
                                )
                            }
                        }
                    }
                } else {
                    item(key = "limit") {
                        PageSlot { ScanLimitRow(candidates.size, batch.size, limit, enabled = !loading) { limit = it } }
                    }

                    if (results.isNotEmpty()) {
                        item(key = "pulse") {
                            PageSlot { MarketPulse(results, readyFilter) { readyFilter = it } }
                        }
                    }

                    // Полоска скана над списком: результаты приходят по ходу, видно и найденное,
                    // и сколько монет ещё впереди.
                    if (loading) {
                        item(key = "progress") { PageSlot { ScanProgressStrip(progress) } }
                    }

                    when {
                        candidates.isEmpty() && results.isEmpty() -> item(key = "no-candidates") {
                            PageSlot {
                                NoticeCard(
                                    "Кандидатов сканера нет",
                                    "Запустите бота, чтобы сканер наполнил список. Любую монету можно разобрать поиском выше — по тикеру или mint."
                                )
                            }
                        }
                        loading && results.isEmpty() -> item(key = "skeletons") {
                            PageSlot { ScanningSkeletons(progress) }
                        }
                        progress == null -> item(key = "start") {
                            PageSlot {
                                NoticeCard(
                                    "Разбор ещё не запускали",
                                    "Разберу ${batch.size} ${plural(batch.size, "монету", "монеты", "монет")} сканера по методике Dars ($tfLabel). " +
                                        "Каждая монета — два запроса свечей к GeckoTerminal (~30 запросов/мин), поэтому скан идёт постепенно.",
                                    action = "Разобрать ${batch.size}"
                                ) { analyzer.startScan(batch, darsConfig) }
                            }
                        }
                        results.isEmpty() -> item(key = "empty") {
                            PageSlot {
                                NoticeCard(
                                    "Не удалось разобрать монеты",
                                    "GeckoTerminal не отдал свечи — возможно, упёрлись в лимит запросов. Попробуйте обновить через минуту.",
                                    action = "Обновить"
                                ) { analyzer.startScan(batch, darsConfig) }
                            }
                        }
                    }

                    if (results.isNotEmpty()) {
                        // Фильтр по фазе. Рядом с фазой — сколько монет в ней, чтобы пустой выбор
                        // был виден заранее.
                        item(key = "phases") {
                            PageSlot {
                                Text(
                                    "Фаза по ${analysisTfShort(darsConfig.entryTf, darsConfig.entryTfAggregate)}",
                                    fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(6.dp))
                                val presentPhases = MarketPhase.entries.filter { ph -> results.any { it.phase == ph } }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item(key = "phase-all") {
                                        HistoryFilterChip("Все ${results.size}", phaseFilter == null) { phaseFilter = null }
                                    }
                                    items(presentPhases, key = { it.name }) { ph ->
                                        val n = results.count { it.phase == ph }
                                        HistoryFilterChip("${ph.label} $n", phaseFilter == ph) { phaseFilter = ph }
                                    }
                                }
                            }
                        }
                        item(key = "list-header") {
                            PageSlot { TopListHeader(shownResults.size, sort) { sort = it } }
                        }
                    }

                    if (results.isNotEmpty() && shownResults.isEmpty()) {
                        item(key = "filtered-out") {
                            PageSlot {
                                NoticeCard(
                                    "Под фильтр никто не попал",
                                    "Снимите фильтр фазы или готовности — монеты в скане есть.",
                                    action = "Сбросить фильтры"
                                ) { phaseFilter = null; readyFilter = null }
                            }
                        }
                    }

                    // Ключ по mint: список пересортировывается по ходу скана, и без ключа Compose
                    // терял бы «развёрнуто» при каждом новом результате.
                    items(shownResults, key = { it.mint.ifBlank { it.symbol } }) { a ->
                        PageSlot(Modifier.animateItem()) {
                            CoinAnalysisCard(
                                a = a,
                                fav = if (settingsStore == null || a.mint.isBlank()) null
                                      else FavoriteState(a.mint in favoriteMints) { toggleFavorite(a) },
                                candles = analyzer.cachedCandles(a.mint),
                                onOpen = { query = a.symbol; runSearch(a.mint.ifBlank { a.symbol }, a.symbol) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Шапки ────────────────────────────────────────────────────────────────────

/** Шапка обзора: масштаб разбора, время последнего скана и кнопка «Обновить». */
@Composable
private fun OverviewHeader(
    subtitle: String,
    loading: Boolean,
    progress: AnalysisProgress?,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Анализ монет", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            // Таймфреймы и время расчёта — иначе непонятно, к какому масштабу относится вердикт.
            Text(subtitle, fontSize = 11.sp, color = Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.9f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "refreshScale"
        )
        val active = !loading && enabled
        Surface(
            color = if (active) GreenDim else SurfaceCard,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clickable(interactionSource = interaction, indication = null, enabled = active) { onRefresh() }
        ) {
            Text(
                if (loading) progress?.let { "${it.done}/${it.total}" } ?: "Считаю…" else "↻ Обновить",
                color = if (active) Green else TextSecondary, fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

/**
 * Шапка режима фокуса: назад к списку, монета и пересчёт именно её. Заменяет заголовок экрана:
 * пока открыт разбор одной монеты, обновлять хочется её, а не весь скан.
 */
@Composable
private fun FocusBar(
    symbol: String,
    iconUrl: String?,
    price: String?,
    scale: String?,
    busy: Boolean,
    /** null — избранное недоступно или монета ещё считается: звезду не рисуем. */
    saved: Boolean?,
    onToggleFav: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable { onBack() }) {
            Text("←", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
        }
        Spacer(Modifier.width(10.dp))
        CoinIcon(url = iconUrl, modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceCard)) {
            Text("🪙", fontSize = 18.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(symbol, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                price?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("$$it", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium,
                        maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            Text(scale ?: "разбор одной монеты", fontSize = 11.sp, color = Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        // Звезда живёт здесь, а не на карточке: в фокусе у карточки нет своей шапки.
        saved?.let { isSaved ->
            Text(
                if (isSaved) "★" else "☆",
                fontSize = 20.sp,
                color = if (isSaved) Amber else TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onToggleFav() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Surface(
            color = if (busy) SurfaceCard else GreenDim,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.clickable(enabled = !busy) { onRefresh() }
        ) {
            Text(if (busy) "…" else "↻", color = if (busy) TextSecondary else Green,
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
        }
    }
}

/**
 * Вердикт фокуса: что с монетой и что с ней делать — одной строкой, до всех подробностей.
 * Первый экран должен отвечать на «входить или нет» без чтения таблицы.
 */
@Composable
private fun FocusVerdict(a: CoinAnalysis, rank: String?) {
    val color = readinessColor(a.readiness)
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${a.readiness.emoji}  ${a.readiness.label}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
                Text("балл ${a.score}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.height(8.dp))
            Text(actionLine(a), fontSize = 12.sp, color = TextPrimary, lineHeight = 17.sp)
            a.setup?.let {
                Spacer(Modifier.height(4.dp))
                Text("Сетап: $it", fontSize = 11.sp, color = Purple)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(trendLabel(a.trend), trendColor(a.trend))
                Tag(a.phase.label, Purple)
                if (a.market.changeH1Percent != 0.0) {
                    Tag("${signedPct(a.market.changeH1Percent)} за 1ч", if (a.market.changeH1Percent >= 0) Green else Red)
                }
            }
            // Подвал: до какого момента вердикт действителен и как монета смотрится на фоне скана.
            Spacer(Modifier.height(8.dp))
            Text(
                "Бар ${a.entryTimeframe} ${barCloseText(a.timing)}" +
                    (rank?.let { " · $it" } ?: ""),
                fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp
            )
        }
    }
}

/** Что делать с монетой прямо сейчас — одной строкой; стоп и цель стоят таблицей ниже. */
private fun actionLine(a: CoinAnalysis): String {
    val e = a.entry
    return when {
        e != null && a.blockers.isEmpty() ->
            "Покупка в зоне ${MarketAnalysis.fmtPrice(e.zoneLow)} – ${MarketAnalysis.fmtPrice(e.zoneHigh)} · риск/прибыль 1:${fmt2(e.riskReward)}"
        a.blockers.isNotEmpty() -> "Не входим: ${a.blockers.first()}"
        else -> "Готового плана входа нет — смотрим разбор ниже."
    }
}

// ─── Обзор: поиск, вкладки, пульс, скан ────────────────────────────────────────

/** Поле поиска монеты + кнопка разбора. Регистр не трогаем: mint в base58 к нему чувствителен. */
@Composable
private fun AnalysisSearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(it.replace(" ", "")) },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("Тикер или mint — BONK, DezX…", color = TextSecondary, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Text("🔍", fontSize = 15.sp) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Text("✕", color = TextSecondary, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClear() }.padding(8.dp))
                }
            },
            singleLine = true,
            colors = settingsFieldColors(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
        val canSearch = query.isNotBlank() && !searching
        Surface(
            color = if (canSearch) GreenDim else SurfaceCard,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.clickable(enabled = canSearch) { onSearch() }
        ) {
            Text(
                if (searching) "…" else "Разбор",
                color = if (canSearch) Green else TextSecondary,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
        }
    }
}

/** Чипы быстрого разбора: недавние монеты, следом лучшие по баллу сканера. */
@Composable
private fun QuickPickRow(recent: List<QuickPick>, suggested: List<QuickPick>, onPick: (QuickPick) -> Unit) {
    val recentQueries = recent.map { it.query }.toSet()
    val all = (recent + suggested.filterNot { it.query in recentQueries }).take(7)
    if (all.isEmpty()) return
    Column {
        Text(if (recent.isEmpty()) "Лучшие в сканере" else "Недавние и лучшие в сканере",
            fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(all, key = { it.query }) { pick ->
                val isRecent = pick.query in recentQueries
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .border(1.dp, if (isRecent) Blue.copy(alpha = 0.4f) else BorderColor, RoundedCornerShape(20.dp))
                        .clickable { onPick(pick) }
                ) {
                    Text(
                        pick.label,
                        color = if (isRecent) Blue else TextPrimary,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** Переключатель из двух сегментов: «Скан» / «Избранное». */
@Composable
private fun SegmentedTabs(left: String, right: String, rightSelected: Boolean, onSelect: (right: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard).padding(3.dp)) {
        listOf(false to left, true to right).forEach { (isRight, label) ->
            val selected = isRight == rightSelected
            Surface(
                color = if (selected) BgDark else SurfaceCard,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).clickable { onSelect(isRight) }
            ) {
                Text(
                    label,
                    color = if (selected) TextPrimary else TextSecondary,
                    fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)
                )
            }
        }
    }
}

/** Сколько монет сканера разбирать за раз. На время скана выбор заморожен. */
@Composable
private fun ScanLimitRow(available: Int, selected: Int, limit: Int, enabled: Boolean, onLimit: (Int) -> Unit) {
    if (available == 0) return
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Сколько монет разбирать", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Text("в сканере $available · выбрано $selected", fontSize = 10.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(10, 25, 50, 100)) { n ->
                HistoryFilterChip("$n", selected = limit == n) { if (enabled) onLimit(n) }
            }
            item { HistoryFilterChip("Все", selected = limit == Int.MAX_VALUE) { if (enabled) onLimit(Int.MAX_VALUE) } }
        }
        if (selected > 30) {
            Spacer(Modifier.height(6.dp))
            Text("Много монет — скан дольше из-за лимита GeckoTerminal (~30 запросов/мин)",
                fontSize = 10.sp, color = TextSecondary)
        }
    }
}

/**
 * Пульс скана: сколько монет готовы ко входу, сколько формируются и сколько отсеяны. Плитки —
 * заодно и фильтр списка: на «есть ли вообще что-то готовое» экран отвечает числом сверху.
 */
@Composable
private fun MarketPulse(results: List<CoinAnalysis>, selected: Readiness?, onSelect: (Readiness?) -> Unit) {
    val up = results.count { it.trend == TrendDirection.UP }
    val down = results.count { it.trend == TrendDirection.DOWN }
    val best = results.maxByOrNull { it.score }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(Readiness.READY, "Готовы", Green),
                Triple(Readiness.WAIT, "Ждём", Amber),
                Triple(Readiness.IGNORE, "Мимо", Red),
            ).forEach { (r, label, color) ->
                PulseTile(label, results.count { it.readiness == r }, color, selected == r) {
                    onSelect(if (selected == r) null else r)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "▲ $up   ▼ $down" + (best?.let { "   ·   лучший ${it.symbol} ${it.score}" } ?: ""),
                fontSize = 11.sp, color = TextSecondary, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (selected != null) {
                Text("сбросить ✕", fontSize = 11.sp, color = Blue,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(null) }
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/** Плитка пульса: число крупно, подпись мелко; выбранная подсвечена рамкой своего цвета. */
@Composable
private fun RowScope.PulseTile(label: String, count: Int, color: Color, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = color.copy(alpha = if (active) 0.20f else 0.08f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.weight(1f)
            .border(1.dp, if (active) color else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                color = if (count > 0) color else color.copy(alpha = 0.5f))
            Text(label, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

/** Заголовок списка с выбором сортировки. */
@Composable
private fun TopListHeader(count: Int, sort: TopSort, onSort: (TopSort) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Монеты · $count", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Box {
            Surface(color = SurfaceCard, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { menu = true }) {
                Text("${sort.label}  ▾", color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TopSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(s.label, fontSize = 13.sp,
                                color = if (s == sort) Green else TextPrimary,
                                fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal)
                        },
                        onClick = { onSort(s); menu = false }
                    )
                }
            }
        }
    }
}

/** Полоска хода скана. Пульсирующая точка — признак жизни, пока не пришла первая монета. */
@Composable
private fun ScanProgressStrip(p: AnalysisProgress?) {
    val fraction by animateFloatAsState(
        targetValue = (p?.fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "scanFraction"
    )
    val pulse = rememberInfiniteTransition(label = "scanPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), repeatMode = RepeatMode.Reverse),
        label = "scanAlpha"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Green.copy(alpha = dotAlpha)))
                Spacer(Modifier.width(6.dp))
                Text(p?.lastSymbol?.let { "Разобрал $it" } ?: "Гружу свечи первых монет…",
                    fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            p?.let {
                Text("найдено ${it.best.size}" + if (it.failed > 0) " · пропущено ${it.failed}" else "",
                    fontSize = 11.sp, color = TextSecondary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(SurfaceCard)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Green))
        }
    }
}

/** Пока не готова ни одна монета — скелетоны вместо пустоты, чтобы список не «прыгал». */
@Composable
private fun ScanningSkeletons(p: AnalysisProgress?) {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val alpha by shimmer.animateFloat(
        initialValue = 0.25f, targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            p?.let { "Разбираю ${it.total} ${plural(it.total, "монету", "монеты", "монет")} — лучшие появятся здесь по мере готовности" }
                ?: "Гружу свечи с GeckoTerminal…",
            fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
        )
        repeat(3) {
            Surface(color = SurfaceCard.copy(alpha = alpha), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(92.dp)) {}
        }
    }
}

/** Сообщение вместо содержимого — «пусто», «не запускали», «под фильтр никто не попал». */
@Composable
private fun NoticeCard(title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                Surface(color = GreenDim, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable { onAction() }) {
                    Text(action, color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Строка статуса поиска: «Разбираю …» и текст ошибки, когда монета не найдена. */
@Composable
private fun SearchStatusCard(text: String, isError: Boolean = false) {
    Surface(
        color = if (isError) Red.copy(alpha = 0.08f) else SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(14.dp))
    ) {
        Text(text, fontSize = 13.sp, color = if (isError) Red else TextSecondary, lineHeight = 18.sp,
            modifier = Modifier.padding(16.dp))
    }
}

/** Возврат из фокуса в обзор — второй выход, чтобы не тянуться к шапке после длинного разбора. */
@Composable
private fun BackToTopButton(onClick: () -> Unit) {
    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(12.dp)).clickable { onClick() }
    ) {
        Text("←  Ко всем монетам", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
    }
}

// ─── Карточка разбора ──────────────────────────────────────────────────────────

/** Звезда на карточке свежего разбора: состояние и переключение. */
internal class FavoriteState(val saved: Boolean, val onToggle: () -> Unit)

/**
 * Карточка одной монеты: свёрнуто — вердикт и план, развёрнуто — график и весь разбор по урокам.
 * internal — чтобы рендер-тест мог нарисовать её развёрнутой без сети и без экрана целиком.
 */
@Composable
internal fun CoinAnalysisCard(
    a: CoinAnalysis,
    fav: FavoriteState? = null,
    /** Свечи разбора из кэша анализатора. null — график не рисуется, за картинкой в сеть не ходим. */
    candles: List<Candle>? = null,
    chartHeight: Dp = 170.dp,
    startExpanded: Boolean = false,
    /**
     * Рисовать собственную шапку. false — карточку открыли страницей фокуса: символ, цена и балл
     * уже стоят в шапке экрана и вердикте, а сворачивать полный разбор там незачем.
     */
    showHeader: Boolean = true,
    /** Открыть монету в режиме фокуса (пересчитать и показать отдельно). null — кнопки нет. */
    onOpen: (() -> Unit)? = null,
) {
    var expanded by remember(a.mint, a.symbol) { mutableStateOf(startExpanded) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "coinCardScale"
    )
    val chevron by animateFloatAsState(targetValue = if (expanded) 180f else 0f, animationSpec = tween(260), label = "coinChevron")
    val readyColor = readinessColor(a.readiness)

    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
            .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
            .then(
                if (!showHeader) Modifier
                else Modifier.clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
            )
    ) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            if (showHeader) {
                // ── Шапка: монета, тренд, балл, звезда ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CoinIcon(url = a.iconUrl, modifier = Modifier.size(36.dp).clip(CircleShape).background(BgDark)) {
                        Text("🪙", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.symbol, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Text(trendLabel(a.trend), fontSize = 12.sp, color = trendColor(a.trend), fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text("$${MarketAnalysis.fmtPrice(a.price)} · ${a.phase.label}", fontSize = 11.sp, color = TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${a.timeframeLabel} · бар ${barCloseText(a.timing)}",
                            fontSize = 10.sp, color = Blue, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            fav?.let { f ->
                                Text(
                                    if (f.saved) "★" else "☆",
                                    fontSize = 18.sp,
                                    color = if (f.saved) Amber else TextSecondary,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { f.onToggle() }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                            Surface(color = readyColor.copy(alpha = 0.15f), shape = RoundedCornerShape(7.dp)) {
                                Text("${a.readiness.emoji} ${a.score}", color = readyColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (expanded) "свернуть" else "подробнее", fontSize = 10.sp, color = TextSecondary)
                            Spacer(Modifier.width(3.dp))
                            Text("▼", fontSize = 9.sp, color = TextSecondary, modifier = Modifier.graphicsLayer { rotationZ = chevron })
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(a.readiness.label, fontSize = 12.sp, color = readyColor, fontWeight = FontWeight.Medium)
                a.setup?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, fontSize = 12.sp, color = Purple)
                }
            }

            // ── Примерная точка входа (бот спотовый — только покупка) ──
            a.entry?.let { e ->
                Spacer(Modifier.height(10.dp))
                Surface(color = BgDark, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp)) {
                        Text("Точка входа — покупка (ориентир)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                        Spacer(Modifier.height(7.dp))
                        AnalysisRow("Зона входа", "${MarketAnalysis.fmtPrice(e.zoneLow)} – ${MarketAnalysis.fmtPrice(e.zoneHigh)}", TextPrimary)
                        AnalysisRow("Стоп", "${MarketAnalysis.fmtPrice(e.stopLoss)}  −${fmt1(e.riskPercent)}%", Red)
                        AnalysisRow("Цель", "${MarketAnalysis.fmtPrice(e.takeProfit)}  +${fmt1(e.rewardPercent)}%", Green)
                        AnalysisRow("Риск / прибыль", "1 : ${fmt2(e.riskReward)}", when {
                            e.riskReward >= 2.0 -> Green
                            e.riskReward > 0.0 -> Amber
                            else -> Red
                        })
                        Spacer(Modifier.height(6.dp))
                        Text("Триггер: ${e.trigger}", fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp)
                    }
                }
            }

            // ── Запреты Урока 4 — всегда на виду: это главное «почему нет входа» ──
            if (a.blockers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(color = Red.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Почему не входим (Урок 4)", fontSize = 11.sp, color = Red, fontWeight = FontWeight.Bold)
                        a.blockers.forEach { Text("• $it", fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp) }
                    }
                }
            }

            // ── Развёрнутый разбор ──
            if (expanded) {
                if (showHeader) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = BorderColor)
                }
                Spacer(Modifier.height(10.dp))

                // График — только из свечей, уже загруженных анализатором: карточка не стоит
                // ни одного запроса к GeckoTerminal.
                if (candles != null && candles.size >= 2) {
                    CandleChart(
                        candles = candles,
                        overlay = a.chart,
                        height = chartHeight,
                        caption = a.chart?.let { setupCaption(it) }?.takeIf { it.isNotBlank() },
                        lastBarForming = a.timing.msToBarClose > 0L,
                        viewportKey = a.mint,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChartLegend()
                    Spacer(Modifier.height(12.dp))
                } else if (showHeader) {
                    Text("Свечи разбора устарели — обновите скан, чтобы увидеть график", fontSize = 10.sp, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                }

                if (onOpen != null) {
                    Surface(color = Blue.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
                        Text("🔎 Открыть отдельно и пересчитать", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── Когда и по чему считали ──
                SectionTitle("Данные разбора")
                val t = a.timing
                AnalysisRow("Время разбора (наше)", formatLocalTime(t.analyzedAt), TextPrimary)
                AnalysisRow("Последний бар (DEX, UTC)",
                    formatDexTime(t.lastBarTime) + if (t.dataAgeMs > 0) " · ${MarketAnalysis.formatSpan(t.dataAgeMs)} назад" else "",
                    TextPrimary)
                AnalysisRow("Тренд по ТФ", "${a.trendTimeframe} · ${t.trendBars} баров",
                    if (t.trendFromHigherTimeframe) TextPrimary else Amber)
                if (!t.trendFromHigherTimeframe) {
                    Text("Истории старшего ТФ не хватило — тренд взят со структуры рабочего",
                        fontSize = 10.sp, color = Amber, lineHeight = 14.sp)
                }
                AnalysisRow("Вход по ТФ", "${a.entryTimeframe} · ${t.bars} баров", TextPrimary)
                AnalysisRow("Текущий бар", barCloseText(t), Amber)
                a.barsSinceCorrection?.let {
                    val barMs = (t.nextBarAt - t.lastBarTime).coerceAtLeast(0L)
                    AnalysisRow("Возраст сетапа", "$it ${plural(it, "бар", "бара", "баров")} · ${MarketAnalysis.formatSpan(it * barMs)}", TextPrimary)
                }

                // ── Урок 1 ──
                if (a.impulse != null || a.correction != null) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Урок 1 — сравнение по 4 признакам")
                    AnalysisRow("Сила импульса", "${(a.dominance * 100).toInt()}%", if (a.dominance >= 0.6) Green else Amber)
                    AnalysisRow("Вторая сторона", "${(a.opposition * 100).toInt()}%", if (a.opposition <= 0.45) Green else Red)
                    a.impulse?.let { LegLine("Импульс", it) }
                    a.correction?.let { LegLine("Коррекция", it) }
                }

                // ── Урок 2: уровни ──
                if (a.support != null || a.resistance != null) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Урок 2 — ближайшие уровни")
                    a.resistance?.let {
                        AnalysisRow("Сопротивление",
                            "${MarketAnalysis.fmtPrice(it.price)} · ${MarketAnalysis.levelKindLabel(it)} · ${signedPct(distancePct(a.price, it.price))}", Red)
                    }
                    a.support?.let {
                        AnalysisRow("Поддержка",
                            "${MarketAnalysis.fmtPrice(it.price)} · ${MarketAnalysis.levelKindLabel(it)} · ${signedPct(distancePct(a.price, it.price))}", Green)
                    }
                }

                // ── Рыночный фон из сканера ──
                val m = a.market
                if (m.liquidityUsd > 0.0 || m.marketCap > 0.0 || m.volumeH1Usd > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Рыночный фон (DexScreener)")
                    if (m.liquidityUsd > 0.0) AnalysisRow("Ликвидность", "$${formatLargeNumber(m.liquidityUsd)}", TextPrimary)
                    if (m.marketCap > 0.0) AnalysisRow("Капитализация", "$${formatLargeNumber(m.marketCap)}", TextPrimary)
                    if (m.volumeH1Usd > 0.0) AnalysisRow("Оборот за час", "$${formatLargeNumber(m.volumeH1Usd)}", TextSecondary)
                    AnalysisRow("Изменение", "5м ${signedPct(m.changeM5Percent)} · 1ч ${signedPct(m.changeH1Percent)}",
                        if (m.changeH1Percent >= 0) Green else Red)
                    m.buyRatioH1?.let { r ->
                        AnalysisRow("Покупки за час", "${(r * 100).toInt()}% (${m.buysH1}/${m.sellsH1})", if (r >= 0.5) Green else Red)
                    }
                    if (m.tokenAgeMinutes > 0) {
                        AnalysisRow("Возраст пары", MarketAnalysis.formatSpan(m.tokenAgeMinutes * 60_000L), TextSecondary)
                    }
                }

                if (a.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Что видно на графике")
                    a.notes.forEach { Text("• $it", fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp) }
                }

                if (a.mint.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    MintCopyRow(a.mint)
                }
            }
        }
    }
}

/** Легенда цветов графика — один раз под картинкой, чтобы линии не приходилось угадывать. */
@Composable
private fun ChartLegend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendSwatch("импульс", Blue)
        LegendSwatch("коррекция", Purple)
        LegendSwatch("вход", Amber)
        LegendSwatch("стоп", Red)
        LegendSwatch("цель", Green)
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 11.sp, color = Blue, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LegLine(title: String, c: LegCharacter) {
    Spacer(Modifier.height(6.dp))
    Text(title, fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
    Text(charDesc(c), fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp)
}

@Composable
private fun AnalysisRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 11.sp, color = valueColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

// ─── Избранное ────────────────────────────────────────────────────────────────

/**
 * Карточка избранной монеты — рисуется из сохранённого снимка [FavoriteCoin], поэтому доступна
 * без скана и когда монета ушла из сканера. Свёрнутая — главное и возраст снимка; развёрнутая —
 * весь сохранённый разбор. «Разобрать заново» открывает монету в фокусе со свежими свечами.
 */
@Composable
private fun FavoriteCard(c: FavoriteCoin, now: Long, onRemove: () -> Unit, onReanalyze: () -> Unit) {
    var expanded by remember(c.mint) { mutableStateOf(false) }
    val readyColor = when (c.readinessEmoji) {
        "🟢" -> Green
        "🟡" -> Amber
        "🔴" -> Red
        else -> TextSecondary
    }
    val name = c.symbol.ifBlank { if (c.mint.length > 10) "${c.mint.take(4)}…${c.mint.takeLast(4)}" else c.mint }
    val trend = when (c.trend) {
        "UP" -> TrendDirection.UP
        "DOWN" -> TrendDirection.DOWN
        else -> TrendDirection.FLAT
    }

    Surface(
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(14.dp)).clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            // Возраст снимка — первым: решение по вчерашнему разбору принимать нельзя.
            val age = if (c.analyzedAt > 0L) now - c.analyzedAt else null
            Surface(color = Amber.copy(alpha = if ((age ?: 0L) > 3_600_000L) 0.12f else 0.06f), shape = RoundedCornerShape(8.dp)) {
                Text(
                    buildString {
                        append("★ снимок")
                        age?.let { append(" ${MarketAnalysis.formatSpan(it)} назад") }
                        if (c.addedAt > 0L) append(" · в избранном с ${formatLocalTime(c.addedAt)}")
                    },
                    fontSize = 10.sp, color = Amber, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinIcon(url = c.iconUrl, modifier = Modifier.size(36.dp).clip(CircleShape).background(BgDark)) {
                    Text("🪙", fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (c.trend.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(trendLabel(trend), fontSize = 12.sp, color = trendColor(trend), maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            c.price.takeIf { it > 0.0 }?.let { "$${MarketAnalysis.fmtPrice(it)}" },
                            c.phaseLabel.takeIf { it.isNotBlank() },
                            c.timeframeLabel.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text("★", fontSize = 18.sp, color = Amber,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove() }.padding(horizontal = 6.dp, vertical = 2.dp))
                Surface(color = readyColor.copy(alpha = 0.15f), shape = RoundedCornerShape(7.dp)) {
                    Text("${c.readinessEmoji} ${c.score}".trim(), color = readyColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            if (c.readinessLabel.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(c.readinessLabel, fontSize = 12.sp, color = readyColor, fontWeight = FontWeight.Medium)
            }
            c.setup?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, fontSize = 12.sp, color = Purple)
            }

            if (expanded) {
                if (c.hasEntry) {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = BgDark, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(11.dp)) {
                            Text("Точка входа — покупка (на момент снимка)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                            Spacer(Modifier.height(7.dp))
                            AnalysisRow("Зона входа", "${MarketAnalysis.fmtPrice(c.entryZoneLow)} – ${MarketAnalysis.fmtPrice(c.entryZoneHigh)}", TextPrimary)
                            AnalysisRow("Стоп", "${MarketAnalysis.fmtPrice(c.stopLoss)}  −${fmt1(c.riskPercent)}%", Red)
                            AnalysisRow("Цель", "${MarketAnalysis.fmtPrice(c.takeProfit)}  +${fmt1(c.rewardPercent)}%", Green)
                            AnalysisRow("Риск / прибыль", "1 : ${fmt2(c.riskReward)}", if (c.riskReward >= 2.0) Green else Amber)
                            c.entryTrigger?.let {
                                Spacer(Modifier.height(6.dp))
                                Text("Триггер: $it", fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp)
                            }
                        }
                    }
                }
                if (c.blockers.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = Red.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Что мешало входу", fontSize = 11.sp, color = Red, fontWeight = FontWeight.Bold)
                            c.blockers.forEach { Text("• $it", fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp) }
                        }
                    }
                }

                if (c.impulseDesc != null || c.correctionDesc != null) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Урок 1 — сравнение по 4 признакам")
                    AnalysisRow("Сила импульса", "${(c.dominance * 100).toInt()}%", if (c.dominance >= 0.6) Green else Amber)
                    AnalysisRow("Вторая сторона", "${(c.opposition * 100).toInt()}%", if (c.opposition <= 0.45) Green else Red)
                    c.impulseDesc?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("Импульс", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(it, fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp)
                    }
                    c.correctionDesc?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("Коррекция", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(it, fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp)
                    }
                }

                if (c.supportPrice > 0.0 || c.resistancePrice > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Урок 2 — ближайшие уровни")
                    if (c.resistancePrice > 0.0) AnalysisRow("Сопротивление", MarketAnalysis.fmtPrice(c.resistancePrice), Red)
                    if (c.supportPrice > 0.0) AnalysisRow("Поддержка", MarketAnalysis.fmtPrice(c.supportPrice), Green)
                }

                if (c.liquidityUsd > 0.0 || c.marketCap > 0.0 || c.volumeH1Usd > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Рыночный фон (DexScreener)")
                    if (c.liquidityUsd > 0.0) AnalysisRow("Ликвидность", "$${formatLargeNumber(c.liquidityUsd)}", TextPrimary)
                    if (c.marketCap > 0.0) AnalysisRow("Капитализация", "$${formatLargeNumber(c.marketCap)}", TextPrimary)
                    if (c.volumeH1Usd > 0.0) AnalysisRow("Оборот за час", "$${formatLargeNumber(c.volumeH1Usd)}", TextSecondary)
                    AnalysisRow("Изменение", "5м ${signedPct(c.changeM5Percent)} · 1ч ${signedPct(c.changeH1Percent)}",
                        if (c.changeH1Percent >= 0) Green else Red)
                    c.buyRatioH1?.let { r ->
                        AnalysisRow("Покупки за час", "${(r * 100).toInt()}% (${c.buysH1}/${c.sellsH1})", if (r >= 0.5) Green else Red)
                    }
                }

                if (c.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Что было видно на графике")
                    c.notes.forEach { Text("• $it", fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp) }
                }

                if (c.analyzedAt > 0L) {
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("Данные снимка")
                    AnalysisRow("Время разбора (наше)", formatLocalTime(c.analyzedAt), TextPrimary)
                    AnalysisRow("Последний бар (DEX, UTC)", formatDexTime(c.lastBarTime), TextPrimary)
                }

                if (c.mint.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    MintCopyRow(c.mint)
                }
            }

            if (c.mint.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(color = GreenDim, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onReanalyze() }) {
                    Text("↻ Разобрать заново — свежие свечи и график", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
            }
        }
    }
}

// ─── Форматирование ───────────────────────────────────────────────────────────

/** Снимок карточки разбора для сохранения в избранное. */
private fun CoinAnalysis.toFavorite(): FavoriteCoin = FavoriteCoin(
    mint = mint,
    symbol = symbol,
    iconUrl = iconUrl,
    price = price,
    score = score,
    trend = trend.name,
    phaseLabel = phase.label,
    readinessLabel = readiness.label,
    readinessEmoji = readiness.emoji,
    timeframeLabel = timeframeLabel,
    setup = setup,
    dominance = dominance,
    opposition = opposition,
    impulseDesc = impulse?.let { charDesc(it) },
    correctionDesc = correction?.let { charDesc(it) },
    entryZoneLow = entry?.zoneLow ?: 0.0,
    entryZoneHigh = entry?.zoneHigh ?: 0.0,
    entryPrice = entry?.entryPrice ?: 0.0,
    stopLoss = entry?.stopLoss ?: 0.0,
    takeProfit = entry?.takeProfit ?: 0.0,
    riskReward = entry?.riskReward ?: 0.0,
    riskPercent = entry?.riskPercent ?: 0.0,
    rewardPercent = entry?.rewardPercent ?: 0.0,
    entryTrigger = entry?.trigger,
    supportPrice = support?.price ?: 0.0,
    resistancePrice = resistance?.price ?: 0.0,
    liquidityUsd = market.liquidityUsd,
    marketCap = market.marketCap,
    volumeH1Usd = market.volumeH1Usd,
    changeM5Percent = market.changeM5Percent,
    changeH1Percent = market.changeH1Percent,
    buysH1 = market.buysH1,
    sellsH1 = market.sellsH1,
    blockers = blockers,
    notes = notes,
    analyzedAt = timing.analyzedAt,
    lastBarTime = timing.lastBarTime,
)

private fun charDesc(c: LegCharacter): String =
    "скорость ${c.speedLabel} · бары ${c.barSizeLabel} · закрытия ${c.closeLabel} · объём ${c.volumeLabel}"

private fun analysisTfShort(tf: String, agg: Int): String = when (tf.lowercase()) {
    "minute" -> "${agg}m"
    "hour" -> "${agg}h"
    "day" -> "${agg}d"
    else -> "$tf×$agg"
}

private fun readinessColor(r: Readiness): Color = when (r) {
    Readiness.READY -> Green
    Readiness.WAIT -> Amber
    Readiness.IGNORE -> Red
}

private fun trendLabel(t: TrendDirection): String = when (t) {
    TrendDirection.UP -> "▲ тренд вверх"
    TrendDirection.DOWN -> "▼ тренд вниз"
    TrendDirection.FLAT -> "— нет тренда"
}

private fun trendColor(t: TrendDirection): Color = when (t) {
    TrendDirection.UP -> Green
    TrendDirection.DOWN -> Red
    TrendDirection.FLAT -> TextSecondary
}

/**
 * «закроется через 3 мин» — пока бар разбора идёт, и «уже закрыт», когда разбор старше бара:
 * «через 0 мин» на вчерашнем снимке читалось бы как «вот-вот».
 */
private fun barCloseText(t: AnalysisTiming): String =
    if (t.msToBarClose > 0L) "закроется через ${MarketAnalysis.formatSpan(t.msToBarClose)}"
    else "уже закрыт — обновите разбор"

/** Насколько уровень выше (+) или ниже (−) текущей цены, %. */
private fun distancePct(price: Double, level: Double): Double =
    if (price > 0.0) (level - price) / price * 100.0 else 0.0

/** «+3.4%» / «−1.2%» — знак и один знак после точки, без локали (запятая в ru ломала бы вид). */
private fun signedPct(v: Double): String = "${if (v >= 0) "+" else "−"}${fmt1(abs(v))}%"

private fun fmt1(v: Double): String {
    val r = kotlin.math.round(abs(v) * 10).toLong()
    return "${if (v < 0) "-" else ""}${r / 10}.${r % 10}"
}

private fun fmt2(v: Double): String {
    val r = kotlin.math.round(abs(v) * 100).toLong()
    return "${if (v < 0) "-" else ""}${r / 100}.${(r % 100).toString().padStart(2, '0')}"
}
