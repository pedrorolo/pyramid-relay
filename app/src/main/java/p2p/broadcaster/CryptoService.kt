package p2p.broadcaster

import android.content.Context
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import p2p.broadcaster.EventLog

class CryptoService(private val context: Context? = null) {
    companion object {
        private const val RSA = "RSA"
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
                val kf = KeyFactory.getInstance(RSA)
                keys[alias] = kf.generatePrivate(spec)
            } catch (e: Exception) {
                EventLog.log("crypto", "Failed to load key from ${file.name}: ${e.message}")
            }
        }
    }

    fun generateRsaKeyPair(): KeyPair = KeyPairGenerator.getInstance(RSA).apply { initialize(2048) }.generateKeyPair()

    fun storeKeyPair(alias: String, keyPair: KeyPair) {
        keys[alias] = keyPair.private
        persistKey(alias, keyPair.private)
    }

    fun storeRecipientKey(publicKey: PublicKey, keyPair: KeyPair) {
        storeKeyPair(recipientAlias(publicKey), keyPair)
    }

    fun getRecipientPrivateKey(publicKeyBase64: String): PrivateKey? =
        getPrivateKey("rk_" + keyId(publicKeyBase64).joinToString("") { "%02x".format(it) })

    private fun recipientAlias(publicKey: PublicKey) = "rk_" + keyId(publicKeyToBase64(publicKey)).joinToString("") { "%02x".format(it) }

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
            val kf = KeyFactory.getInstance(RSA)
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
        return KeyFactory.getInstance(RSA).generatePublic(X509EncodedKeySpec(bytes))
    }

    fun deleteKey(alias: String) {
        keys.remove(alias)
        val dir = getKeyStoreFile() ?: return
        java.io.File(dir, alias).delete()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    /** Encrypts compressed bytes. The envelope is self-contained for relay forwarding. */
    fun encryptCompressed(compressed: ByteArray, privateKey: PrivateKey): ByteArray {
        require(privateKey.algorithm.equals(RSA, ignoreCase = true)) { "Private key must be RSA" }
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(compressed)
        val wrapped = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, privateKey)
            doFinal(aes.encoded)
        }
        return byteArrayOf('P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(), 1) +
            nonce + intBytes(wrapped.size) + wrapped + ciphertext
    }

    fun decryptCompressed(envelope: ByteArray, publicKey: PublicKey): ByteArray {
        require(envelope.size >= 17 && envelope.copyOfRange(0, 4).contentEquals(byteArrayOf('P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte()))) { "Invalid encrypted envelope" }
        require(envelope[4].toInt() == 1) { "Unsupported encrypted envelope version" }
        val nonce = envelope.copyOfRange(5, 17)
        val wrappedSize = readInt(envelope, 17)
        require(wrappedSize > 0 && envelope.size >= 21 + wrappedSize) { "Invalid wrapped key" }
        val wrapped = envelope.copyOfRange(21, 21 + wrappedSize)
        val ciphertext = envelope.copyOfRange(21 + wrappedSize, envelope.size)
        val aesBytes = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, publicKey); doFinal(wrapped)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun readInt(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)

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
