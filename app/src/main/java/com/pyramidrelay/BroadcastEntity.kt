package com.pyramidrelay

enum class Role { ORIGINATOR, RELAY }

data class BroadcastEntity(
    val fileId: String,
    val fileName: String,
    val relayName: String = fileName,
    val mimeType: String,
    val internalUri: String,
    val fileHash: String,
    val fileSize: Long,
    val compressedSize: Long,
    val version: Int,
    val publicKey: String,
    val privateKeyAlias: String?,
    val signature: String,
    val role: Role,
    val createdAt: Long,
    val updatedAt: Long
)
