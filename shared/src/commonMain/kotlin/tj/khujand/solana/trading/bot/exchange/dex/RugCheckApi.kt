package tj.khujand.solana.trading.bot.exchange.dex

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RugCheckReport(
    val mint: String = "",
    val score: Int = 0,
    @SerialName("score_normalised") val scoreNormalised: Int = 0,
    val risks: List<RugRisk> = emptyList(),
)

@Serializable
data class RugRisk(
    val name: String = "",
    val description: String = "",
    val level: String = "info",  // "info" | "warn" | "danger"
    val score: Int = 0,
)

enum class RugRiskLevel { GOOD, WARN, DANGER, UNKNOWN }

data class RugCheckResult(
    val score: Int,
    val level: RugRiskLevel,
    val topRisks: List<String>,
    val passed: Boolean,
)

object RugCheckApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 8_000 }
        install(ContentNegotiation) { json(json) }
    }

    suspend fun check(
        mintAddress: String,
        maxScoreAllowed: Int = 5000,
        failClosed: Boolean = true,
    ): RugCheckResult {
        return try {
            val response: HttpResponse = client.get(
                "https://api.rugcheck.xyz/v1/tokens/$mintAddress/report"
            ) {
                header("Accept", "application/json")
            }
            val body = response.bodyAsText()
            val report = json.decodeFromString<RugCheckReport>(body)

            val dangerCount = report.risks.count { it.level == "danger" }
            val level = when {
                dangerCount >= 2 || report.score > 8000 -> RugRiskLevel.DANGER
                dangerCount == 1 || report.score > maxScoreAllowed -> RugRiskLevel.WARN
                else -> RugRiskLevel.GOOD
            }

            val topRisks = report.risks
                .filter { it.level in listOf("danger", "warn") }
                .sortedByDescending { it.score }
                .take(3)
                .map { it.name }

            RugCheckResult(
                score    = report.score,
                level    = level,
                topRisks = topRisks,
                passed   = level != RugRiskLevel.DANGER && report.score <= maxScoreAllowed,
            )
        } catch (e: Exception) {
            // API недоступен: fail-closed = отклоняем токен; fail-open = пропускаем
            RugCheckResult(
                score    = -1,
                level    = RugRiskLevel.UNKNOWN,
                topRisks = emptyList(),
                passed   = !failClosed,
            )
        }
    }
}
