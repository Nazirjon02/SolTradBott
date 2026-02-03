package tj.khujand.solana.trading.bot.crypto

private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

fun encodeBase58(input: ByteArray): String {
    if (input.isEmpty()) return ""
    var zeros = 0
    while (zeros < input.size && input[zeros].toInt() == 0) zeros++

    val encoded = CharArray(input.size * 2)
    var outputStart = encoded.size
    var startAt = zeros
    val inputCopy = input.copyOf()
    while (startAt < inputCopy.size) {
        var carry = 0
        for (i in startAt until inputCopy.size) {
            val value = (inputCopy[i].toInt() and 0xFF)
            val newValue = carry * 256 + value
            inputCopy[i] = (newValue / 58).toByte()
            carry = newValue % 58
        }
        if (inputCopy[startAt].toInt() == 0) {
            startAt++
        }
        encoded[--outputStart] = ALPHABET[carry]
    }
    while (outputStart < encoded.size && encoded[outputStart] == ALPHABET[0]) {
        outputStart++
    }
    while (zeros-- > 0) {
        encoded[--outputStart] = ALPHABET[0]
    }
    return String(encoded, outputStart, encoded.size - outputStart)
}

fun decodeBase58(input: String): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    val input58 = IntArray(input.length)
    for (i in input.indices) {
        val index = ALPHABET.indexOf(input[i])
        require(index >= 0) { "Invalid Base58 character: ${input[i]}" }
        input58[i] = index
    }
    var zeros = 0
    while (zeros < input58.size && input58[zeros] == 0) zeros++

    val decoded = ByteArray(input.length)
    var outputStart = decoded.size
    var startAt = zeros
    while (startAt < input58.size) {
        var carry = 0
        for (i in startAt until input58.size) {
            val value = input58[i]
            val newValue = carry * 58 + value
            input58[i] = newValue / 256
            carry = newValue % 256
        }
        if (input58[startAt] == 0) {
            startAt++
        }
        decoded[--outputStart] = carry.toByte()
    }
    while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) {
        outputStart++
    }
    val result = ByteArray(decoded.size - (outputStart - zeros))
    var index = 0
    while (index < zeros) {
        result[index++] = 0
    }
    while (outputStart < decoded.size) {
        result[index++] = decoded[outputStart++]
    }
    return result
}
