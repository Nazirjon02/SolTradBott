package tj.khujand.solana.trading.bot

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform