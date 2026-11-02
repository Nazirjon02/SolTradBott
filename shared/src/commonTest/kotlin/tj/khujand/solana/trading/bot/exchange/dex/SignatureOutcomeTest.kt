package tj.khujand.solana.trading.bot.exchange.dex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Разбор статуса подписи из getSignatureStatuses (см. [signatureOutcome]). */
class SignatureOutcomeTest {

    private fun status(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun failedSwapIsNotSuccessEvenWhenConfirmed() {
        // Упавший своп (slippage) тоже попадает в блок и получает confirmationStatus —
        // раньше бот считал такую продажу исполненной.
        val s = status("""{"slot":1,"confirmations":null,"err":{"InstructionError":[3,{"Custom":6001}]},"confirmationStatus":"finalized"}""")
        assertEquals(false, signatureOutcome(s))
    }

    @Test
    fun confirmedWithoutErrorIsSuccess() {
        assertEquals(true, signatureOutcome(status("""{"slot":1,"err":null,"confirmationStatus":"confirmed"}""")))
        assertEquals(true, signatureOutcome(status("""{"slot":1,"err":null,"confirmationStatus":"finalized"}""")))
    }

    @Test
    fun processedOrUnknownIsPending() {
        assertNull(signatureOutcome(status("""{"slot":1,"err":null,"confirmationStatus":"processed"}""")))
        assertNull(signatureOutcome(null)) // узел ещё не видел подпись: value = [null]
    }
}
