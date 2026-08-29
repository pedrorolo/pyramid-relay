package p2p.broadcaster

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {

    @Test
    fun `spec 8 - BLE META_CHAR payload is 122 bytes`() {
        assertEquals(58, BleMetaPayload.FIXED_SIZE)
    }

    @Test
    fun `spec 8 - BLE META_CHAR layout fileId16 version4 sig64 fileHash32 size4`() {
        val fileId = ByteArray(16) { (it + 1).toByte() }
        val version = 1
        val fileHash = ByteArray(32) { (it + 30).toByte() }
        val fileSize = 1024L

        val payload = BleMetaPayload(fileId, version, fileHash, fileSize, "test.bin")
        val bytes = payload.toBytes()

        // Fixed prefix (no name) = 58 bytes; + 8B name = 66
        assertEquals(BleMetaPayload.FIXED_SIZE + 8, bytes.size)

        var offset = 0
        assertArrayEquals(fileId, bytes.copyOfRange(offset, offset + 16)); offset += 16
        assertEquals(version, ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)); offset += 4
        assertArrayEquals(fileHash, bytes.copyOfRange(offset, offset + 32)); offset += 32
        val decodedSize = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        assertEquals(fileSize, decodedSize)
    }

    @Test
    fun `spec 8 - BleMetaPayload toBytes and fromBytes roundtrip`() {
        val fileId = ByteArray(16) { it.toByte() }
        val version = 42
        val fileHash = ByteArray(32) { (it * 4).toByte() }
        val fileSize = 123456789L

        val payload = BleMetaPayload(fileId, version, fileHash, fileSize, "report.pdf")
        val restored = BleMetaPayload.fromBytes(payload.toBytes())!!

        assertArrayEquals(fileId, restored.fileId)
        assertEquals(version, restored.version)
        assertArrayEquals(fileHash, restored.fileHash)
        assertEquals(fileSize, restored.fileSize)
        assertEquals("report.pdf", restored.fileName)
    }

    @Test
    fun `spec 8 - BleMetaPayload fromBytes rejects short data`() {
        assertNull(BleMetaPayload.fromBytes(ByteArray(10)))
        assertNull(BleMetaPayload.fromBytes(ByteArray(0)))
        assertNull(BleMetaPayload.fromBytes(ByteArray(BleMetaPayload.FIXED_SIZE - 1)))
        // Declares 8 bytes of name but buffer only has 4 trailing bytes
        val shortName = ByteArray(BleMetaPayload.FIXED_SIZE + 4).also { it[BleMetaPayload.FIXED_SIZE - 2] = 0; it[BleMetaPayload.FIXED_SIZE - 1] = 8 }
        assertNull(BleMetaPayload.fromBytes(shortName))
    }

    @Test
    fun `spec 8 - BleMetaPayload version BE32 encoding roundtrip`() {
        val payload = BleMetaPayload(ByteArray(16), 0x01020304, ByteArray(32), 0, "f")
        val bytes = payload.toBytes()
        val restored = BleMetaPayload.fromBytes(bytes)!!
        assertEquals(0x01020304, restored.version)
    }

    @Test
    fun `spec 8 - BleMetaPayload fileSize BE32 encoding for max size`() {
        val maxFile = BleMetaPayload(ByteArray(16), 1, ByteArray(32), 20L * 1024 * 1024, "f")
        val bytes = maxFile.toBytes()
        val restored = BleMetaPayload.fromBytes(bytes)!!
        assertEquals(20L * 1024 * 1024, restored.fileSize)
    }

    @Test
    fun `spec 8 - BleAdv ServiceData layout fileIdHash6 version4 keyId4 = 14 bytes`() {
        val fileIdHash = ByteArray(6) { it.toByte() }
        val version = 5
        val keyId = ByteArray(4) { (it + 10).toByte() }

        val adv = BleAdvertisement(fileIdHash, version, keyId)
        val serviceData = adv.toServiceData()

        assertEquals(14, serviceData.size)
        assertArrayEquals(fileIdHash, serviceData.copyOfRange(0, 6))
        assertEquals(version, ((serviceData[6].toInt() and 0xFF) shl 24) or
            ((serviceData[7].toInt() and 0xFF) shl 16) or
            ((serviceData[8].toInt() and 0xFF) shl 8) or
            (serviceData[9].toInt() and 0xFF))
        assertArrayEquals(keyId, serviceData.copyOfRange(10, 14))
    }

    @Test
    fun `spec 8 - BleAdv ServiceData roundtrip`() {
        val adv = BleAdvertisement(ByteArray(6) { (it + 100).toByte() }, 7, ByteArray(4) { (it + 50).toByte() })
        val serviceData = adv.toServiceData()
        val restored = BleAdvertisement.fromServiceData(serviceData)!!

        assertArrayEquals(adv.fileIdHash, restored.fileIdHash)
        assertEquals(adv.version, restored.version)
        assertArrayEquals(adv.keyId, restored.keyId)
    }

    @Test
    fun `spec 8 - BleAdv fromServiceData rejects wrong size`() {
        assertNull(BleAdvertisement.fromServiceData(ByteArray(10)))
        assertNull(BleAdvertisement.fromServiceData(ByteArray(0)))
        assertNull(BleAdvertisement.fromServiceData(ByteArray(13)))
    }

    @Test
    fun `spec 4 - Role enum has ORIGINATOR and RELAY`() {
        assertEquals(2, Role.entries.size)
        assertNotNull(Role.valueOf("ORIGINATOR"))
        assertNotNull(Role.valueOf("RELAY"))
    }

    @Test
    fun `spec 4 - BroadcastEntity has all required fields`() {
        val b = BroadcastEntity(
            fileId = "test-id",
            fileName = "test.txt",
            mimeType = "text/plain",
            internalUri = "/data/files/store/test-id/v1/file",
            fileHash = "abcdef0123456789",
            fileSize = 1024,
            compressedSize = 1024,
            version = 1,
            publicKey = "base64-public-key",
            privateKeyAlias = "sk_test-id",
            signature = "base64-signature",
            role = Role.ORIGINATOR,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        assertEquals("test-id", b.fileId)
        assertEquals("test.txt", b.fileName)
        assertEquals(1, b.version)
        assertEquals(Role.ORIGINATOR, b.role)
        assertEquals("sk_test-id", b.privateKeyAlias)
    }

    @Test
    fun `spec 4 - BroadcastEntity relay has null privateKeyAlias`() {
        val b = BroadcastEntity(
            fileId = "relay-id", fileName = "f.txt", mimeType = "text/plain",
            internalUri = "/path", fileHash = "abc", fileSize = 100, compressedSize = 100,
            version = 1, publicKey = "pk", privateKeyAlias = null,
            signature = "sig", role = Role.RELAY, createdAt = 0L, updatedAt = 0L
        )
        assertNull(b.privateKeyAlias)
        assertEquals(Role.RELAY, b.role)
    }

    @Test
    fun `spec 4 - SubscriptionEntity has all required fields from spec`() {
        val s = SubscriptionEntity(
            fileId = "sub-id",
            publicKey = "base64-pk-from-qr",
            fileName = null,
            localVersion = null,
            localUri = null,
            subscribedAt = 3000L,
            lastSeenVersion = null,
            lastSeenAt = null,
            lastNotifiedVersion = null
        )
        assertEquals("sub-id", s.fileId)
        assertEquals("base64-pk-from-qr", s.publicKey)
        assertNull(s.fileName)
        assertNull(s.localVersion)
        assertNull(s.localUri)
    }

    @Test
    fun `spec 4 - SubscriptionEntity with fetched file has localVersion and localUri`() {
        val s = SubscriptionEntity(
            fileId = "sub-id", publicKey = "pk", fileName = "doc.pdf",
            localVersion = 3, localUri = "/data/files/store/sub-id/v3/file",
            subscribedAt = 1000L, lastSeenVersion = 3, lastSeenAt = 2000L,
            lastNotifiedVersion = 3
        )
        assertEquals(3, s.localVersion)
        assertNotNull(s.localUri)
        assertEquals(3, s.lastSeenVersion)
    }

    @Test
    fun `spec 4 - SubscriptionEntity lastNotifiedVersion tracks dedupe`() {
        val s1 = SubscriptionEntity("id", "pk", null, null, null, 0L, null, null, null)
        assertNull(s1.lastNotifiedVersion)
        val s2 = SubscriptionEntity("id", "pk", null, null, null, 0L, 1, 100L, 1)
        assertEquals(1, s2.lastNotifiedVersion)
    }

    @Test
    fun `APP_SERVICE_UUID is set`() {
        assertNotNull(APP_SERVICE_UUID)
        assertTrue(APP_SERVICE_UUID.contains("-"))
    }

    @Test
    fun `META_CHAR_UUID is set`() {
        assertNotNull(META_CHAR_UUID)
    }

    @Test
    fun `INFO_CHAR_UUID is set`() {
        assertNotNull(INFO_CHAR_UUID)
    }

    @Test
    fun `spec 8 - BleAdvertisement equality with contentEquals`() {
        val a1 = BleAdvertisement(ByteArray(6) { 1 }, 1, ByteArray(4) { 2 })
        val a2 = BleAdvertisement(ByteArray(6) { 1 }, 1, ByteArray(4) { 2 })
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
    }

    @Test
    fun `spec 8 - BleMetaPayload equality with contentEquals`() {
        val p1 = BleMetaPayload(ByteArray(16), 1, ByteArray(32), 100, "x")
        val p2 = BleMetaPayload(ByteArray(16), 1, ByteArray(32), 100, "x")
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }
}
