package tj.khujand.solana.trading.bot.bot.telegram.callback

data class CallbackPayload(
    val section: String,
    val action: String,
    val param: String? = null
)

object CallbackDataCodec {
    private const val VERSION = "v1"
    private const val DELIMITER = "|"

    fun encode(payload: CallbackPayload): String {
        return listOf(
            VERSION,
            payload.section,
            payload.action,
            payload.param ?: ""
        ).joinToString(DELIMITER)
    }

    fun decode(raw: String): CallbackPayload? {
        val parts = raw.split(DELIMITER)
        if (parts.size < 3 || parts[0] != VERSION) return null
        return CallbackPayload(
            section = parts[1],
            action = parts[2],
            param = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        )
    }
}
