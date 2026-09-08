package com.pyramidrelay

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Public issue tracker: doubles as the abuse-report contact (see EULA §3, PRIVACY.md §11). */
const val ISSUE_TRACKER_URL = "https://github.com/pedrorolo/pyramid-relay/issues"

/**
 * Terms gate shown before the first broadcast or subscription (Play UGC policy:
 * users must accept the terms of use before they can create or upload UGC).
 */
@Composable
fun TermsGateDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Community terms") },
        text = {
            Text(
                "Files you broadcast or subscribe to are shared with nearby devices, " +
                    "which automatically relay them further.\n\n" +
                    "• Only share files you have the right to distribute.\n" +
                    "• No illegal content, no harassment, no sexual content involving " +
                    "minors — zero tolerance.\n" +
                    "• You can report or block any file from its entry; blocked " +
                    "files are deleted, never fetched again, and never relayed.\n\n" +
                    "Full terms: Settings → End User License Agreement."
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } }
    )
}

/**
 * Combined report/block dialog for a subscription (Play UGC policy: in-app
 * system for reporting objectionable UGC and blocking users/content).
 * Blocking takes effect immediately on-device; reporting opens the public
 * issue tracker so the maintainer can act (e.g. block originator keys).
 */
@Composable
fun ReportBlockDialog(
    displayName: String,
    fileId: String,
    onBlock: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report or block this file?") },
        text = {
            Text(
                "\"$displayName\"\n(file ${fileId.takeLast(8)})\n\n" +
                    "Blocking deletes local files, stops relaying, and never " +
                    "fetches or forwards this file again. Reporting opens the " +
                    "public issue tracker so the maintainer can act."
            )
        },
        confirmButton = { TextButton(onClick = onBlock) { Text("Block file") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    EventLog.log("sub", "Reported file ${fileId.takeLast(8)} via issue tracker")
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_TRACKER_URL))
                        )
                    } catch (_: Exception) { }
                    onDismiss()
                }) { Text("Report online") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
