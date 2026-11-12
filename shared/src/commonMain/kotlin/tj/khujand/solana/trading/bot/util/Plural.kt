package tj.khujand.solana.trading.bot.util

import kotlin.math.abs

/** Русское склонение по числу: 1 монета, 2 монеты, 5 монет, 11 монет, 21 монета. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val n100 = abs(n) % 100
    val n10 = n100 % 10
    return when {
        n100 in 11..14 -> many
        n10 == 1 -> one
        n10 in 2..4 -> few
        else -> many
    }
}
