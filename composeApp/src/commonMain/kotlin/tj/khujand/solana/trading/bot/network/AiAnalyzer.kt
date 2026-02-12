package tj.khujand.solana.trading.bot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// ════════════════════════════════════════════════════════════════════════════════
// AI ANALYZER - анализ токенов через Claude/GPT перед входом
// ════════════════════════════════════════════════════════════════════════════════

// ──── Модели для API запросов ────
@Serializable
data class GroqRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val max_tokens: Int = 1024,
    val temperature: Double = 0.7
)

@Serializable
data class GroqMessage(
    val role: String,
    val content: String
)

@Serializable
data class ClaudeRequest(
    val model: String,
    val max_tokens: Int = 1024,
    val messages: List<ClaudeMessage>
)

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String
)

// ──── Результат анализа ────
@Serializable
data class AiAnalysisResult(
    val score: Int,                    // 0-100
    val rugRisk: String,               // LOW | MEDIUM | HIGH | CRITICAL
    val momentumPhase: String,         // ACCUMULATION | EARLY_PUMP | FOMO_PEAK | DISTRIBUTION
    val entrySignal: String,           // STRONG_BUY | BUY | WAIT | AVOID | STRONG_AVOID
    val confidence: Double,            // 0.0-1.0
    val redFlags: String,              // comma separated or NONE
    val greenFlags: String,            // comma separated or NONE
    val reason: String,                // one sentence explanation
    val optimalEntryCap: String,       // NOW or $value
    val predictedPeakCap: String,      // $estimated peak
    val rawResponse: String = ""       // полный ответ AI (для отладки)
)

