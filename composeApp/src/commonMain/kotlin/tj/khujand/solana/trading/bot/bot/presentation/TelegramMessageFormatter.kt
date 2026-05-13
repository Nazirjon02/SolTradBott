package tj.khujand.solana.trading.bot.bot.presentation

import tj.khujand.solana.trading.bot.bot.domain.model.DealsSummary
import tj.khujand.solana.trading.bot.bot.domain.model.ExitStrategyView
import tj.khujand.solana.trading.bot.bot.domain.model.FilterSettingsView
import tj.khujand.solana.trading.bot.bot.domain.model.MonitoredTokenView
import tj.khujand.solana.trading.bot.bot.domain.model.SystemSnapshot
import tj.khujand.solana.trading.bot.bot.domain.model.TradingMode
import kotlin.math.absoluteValue

object TelegramMessageFormatter {

    fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** Короткое уведомление о результате действия (старт/стоп, смена режима и т.д.) */
    fun actionNotice(message: String): String =
        "<blockquote>${escapeHtml(message)}</blockquote>\n\n"

    fun helpMessage(): String = buildString {
        appendLine(bold("📖 Команды"))
        appendLine("${cmd("/start")} — главное меню")
        appendLine("${cmd("/status")} — статус системы")
        appendLine("${cmd("/monitor_start")} — запуск мониторинга")
        appendLine("${cmd("/monitor_stop")} — остановка")
        appendLine("${cmd("/mode")} — режим demo / real")
        appendLine("${cmd("/balance")} — баланс")
        appendLine("${cmd("/deals")} — сводка по сделкам")
        appendLine("${cmd("/monitoring")} — открытые позиции")
        appendLine("${cmd("/filters")} — фильтры входа")
        appendLine("${cmd("/exit")} — стратегия выхода")
        appendLine("${cmd("/panic")} — 🚨 закрыть ВСЕ позиции немедленно")
        appendLine()
        appendLine(italic("Удобнее пользоваться кнопками меню под сообщением."))
    }

    fun mainMenuMessage(snapshot: SystemSnapshot): String {
        return buildString {
            appendLine(bold("⚡ SolTradBot"))
            appendLine(italic("Панель управления"))
            appendLine()
            appendLine(sectionLabel("Сводка"))
            appendLine(row("Мониторинг", monitoringLabel(snapshot.isMonitoring)))
            appendLine(row("Режим", formatMode(snapshot.mode)))
            appendLine(row("Баланс (demo)", codeUsd(snapshot.demoBalanceUsd)))
            appendLine(
                row(
                    "Сделки",
                    "${code(snapshot.dealsSummary.totalTrades.toString())} · " +
                        "✅${snapshot.dealsSummary.profitableTrades} · " +
                        "✖${snapshot.dealsSummary.losingTrades} · " +
                        "TP ${snapshot.dealsSummary.tpTriggerHits}"
                )
            )
            appendLine()
            appendLine(italic("Выберите действие ниже ↓"))
        }
    }

    fun statusMessage(snapshot: SystemSnapshot): String {
        return buildString {
            appendLine(bold("📊 Статус системы"))
            appendLine()
            appendLine(sectionLabel("Работа"))
            appendLine(row("Мониторинг", monitoringLabel(snapshot.isMonitoring)))
            appendLine(row("Режим", formatMode(snapshot.mode)))
            appendLine(row("Баланс", codeUsd(snapshot.demoBalanceUsd)))
            appendLine()
            appendLine(sectionLabel("Сделки"))
            appendLine(formatDealsSummaryHtml(snapshot.dealsSummary))
            appendLine()
            appendLine(sectionLabel("Ключевые параметры"))
            snapshot.keyParameters.forEach { (k, v) ->
                appendLine("• ${escapeHtml(k)} → ${code(v)}")
            }
        }
    }

    fun balanceMessage(balanceUsd: Double, mode: TradingMode): String {
        return buildString {
            appendLine(bold("💰 Баланс"))
            appendLine()
            appendLine(row("Режим", formatMode(mode)))
            appendLine(row("Сумма (demo)", codeUsd(balanceUsd)))
            appendLine()
            appendLine(italic("🔄 Обновить — актуальные цифры"))
        }
    }

    fun dealsSummaryMessage(summary: DealsSummary): String {
        return buildString {
            appendLine(bold("📈 Сделки"))
            appendLine()
            appendLine(formatDealsSummaryHtml(summary))
        }
    }

