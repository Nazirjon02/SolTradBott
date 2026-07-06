package tj.khujand.solana.trading.bot.server

import java.io.File

data class DrxConfig(
    val rpcUrl: String = env("SOLANA_RPC_URL") ?: "https://api.mainnet-beta.solana.com",
    // Seed-фраза кошелька для REAL-режима. Приоритет: env > БД (SettingsStore).
    val walletSeed: String = env("SOLANA_WALLET_SEED") ?: "",
    // true = paper-trading (DEMO). Начальное значение; в рантайме переключается из UI/Telegram.
    val demoMode: Boolean = env("DEMO_MODE")?.toBoolean() ?: true,

    val telegramToken: String = env("TG_BOT_TOKEN") ?: "",
    val telegramChatId: Long = env("TG_CHAT_ID")?.toLongOrNull() ?: 0L,

    val serverPort: Int = env("PORT")?.toIntOrNull() ?: 8080,
    val apiKey: String = env("SOLTRAD_API_KEY") ?: "soltrad-secret",

    val dbPath: String = env("DB_PATH") ?: "soltradbot.db",
)

/**
 * Возвращает значение переменной окружения с поддержкой `.env` файла в рабочей папке.
 *
 * Приоритет:
 *   1. Реальная переменная окружения (System.getenv)
 *   2. Запись в `.env` файле рядом с исполняемым файлом
 *   3. null
 */
private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() } ?: DotEnv.get(key)

/** Минимальный парсер `.env` файла (как в MRX). Кэширует содержимое при первом обращении. */
private object DotEnv {
    private val values: Map<String, String> by lazy { loadDotEnv() }

    fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }

    private fun loadDotEnv(): Map<String, String> {
        val candidates = listOf(
            File(".env"),
            File("../.env"),
            File("../../.env")
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile } ?: return emptyMap()
        return try {
            val out = mutableMapOf<String, String>()
            file.useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    val k = line.substring(0, eq).trim()
                    var v = line.substring(eq + 1).trim()
                    if ((v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) ||
                        (v.startsWith("'") && v.endsWith("'") && v.length >= 2)
                    ) {
                        v = v.substring(1, v.length - 1)
                    }
                    out[k] = v
                }
            }
            println("📄 Загружен .env: ${file.absolutePath} (${out.size} переменных)")
            out
        } catch (e: Throwable) {
            println("⚠️ Не удалось прочитать ${file.absolutePath}: ${e.message}")
            emptyMap()
        }
    }
}
