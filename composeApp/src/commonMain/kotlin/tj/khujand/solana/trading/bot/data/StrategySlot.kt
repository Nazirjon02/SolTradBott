package tj.khujand.solana.trading.bot.data

import kotlinx.serialization.Serializable
import tj.khujand.solana.trading.bot.network.FilterSettings

@Serializable
data class StrategySlot(
    val id: String,
    val name: String,
    val emoji: String,
    val colorHex: String,
    val settings: FilterSettings,
    val isCustom: Boolean = false,
)

fun parseStrategyColor(hex: String): Triple<Int, Int, Int> {
    val stripped = hex.trimStart('#').padStart(6, '0')
    val r = stripped.substring(0, 2).toInt(16)
    val g = stripped.substring(2, 4).toInt(16)
    val b = stripped.substring(4, 6).toInt(16)
    return Triple(r, g, b)
}