    fun monitoringMessage(tokens: List<MonitoredTokenView>): String {
        return buildString {
            appendLine(bold("👁 Мониторинг позиций"))
            appendLine()
            if (tokens.isEmpty()) {
                appendLine(italic("Сейчас нет открытых позиций."))
            } else {
                tokens.forEachIndexed { index, token ->
                    val pnlSign = if (token.profitUsd >= 0) "+" else ""
                    val pctSign = if (token.priceChangePercent >= 0) "+" else ""
                    appendLine(bold("${index + 1}. ${escapeHtml(token.name)}"))
                    appendLine(code(token.tokenAddress))
                    val pnlText = "${pnlSign}$$${formatUsd(token.profitUsd)}"
                    appendLine(
                        "P&amp;L: ${bold(pnlText)} (${pctSign}${token.priceChangePercent.toInt()}%)"
                    )
                    if (token.jupiterSellLastError.isNotBlank()) {
                        appendLine(
                            "⚠️ Jupiter: ${italic(escapeHtml(token.jupiterSellLastError))}"
                        )
                    }
                    appendLine()
                }
                appendLine(italic("Адрес токена можно скопировать из блока выше."))
                if (tokens.any { it.jupiterSellLastError.isNotBlank() }) {
                    appendLine()
                    appendLine(
                        italic("Пока продажа Jupiter не прошла, позиция остаётся здесь; запись в сделках появится после успешного выхода.")
                    )
                }
            }
        }
    }

    fun filtersMessage(view: FilterSettingsView): String =
        filtersMessage(view, 0)

    fun filtersMessage(view: FilterSettingsView, page: Int): String {
        val s = view.settings
        val p = page.coerceIn(0, TelegramUiPages.FILTERS_PAGE_COUNT - 1)
        return buildString {
            appendLine(bold("🎯 Фильтры входа") + " " + pageIndicator(p + 1, TelegramUiPages.FILTERS_PAGE_COUNT))
            appendLine()
            when (p) {
                0 -> {
                    appendLine(sectionLabel("Пороги входа"))
                    appendLine(row("Макс. токенов", code(s.maxTokensToMonitor.toString())))
                    appendLine(row("Макс. возраст (мин)", code(s.entryMaxAgeMinutes.toString())))
                    appendLine(
                        row(
                            "Капитализация",
                            "${code(s.entryMinMarketCap.toInt().toString())} — ${code(s.entryMaxMarketCap.toInt().toString())}"
                        )
                    )
                    appendLine(row("Ликвидность ≥", code(s.entryMinLiquidity.toInt().toString())))
                    appendLine(row("Объём 24h ≥", code(s.entryMinVolume.toInt().toString())))
                    appendLine(row("Объём 5m ≥", code(s.entryMinVolumeM5.toInt().toString())))
                    appendLine()
                    appendLine(sectionLabel("Объёмы и соцсети"))
                    appendLine(rowToggle("Vol 24h", s.useVolumeH24))
                    appendLine(rowToggle("Vol 5m", s.useVolumeM5))
                    appendLine(rowToggle("Соцсети", s.requireSocials))
                    appendLine(rowToggle("Сайт", s.requireWebsite))
                }
                1 -> {
                    appendLine(sectionLabel("AI"))
                    appendLine(rowToggle("Анализ AI", s.useAiAnalysis))
                    appendLine(rowToggle("Закрывать при сбое AI", s.aiFailClosed))
                    appendLine(row("Min AI score", code(s.minAiScore.toString())))
                    appendLine(row("Max rug risk", code(s.maxAiRugRisk.toString())))
                }
                else -> {
                    appendLine(sectionLabel("Риски и стопы"))
                    appendLine(row("Max дневной убыток ($)", code(s.maxDailyLossUsd.toInt().toString())))
                    appendLine(row("Max экспозиция ($)", code(s.maxTotalExposureUsd.toInt().toString())))
                    appendLine(row("Серия убытков", code(s.maxConsecutiveLosses.toString())))
                    appendLine(row("SL по цене", code("${s.stopLossByPricePct.toInt()}%")))
                    appendLine(row("SL по капе", code("${s.stopLossByMarketCapPct.toInt()}%")))
                    appendLine(row("Трейлинг", code("${s.trailingStopPct.toInt()}%")))
                    appendLine(row("Откат стадии", code("${s.stagePullbackPct.toInt()}%")))
                }
            }
            appendLine()
            appendLine(italic(filtersFooterHint(p)))
        }
    }

    fun exitStrategyMessage(view: ExitStrategyView): String =
        exitStrategyMessage(view, 0)

