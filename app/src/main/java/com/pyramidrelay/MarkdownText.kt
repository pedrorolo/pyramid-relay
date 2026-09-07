package com.pyramidrelay

import android.graphics.Color as AndroidColor
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val markwon = remember(colorScheme) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    AndroidView(
        modifier = if (scrollable) modifier.verticalScroll(rememberScrollState()) else modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setBackgroundColor(AndroidColor.TRANSPARENT)
            }
        },
        update = { tv ->
            tv.setTextColor(colorScheme.onSurface.toArgb())
            tv.setLinkTextColor(colorScheme.primary.toArgb())
            markwon.setMarkdown(tv, text)
        }
    )
}
