package com.pyramidrelay

data class SubscriptionEntity(
    val fileId: String,
    val publicKey: String,
    val fileName: String? = null,
    val relayName: String? = null,
    val localVersion: Int? = null,
    val localUri: String? = null,
    val subscribedAt: Long,
    val lastSeenVersion: Int? = null,
    val lastSeenAt: Long? = null,
    val lastNotifiedVersion: Int? = null
)
