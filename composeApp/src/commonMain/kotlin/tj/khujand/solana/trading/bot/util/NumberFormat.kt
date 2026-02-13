package tj.khujand.solana.trading.bot.util

import kotlin.math.pow
import kotlin.math.round

/**
 * KMP-совместимое форматирование чисел (без String.format — работает на всех платформах).
 */

/** Округление до N знаков после запятой */
private fun Double.roundToDecimals(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return round(this * factor) / factor
}

/** Убрать лишние нули в конце */
private fun String.removeTrailingZeros(): String = trimEnd('0').trimEnd('.')

/**
 * Формат числа с фиксированным количеством знаков после запятой (аналог %.2f, %.4f и т.д.).
 * Поддерживает малые числа без научной нотации (1.073E-4 → 0.0001073).
 */
fun formatNumber(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = value.roundToDecimals(decimals)
    val str = rounded.toString()
    // Double.toString() даёт "1.073E-4" для малых чисел — форматируем вручную
    if (str.contains('e', ignoreCase = true)) {
        val sign = if (rounded < 0) "-" else ""
        val v = kotlin.math.abs(rounded)
        if (v == 0.0) return if (decimals > 0) "0.${"0".repeat(decimals)}" else "0"
        val factor = 10.0.pow(decimals)
        val scaled = round(v * factor).toLong()
        val decimalPart = scaled.toString().padStart(decimals, '0').take(decimals)
        return sign + "0." + decimalPart.removeTrailingZeros().ifEmpty { "0" }
    }
    val parts = str.split('.')
    return if (decimals <= 0) {
        parts[0]
    } else if (parts.size == 1) {
        "${parts[0]}.${"0".repeat(decimals)}"
    } else {
        val decimalPart = parts[1].replace(Regex("[eE].*"), "").padEnd(decimals, '0').take(decimals)
        "${parts[0]}.$decimalPart".removeTrailingZeros()
    }
}

/**
 * Формат числа с разделителями тысяч (аналог %,.0f) — для больших сумм.
 */
fun formatNumberWithCommas(value: Double, decimals: Int = 0): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = value.roundToDecimals(decimals)
    val parts = rounded.toString().split('.')
    val intPart = parts[0].replace(Regex("(-?\\d)(?=(\\d{3})+$)"), "$1,")
    val decPart = if (decimals > 0 && parts.size > 1) {
        parts[1].padEnd(decimals, '0').take(decimals)
    } else null
    return if (decPart != null) "$intPart.$decPart" else intPart
}

/**
 * Умное форматирование: большие числа без дробной части, маленькие — с 2–4 знаками.
 */
fun formatNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    return when {
        value >= 1000 -> value.roundToDecimals(0).toLong().toString()
        value >= 1 -> value.roundToDecimals(2).toString().removeTrailingZeros()
        else -> value.roundToDecimals(4).toString().removeTrailingZeros()
    }
}

/** Короткий формат: 1K, 1.5M, 2B */
fun formatSimpleNumber(number: Int): String = when {
    number >= 1_000_000 -> "${number / 1_000_000}M"
    number >= 1_000 -> "${number / 1_000}K"
    else -> number.toString()
}

/** Демо-баланс: всегда 2 знака после запятой (например 9999.00) */
fun formatDemoBalance(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0.00"
    val rounded = round(value * 100) / 100
    val parts = rounded.toString().split('.')
    return if (parts.size == 1) "${parts[0]}.00" else {
        val dec = parts[1].padEnd(2, '0').take(2)
        "${parts[0]}.$dec"
    }
}

/** Большие числа в виде 1.5K, 2.3M, 1.2B */
fun formatLargeNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    return when {
        value >= 1_000_000_000 -> formatNumber(value / 1_000_000_000, 2) + "B"
        value >= 1_000_000 -> formatNumber(value / 1_000_000, 2) + "M"
        value >= 1_000 -> formatNumber(value / 1_000, 2) + "K"
        value >= 1 -> formatNumber(value, 0)
        else -> formatNumber(value, 4)
    }
}
