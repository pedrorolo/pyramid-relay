package p2p.broadcaster

// 16-bit alias 0xF47B in Bluetooth base UUID form. The advertisement must fit
// legacy 31B packets: Flags 3B + ServiceData AD structure (2B len/type + 2B
// UUID16 + 14B payload) = 21B. A 128-bit UUID would cost 16B more (35B total)
// and fail with ADVERTISE_FAILED_DATA_TOO_LARGE. GATT characteristics stay
// 128-bit - they never appear in the advertisement.
const val APP_SERVICE_UUID = "0000f47b-0000-1000-8000-00805f9b34fb"
const val META_CHAR_UUID = "f47b5e2a-1c3d-4a6e-8b9f-0d2e4f6a8c0c"
const val INFO_CHAR_UUID = "f47b5e2a-1c3d-4a6e-8b9f-0d2e4f6a8c0d"
const val STREAM_CHAR_UUID = "f47b5e2a-1c3d-4a6e-8b9f-0d2e4f6a8c0e"
const val TRANSFER_PORT = 8988
// GATT-only transfer (option C): conservative Android-to-Android BLE throughput
// is ~50 KB/s; a 15 MB file therefore takes at most ~5 minutes to transfer.
const val MAX_FILE_SIZE = 15L * 1024 * 1024

data class BleAdvertisement(
    val fileIdHash: ByteArray,
    val version: Int,
    val keyId: ByteArray
) {
    companion object {
        const val SERVICE_DATA_SIZE = 6 + 4 + 4

        fun fromServiceData(data: ByteArray): BleAdvertisement? {
            if (data.size < SERVICE_DATA_SIZE) return null
            val fileIdHash = data.copyOfRange(0, 6)
            val version = ((data[6].toInt() and 0xFF) shl 24) or
                ((data[7].toInt() and 0xFF) shl 16) or
                ((data[8].toInt() and 0xFF) shl 8) or
                (data[9].toInt() and 0xFF)
            val keyId = data.copyOfRange(10, 14)
            return BleAdvertisement(fileIdHash, version, keyId)
        }
    }

    fun toServiceData(): ByteArray {
        val out = ByteArray(SERVICE_DATA_SIZE)
        fileIdHash.copyInto(out, 0, 0, 6)
        out[6] = (version shr 24).toByte()
        out[7] = (version shr 16).toByte()
        out[8] = (version shr 8).toByte()
        out[9] = version.toByte()
        keyId.copyInto(out, 10, 0, 4)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleAdvertisement) return false
        return fileIdHash.contentEquals(other.fileIdHash) &&
            version == other.version &&
            keyId.contentEquals(other.keyId)
    }

    override fun hashCode(): Int {
        var result = fileIdHash.contentHashCode()
        result = 31 * result + version
        result = 31 * result + keyId.contentHashCode()
        return result
    }
}

data class BleMetaPayload(
    val fileId: ByteArray,
    val version: Int,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val fileHash: ByteArray,
    val fileSize: Long
) {
    companion object {
        const val SIZE = 16 + 4 + 32 + 64 + 32 + 4

        fun fromBytes(data: ByteArray): BleMetaPayload? {
            if (data.size < SIZE) return null
            var offset = 0
            val fileId = data.copyOfRange(offset, offset + 16); offset += 16
            val version = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            offset += 4
            val publicKey = data.copyOfRange(offset, offset + 32); offset += 32
            val signature = data.copyOfRange(offset, offset + 64); offset += 64
            val fileHash = data.copyOfRange(offset, offset + 32); offset += 32
            val fileSize = ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
            return BleMetaPayload(fileId, version, publicKey, signature, fileHash, fileSize)
        }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(SIZE)
        var offset = 0
        fileId.copyInto(out, offset); offset += 16
        out[offset] = (version shr 24).toByte()
        out[offset + 1] = (version shr 16).toByte()
        out[offset + 2] = (version shr 8).toByte()
        out[offset + 3] = version.toByte()
        offset += 4
        publicKey.copyInto(out, offset); offset += 32
        signature.copyInto(out, offset); offset += 64
        fileHash.copyInto(out, offset); offset += 32
        out[offset] = (fileSize shr 24).toByte()
        out[offset + 1] = (fileSize shr 16).toByte()
        out[offset + 2] = (fileSize shr 8).toByte()
        out[offset + 3] = fileSize.toByte()
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleMetaPayload) return false
        return fileId.contentEquals(other.fileId) &&
            version == other.version &&
            publicKey.contentEquals(other.publicKey) &&
            signature.contentEquals(other.signature) &&
            fileHash.contentEquals(other.fileHash) &&
            fileSize == other.fileSize
    }

    override fun hashCode(): Int {
        var result = fileId.contentHashCode()
        result = 31 * result + version
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + fileHash.contentHashCode()
        result = 31 * result + fileSize.hashCode()
        return result
    }
}
