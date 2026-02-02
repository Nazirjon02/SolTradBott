package tj.khujand.solana.trading.bot.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tj.khujand.solana.trading.bot.network.FilterSettings
import tj.khujand.solana.trading.bot.util.AppSettings

object FilterSettingsManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val KEY_FILTER_SETTINGS = "filter_settings_v1"
    private const val KEY_MIN_VOLUME = "filter_min_volume"
    private const val KEY_MAX_AGE = "filter_max_age"
    private const val KEY_MIN_LIQUIDITY = "filter_min_liquidity"
    private const val KEY_CHAINS = "filter_chains"
    private const val KEY_EXCLUDE_RUG = "filter_exclude_rug"
    private const val KEY_CHECK_HOLDERS = "filter_check_holders"

    // Сохранить настройки
    fun saveSettings(settings: FilterSettings) {
        try {
            // Сохраняем каждое поле отдельно (проще для UI)
            AppSettings.putDouble(KEY_MIN_VOLUME, settings.minVolumeUSD)
            AppSettings.putInt(KEY_MAX_AGE, settings.maxAgeHours)
            AppSettings.putDouble(KEY_MIN_LIQUIDITY, settings.minLiquidityUSD)
            AppSettings.putBoolean(KEY_EXCLUDE_RUG, settings.excludeRugPull)
            AppSettings.putBoolean(KEY_CHECK_HOLDERS, settings.checkHolders)

            // Сохраняем список цепочек как JSON
            val chainsJson = json.encodeToString(settings.chains)
            AppSettings.putString(KEY_CHAINS, chainsJson)

            // Также сохраняем весь объект целиком как JSON (для быстрой загрузки)
            val fullSettingsJson = json.encodeToString(settings)
            AppSettings.putString(KEY_FILTER_SETTINGS, fullSettingsJson)

        } catch (e: Exception) {
            println("Ошибка сохранения настроек: ${e.message}")
        }
    }

    // Загрузить настройки
    fun loadSettings(): FilterSettings {
        return try {
            // Пробуем загрузить полный объект
            val fullJson = AppSettings.getStringSafe(KEY_FILTER_SETTINGS, "")
            if (fullJson.isNotEmpty()) {
                json.decodeFromString<FilterSettings>(fullJson)
            } else {
                // Или загружаем по отдельным полям (для обратной совместимости)
                loadFromIndividualFields()
            }
        } catch (e: Exception) {
            println("Ошибка загрузки настроек, используем значения по умолчанию: ${e.message}")
            FilterSettings() // Значения по умолчанию
        }
    }

    // Загрузить из отдельных полей (старый способ)
    private fun loadFromIndividualFields(): FilterSettings {
        val minVolume = AppSettings.getDoubleSafe(KEY_MIN_VOLUME, 5000.0)
        val maxAge = AppSettings.getIntSafe(KEY_MAX_AGE, 24)
        val minLiquidity = AppSettings.getDoubleSafe(KEY_MIN_LIQUIDITY, 10000.0)
        val excludeRug = AppSettings.getBooleanSafe(KEY_EXCLUDE_RUG, true)
        val checkHolders = AppSettings.getBooleanSafe(KEY_CHECK_HOLDERS, false)

        // Загружаем список цепочек
        val chainsJson = AppSettings.getStringSafe(KEY_CHAINS, "")
        val chains = if (chainsJson.isNotEmpty()) {
            try {
                json.decodeFromString<List<String>>(chainsJson)
            } catch (e: Exception) {
                listOf("solana") // По умолчанию только Solana
            }
        } else {
            listOf("solana") // По умолчанию только Solana
        }

        // Загружаем новые поля из конфига (значения по умолчанию)
        return FilterSettings(
            minVolumeUSD = minVolume,
            maxAgeHours = maxAge,
            minLiquidityUSD = minLiquidity,
            chains = chains,
            excludeRugPull = excludeRug,
            checkHolders = checkHolders,
            // Новые поля из конфига
            liquidityMinUsd = 200.0,
            volumeH24MinUsd = 1000.0,
            pairMaxAgeHours = 1.0,
            buysH1Min = 1,
            maxSellsToBuysRatioH1 = 1.2,
            maxAbsPriceChangeH1Pct = 250.0,
            maxTokensPerTick = 2,
            minScoreAccept = 10,
            rpcUrl = "https://api.mainnet-beta.solana.com",
            rpcTimeoutSeconds = 12,
            useTokenBoostsApi = true, // По умолчанию используем token-boosts
            cooldownMinutes = 180,
            maxReentriesAfterClose = 1,
            autoStopEnabled = true,
            autoStopDropPercent = 15.0,
            autoStopFromPeak = true
        )
    }

    // Сбросить настройки к значениям по умолчанию
    fun resetToDefaults() {
        AppSettings.remove(KEY_FILTER_SETTINGS)
        AppSettings.remove(KEY_MIN_VOLUME)
        AppSettings.remove(KEY_MAX_AGE)
        AppSettings.remove(KEY_MIN_LIQUIDITY)
        AppSettings.remove(KEY_CHAINS)
        AppSettings.remove(KEY_EXCLUDE_RUG)
        AppSettings.remove(KEY_CHECK_HOLDERS)
    }

    // Проверить есть ли сохраненные настройки
    fun hasSavedSettings(): Boolean {
        return AppSettings.getStringSafe(KEY_FILTER_SETTINGS, "").isNotEmpty() ||
                AppSettings.getStringSafe(KEY_CHAINS, "").isNotEmpty()
    }
}