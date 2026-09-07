package com.pyramidrelay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.util.Linkify
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import java.util.regex.Pattern
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

abstract class FileViewer {
    abstract val extensions: Set<String>

    fun canHandle(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in extensions
    }

    @Composable
    abstract fun Render(filePath: String)
}

class MarkdownViewer : FileViewer() {
    override val extensions = setOf("md", "markdown")

    @Composable
    override fun Render(filePath: String) {
        val markdown = remember(filePath) {
            try {
                java.io.File(filePath).readText()
            } catch (e: Exception) {
                "*Error reading file: ${e.message}*"
            }
        }
        MarkdownText(
            text = markdown,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            scrollable = true
        )
    }
}

class ImageViewer : FileViewer() {
    override val extensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    @Composable
    override fun Render(filePath: String) {
        val colorScheme = MaterialTheme.colorScheme
        val bitmap = remember(filePath) { decodeSampledBitmap(filePath) }
        val scrollState = rememberScrollState()
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }
            },
            update = { imageView ->
                imageView.setBackgroundColor(colorScheme.surface.toArgb())
                imageView.setImageBitmap(bitmap)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(scrollState)
        )
    }

    private fun decodeSampledBitmap(filePath: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = calculateInSampleSize(options, 2048, 2048)
        options.inJustDecodeBounds = false
        return try {
            BitmapFactory.decodeFile(filePath, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

class TextViewer : FileViewer() {
    override val extensions = setOf("txt", "text", "log", "csv", "json", "xml")

    @Composable
    override fun Render(filePath: String) {
        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme
        val text = remember(filePath) {
            try {
                java.io.File(filePath).readText()
            } catch (e: Exception) {
                "Error reading file: ${e.message}"
            }
        }
        val scrollState = rememberScrollState()
        AndroidView(
            factory = { ctx ->
                TextView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    setLineSpacing(0f, 1.2f)
                    textSize = 14f
                    setTextIsSelectable(true)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            },
            update = { textView ->
                textView.setTextColor(colorScheme.onSurface.toArgb())
                textView.setLinkTextColor(colorScheme.primary.toArgb())
                textView.text = text
                Linkify.addLinks(textView, Linkify.WEB_URLS)
                Linkify.addLinks(textView, Pattern.compile("pyramidrelay://[^\\s]+"), "pyramidrelay")
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(scrollState)
        )
    }
}

class HtmlViewer : FileViewer() {
    override val extensions = setOf("html", "htm")

    @Composable
    override fun Render(filePath: String) {
        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme
        val html = remember(filePath) {
            try {
                java.io.File(filePath).readText()
            } catch (e: Exception) {
                "<p>Error reading file: ${e.message}</p>"
            }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(colorScheme.surface.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith("pyramidrelay://")) {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                return true
                            }
                            return false
                        }
                    }
                }
            },
            update = { webView ->
                webView.setBackgroundColor(colorScheme.surface.toArgb())
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
        )
    }
}

val fileViewers: List<FileViewer> = listOf(ImageViewer(), MarkdownViewer(), TextViewer(), HtmlViewer())

fun getViewerFor(fileName: String): FileViewer? {
    return fileViewers.firstOrNull { it.canHandle(fileName) }
}
