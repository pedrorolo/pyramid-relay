package p2p.broadcaster

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CryptoServiceTest {

    private lateinit var crypto: CryptoService

    @Before
    fun setup() {
        crypto = CryptoService()
    }

    @Test
    fun `spec 5 - keypair generation produces Ed25519 key pair`() {
        val kp = crypto.generateEd25519KeyPair()
        assertNotNull(kp.public)
        assertNotNull(kp.private)
        assertEquals("EdDSA", kp.public.algorithm)
        assertEquals("EdDSA", kp.private.algorithm)
    }

    @Test
    fun `spec 5 - sign and verify roundtrip`() {
        val kp = crypto.generateEd25519KeyPair()
        val msg = "test message".toByteArray()
        val sig = crypto.sign(msg, kp.private)
        assertNotNull(sig)
        assertEquals(64, sig.size)
        assertTrue(crypto.verify(msg, sig, kp.public))
    }

    @Test
    fun `spec 5 - verify rejects different key pair`() {
        val kp1 = crypto.generateEd25519KeyPair()
        val kp2 = crypto.generateEd25519KeyPair()
        val msg = "signed by kp1".toByteArray()
        val sig = crypto.sign(msg, kp1.private)
        assertFalse(crypto.verify(msg, sig, kp2.public))
    }

    @Test
    fun `spec 5 - verify rejects tampered message`() {
        val kp = crypto.generateEd25519KeyPair()
        val msg = "original".toByteArray()
        val sig = crypto.sign(msg, kp.private)
        assertFalse(crypto.verify("tampered".toByteArray(), sig, kp.public))
    }

    @Test
    fun `spec 5 - verify rejects truncated signature`() {
        val kp = crypto.generateEd25519KeyPair()
        val msg = "test".toByteArray()
        val sig = crypto.sign(msg, kp.private)
        val truncated = sig.copyOf(32)
        try {
            assertFalse(crypto.verify(msg, truncated, kp.public))
        } catch (_: java.security.SignatureException) {
            // Ed25519 verify may throw on malformed signature
        }
    }

    @Test
    fun `spec 5 - sha256 produces 32 byte digest`() {
        val data = "hello".toByteArray()
        val hash = crypto.sha256(data)
        assertEquals(32, hash.size)
    }

    @Test
    fun `spec 5 - sha256 is deterministic`() {
        val data = "deterministic".toByteArray()
        assertArrayEquals(crypto.sha256(data), crypto.sha256(data))
    }

    @Test
    fun `spec 5 - sha256Hex produces lowercase hex string`() {
        val hex = crypto.sha256Hex("test".toByteArray())
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `spec 4 - buildSignatureMessage matches format utf8(fileId) || BE32(version) || hex(fileHash)`() {
        val fileId = "abc-123"
        val version = 1
        val fileHashHex = crypto.sha256Hex("file-content".toByteArray())
        val msg = crypto.buildSignatureMessage(fileId, version, fileHashHex)

        val expectedFileId = fileId.toByteArray(Charsets.UTF_8)
        val expectedVersion = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val expectedHash = fileHashHex.toByteArray(Charsets.UTF_8)

        assertEquals(expectedFileId.size + 4 + expectedHash.size, msg.size)
        assertArrayEquals(expectedFileId, msg.copyOfRange(0, expectedFileId.size))
        assertArrayEquals(expectedVersion, msg.copyOfRange(expectedFileId.size, expectedFileId.size + 4))
        assertArrayEquals(expectedHash, msg.copyOfRange(expectedFileId.size + 4, msg.size))
    }

    @Test
    fun `spec 4 - BE32 version encoding for large version`() {
        val msg = crypto.buildSignatureMessage("id", 0x01020304, "hash")
        val idBytes = "id".toByteArray(Charsets.UTF_8)
        assertEquals(0x01, msg[idBytes.size].toInt() and 0xFF)
        assertEquals(0x02, msg[idBytes.size + 1].toInt() and 0xFF)
        assertEquals(0x03, msg[idBytes.size + 2].toInt() and 0xFF)
        assertEquals(0x04, msg[idBytes.size + 3].toInt() and 0xFF)
    }

    @Test
    fun `spec 4 - sign over spec message format and verify`() {
        val kp = crypto.generateEd25519KeyPair()
        val fileId = "test-file-id"
        val version = 1
        val fileHashHex = crypto.sha256Hex("file-data".toByteArray())
        val msg = crypto.buildSignatureMessage(fileId, version, fileHashHex)
        val sig = crypto.sign(msg, kp.private)
        assertTrue(crypto.verify(msg, sig, kp.public))
    }

    @Test
    fun `spec 8 - fileIdHash returns 6 bytes`() {
        val hash = crypto.fileIdHash("any-file-id")
        assertEquals(6, hash.size)
    }

    @Test
    fun `spec 8 - fileIdHash is deterministic`() {
        val id = "same-id"
        assertArrayEquals(crypto.fileIdHash(id), crypto.fileIdHash(id))
    }

    @Test
    fun `spec 8 - fileIdHash differs for different ids`() {
        assertNotEquals(
            crypto.fileIdHash("id-a").toList(),
            crypto.fileIdHash("id-b").toList()
        )
    }

    @Test
    fun `spec 8 - keyId returns 4 bytes`() {
        val kp = crypto.generateEd25519KeyPair()
        val base64 = crypto.publicKeyToBase64(kp.public)
        val kid = crypto.keyId(base64)
        assertEquals(4, kid.size)
    }

    @Test
    fun `spec 8 - keyId is deterministic`() {
        val kp = crypto.generateEd25519KeyPair()
        val base64 = crypto.publicKeyToBase64(kp.public)
        assertArrayEquals(crypto.keyId(base64), crypto.keyId(base64))
    }

    @Test
    fun `spec 8 - keyId differs for different keys`() {
        val kp1 = crypto.generateEd25519KeyPair()
        val kp2 = crypto.generateEd25519KeyPair()
        val b1 = crypto.publicKeyToBase64(kp1.public)
        val b2 = crypto.publicKeyToBase64(kp2.public)
        assertNotEquals(crypto.keyId(b1).toList(), crypto.keyId(b2).toList())
    }

    @Test
    fun `spec 5 - publicKeyToBase64 and publicKeyFromBase64 roundtrip`() {
        val kp = crypto.generateEd25519KeyPair()
        val base64 = crypto.publicKeyToBase64(kp.public)
        assertTrue(base64.isNotEmpty())
        val restored = crypto.publicKeyFromBase64(base64)
        assertEquals(kp.public, restored)
    }

    @Test
    fun `spec 5 - store and retrieve private key by alias`() {
        val kp = crypto.generateEd25519KeyPair()
        crypto.storeKeyPair("sk_test", kp)
        val retrieved = crypto.getPrivateKey("sk_test")
        assertNotNull(retrieved)
        assertEquals(kp.private, retrieved)
    }

    @Test
    fun `spec 5 - getPrivateKey returns null for nonexistent alias`() {
        assertNull(crypto.getPrivateKey("nonexistent"))
    }

    @Test
    fun `spec 5 - deleteKey removes stored key`() {
        val kp = crypto.generateEd25519KeyPair()
        crypto.storeKeyPair("to_delete", kp)
        assertNotNull(crypto.getPrivateKey("to_delete"))
        crypto.deleteKey("to_delete")
        assertNull(crypto.getPrivateKey("to_delete"))
    }

    @Test
    fun `spec 5 - sign with retrieved key via alias and verify against public`() {
        val kp = crypto.generateEd25519KeyPair()
        crypto.storeKeyPair("sign_alias", kp)
        val privKey = crypto.getPrivateKey("sign_alias")!!
        val msg = "sign via alias".toByteArray()
        val sig = crypto.sign(msg, privKey)
        assertTrue(crypto.verify(msg, sig, kp.public))
    }

    @Test
    fun `spec 7 - MAX_FILE_SIZE fits 5-minute GATT transfer`() {
        // ~50 KB/s conservative BLE GATT throughput * 300 s = 15 MB
        assertEquals(15L * 1024 * 1024, MAX_FILE_SIZE)
        assertEquals(15728640L, MAX_FILE_SIZE)
    }

    @Test
    fun `spec 9 - TRANSFER_PORT is 8988`() {
        assertEquals(8988, TRANSFER_PORT)
    }

    @Test
    fun `spec 4 - multiple sign operations are independent`() {
        val kp = crypto.generateEd25519KeyPair()
        val msg1 = "message 1".toByteArray()
        val msg2 = "message 2".toByteArray()
        val sig1 = crypto.sign(msg1, kp.private)
        val sig2 = crypto.sign(msg2, kp.private)
        assertFalse(sig1.contentEquals(sig2))
        assertTrue(crypto.verify(msg1, sig1, kp.public))
        assertTrue(crypto.verify(msg2, sig2, kp.public))
        assertFalse(crypto.verify(msg1, sig2, kp.public))
    }

    @Test
    fun `spec 5 - SHA-256 of empty input is valid`() {
        val hash = crypto.sha256(byteArrayOf())
        assertEquals(32, hash.size)
    }
}
