package tj.khujand.solana.trading.bot.exchange.chain

import java.io.File

/** На телефоне отчёт сверки в файл не пишется: итог виден в настройках и ленте. */
actual fun chainReportDir(): String? = null

actual fun appendChainFile(path: String, text: String) {
    runCatching { File(path).apply { parentFile?.mkdirs() }.appendText(text) }
}
