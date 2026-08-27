package p2p.broadcaster

import android.graphics.Bitmap
import p2p.broadcaster.EventLog
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

@Composable
fun QrDisplayDialog(
    fileId: String,
    pk: String,
    name: String,
    version: Int,
    onDismiss: () -> Unit
) {
    val uri = "p2pbroadcaster://subscribe?fileId=$fileId&pk=$pk&name=$name&v=$version"
    val bitmap = remember(uri) {
        try {
            BarcodeEncoder().encodeBitmap(uri, BarcodeFormat.QR_CODE, 400, 400)
        } catch (e: Exception) {
            EventLog.log("app", "Failed to encode QR bitmap: ${e.message}")
            Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share QR Code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(250.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("v$version | $name", modifier = Modifier.padding(top = 8.dp))
                Text("Scan to subscribe", modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
