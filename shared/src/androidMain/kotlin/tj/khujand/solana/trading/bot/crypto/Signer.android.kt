package tj.khujand.solana.trading.bot.crypto

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.security.MessageDigest
import java.security.Security
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec

actual fun createSignerFromSeedPhrase(seedPhrase: String): Signer {
    Security.addProvider(EdDSASecurityProvider())
    val seed = mnemonicToSeed(seedPhrase.trim(), "")
    val keySeed = deriveSlip10Ed25519(seed)
    return Ed25519Signer(keySeed)
}

private class Ed25519Signer(private val seed: ByteArray) : Signer {
    private val spec = EdDSANamedCurveTable.getByName("Ed25519")
    private val privateKeySpec = EdDSAPrivateKeySpec(seed, spec)
    private val privateKey = EdDSAPrivateKey(privateKeySpec)
    private val publicKeyBytes = privateKeySpec.a.toByteArray()

    override fun publicKeyBase58(): String {
        return encodeBase58(publicKeyBytes)
    }

    override fun sign(message: ByteArray): ByteArray {
        val signature = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }
}

private data class Slip10Node(val key: ByteArray, val chainCode: ByteArray)

private fun deriveSlip10Ed25519(seed: ByteArray): ByteArray {
    val master = hmacSha512("ed25519 seed".toByteArray(), seed)
    var node = Slip10Node(master.copyOfRange(0, 32), master.copyOfRange(32, 64))
    val path = intArrayOf(44, 501, 0, 0)
    for (index in path) {
        val hardened = index or 0x80000000.toInt()
        node = deriveChild(node, hardened)
    }
    return node.key
}

private fun deriveChild(node: Slip10Node, index: Int): Slip10Node {
    val data = ByteArray(1 + 32 + 4)
    data[0] = 0
    node.key.copyInto(data, 1, 0, 32)
    data[33] = ((index ushr 24) and 0xFF).toByte()
    data[34] = ((index ushr 16) and 0xFF).toByte()
    data[35] = ((index ushr 8) and 0xFF).toByte()
    data[36] = (index and 0xFF).toByte()
    val i = hmacSha512(node.chainCode, data)
    return Slip10Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
}

private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(key, "HmacSHA512"))
    return mac.doFinal(data)
}

private fun mnemonicToSeed(mnemonic: String, passphrase: String): ByteArray {
    val normalizedMnemonic = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
    val normalizedPassphrase = Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
    val salt = "mnemonic$normalizedPassphrase"
    val spec = PBEKeySpec(
        normalizedMnemonic.toCharArray(),
        salt.toByteArray(),
        2048,
        512
    )
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    return factory.generateSecret(spec).encoded
}
