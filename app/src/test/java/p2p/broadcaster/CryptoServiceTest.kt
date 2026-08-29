package p2p.broadcaster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CryptoServiceTest {
    private lateinit var crypto: CryptoService

    @Before
    fun setup() {
        crypto = CryptoService()
    }

    @Test
    fun `RSA keypair generation produces RSA keys`() {
        val keyPair = crypto.generateRsaKeyPair()
        assertEquals("RSA", keyPair.public.algorithm)
        assertEquals("RSA", keyPair.private.algorithm)
        assertNotNull(keyPair.public.encoded)
        assertNotNull(keyPair.private.encoded)
    }

    @Test
    fun `public key base64 roundtrip preserves RSA key`() {
        val keyPair = crypto.generateRsaKeyPair()
        val encoded = crypto.publicKeyToBase64(keyPair.public)
        assertEquals(keyPair.public, crypto.publicKeyFromBase64(encoded))
    }

    @Test
    fun `hybrid envelope roundtrip uses private wrapping and public recovery`() {
        val keyPair = crypto.generateRsaKeyPair()
        val payload = ByteArray(100_000) { (it * 31).toByte() }

        val encrypted = crypto.encryptCompressed(payload, keyPair.private)

        assertFalse(payload.contentEquals(encrypted))
        assertArrayEquals(payload, crypto.decryptCompressed(encrypted, keyPair.public))
    }

    @Test
    fun `hybrid envelope rejects tampered ciphertext`() {
        val keyPair = crypto.generateRsaKeyPair()
        val encrypted = crypto.encryptCompressed("payload".toByteArray(), keyPair.private)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        try {
            crypto.decryptCompressed(encrypted, keyPair.public)
            assertTrue("tampered ciphertext must be rejected", false)
        } catch (_: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `hybrid envelope rejects wrong public key`() {
        val sender = crypto.generateRsaKeyPair()
        val other = crypto.generateRsaKeyPair()
        val encrypted = crypto.encryptCompressed("payload".toByteArray(), sender.private)

        try {
            crypto.decryptCompressed(encrypted, other.public)
            assertTrue("wrong key must be rejected", false)
        } catch (_: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `key storage persists RSA private key`() {
        val keyPair = crypto.generateRsaKeyPair()
        crypto.storeKeyPair("rsa_test", keyPair)
        assertEquals(keyPair.private, crypto.getPrivateKey("rsa_test"))
        crypto.deleteKey("rsa_test")
        assertNull(crypto.getPrivateKey("rsa_test"))
    }

    @Test
    fun `sha256 and identifiers remain deterministic`() {
        val data = "deterministic".toByteArray()
        assertArrayEquals(crypto.sha256(data), crypto.sha256(data))
        assertEquals(32, crypto.sha256(data).size)
        assertEquals(6, crypto.fileIdHash("file").size)
        assertEquals(4, crypto.keyId(crypto.publicKeyToBase64(crypto.generateRsaKeyPair().public)).size)
    }
}
