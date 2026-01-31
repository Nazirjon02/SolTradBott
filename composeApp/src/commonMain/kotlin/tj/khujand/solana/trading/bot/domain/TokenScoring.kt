package tj.khujand.solana.trading.bot.domain

import tj.khujand.solana.trading.bot.network.SplMintInfo
import tj.khujand.solana.trading.bot.network.TokenPair

/**
 * Результат подсчета очков токена
 */
data class TokenScoreResult(
    val totalScore: Int,
    val reasons: List<String> = emptyList(),
    val hardRejectReasons: List<String> = emptyList(),
    val isAccepted: Boolean
)

/**
 * Система подсчета очков для токенов
 */
object TokenScoring {
    
    private const val INITIAL_SCORE = 50
    private const val MIN_SCORE_ACCEPT = 70
    
    /**
     * Подсчитать очки для токена
     */
    fun calculateScore(
        token: TokenPair,
        mintInfo: SplMintInfo?,
        settings: tj.khujand.solana.trading.bot.network.FilterSettings
    ): TokenScoreResult {
        var score = INITIAL_SCORE
        val reasons = mutableListOf<String>()
        val hardRejectReasons = mutableListOf<String>()
        
        // ЖЕСТКИЕ ОТКЛОНЕНИЯ (проверяем первыми)
        if (mintInfo != null) {
            if (mintInfo.hasMintAuthority) {
                hardRejectReasons.add("Mint authority не отозвано")
                return TokenScoreResult(
                    totalScore = 0,
                    hardRejectReasons = hardRejectReasons,
                    isAccepted = false
                )
            }
            
            if (mintInfo.hasFreezeAuthority) {
                hardRejectReasons.add("Freeze authority не отозвано")
                return TokenScoreResult(
                    totalScore = 0,
                    hardRejectReasons = hardRejectReasons,
                    isAccepted = false
                )
            }
        }
        
        // БОНУСЫ
        
        // 1. Высокая ликвидность
        val liquidity = token.liquidity?.usd ?: 0.0
        when {
            liquidity >= 50000 -> {
                score += 15
                reasons.add("Высокая ликвидность (${formatNumber(liquidity)})")
            }
            liquidity >= 20000 -> {
                score += 10
                reasons.add("Хорошая ликвидность (${formatNumber(liquidity)})")
            }
            liquidity >= 10000 -> {
                score += 5
                reasons.add("Удовлетворительная ликвидность (${formatNumber(liquidity)})")
            }
            liquidity < settings.liquidityMinUsd -> {
                score -= 20
                reasons.add("Низкая ликвидность (${formatNumber(liquidity)})")
            }
        }
        
        // 2. Большой объем
        val volumeH24 = token.volume?.h24 ?: 0.0
        when {
            volumeH24 >= 100000 -> {
                score += 15
                reasons.add("Высокий объем 24ч (${formatNumber(volumeH24)})")
            }
            volumeH24 >= 50000 -> {
                score += 10
                reasons.add("Хороший объем 24ч (${formatNumber(volumeH24)})")
            }
            volumeH24 >= 10000 -> {
                score += 5
                reasons.add("Удовлетворительный объем 24ч (${formatNumber(volumeH24)})")
            }
            volumeH24 < settings.volumeH24MinUsd -> {
                score -= 15
                reasons.add("Низкий объем 24ч (${formatNumber(volumeH24)})")
            }
        }
        
        // 3. Много покупок H1
        val buysH1 = token.txns?.h1?.buys ?: 0
        when {
            buysH1 >= 50 -> {
                score += 10
                reasons.add("Много покупок H1 ($buysH1)")
            }
            buysH1 >= 20 -> {
                score += 5
                reasons.add("Хорошее количество покупок H1 ($buysH1)")
            }
            buysH1 < settings.buysH1Min -> {
                score -= 10
                reasons.add("Мало покупок H1 ($buysH1)")
            }
        }
        
        // 4. Отозвано разрешение на создание токенов (mintAuthority)
        if (mintInfo != null && !mintInfo.hasMintAuthority) {
            score += 10
            reasons.add("Mint authority отозвано")
        }
        
        // 5. Отозвано разрешение на заморозку (freezeAuthority)
        if (mintInfo != null && !mintInfo.hasFreezeAuthority) {
            score += 10
            reasons.add("Freeze authority отозвано")
        }
        
        // ШТРАФЫ
        
        // 6. Высокое соотношение продаж/покупок H1
        val sellsH1 = token.txns?.h1?.sells ?: 0
        if (buysH1 > 0) {
            val sellBuyRatio = sellsH1.toDouble() / buysH1
            when {
                sellBuyRatio > settings.maxSellsToBuysRatioH1 -> {
                    score -= 15
                    reasons.add("Высокое соотношение продаж/покупок H1 ($sellBuyRatio)")
                }
                sellBuyRatio > 1.0 -> {
                    score -= 5
                    reasons.add("Превышение продаж над покупками H1 ($sellBuyRatio)")
                }
            }
        }
        
        // 7. Экстремальное изменение цены H1
        // Примечание: priceChange.h1 может быть в разных форматах, используем простую проверку
        // Если есть поле priceChange, можно добавить проверку
        
        // Ограничиваем очки в диапазоне 0-100
        score = score.coerceIn(0, 100)
        
        val isAccepted = score >= settings.minScoreAccept && hardRejectReasons.isEmpty()
        
        return TokenScoreResult(
            totalScore = score,
            reasons = reasons,
            hardRejectReasons = hardRejectReasons,
            isAccepted = isAccepted
        )
    }
    
    private fun formatNumber(value: Double): String {
        return when {
            value >= 1_000_000 -> {
                val millions = value / 1_000_000
                "${roundTo2Decimals(millions)}M"
            }
            value >= 1_000 -> {
                val thousands = value / 1_000
                "${roundTo2Decimals(thousands)}K"
            }
            else -> roundTo2Decimals(value)
        }
    }
    
    private fun roundTo2Decimals(value: Double): String {
        // Округляем до 2 знаков после запятой
        val rounded = kotlin.math.round(value * 100) / 100.0
        val str = rounded.toString()
        
        // Убираем лишние нули после запятой
        return if (str.contains('.')) {
            str.trimEnd('0').trimEnd('.')
        } else {
            str
        }
    }
}
