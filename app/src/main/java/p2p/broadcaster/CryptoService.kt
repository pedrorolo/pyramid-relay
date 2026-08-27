package p2p.broadcaster

import android.content.Context
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import p2p.broadcaster.EventLog

class CryptoService(private val context: Context? = null) {
    companion object {
        private const val ED25519 = "Ed25519"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_TYPE = "BKS"
    }

    private val keys = mutableMapOf<String, PrivateKey>()

    init {
        if (java.security.Security.getProvider("BC") == null) {
            try { java.security.Security.addProvider(BouncyCastleProvider()) } catch (e: Exception) { EventLog.log("crypto", "Failed to register BouncyCastle provider: ${e.message}") }
        }
        loadPersistedKeys()
    }

    private fun getKeyStoreFile() = context?.getDir("keystore", Context.MODE_PRIVATE)

    private fun loadPersistedKeys() {
        val dir = getKeyStoreFile() ?: return
        dir.listFiles()?.forEach { file ->
            try {
                val alias = file.name
                val keyBytes = file.readBytes()
                val spec = PKCS8EncodedKeySpec(keyBytes)
                val kf = try { KeyFactory.getInstance(ED25519) } catch (_: Exception) { KeyFactory.getInstance(ED25519, "BC") }
                keys[alias] = kf.generatePrivate(spec)
            } catch (e: Exception) {
                EventLog.log("crypto", "Failed to load key from ${file.name}: ${e.message}")
            }
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
        persistKey(alias, keyPair.private)
    }

    private fun persistKey(alias: String, privateKey: PrivateKey) {
        val dir = getKeyStoreFile() ?: return
        try {
            val file = java.io.File(dir, alias)
            file.writeBytes(privateKey.encoded)
        } catch (e: Exception) {
            EventLog.log("crypto", "Failed to persist key $alias: ${e.message}")
        }
    }

    fun getPrivateKey(alias: String): PrivateKey? {
        keys[alias]?.let { return it }
        // Try loading from disk (key may have been stored before this instance existed)
        val dir = getKeyStoreFile() ?: return null
        val file = java.io.File(dir, alias)
        if (!file.exists()) return null
        return try {
            val keyBytes = file.readBytes()
            val spec = PKCS8EncodedKeySpec(keyBytes)
            val kf = try { KeyFactory.getInstance(ED25519) } catch (_: Exception) { KeyFactory.getInstance(ED25519, "BC") }
            val key = kf.generatePrivate(spec)
            keys[alias] = key
            key
        } catch (e: Exception) {
            EventLog.log("crypto", "Failed to load key $alias: ${e.message}")
            null
        }
    }

    fun publicKeyToBase64(publicKey: PublicKey): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded)
    }

    fun publicKeyFromBase64(base64: String): PublicKey {
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
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        return KeyFactory.getInstance(ED25519).generatePublic(X509EncodedKeySpec(prefix + raw))
    }

    fun deleteKey(alias: String) {
        keys.remove(alias)
        val dir = getKeyStoreFile() ?: return
        java.io.File(dir, alias).delete()
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
            EventLog.log("crypto", "keyId from malformed public key: ${e.message}")
            ByteArray(4)
        }
}
