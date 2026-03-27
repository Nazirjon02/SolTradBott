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
                listOf(button("▶️ Start", "trade", "start"), button("⏹ Stop", "trade", "stop")),
                listOf(button("📊 Status", "main", "status"), button("💼 Balance", "main", "balance")),
                listOf(button("🧾 Deals", "main", "deals"), button("⚙️ Filters", "main", "filters")),
                listOf(button("📤 Exit Strategy", "main", "exit")),
                listOf(button("🔁 Mode", "main", "mode"), button("🔄 Refresh", "main", "refresh"))
            )
        )
    }

    fun modeMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🟢 DEMO", "mode", "set", "demo"), button("🔴 REAL", "mode", "set", "real")),
                listOf(button("⬅️ Back", "main", "home"))
            )
        )
    }

    fun confirmRealModeMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("✅ Confirm REAL", "mode", "confirm", "real")),
                listOf(button("❌ Cancel", "mode", "cancel", "real"))
            )
        )
    }

    fun balanceMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🔄 Refresh", "balance", "refresh")),
                listOf(button("⬅️ Back", "main", "home"))
            )
        )
    }

    fun dealsMenu(): TelegramInlineKeyboard {
        return TelegramInlineKeyboard(
            rows = listOf(
                listOf(button("🔄 Refresh", "deals", "refresh")),
                listOf(button("⬅️ Back", "main", "home"))
            )
        )
    }

    fun filtersMenu(view: FilterSettingsView): TelegramInlineKeyboard {
        val rows = mutableListOf<List<TelegramInlineKeyboardButton>>()
        view.editableFields.forEach { field ->
            rows += listOf(
                button("➖ ${field.title}", "filters", "dec", field.key),
                button("➕ ${field.title}", "filters", "inc", field.key)
            )
        }
        rows += listOf(
            button("AI ${if (view.settings.useAiAnalysis) "✅" else "❌"}", "filters", "toggle", "useAiAnalysis"),
            button("Vol5m ${if (view.settings.useVolumeM5) "✅" else "❌"}", "filters", "toggle", "useVolumeM5")
        )
        rows += listOf(
            button("AI fail-closed ${if (view.settings.aiFailClosed) "✅" else "❌"}", "filters", "toggle", "aiFailClosed")
        )
        rows += listOf(
            button("Vol24h ${if (view.settings.useVolumeH24) "✅" else "❌"}", "filters", "toggle", "useVolumeH24")
        )
        rows += listOf(
            button("Socials ${if (view.settings.requireSocials) "✅" else "❌"}", "filters", "toggle", "requireSocials"),
            button("Website ${if (view.settings.requireWebsite) "✅" else "❌"}", "filters", "toggle", "requireWebsite")
        )
        rows += listOf(
            button("RISK LOW", "filters", "set_risk", "LOW"),
            button("RISK MED", "filters", "set_risk", "MEDIUM"),
            button("RISK HIGH", "filters", "set_risk", "HIGH")
        )
        rows += listOf(button("🔄 Refresh", "filters", "refresh"))
        rows += listOf(button("⬅️ Back", "main", "home"))
        return TelegramInlineKeyboard(rows = rows)
    }

    fun exitStrategyMenu(view: ExitStrategyView): TelegramInlineKeyboard {
        val rows = mutableListOf<List<TelegramInlineKeyboardButton>>()
        rows += listOf(
            button("Stages ${if (view.settings.exitStrategy == "stages") "✅" else ""}", "exit", "mode", "stages"),
            button("Aggressive ${if (view.settings.exitStrategy == "aggressive") "✅" else ""}", "exit", "mode", "aggressive")
        )
        view.editableFields.forEach { field ->
            rows += listOf(
                button("➖ ${field.title}", "exit", "dec", field.key),
                button("➕ ${field.title}", "exit", "inc", field.key)
            )
        }
        rows += listOf(button("🔄 Refresh", "exit", "refresh"))
        rows += listOf(button("⬅️ Back", "main", "home"))
        return TelegramInlineKeyboard(rows = rows)
    }

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
