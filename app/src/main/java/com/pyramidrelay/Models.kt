package com.pyramidrelay

// 16-bit alias 0xF47B in Bluetooth base UUID form. The advertisement must fit
// legacy 31B packets: Flags 3B + ServiceData AD structure (2B len/type + 2B
// UUID16 + 14B payload) = 21B. A 128-bit UUID would cost 16B more (35B total)
// and fail with ADVERTISE_FAILED_DATA_TOO_LARGE. GATT characteristics stay
// 128-bit - they never appear in the advertisement.
// 0x6D38 is the 16-bit value derived from uuidgen output
// 6d388575-46d6-4e84-9384-b14fb2006b20. Keeping the service UUID in the
// Bluetooth base form leaves room for the 14-byte discovery payload.
const val APP_SERVICE_UUID = "00006d38-0000-1000-8000-00805f9b34fb"
const val META_CHAR_UUID = "826c59a6-7b83-4270-a039-2550fa5b5aef"
const val INFO_CHAR_UUID = "3a18840a-9dd4-4ae1-8f13-daa6d8240183"
const val STREAM_CHAR_UUID = "4939a5ce-2837-4a3f-91b2-e83ede29d06f"
const val TRANSFER_PORT = 8988
// GATT-only transfer (option C): conservative Android-to-Android BLE throughput
// is ~50 KB/s; a 100 MB file therefore takes at most ~33 minutes to transfer.

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
    val fileHash: ByteArray,
    val fileSize: Long,
    val fileName: String
) {
    companion object {
        // Fixed prefix size (everything except the variable-length fileName):
        // fileId 16B + version 4B + sig 64B + hash 32B + size 4B + nameLen 2B
        const val FIXED_SIZE = 16 + 4 + 32 + 4 + 2
        const val MAX_NAME_LEN = 65535

        fun fromBytes(data: ByteArray): BleMetaPayload? {
            if (data.size < FIXED_SIZE) return null
            var offset = 0
            val fileId = data.copyOfRange(offset, offset + 16); offset += 16
            val version = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            offset += 4
            val fileHash = data.copyOfRange(offset, offset + 32); offset += 32
            val fileSize = ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
            offset += 4
            val nameLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (nameLen > MAX_NAME_LEN) return null
            if (data.size < FIXED_SIZE + nameLen) return null
            val fileName = String(data.copyOfRange(offset, offset + nameLen), Charsets.UTF_8)
            return BleMetaPayload(fileId, version, fileHash, fileSize, fileName)
        }
    }

    fun toBytes(): ByteArray {
        val rawNameBytes = fileName.toByteArray(Charsets.UTF_8)
        val nameBytes = if (rawNameBytes.size > MAX_NAME_LEN) rawNameBytes.copyOf(MAX_NAME_LEN) else rawNameBytes
        val out = ByteArray(FIXED_SIZE + nameBytes.size)
        var offset = 0
        fileId.copyInto(out, offset); offset += 16
        out[offset] = (version shr 24).toByte()
        out[offset + 1] = (version shr 16).toByte()
        out[offset + 2] = (version shr 8).toByte()
        out[offset + 3] = version.toByte()
        offset += 4
        fileHash.copyInto(out, offset); offset += 32
        out[offset] = (fileSize shr 24).toByte()
        out[offset + 1] = (fileSize shr 16).toByte()
        out[offset + 2] = (fileSize shr 8).toByte()
        out[offset + 3] = fileSize.toByte()
        offset += 4
        out[offset] = (nameBytes.size shr 8).toByte()
        out[offset + 1] = nameBytes.size.toByte()
        offset += 2
        nameBytes.copyInto(out, offset)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleMetaPayload) return false
        return fileId.contentEquals(other.fileId) &&
            version == other.version &&
            fileHash.contentEquals(other.fileHash) &&
            fileSize == other.fileSize &&
            fileName == other.fileName
    }

    override fun hashCode(): Int {
        var result = fileId.contentHashCode()
        result = 31 * result + version
        result = 31 * result + fileHash.contentHashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
