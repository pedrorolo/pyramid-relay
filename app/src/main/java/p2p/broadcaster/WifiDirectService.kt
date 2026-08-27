package p2p.broadcaster

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectService(private val context: Context) {
    companion object {
        private const val TAG = "WifiDirect"
        private const val TRANSFER_PORT_NUM = 8988
        private const val PUSH_MAGIC = "P2PF"
        private const val PULL_MAGIC = "P2PG"
        const val GO_ADDRESS = "192.168.49.1"
        /** Peer device names tagged by our app start with this prefix. */
        private const val NAME_PREFIX = "P2PB-"

        fun reasonText(reason: Int) = when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            else -> "code $reason"
        }

        fun keyIdHex(keyId: ByteArray) = keyId.joinToString("") { "%02x".format(it) }.take(8)
    }

    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    var onTransferReceived: (suspend (fileId: String, version: Int, inputStream: InputStream, fileSize: Long) -> Unit)? = null
    var onRequestFile: (suspend (fileId: String, version: Int, outputStream: java.io.OutputStream) -> Unit)? = null

    // Discovered peers / negotiated connection info delivered by the system receiver
    @Volatile private var lastPeers: Collection<WifiP2pDevice>? = null
    @Volatile private var lastConnInfo: WifiP2pInfo? = null
    private val peersLatch = java.util.concurrent.CountDownLatch(1)
    private val connLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * Initializes the P2P framework channel and registers the system broadcast
     * receiver that feeds peer-list and connection-info updates. Must be called
     * before any other P2P operation - without it every call fails instantly
     * with a null channel.
     */
    @SuppressLint("MissingPermission")
    fun initialize() {
        if (channel != null) return
        val mgr = wifiP2pManager ?: run { EventLog.log("wifi", "WifiP2pManager unavailable on this device"); return }
        channel = mgr.initialize(context, context.mainLooper, null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        @Suppress("DEPRECATION")
                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                            android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            try { mgr.requestPeers(ch) { peers -> lastPeers = peers.deviceList; peersLatch.countDown() } } catch (e: Exception) { EventLog.log("wifi", "requestPeers failed: ${e.message}") }
                        } else peersLatch.countDown()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        try { mgr.requestConnectionInfo(ch) { info -> lastConnInfo = info; connLatch.countDown() } } catch (_: Exception) {}
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                            EventLog.log("wifi", "Wi-Fi P2P DISABLED (state=$state) - enable Wi-Fi")
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
        EventLog.log("wifi", "P2P framework initialized")
    }

    /** Tags this device's P2P name so peers can recognize our advertisements' owner.
     *  Hidden API - best effort via reflection; silently skipped where blocked. */
    fun setDeviceTag(keyId: ByteArray) {
        val ch = channel ?: run { EventLog.log("wifi", "setDeviceTag skipped - channel not initialized"); return }
        try {
            val m = wifiP2pManager!!.javaClass.getMethod(
                "setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java
            )
            m.invoke(wifiP2pManager, ch, "$NAME_PREFIX${keyIdHex(keyId)}", object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) { /* Hidden API blocked - fall back to first-peer matching */ }
    }

    private fun locationManagerEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
    }

    /**
     * Discovers peers, connects to OUR peer tagged with [keyId], waits for the
     * negotiated group, and returns the group owner's IP to transfer against.
     */
    @SuppressLint("MissingPermission")
    fun ensureConnectedToDevice(keyId: ByteArray, timeoutMs: Long = 30_000): String? {
        val ch = channel ?: run { EventLog.log("wifi", "P2P channel not initialized"); return null }
        if (!locationManagerEnabled()) {
            EventLog.log("wifi", "Location services OFF - Wi-Fi Direct discovery impossible. Enable Location.")
            return null
        }
        val want = NAME_PREFIX + keyIdHex(keyId)
        val start = System.currentTimeMillis()

        // A full discovery sweep across the three social channels can take well
        // over 10s - do NOT interrupt it; wait for the PEERS_CHANGED callback
        // (an EMPTY peer list still counts as an arrival).
        try {
            wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { EventLog.log("wifi", "discoverPeers failed: ${reasonText(reason)}") }
            })
        } catch (e: Exception) {
            EventLog.log("wifi", "discoverPeers error: ${e.message}")
            return null
        }
        while (lastPeers == null && System.currentTimeMillis() - start < timeoutMs) { Thread.sleep(200) }
        val peers = lastPeers
        if (peers == null) { EventLog.log("wifi", "No PEERS_CHANGED within ${timeoutMs}ms"); return null }
        val names = peers.joinToString(", ") { "\"${it.deviceName}\"" }
        // Prefer a peer tagged by our app with the matching keyId; otherwise (hidden
        // setDeviceName blocked) try the first discovered peer - the pull itself
        // verifies the peer actually serves the file.
        val target = peers.firstOrNull { it.deviceName == want }
            ?: peers.firstOrNull()
            ?: run {
                EventLog.log("wifi", "Peer list empty")
                return null
            }
        EventLog.log("wifi", "Connecting to peer \"${target.deviceName}\"...")
        val config = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            groupOwnerIntent = 0 // prefer being client; the file holder (broadcaster) should win GO
        }
        lastConnInfo = null
        try {
            wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { EventLog.log("wifi", "connect failed: ${reasonText(reason)}") }
            })
        } catch (e: Exception) { EventLog.log("wifi", "connect error: ${e.message}"); return null }
        while (System.currentTimeMillis() - start < timeoutMs) {
            val info = lastConnInfo
            if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                val go = info.groupOwnerAddress.hostAddress
                EventLog.log("wifi", "P2P group formed - GO=$go (weAreGo=${info.isGroupOwner})")
                return go
            }
            Thread.sleep(200)
        }
        EventLog.log("wifi", "Timed out waiting for P2P group formation")
        return null
    }

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        channel?.let { wifiP2pManager?.removeGroup(it, null) }
    }

    @Volatile
    private var serverRunning = false

    fun startServer() {
        if (serverRunning) return
        serverRunning = true
        scope.launch {
            try {
                // Bind explicitly so SO_REUSEADDR is set BEFORE the OS assigns the port:
                // prevents TIME_WAIT-style rebind failures between service restarts.
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(TRANSFER_PORT_NUM))
                serverSocket = socket
                Log.d(TAG, "Server listening on port $TRANSFER_PORT_NUM")
                EventLog.log("wifi", "Transfer server listening on port $TRANSFER_PORT_NUM")
                while (serverRunning) {
                    val s = serverSocket ?: break
                    val conn = try { s.accept() } catch (e: Exception) { EventLog.log("wifi", "Server accept failed: ${e.message}"); break }
                    if (!serverRunning) { runCatching { conn.close() }; break }
                    launch { handleServerConnection(conn) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                EventLog.log("wifi", "Transfer server error: ${e.message}")
            } finally {
                // If we exited early (stopped before we even bound), make sure we never
                // leave an orphaned bound socket behind.
                if (!serverRunning) runCatching { serverSocket?.close() }
                runCatching { serverSocket?.close() }
            }
        }
    }

    private suspend fun handleServerConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val magic = ByteArray(4)
            readFully(input, magic)
            when (String(magic)) {
                PUSH_MAGIC -> handlePushRequest(socket, input)
                PULL_MAGIC -> handlePullRequest(socket, input)
                else -> socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection", e)
            EventLog.log("wifi", "Connection handling error: ${e.message}")
        } finally {
            socket.close()
        }
    }

    /** Peer pushed a file to us: "P2PF" | fileIdLen(u32) | fileId | version(u32) | fileSize(u64) | body */
    private suspend fun handlePushRequest(socket: Socket, input: InputStream) {
        val fileIdLen = readInt(input)
        val fileIdBytes = ByteArray(fileIdLen)
        readFully(input, fileIdBytes)
        val fileId = String(fileIdBytes, Charsets.UTF_8)
        val version = readInt(input)
        val fileSize = readLong(input)
        EventLog.log("wifi", "Incoming push: $fileId v$version (${fileSize}B)")
        onTransferReceived?.invoke(fileId, version, input, fileSize)
        EventLog.log("wifi", "Incoming push of $fileId v$version finished")
    }

    /** Peer pulled a file from us: request "P2PG" | fileIdLen(u32) | fileId | version(u32),
     *  response "P2PF" | fileSize(u64) | body. */
    private suspend fun handlePullRequest(socket: Socket, input: InputStream) {
        val output = socket.getOutputStream()
        val fileIdLen = readInt(input)
        val fileIdBytes = ByteArray(fileIdLen)
        readFully(input, fileIdBytes)
        val fileId = String(fileIdBytes, Charsets.UTF_8)
        val version = readInt(input)
        val provider = onRequestFile
        if (provider == null) { socket.close(); EventLog.log("wifi", "Pull request for $fileId but no provider set"); return }
        try {
            // Buffer the file in memory so the header can carry an exact size.
            val buffer = java.io.ByteArrayOutputStream()
            provider(fileId, version, buffer)
            val data = buffer.toByteArray()
            output.write(PUSH_MAGIC.toByteArray(Charsets.US_ASCII))
            output.write(longToBytes(data.size.toLong()))
            output.write(data)
            output.flush()
            EventLog.log("wifi", "Served pull: $fileId v$version (${data.size}B)")
        } catch (e: Exception) {
            EventLog.log("wifi", "Failed serving pull for $fileId: ${e.message}")
            Log.e(TAG, "Error serving pull request for $fileId", e)
        }
    }

    /** Pull a file from a peer's server and write it to [outputStream]. Suspends until complete.
     *  Returns true on success. */
    suspend fun pullFile(
        goAddress: String,
        fileId: String,
        version: Int,
        outputStream: java.io.OutputStream,
    ): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(goAddress, TRANSFER_PORT_NUM), 10_000)
            socket.soTimeout = 30_000
            try {
                val output = socket.getOutputStream()
                output.write(PULL_MAGIC.toByteArray(Charsets.US_ASCII))
                output.write(intToBytes(fileId.toByteArray().size))
                output.write(fileId.toByteArray(Charsets.UTF_8))
                output.write(intToBytes(version))
                output.flush()

                val input = socket.getInputStream()
                val magic = ByteArray(4)
                readFully(input, magic)
                if (String(magic) != PUSH_MAGIC) { EventLog.log("wifi", "Pull rejected: bad response header"); return false }
                val fileSize = readLong(input)
                EventLog.log("wifi", "Receiving $fileId v$version ($fileSize B)...")
                copyStream(input, outputStream, fileSize)
                true
            } finally {
                outputStream.close()
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling file $fileId", e)
            EventLog.log("wifi", "Failed pulling file $fileId: ${e.message}")
            false
        }
    }

    /** Suspends until the transfer server socket is bound (or times out). */
    suspend fun awaitServerReady(timeoutMs: Long = 5_000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val s = serverSocket
            if (s != null && !s.isClosed) return true
            kotlinx.coroutines.delay(50)
        }
        return false
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw java.io.EOFException("Unexpected end of stream")
            offset += n
        }
    }

    private fun readInt(input: InputStream): Int {
        val buf = ByteArray(4); readFully(input, buf)
        return ((buf[0].toInt() and 0xFF) shl 24) or
            ((buf[1].toInt() and 0xFF) shl 16) or
            ((buf[2].toInt() and 0xFF) shl 8) or
            (buf[3].toInt() and 0xFF)
    }

    private fun readLong(input: InputStream): Long {
        val buf = ByteArray(8); readFully(input, buf)
        var v = 0L
        for (b in buf) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    private fun copyStream(input: InputStream, output: java.io.OutputStream, expectedSize: Long) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (total < expectedSize) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedSize - total).toInt())
            if (n < 0) throw java.io.EOFException("File shorter than announced")
            output.write(buffer, 0, n)
            total += n
        }
        output.flush()
    }

    fun sendFile(
        goAddress: String,
        fileId: String,
        version: Int,
        fileSize: Long,
        inputStream: InputStream,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(goAddress, TRANSFER_PORT_NUM), 10_000)
                val output = socket.getOutputStream()
                output.write("P2PF".toByteArray())
                output.write(intToBytes(fileId.toByteArray().size))
                output.write(fileId.toByteArray())
                output.write(intToBytes(version))
                output.write(longToBytes(fileSize))
                val buffer = ByteArray(65536)
                var totalSent = 0L
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                    totalSent += bytesRead
                    onProgress?.invoke(totalSent, fileSize)
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending file", e)
                EventLog.log("wifi", "Failed sending file: ${e.message}")
            }
        }
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private fun longToBytes(value: Long) = byteArrayOf(
        ((value ushr 56) and 0xFF).toByte(),
        ((value ushr 48) and 0xFF).toByte(),
        ((value ushr 40) and 0xFF).toByte(),
        ((value ushr 32) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    fun stopServer() {
        serverRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            EventLog.log("wifi", "Error closing server socket: ${e.message}")
        }
        serverSocket = null
    }

    fun destroy() {
        stopServer()
        removeGroup()
        scope.cancel()
    }
}