    fun exitStrategyMessage(view: ExitStrategyView, page: Int): String {
        val s = view.settings
        val p = page.coerceIn(0, TelegramUiPages.EXIT_PAGE_COUNT - 1)
        return buildString {
            appendLine(bold("📤 Стратегия выхода") + " " + pageIndicator(p + 1, TelegramUiPages.EXIT_PAGE_COUNT))
            appendLine()
            when (p) {
                0 -> {
                    appendLine(sectionLabel("Режим"))
                    appendLine(row("Тип", code(s.exitStrategy)))
                    appendLine(row("Aggressive TP", code("${s.aggressiveTakeProfitPct.toInt()}%")))
                    appendLine(row("Aggressive sell", code("${s.aggressiveSellPct.toInt()}%")))
                    appendLine()
                    appendLine(sectionLabel("Стадии (кап / %)"))
                    appendLine(row("1", "${code(s.exitStage1Cap.toInt().toString())} / ${code("${s.exitStage1Pct.toInt()}%")}"))
                    appendLine(row("2", "${code(s.exitStage2Cap.toInt().toString())} / ${code("${s.exitStage2Pct.toInt()}%")}"))
                    appendLine(row("3", "${code(s.exitStage3Cap.toInt().toString())} / ${code("${s.exitStage3Pct.toInt()}%")}"))
                    appendLine(row("4", "${code(s.exitStage4Cap.toInt().toString())} / ${code("${s.exitStage4Pct.toInt()}%")}"))
                }
                else -> {
                    appendLine(sectionLabel("Время"))
                    appendLine(rowToggle("Выход по таймеру", s.useTimeBasedExit))
                    appendLine(row("Минуты", code(s.timeBasedExitMinutes.toString())))
                    appendLine()
                    appendLine(sectionLabel("Торговые часы (UTC)"))
                    appendLine(rowToggle("Ограничение по часам", s.tradingHoursEnabled))
                    if (s.tradingHoursEnabled) {
                        appendLine(row("Окно", code("${s.tradingHoursStartUtcHour}:00 – ${s.tradingHoursEndUtcHour}:00")))
                    }
                }
            }
            appendLine()
            appendLine(italic(exitFooterHint(p)))
        }
    }

    private fun pageIndicator(current: Int, total: Int): String =
        italic("($current/$total)")

    private fun filtersFooterHint(page: Int): String = when (page) {
        0 -> "◀ ▶ — страницы. Пороги и переключатели объёмов — кнопками ниже."
        1 -> "AI-пороги и риск-профиль — кнопками ± и рядом риска."
        else -> "Стопы и лимиты — кнопками ± ниже."
    }

    private fun exitFooterHint(page: Int): String = when (page) {
        0 -> "◀ ▶ — вторая страница: таймер и торговые часы."
        else -> "Таймер и часы — кнопками ниже."
    }

    fun modeMessage(mode: TradingMode): String {
        return buildString {
            appendLine(bold("🔐 Режим торговли"))
            appendLine()
            appendLine(row("Сейчас", formatMode(mode)))
            appendLine()
            appendLine("⚠️ ${bold("REAL")}: реальные сделки через Jupiter.")
        }
    }

    fun confirmRealModeHtml(): String =
        buildString {
            appendLine(bold("⚠️ Подтверждение"))
            appendLine()
            appendLine("Перейти в режим ${code("REAL")}?")
            appendLine(italic("Реальные средства и исполнение на бирже."))
        }

    private fun formatDealsSummaryHtml(summary: DealsSummary): String {
        val pnlPrefix = if (summary.netProfitUsd >= 0) "+" else "−"
        val pnlVal = formatUsd(summary.netProfitUsd.absoluteValue)
        return buildString {
            appendLine(row("Всего", code(summary.totalTrades.toString())))
            appendLine(row("В плюс", code("+${summary.profitableTrades}")))
            appendLine(row("TP сработало", code(summary.tpTriggerHits.toString())))
            appendLine(row("В минус", code("−${summary.losingTrades}")))
            appendLine(row("Win rate", code("${summary.winRatePct.toInt()}%")))
            appendLine(row("Итог P&amp;L", bold("${pnlPrefix}$$${pnlVal}")))
        }
    }

    private fun monitoringLabel(active: Boolean): String =
        if (active) "🟢 активен" else "⚪ выкл"

    private fun formatMode(mode: TradingMode): String =
        if (mode == TradingMode.REAL) "🔴 real" else "🟢 demo"

    private fun formatUsd(value: Double): String =
        (kotlin.math.round(value * 100.0) / 100.0).toString()

    private fun bold(s: String) = "<b>$s</b>"

    private fun italic(s: String) = "<i>$s</i>"

    private fun code(s: String) = "<code>${escapeHtml(s)}</code>"

    private fun codeUsd(value: Double) = "<code>\$${escapeHtml(formatUsd(value))}</code>"

    private fun cmd(s: String) = "<code>${escapeHtml(s)}</code>"

    private fun sectionLabel(title: String) = "▸ ${bold(escapeHtml(title))}"

    private fun row(label: String, value: String) = "• ${escapeHtml(label)}: $value"

    private fun rowToggle(label: String, on: Boolean) =
        "• ${escapeHtml(label)}: ${if (on) "✅" else "❌"}"
}