class AiAnalyzer(private val settings: FilterSettings) {
    
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = (settings.aiTimeoutSeconds * 1000).toLong()
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 🤖 АНАЛИЗ ТОКЕНА через AI
    // ════════════════════════════════════════════════════════════════════════════════
    suspend fun analyzeToken(pair: TokenPair, ageMinutes: Long): AiAnalysisResult? {
        if (!settings.useAiAnalysis || settings.aiApiKey.isBlank()) {
            return null
        }

        return try {
            val prompt = buildEntryAnalysisPrompt(pair, ageMinutes)
            val response = when (settings.aiProvider.lowercase()) {
                "groq" -> callGroq(prompt)
                "claude" -> callClaude(prompt)
                "openai" -> callOpenAI(prompt)
                else -> {
                    println("⚠️ Unknown AI provider: ${settings.aiProvider}")
                    return null
                }
            }
            parseAiResponse(response)
        } catch (e: Exception) {
            println("❌ AI Analysis error: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 📝 ПОСТРОЕНИЕ ПРОМТА ДЛЯ AI
    // ════════════════════════════════════════════════════════════════════════════════
    private fun buildEntryAnalysisPrompt(pair: TokenPair, ageMinutes: Long): String {
        val lpRatio = (pair.liquidity?.usd ?: 0.0) / (pair.marketCap ?: 1.0) * 100
        val volumeToLiqRatio = (pair.volume?.h24 ?: 0.0) / (pair.liquidity?.usd?.coerceAtLeast(1.0) ?: 1.0)
        val buysM5 = pair.txns?.m5?.buys ?: 0
        val sellsM5 = pair.txns?.m5?.sells ?: 1
        val buySellRatio = buysM5.toDouble() / sellsM5.coerceAtLeast(1)
        
        return """
You are an expert Solana memecoin analyzer specializing in detecting rug pulls and finding early gems. Analyze this token:

═══════════════════════════════════════
📊 TOKEN: ${pair.baseToken?.symbol ?: "Unknown"} (${pair.baseToken?.name ?: "Unknown"})
Address: ${pair.baseToken?.address ?: "Unknown"}
═══════════════════════════════════════

💰 FINANCIALS:
- Market Cap: ${'$'}${String.format("%,.0f", pair.marketCap ?: 0.0)}
- Liquidity (USD): ${'$'}${String.format("%,.0f", pair.liquidity?.usd ?: 0.0)}
- LP/MC Ratio: ${String.format("%.2f", lpRatio)}% ${when {
            lpRatio < 3 -> "⚠️ CRITICAL - Very low liquidity"
            lpRatio < 5 -> "⚠️ WARNING - Low liquidity"
            lpRatio in 5.0..15.0 -> "✅ HEALTHY"
            else -> "⚠️ Too high - possibly fake"
        }}

📈 VOLUME ANALYSIS:
- Last 5 minutes: ${'$'}${String.format("%,.0f", pair.volume?.m5 ?: 0.0)}
- Last 1 hour: ${'$'}${String.format("%,.0f", pair.volume?.h1 ?: 0.0)}
- Last 24 hours: ${'$'}${String.format("%,.0f", pair.volume?.h24 ?: 0.0)}
- Volume/Liquidity Ratio: ${String.format("%.2f", volumeToLiqRatio)}x ${if (volumeToLiqRatio > 10) "⚠️ SUSPICIOUS - Possible wash trading" else ""}

🔄 TRADING ACTIVITY (Last 5 min):
- Total Buys: $buysM5
- Total Sells: $sellsM5
- Buy/Sell Ratio: ${String.format("%.2f", buySellRatio)} ${when {
            buySellRatio > 3 -> "🚀 STRONG buy pressure"
            buySellRatio > 1.5 -> "✅ Good buy pressure"
            buySellRatio < 0.7 -> "⚠️ Selling pressure"
            else -> "⚖️ Balanced"
        }}

📉 PRICE MOMENTUM:
- 5min change: ${pair.priceChange?.m5 ?: 0.0}% ${when {
            (pair.priceChange?.m5 ?: 0.0) > 200 -> "⚠️ EXTREME pump - likely top"
            (pair.priceChange?.m5 ?: 0.0) > 100 -> "⚠️ Strong pump - exercise caution"
            (pair.priceChange?.m5 ?: 0.0) > 30 -> "🚀 Good momentum"
            (pair.priceChange?.m5 ?: 0.0) < -20 -> "📉 Dumping"
            else -> "⚖️ Stable"
        }}
- 1hour change: ${pair.priceChange?.h1 ?: 0.0}%
- 24hour change: ${pair.priceChange?.h24 ?: 0.0}%

⏰ TOKEN AGE: $ageMinutes minutes ${when {
            ageMinutes < 5 -> "⚠️ VERY NEW - high risk"
            ageMinutes < 30 -> "🆕 Fresh listing"
            ageMinutes < 120 -> "✅ Early stage"
            else -> "📅 Established"
        }}

🌐 LEGITIMACY MARKERS:
- Socials: ${pair.info?.socials?.size ?: 0} ${if ((pair.info?.socials?.size ?: 0) == 0) "❌ NO SOCIALS - RED FLAG" else "✅"}
- Website: ${if ((pair.info?.websites?.size ?: 0) > 0) "✅ YES" else "❌ NO - RED FLAG"}

═══════════════════════════════════════
🎯 ANALYSIS FRAMEWORK:
═══════════════════════════════════════

🚨 RUG PULL INDICATORS:
1. LP/MC < 3% = CRITICAL (easy to drain liquidity)
2. Age < 10min + Cap > ${'$'}50k = Instant pump (suspicious)
3. Volume/Liq > 15x = Wash trading
4. Price +300% in 5min + No socials = Classic pump & dump
5. Buys = Sells exactly = Bot manipulation
6. High cap but zero socials/website = Ghost project

✅ BULLISH SIGNALS:
1. LP/MC 5-15% = Healthy backing
2. Volume growing exponentially (m5 > h1/12 * 2)
3. Buy ratio 1.5-3.0 = Organic interest (not bot pumped)
4. Price +20-80% in 5min = Early momentum (not FOMO top)
5. Age 10-60min + good socials = Real project launching
6. Steady transaction count increase

📊 MOMENTUM PHASES:
- ACCUMULATION: Sideways price, volume increasing, low cap
  → BEST entry, whales loading before pump
- EARLY PUMP: +30-80% in 5min, volume spike, ratio 1.5-2.5
  → GOOD entry if fundamentals solid
- FOMO PEAK: +150%+ in 5min, extreme volume, ratio >3
  → AVOID - you're buying from early buyers
- DISTRIBUTION: Price up but volume declining
  → EXIT signal, whales dumping

═══════════════════════════════════════
📋 REQUIRED OUTPUT FORMAT:
═══════════════════════════════════════

⚠️ CRITICAL: Respond ONLY with the format below, NO explanations before/after!

SCORE: [0-100]
RUG_RISK: [LOW|MEDIUM|HIGH|CRITICAL]
MOMENTUM_PHASE: [ACCUMULATION|EARLY_PUMP|FOMO_PEAK|DISTRIBUTION]
ENTRY_SIGNAL: [STRONG_BUY|BUY|WAIT|AVOID|STRONG_AVOID]
CONFIDENCE: [0.0-1.0]
RED_FLAGS: [comma separated list or NONE]
GREEN_FLAGS: [comma separated list or NONE]
REASON: [one concise sentence explaining the decision]
OPTIMAL_ENTRY_CAP: [${'$'}value if waiting, or NOW if immediate buy]
PREDICTED_PEAK_CAP: [${'$'}estimated peak based on pattern]

═══════════════════════════════════════
📝 EXAMPLE RESPONSE:
═══════════════════════════════════════

SCORE: 75
RUG_RISK: MEDIUM
MOMENTUM_PHASE: EARLY_PUMP
ENTRY_SIGNAL: BUY
CONFIDENCE: 0.82
RED_FLAGS: No website
GREEN_FLAGS: Strong buy pressure, Healthy LP ratio, Early stage
REASON: Solid fundamentals with good momentum, but missing website raises minor concerns
OPTIMAL_ENTRY_CAP: NOW
PREDICTED_PEAK_CAP: ${'$'}250000

═══════════════════════════════════════

Now analyze the token above and provide ONLY the format (no thinking, no extra text):
""".trimIndent()
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 🚀 GROQ API CALL (БЕСПЛАТНО, ОЧЕНЬ БЫСТРО!)
    // ════════════════════════════════════════════════════════════════════════════════
    private suspend fun callGroq(prompt: String): String {
        println("🔵 Groq API: отправка запроса...")
        println("🔑 API Key: ${settings.aiApiKey.take(10)}...")
        println("🤖 Model: ${settings.aiModel}")
        println("📝 Prompt (first 300 chars): ${prompt.take(300)}...")
        
        val requestBody = GroqRequest(
            model = settings.aiModel,
            messages = listOf(GroqMessage(role = "user", content = prompt)),
            max_tokens = 1024,
            temperature = 0.7
        )
        
        try {
            val response = client.post("https://api.groq.com/openai/v1/chat/completions") {
                header("Authorization", "Bearer ${settings.aiApiKey}")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            
            println("📡 HTTP Status: ${response.status}")
            
            val json = response.body<JsonObject>()
            println("📦 JSON Response: $json")
            
            // Проверка на ошибку
            val error = json["error"]?.let { it as? JsonObject }
            if (error != null) {
                val errorMsg = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
                println("❌ API Error: $errorMsg")
                return ""
            }
            
            val choices = json["choices"]?.let { it as? JsonArray }
            val firstChoice = choices?.firstOrNull()?.let { it as? JsonObject }
            val message = firstChoice?.get("message")?.let { it as? JsonObject }
            val content = message?.get("content")?.jsonPrimitive?.content ?: ""
            
            println("✅ Response length: ${content.length} chars")
            return content
        } catch (e: Exception) {
            println("❌ Groq API Exception: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 🔵 CLAUDE API CALL
    // ════════════════════════════════════════════════════════════════════════════════
    private suspend fun callClaude(prompt: String): String {
        val requestBody = ClaudeRequest(
            model = settings.aiModel,
            max_tokens = 1024,
            messages = listOf(ClaudeMessage(role = "user", content = prompt))
        )
        
        val response = client.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", settings.aiApiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        
        val json = response.body<JsonObject>()
        val content = json["content"]?.let { it as? JsonArray }
        val textBlock = content?.firstOrNull()?.let { it as? JsonObject }
        return textBlock?.get("text")?.jsonPrimitive?.content ?: ""
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 🟢 OPENAI API CALL
    // ════════════════════════════════════════════════════════════════════════════════
    private suspend fun callOpenAI(prompt: String): String {
        val requestBody = GroqRequest( // Groq и OpenAI используют одинаковый формат
            model = settings.aiModel,
            messages = listOf(GroqMessage(role = "user", content = prompt)),
            max_tokens = 1024,
            temperature = 0.7
        )
        
        val response = client.post("https://api.openai.com/v1/chat/completions") {
            header("Authorization", "Bearer ${settings.aiApiKey}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        
        val json = response.body<JsonObject>()
        val choices = json["choices"]?.let { it as? JsonArray }
        val firstChoice = choices?.firstOrNull()?.let { it as? JsonObject }
        val message = firstChoice?.get("message")?.let { it as? JsonObject }
        return message?.get("content")?.jsonPrimitive?.content ?: ""
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 📊 ПАРСИНГ ОТВЕТА AI (гибкий, устойчивый к разным форматам)
    // ════════════════════════════════════════════════════════════════════════════════
    private fun parseAiResponse(response: String): AiAnalysisResult {
        val lines = response.lines().map { it.trim() }
        
        // Более гибкий поиск: case-insensitive, игнорируем лишние пробелы
        fun extract(prefix: String): String {
            val prefixUpper = prefix.uppercase()
            val found = lines.firstOrNull { line ->
                val lineUpper = line.uppercase()
                lineUpper.startsWith(prefixUpper) || 
                lineUpper.startsWith("$prefixUpper:") ||
                lineUpper.startsWith("**$prefixUpper**") // для markdown
            }
            
            if (found == null) return ""
            
            // Убираем префикс и всё после двоеточия
            return found
                .substringAfter(":", "")
                .replace("**", "") // убираем markdown
                .trim()
        }
        
        return AiAnalysisResult(
            score = extract("SCORE").toIntOrNull() ?: 0,
            rugRisk = extract("RUG_RISK").uppercase(),
            momentumPhase = extract("MOMENTUM_PHASE").uppercase(),
            entrySignal = extract("ENTRY_SIGNAL").uppercase(),
            confidence = extract("CONFIDENCE").replace(",", ".").toDoubleOrNull() ?: 0.0, // поддержка запятой как разделителя
            redFlags = extract("RED_FLAGS"),
            greenFlags = extract("GREEN_FLAGS"),
            reason = extract("REASON"),
            optimalEntryCap = extract("OPTIMAL_ENTRY_CAP"),
            predictedPeakCap = extract("PREDICTED_PEAK_CAP"),
            rawResponse = response
        )
    }

    fun close() {
        client.close()
    }
}
