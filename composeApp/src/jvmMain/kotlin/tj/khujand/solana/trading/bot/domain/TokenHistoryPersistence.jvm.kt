package tj.khujand.solana.trading.bot.domain

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tj.khujand.solana.trading.bot.util.AppSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val LEGACY_KEY = "token_history_v1"

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val listSerializer = ListSerializer(TokenHistory.serializer())

private fun historyPath(): Path {
    val dir = Path.of(System.getProperty("user.home"), ".SolTradBot")
    Files.createDirectories(dir)
    return dir.resolve("token_history_v1.json")
}

internal actual object TokenHistoryPersistence {

    actual fun load(): List<TokenHistory> {
        val file = historyPath()
        if (Files.exists(file)) {
            return try {
                val text = Files.readString(file, StandardCharsets.UTF_8)
                if (text.isBlank()) emptyList()
                else json.decodeFromString(listSerializer, text)
            } catch (e: Exception) {
                println("❌ Ошибка чтения истории сделок из файла: ${e.message}")
                emptyList()
            }
        }

        val legacy = AppSettings.getObjectSafe(LEGACY_KEY, emptyList<TokenHistory>())
        if (legacy.isNotEmpty()) {
            try {
                save(legacy)
                AppSettings.remove(LEGACY_KEY)
                println("✅ История сделок перенесена в ~/.SolTradBot/token_history_v1.json (обход лимита Java Preferences ~8KB)")
            } catch (e: Exception) {
                println("⚠️ Не удалось сохранить историю в файл после миграции: ${e.message}")
            }
        }
        return legacy
    }

    actual fun save(list: List<TokenHistory>) {
        try {
            val path = historyPath()
            Files.createDirectories(path.parent)
            val text = json.encodeToString(listSerializer, list)
            Files.writeString(
                path,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            AppSettings.remove(LEGACY_KEY)
        } catch (e: Exception) {
            println("❌ Ошибка сохранения истории сделок в файл: ${e.message}")
        }
    }

    actual fun clear() {
        try {
            val p = historyPath()
            if (Files.exists(p)) Files.deleteIfExists(p)
        } catch (e: Exception) {
            println("⚠️ Не удалось удалить файл истории: ${e.message}")
        }
        AppSettings.remove(LEGACY_KEY)
    }
}
