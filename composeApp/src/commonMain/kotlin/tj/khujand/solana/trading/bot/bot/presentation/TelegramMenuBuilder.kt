package tj.khujand.solana.trading.bot.bot.presentation

import tj.khujand.solana.trading.bot.bot.domain.model.ExitStrategyView
import tj.khujand.solana.trading.bot.bot.domain.model.FilterSettingsView
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramInlineKeyboard
import tj.khujand.solana.trading.bot.bot.telegram.api.TelegramInlineKeyboardButton
import tj.khujand.solana.trading.bot.bot.telegram.callback.CallbackDataCodec
import tj.khujand.solana.trading.bot.bot.telegram.callback.CallbackPayload

object TelegramMenuBuilder {
    fun mainMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("▶️ Старт", "trade", "start"), button("⏹ Стоп", "trade", "stop")),
                listOf(button("📊 Статус", "main", "status"), button("💰 Баланс", "main", "balance")),
                listOf(button("📈 Сделки", "main", "deals"), button("🎯 Фильтры", "main", "filters")),
                listOf(button("📤 Выход", "main", "exit"), button("👁 Мониторинг", "main", "monitoring")),
                listOf(button("🔐 Режим", "main", "mode"), button("🔄 Обновить", "main", "refresh"))
            )
        )
    }

    fun modeMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🟢 Demo", "mode", "set", "demo"), button("🔴 Real", "mode", "set", "real")),
                listOf(button("⬅️ В меню", "main", "home"))
            )
        )
    }

    fun confirmRealModeMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("✅ Да, Real", "mode", "confirm", "real")),
                listOf(button("❌ Отмена", "mode", "cancel", "real"))
            )
        )
    }

    fun balanceMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🔄 Обновить", "balance", "refresh")),
                listOf(button("⬅️ В меню", "main", "home"))
            )
        )
    }

    fun dealsMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🔄 Обновить", "deals", "refresh")),
                listOf(button("⬅️ В меню", "main", "home"))
            )
        )
    }

    fun monitoringMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🔄 Обновить", "monitoring", "refresh")),
                listOf(button("⬅️ В меню", "main", "home"))
            )
        )
    }

    fun filtersMenu(view: FilterSettingsView): TelegramInlineKeyboard =
        filtersMenu(view, 0)

    fun filtersMenu(view: FilterSettingsView, page: Int): TelegramInlineKeyboard {
        val p = page.coerceIn(0, TelegramUiPages.FILTERS_PAGE_COUNT - 1)
        val rows = mutableListOf<List<TelegramInlineKeyboardButton>>()
        if (TelegramUiPages.FILTERS_PAGE_COUNT > 1) {
            rows += listOf(
                button(
                    "◀",
                    "filters",
                    "page",
                    TelegramUiPages.prevPage(p, TelegramUiPages.FILTERS_PAGE_COUNT).toString()
                ),
                button(
                    "▶",
                    "filters",
                    "page",
                    TelegramUiPages.nextPage(p, TelegramUiPages.FILTERS_PAGE_COUNT).toString()
                )
            )
        }
        view.editableFields
            .filter { TelegramUiPages.filterFieldPage(it.key) == p }
            .forEach { field ->
                rows += listOf(
                    button("➖ ${field.title}", "filters", "dec", pp(p, field.key)),
                    button("➕ ${field.title}", "filters", "inc", pp(p, field.key))
                )
            }
        when (p) {
            0 -> {
                rows += listOf(
                    button("Vol 24h ${if (view.settings.useVolumeH24) "✅" else "❌"}", "filters", "toggle", pp(p, "useVolumeH24")),
                    button("Vol 5m ${if (view.settings.useVolumeM5) "✅" else "❌"}", "filters", "toggle", pp(p, "useVolumeM5"))
                )
                rows += listOf(
                    button("Соцсети ${if (view.settings.requireSocials) "✅" else "❌"}", "filters", "toggle", pp(p, "requireSocials")),
                    button("Сайт ${if (view.settings.requireWebsite) "✅" else "❌"}", "filters", "toggle", pp(p, "requireWebsite"))
                )
            }
            1 -> {
                rows += listOf(
                    button("AI ${if (view.settings.useAiAnalysis) "✅" else "❌"}", "filters", "toggle", pp(p, "useAiAnalysis")),
                    button("🔒 AI fail ${if (view.settings.aiFailClosed) "✅" else "❌"}", "filters", "toggle", pp(p, "aiFailClosed"))
                )
                rows += listOf(
                    button("🟢 Риск low", "filters", "set_risk", pp(p, "LOW")),
                    button("🟡 Риск med", "filters", "set_risk", pp(p, "MEDIUM")),
                    button("🔴 Риск high", "filters", "set_risk", pp(p, "HIGH"))
                )
            }
        }
        rows += listOf(button("🔄 Обновить", "filters", "refresh", p.toString()))
        rows += listOf(button("⬅️ В меню", "main", "home"))
        return TelegramInlineKeyboard(rows = rows)
    }

    fun exitStrategyMenu(view: ExitStrategyView): TelegramInlineKeyboard =
        exitStrategyMenu(view, 0)

    fun exitStrategyMenu(view: ExitStrategyView, page: Int): TelegramInlineKeyboard {
        val p = page.coerceIn(0, TelegramUiPages.EXIT_PAGE_COUNT - 1)
        val rows = mutableListOf<List<TelegramInlineKeyboardButton>>()
        val isAggressive = view.settings.exitStrategy == "aggressive"

        rows += listOf(
            button("◀", "exit", "page", TelegramUiPages.prevPage(p, TelegramUiPages.EXIT_PAGE_COUNT).toString()),
            button("▶", "exit", "page", TelegramUiPages.nextPage(p, TelegramUiPages.EXIT_PAGE_COUNT).toString())
        )

        when (p) {
            0 -> {
                // Выбор режима
                rows += listOf(
                    button("📊 Стадии ${if (!isAggressive) "✅" else "·"}", "exit", "mode", pp(p, "stages")),
                    button("⚡ Агресс. ${if (isAggressive) "✅" else "·"}", "exit", "mode", pp(p, "aggressive"))
                )
                // Пресеты одной строкой
                rows += listOf(
                    button("🛡️ Консерв.", "exit", "preset", pp(p, "conservative")),
                    button("⚖️ Баланс", "exit", "preset", pp(p, "balanced")),
                    button("🚀 Мун", "exit", "preset", pp(p, "moon"))
                )
                // Только поля активного режима
                val modeKeys = if (isAggressive) {
                    listOf("aggressiveTakeProfitPct", "aggressiveSellPct")
                } else {
                    listOf(
                        "exitStage1Cap", "exitStage1Pct",
                        "exitStage2Cap", "exitStage2Pct",
                        "exitStage3Cap", "exitStage3Pct",
                        "exitStage4Cap", "exitStage4Pct"
                    )
                }
                view.editableFields.filter { it.key in modeKeys }.forEach { field ->
                    rows += listOf(
                        button("➖ ${field.title}", "exit", "dec", pp(p, field.key)),
                        button("➕ ${field.title}", "exit", "inc", pp(p, field.key))
                    )
                }
            }
            1 -> {
                // Временные настройки
                view.editableFields
                    .filter { TelegramUiPages.exitFieldPage(it.key) == 1 }
                    .forEach { field ->
                        rows += listOf(
                            button("➖ ${field.title}", "exit", "dec", pp(p, field.key)),
                            button("➕ ${field.title}", "exit", "inc", pp(p, field.key))
                        )
                    }
                rows += listOf(
                    button("⏱️ Таймер ${if (view.settings.useTimeBasedExit) "✅" else "❌"}", "exit", "toggle", pp(p, "useTimeBasedExit")),
                    button("🕐 Часы UTC ${if (view.settings.tradingHoursEnabled) "✅" else "❌"}", "exit", "toggle", pp(p, "tradingHoursEnabled"))
                )
            }
        }

        rows += listOf(button("🔄 Обновить", "exit", "refresh", p.toString()))
        rows += listOf(button("⬅️ В меню", "main", "home"))
        return TelegramInlineKeyboard(rows = rows)
    }

    /** Сохраняет номер страницы вместе с ключом: {@code page~tail} */
    private fun pp(page: Int, tail: String): String = "$page~$tail"

    private fun button(
        text: String,
        section: String,
        action: String,
        param: String? = null
    ): TelegramInlineKeyboardButton {
        return TelegramInlineKeyboardButton(
            text = text,
            callbackData = CallbackDataCodec.encode(
                CallbackPayload(section = section, action = action, param = param)
            )
        )
    }
}
