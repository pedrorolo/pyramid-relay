package com.pyramidrelay

import android.text.Html
import android.text.util.Linkify
import android.widget.TextView
import java.util.regex.Pattern
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

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
        val context = LocalContext.current
        val markdown = remember(filePath) {
            try {
                java.io.File(filePath).readText()
            } catch (e: Exception) {
                "*Error reading file: ${e.message}*"
            }
        }
        val markwon = remember(context) { Markwon.create(context) }
        val scrollState = rememberScrollState()
        AndroidView(
            factory = { ctx ->
                TextView(ctx).apply {
                    setTextColor(android.graphics.Color.parseColor("#FFFFFFFF"))
                    setLineSpacing(0f, 1.2f)
                    textSize = 14f
                }
            },
            update = { textView -> markwon.setMarkdown(textView, markdown) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(scrollState)
        )
    }
}

class TextViewer : FileViewer() {
    override val extensions = setOf("txt", "text", "log", "csv", "json", "xml")

    @Composable
    override fun Render(filePath: String) {
        val context = LocalContext.current
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
                    setTextColor(android.graphics.Color.parseColor("#FFFFFFFF"))
                    setLineSpacing(0f, 1.2f)
                    textSize = 14f
                    setTextIsSelectable(true)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            },
            update = { textView ->
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
        val html = remember(filePath) {
            try {
                java.io.File(filePath).readText()
            } catch (e: Exception) {
                "<p>Error reading file: ${e.message}</p>"
            }
        }
        val scrollState = rememberScrollState()
        AndroidView(
            factory = { ctx ->
                TextView(ctx).apply {
                    setTextColor(android.graphics.Color.parseColor("#FFFFFFFF"))
                    setLineSpacing(0f, 1.2f)
                    textSize = 14f
                }
            },
            update = { textView ->
                textView.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(scrollState)
        )
    }
}

val fileViewers: List<FileViewer> = listOf(MarkdownViewer(), TextViewer(), HtmlViewer())

fun getViewerFor(fileName: String): FileViewer? {
    return fileViewers.firstOrNull { it.canHandle(fileName) }
}
