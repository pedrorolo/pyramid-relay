package com.pyramidrelay

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.security.SecureRandom
import java.util.Base64

/**
 * Collects a Play Integrity token at startup.
 *
 * IMPORTANT: A Play Integrity token only proves anything once it is verified
 * server-side against Google's Play Integrity API. This app is fully offline and
 * has no backend, so we only obtain and log the token here; the OS-level tamper
 * protection still comes from Play App Signing (the AAB is re-signed by Google).
 * If a backend is added later, send the obtained token to it and call the
 * Play Integrity API to validate device/app/install integrity.
 */
object PyramidRelayIntegrityHelper {
    private const val TAG = "EventLog"

    fun requestToken(context: Context) {
        try {
            val manager = IntegrityManagerFactory.create(context)
            val nonce = generateNonce()
            manager.requestIntegrityToken(
                IntegrityTokenRequest.builder()
                    .setNonce(nonce)
                    .build()
            ).addOnSuccessListener { response ->
                val token = response.token()
                Log.i(TAG, "[app] Play Integrity token obtained (len=${token.length})")
            }.addOnFailureListener { e ->
                Log.w(TAG, "[app] Play Integrity unavailable: ${e.message}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[app] Play Integrity init failed: ${e.message}")
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }
}
