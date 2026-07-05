package tj.khujand.solana.trading.bot.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
fun signTransactionBase64(unsignedTxBase64: String, signer: Signer): String? {
    val txBytes = try {
        Base64.decode(unsignedTxBase64)
    } catch (e: Exception) {
        println("❌ Failed to decode transaction base64: ${e.message}")
        return null
    }

    val (sigCount, sigLen) = readShortVec(txBytes, 0) ?: return null
    if (sigCount <= 0) {
        println("❌ Transaction has no signatures")
        return null
    }
    val sigSectionStart = sigLen
    val sigSectionSize = sigCount * 64
    val messageStart = sigSectionStart + sigSectionSize
    if (messageStart >= txBytes.size) {
        println("❌ Invalid transaction layout")
        return null
    }

    val message = txBytes.copyOfRange(messageStart, txBytes.size)
    val signature = signer.sign(message)
    if (signature.size != 64) {
        println("❌ Invalid signature size: ${signature.size}")
        return null
    }

    signature.copyInto(txBytes, sigSectionStart, 0, 64)
    return Base64.encode(txBytes)
}

private fun readShortVec(data: ByteArray, offset: Int): Pair<Int, Int>? {
    var len = 0
    var size = 0
    var shift = 0
    while (true) {
        if (offset + size >= data.size) return null
        val b = data[offset + size].toInt() and 0xFF
        len = len or ((b and 0x7F) shl shift)
        size++
        if (b and 0x80 == 0) break
        shift += 7
    }
    return len to size
}
