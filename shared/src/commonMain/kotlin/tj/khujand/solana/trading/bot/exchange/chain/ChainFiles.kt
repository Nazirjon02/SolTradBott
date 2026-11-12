package tj.khujand.solana.trading.bot.exchange.chain

/** Папка отчётов прототипа (десктоп — `chain` рядом с базой); null — писать некуда (Android). */
expect fun chainReportDir(): String?

/** Дописать текст в файл, создав папки; ошибка записи прототип не роняет. */
expect fun appendChainFile(path: String, text: String)

/** Ключ API из адреса узла (`api-key=…`) — в текстах ошибок и журналах заменяется звёздочками. */
fun hideApiKey(text: String?): String? = text?.replace(Regex("(api[-_]?key=)[A-Za-z0-9_.~-]+", RegexOption.IGNORE_CASE), "$1***")
