package com.pyramidrelay

data class SubscriptionEntity(
    val fileId: String,
    val publicKey: String,
    val fileName: String?,
    val localVersion: Int?,
    val localUri: String?,
    val subscribedAt: Long,
    val lastSeenVersion: Int?,
    val lastSeenAt: Long?,
    val lastNotifiedVersion: Int?
)
