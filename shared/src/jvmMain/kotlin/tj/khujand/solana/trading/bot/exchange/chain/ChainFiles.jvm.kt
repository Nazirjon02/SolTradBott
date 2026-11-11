package tj.khujand.solana.trading.bot.exchange.chain

import java.io.File

/** Рядом с базой бота: DB_PATH, как у десктопа, иначе ~/.soltradbot. */
actual fun chainReportDir(): String? {
    val db = System.getenv("DB_PATH")?.takeIf { it.isNotBlank() }
    val base = db?.let { File(it).absoluteFile.parentFile } ?: File(System.getProperty("user.home"), ".soltradbot")
    return File(base, "chain").absolutePath
}

actual fun appendChainFile(path: String, text: String) {
    runCatching { File(path).apply { parentFile?.mkdirs() }.appendText(text) }
}
