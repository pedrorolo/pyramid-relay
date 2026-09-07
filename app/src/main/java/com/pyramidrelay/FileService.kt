package com.pyramidrelay

import android.content.Context
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class FileService(private val context: Context) {

    companion object {
        private const val STORE_DIR = "store"
        private const val MIN_FREE_SPACE = 10L * 1024 * 1024
    }

    fun getStoreDir(): File = File(context.filesDir, STORE_DIR)
    fun getBroadcastDir(fileId: String): File = File(getStoreDir(), fileId)
    fun getVersionDir(fileId: String, version: Int): File = File(getBroadcastDir(fileId), "v$version")
    fun getFile(fileId: String, version: Int): File = File(getVersionDir(fileId, version), "file")
    fun getTmpFile(fileId: String, version: Int): File = File(getVersionDir(fileId, version), "file.tmp")

    fun commitDownloadedFile(fileId: String, version: Int, tmpFile: File): File {
        val versionDir = getVersionDir(fileId, version)
        if (!versionDir.exists() && !versionDir.mkdirs()) {
            throw IllegalStateException("Cannot create download directory: ${versionDir.absolutePath}")
        }
        val target = getFile(fileId, version)
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Cannot replace existing file: ${target.absolutePath}")
        }
        if (!tmpFile.renameTo(target)) {
            throw IllegalStateException("Cannot commit downloaded file: ${tmpFile.absolutePath}")
        }
        return target
    }

    fun importFile(fileId: String, version: Int, inputStream: InputStream, contentLength: Long?): String {
        val vDir = getVersionDir(fileId, version); vDir.mkdirs()
        val tmpFile = getTmpFile(fileId, version)
        try {
            FileOutputStream(tmpFile).use { output -> inputStream.use { input -> input.copyTo(output, bufferSize = 65536) } }
            val stat = StatFs(context.filesDir.path)
            if (stat.availableBlocksLong * stat.blockSizeLong < tmpFile.length() + MIN_FREE_SPACE) {
                tmpFile.delete(); throw IllegalStateException("Insufficient disk space")
            }
            val internalFile = getFile(fileId, version)
            tmpFile.renameTo(internalFile)
            return internalFile.absolutePath
        } catch (e: Exception) { tmpFile.delete(); throw e }
    }

    fun getFileName(uri: android.net.Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return "unknown"
    }

    fun getMimeType(uri: android.net.Uri): String = context.contentResolver.getType(uri) ?: "application/octet-stream"

    fun deleteAll(fileId: String) { getBroadcastDir(fileId).deleteRecursively() }
    fun hasFile(fileId: String, version: Int): Boolean = getFile(fileId, version).exists()

    fun evictOldVersions(fileId: String, keepVersion: Int) {
        val dir = getBroadcastDir(fileId); if (!dir.exists()) return
        dir.listFiles()?.forEach { vDir ->
            val v = vDir.name.removePrefix("v").toIntOrNull()
            if (v != null && v != keepVersion) vDir.deleteRecursively()
        }
    }

    fun readForTransfer(fileId: String, version: Int): InputStream {
        val file = getFile(fileId, version)
        if (!file.exists()) throw IllegalStateException("File not found for transfer")
        return file.inputStream()
    }

    fun getCompressedFile(fileId: String, version: Int): File {
        val compressed = File(getVersionDir(fileId, version), "file.compressed")
        if (compressed.exists()) return compressed
        val original = getFile(fileId, version)
        if (!original.exists()) throw IllegalStateException("File not found: $fileId v$version")
        compressFile(original, compressed)
        return compressed
    }

    fun getCompressedSize(fileId: String, version: Int): Long {
        return getCompressedFile(fileId, version).length()
    }

    fun compressFile(input: File, output: File) {
        GZIPOutputStream(output.outputStream().buffered()).use { gzip ->
            input.inputStream().buffered().use { input ->
                input.copyTo(gzip, bufferSize = 65536)
            }
        }
    }

    fun decompressFile(input: File, output: File) {
        GZIPInputStream(input.inputStream().buffered()).use { gzip ->
            output.outputStream().buffered().use { out ->
                gzip.copyTo(out, bufferSize = 65536)
            }
        }
    }

    fun getAvailableSpace(): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}
