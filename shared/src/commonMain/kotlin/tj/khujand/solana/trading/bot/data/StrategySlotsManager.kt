package tj.khujand.solana.trading.bot.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tj.khujand.solana.trading.bot.exchange.dex.FilterSettings
import tj.khujand.solana.trading.bot.util.AppSettings

object StrategySlotsManager {

    private const val KEY_SLOTS       = "strategy_slots_v1"
    private const val KEY_ACTIVE_ID   = "active_strategy_id"
    private const val KEY_RUNNING_ID  = "running_strategy_id"   // legacy (single) — migrated to the set
    private const val KEY_RUNNING_IDS = "running_strategy_ids_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ─── Read ─────────────────────────────────────────────────────────────────

    fun getSlots(): List<StrategySlot> {
        val raw = AppSettings.getStringSafe(KEY_SLOTS, "")
        val saved: List<StrategySlot> = if (raw.isNotEmpty()) {
            try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
        } else emptyList()
        return if (saved.isEmpty()) defaultSlots() else saved
    }

    fun getActiveId(): String {
        val id = AppSettings.getStringSafe(KEY_ACTIVE_ID, "")
        return if (id.isNotEmpty()) id else StrategyProfile.BALANCED.name
    }

    /** Set of all strategy ids currently running in parallel. */
    fun getRunningIds(): Set<String> {
        val raw = AppSettings.getStringSafe(KEY_RUNNING_IDS, "")
        if (raw.isNotEmpty()) {
            return try { json.decodeFromString<List<String>>(raw).toSet() } catch (_: Exception) { emptySet() }
        }
        // Migrate the legacy single-running-id key into the new set.
        val legacy = AppSettings.getStringSafe(KEY_RUNNING_ID, "")
        if (legacy.isNotEmpty()) {
            val migrated = setOf(legacy)
            setRunningIds(migrated)
            AppSettings.remove(KEY_RUNNING_ID)
            return migrated
        }
        return emptySet()
    }

    fun isRunning(id: String): Boolean = getRunningIds().contains(id)

    /** First running id (back-compat for single-strategy callers). */
    fun getRunningId(): String? = getRunningIds().firstOrNull()

    fun getSlotById(id: String): StrategySlot? = getSlots().find { it.id == id }

    // ─── Write ────────────────────────────────────────────────────────────────

    fun setActiveId(id: String) {
        AppSettings.putString(KEY_ACTIVE_ID, id)
    }

    fun setRunningIds(ids: Set<String>) {
        if (ids.isEmpty()) AppSettings.remove(KEY_RUNNING_IDS)
        else AppSettings.putString(KEY_RUNNING_IDS, json.encodeToString(ids.toList()))
    }

    fun addRunningId(id: String) {
        setRunningIds(getRunningIds() + id)
    }

    fun removeRunningId(id: String) {
        setRunningIds(getRunningIds() - id)
    }

    fun clearRunningIds() {
        setRunningIds(emptySet())
    }

    /** Back-compat: replace the whole running set with a single id (or clear). */
    fun setRunningId(id: String?) {
        if (id == null) clearRunningIds() else setRunningIds(setOf(id))
    }

    fun saveSlots(slots: List<StrategySlot>) {
        AppSettings.putString(KEY_SLOTS, json.encodeToString(slots))
    }

    fun saveOrUpdateSlot(slot: StrategySlot) {
        val current = getSlots().toMutableList()
        val idx = current.indexOfFirst { it.id == slot.id }
        if (idx >= 0) current[idx] = slot else current.add(slot)
        saveSlots(current)
    }

    fun deleteSlot(id: String) {
        val updated = getSlots().filter { it.id != id }
        saveSlots(updated)
        if (getActiveId() == id) setActiveId(updated.firstOrNull()?.id ?: StrategyProfile.BALANCED.name)
        if (isRunning(id)) removeRunningId(id)
    }

    /**
     * Applies a slot's settings to FilterSettingsManager, injecting the
     * global secrets (seed phrase, Jupiter key, AI key) that are stored
     * separately and never inside a slot.
     */
    fun applySlot(slot: StrategySlot, globalSecrets: FilterSettings): FilterSettings {
        return slot.settings.copy(
            seedPhrase    = globalSecrets.seedPhrase,
            jupiterApiKey = globalSecrets.jupiterApiKey,
            aiApiKey      = globalSecrets.aiApiKey,
        )
    }

    /** Saves the current global settings (without secrets) back into a slot. */
    fun syncSlotFromGlobal(slotId: String, global: FilterSettings) {
        val slot = getSlotById(slotId) ?: return
        val sanitized = global.copy(seedPhrase = "", jupiterApiKey = "", aiApiKey = "")
        saveOrUpdateSlot(slot.copy(settings = sanitized))
    }

    // ─── Defaults ─────────────────────────────────────────────────────────────

    private fun defaultSlots(): List<StrategySlot> {
        val base = FilterSettings()
        return listOf(
            StrategySlot(
                id       = StrategyProfile.AGGRESSIVE.name,
                name     = StrategyProfile.AGGRESSIVE.label,
                emoji    = StrategyProfile.AGGRESSIVE.emoji,
                colorHex = "#FF6B35",
                settings = StrategyProfile.AGGRESSIVE.applyTo(base),
            ),
            StrategySlot(
                id       = StrategyProfile.BALANCED.name,
                name     = StrategyProfile.BALANCED.label,
                emoji    = StrategyProfile.BALANCED.emoji,
                colorHex = "#3B82F6",
                settings = StrategyProfile.BALANCED.applyTo(base),
            ),
            StrategySlot(
                id       = StrategyProfile.CONSERVATIVE.name,
                name     = StrategyProfile.CONSERVATIVE.label,
                emoji    = StrategyProfile.CONSERVATIVE.emoji,
                colorHex = "#10B981",
                settings = StrategyProfile.CONSERVATIVE.applyTo(base),
            ),
            StrategySlot(
                id       = StrategyProfile.SCALPING.name,
                name     = StrategyProfile.SCALPING.label,
                emoji    = StrategyProfile.SCALPING.emoji,
                colorHex = "#9747FF",
                settings = StrategyProfile.SCALPING.applyTo(base),
            ),
        )
    }
}
