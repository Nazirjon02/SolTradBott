package tj.khujand.solana.trading.bot.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tj.khujand.solana.trading.bot.util.AppSettings

@Serializable
data class EquitySnapshot(
    val timestampMs: Long,
    val balanceUsd: Double,
)

object EquityManager {
    private const val KEY = "equity_curve_v1"
    private const val MAX_POINTS = 500
    private val json = Json { ignoreUnknownKeys = true }

    fun addSnapshot(balanceUsd: Double) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val list = loadSnapshots().toMutableList()
        // Не добавляем если баланс не изменился и прошло < 5 минут
        val last = list.lastOrNull()
        if (last != null && last.balanceUsd == balanceUsd && (now - last.timestampMs) < 300_000L) return
        list.add(EquitySnapshot(now, balanceUsd))
        if (list.size > MAX_POINTS) list.subList(0, list.size - MAX_POINTS).clear()
        AppSettings.putString(KEY, json.encodeToString(list))
    }

    fun loadSnapshots(): List<EquitySnapshot> {
        val raw = AppSettings.getStringSafe(KEY, "") ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
    }

    fun clear() = AppSettings.remove(KEY)
}
