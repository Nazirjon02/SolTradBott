package tj.khujand.solana.trading.bot.util

import tj.khujand.solana.trading.bot.domain.TokenHistory

expect fun exportTradesToCsv(trades: List<TokenHistory>): String
