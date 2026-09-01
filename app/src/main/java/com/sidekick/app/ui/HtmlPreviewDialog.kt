package com.sidekick.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen HTML preview.
 *
 * Renders [html] in an in-app [WebView] so a teammate's generated page can
 * be seen as an actual rendered website, not a wall of source. This is the
 * "build a website from your phone" demo beat — deterministic (works with
 * any provider, since it reads the assistant message text rather than
 * depending on tool calls) and self-contained (the HTML is inlined, no
 * network fetch).
 *
 * @param html The full HTML document to render.
 * @param onDismiss Invoked when the user closes the preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlPreviewDialog(
    html: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // WebView is expensive to recreate; remember it across recompositions
    // so reopening the preview (or rotating) doesn't re-inflate it.
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview") },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close preview")
                    }
                },
            )
        },
    ) { inner ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        )
        // Load the HTML once the view is composed. Using a LaunchedEffect
        // keyed on the html keeps reloads out of the factory (which runs on
        // a separate thread and can't safely touch the main-thread-only
        // WebView).
        androidx.compose.runtime.LaunchedEffect(html) {
            webView.loadDataWithBaseURL(
                null,
                html,
                "text/html",
                "UTF-8",
                null,
            )
        }
    }
}

/**
 * Detect whether an assistant reply is a full, self-contained HTML document
 * worth offering a preview for.
 *
 * Heuristic (deliberately loose — the model isn't guaranteed to emit a
 * perfect `<!DOCTYPE html>`):
 *  - The text contains an `<html` opening tag, AND
 *  - it contains a `<body` or `<head` tag, AND
 *  - the total length is under ~200 KB (bigger and it's probably not a
 *    hand-written page, or it's a dump that won't render meaningfully).
 *
 * Returns the full HTML string when it looks like a document, else null.
 */
fun extractHtmlDocument(text: String): String? {
    // Strip a leading markdown code fence if present — models almost always
    // wrap HTML in ```html ... ``` or ``` ... ```. Unwrap it so the
    // detector sees the raw document.
    var html = text
    val fenceRegex = Regex(
        "^```(?:html|HTML)?\\s*\\n(.*)\\n```\\s*$",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    html = fenceRegex.find(text.trim())?.groupValues?.get(1) ?: html

    val lower = html.lowercase()
    if (!lower.contains("<html") && !lower.contains("<!doctype")) return null
    if (!lower.contains("<body") && !lower.contains("<head") && !lower.contains("<style")) return null
    if (html.length > 200_000) return null
    return html
}
