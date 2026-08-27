package p2p.broadcaster

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import p2p.broadcaster.EventLog

class CryptoService {
    companion object {
        private const val ED25519 = "Ed25519"
    }

    private val keys = mutableMapOf<String, PrivateKey>()

    init {
        if (java.security.Security.getProvider("BC") == null) {
            try { java.security.Security.addProvider(BouncyCastleProvider()) } catch (e: Exception) { EventLog.log("crypto", "Failed to register BouncyCastle provider: ${e.message}") }
        }
    }

    fun generateEd25519KeyPair(): KeyPair {
        return try {
            KeyPairGenerator.getInstance(ED25519).generateKeyPair()
        } catch (e: Exception) {
            KeyPairGenerator.getInstance(ED25519, "BC").generateKeyPair()
        }
    }

    fun storeKeyPair(alias: String, keyPair: KeyPair) {
        keys[alias] = keyPair.private
    }

    fun getPrivateKey(alias: String): PrivateKey? = keys[alias]

    fun publicKeyToBase64(publicKey: PublicKey): String {
        // URL-safe base64 so the key survives URI query-parameter parsing
        // (standard base64's '+' would be decoded as a space).
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded)
    }

    fun publicKeyFromBase64(base64: String): PublicKey {
        // Try standard base64 first; fall back to URL-safe (the spec says BASE64URL
        // but some encoders/decoders differ).
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: Exception) {
            Base64.getUrlDecoder().decode(base64)
        }
        return KeyFactory.getInstance(ED25519).generatePublic(X509EncodedKeySpec(bytes))
    }

    fun rawPublicKey(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    fun publicKeyFromRaw(raw: ByteArray): PublicKey {
        // X.509 SubjectPublicKeyInfo header for Ed25519: 302a300506032b6570032100
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        return KeyFactory.getInstance(ED25519).generatePublic(X509EncodedKeySpec(prefix + raw))
    }

    fun deleteKey(alias: String) {
        keys.remove(alias)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance(ED25519)
        sig.initSign(privateKey); sig.update(message); return sig.sign()
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val sig = Signature.getInstance(ED25519)
            sig.initVerify(publicKey); sig.update(message); sig.verify(signature)
        } catch (e: Exception) {
            // Malformed signatures can make providers throw (e.g. undecodable curve
            // points) - cryptographic failure is still just "not verified".
            EventLog.log("crypto", "Verification error: ${e.message}")
            false
        }
    }

    fun buildSignatureMessage(fileId: String, version: Int, fileHashHex: String): ByteArray {
        val idBytes = fileId.toByteArray(Charsets.UTF_8)
        val versionBytes = byteArrayOf(
            ((version ushr 24) and 0xFF).toByte(),
            ((version ushr 16) and 0xFF).toByte(),
            ((version ushr 8) and 0xFF).toByte(),
            (version and 0xFF).toByte()
        )
        return idBytes + versionBytes + fileHashHex.toByteArray(Charsets.UTF_8)
    }

    fun fileIdHash(fileId: String): ByteArray = sha256(fileId.toByteArray(Charsets.UTF_8)).copyOfRange(0, 6)

    fun keyId(publicKeyBase64: String): ByteArray =
        try {
            val bytes = try { Base64.getUrlDecoder().decode(publicKeyBase64) } catch (e: Exception) { Base64.getDecoder().decode(publicKeyBase64) }
            sha256(bytes).copyOfRange(0, 4)
        } catch (e: Exception) {
            // Malformed stored key (e.g. bad paste link) must not crash discovery.
            EventLog.log("crypto", "keyId from malformed public key: ${e.message}")
            ByteArray(4)
        }
}
