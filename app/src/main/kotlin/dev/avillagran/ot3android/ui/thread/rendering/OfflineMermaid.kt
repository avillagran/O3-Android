package dev.avillagran.ot3android.ui.thread.rendering

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import dev.avillagran.ot3android.R
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Untrusted diagrams never receive an app bridge, URL grant, file access or network access. */
internal object MermaidDocument {
    const val MAX_SOURCE = 16_384
    const val TIMEOUT_MS = 5_000L
    fun accepts(source: String): Boolean = source.length in 1..MAX_SOURCE && source.lines().size <= 200 &&
        !Regex("%%\\{|\\b(?:click|href)\\b|(?m)^\\s*---", RegexOption.IGNORE_CASE).containsMatchIn(source)

    fun html(source: String, renderer: String, nonce: String): String {
        require(accepts(source))
        val quoted = JSONObject.quote(source).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
<style>body{margin:8px;background:white;color:black}svg{max-width:100%;height:auto}a{pointer-events:none}</style>
</head><body><main id="diagram"></main><script nonce="$nonce">$renderer</script><script nonce="$nonce">
(async()=>{try{
mermaid.initialize({startOnLoad:false,securityLevel:'strict',maxTextSize:$MAX_SOURCE,maxEdges:200,
  suppressErrorRendering:true,deterministicIds:true,flowchart:{htmlLabels:false},secure:['securityLevel','maxTextSize','maxEdges','startOnLoad','secure']});
const result=await mermaid.render('boundedDiagram',$quoted);
if(result.svg.length>1048576)throw Error('limit');
const target=document.getElementById('diagram');target.innerHTML=result.svg;
const svg=target.querySelector('svg');const box=svg && svg.viewBox.baseVal;
if(!svg || target.querySelectorAll('*').length>4000 || !box || box.width>8192 || box.height>8192 || box.width*box.height>16000000)throw Error('limit');
for(const el of target.querySelectorAll('a'))el.removeAttribute('href');
document.title='mermaid-ready';
}catch(e){document.getElementById('diagram').textContent='';document.title='mermaid-failed';}})();
</script></body></html>"""
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun offlineMermaidView(context: android.content.Context, html: String, status: (Boolean) -> Unit): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        blockNetworkImage = true
        domStorageEnabled = false
        databaseEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = true
    }
    // Remove legacy bridges even on older system WebViews. Never install addJavascriptInterface.
    listOf("searchBoxJavaBridge_", "accessibility", "accessibilityTraversal").forEach(::removeJavascriptInterface)
    isLongClickable = false
    setOnLongClickListener { true }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
        @Deprecated("Legacy WebView")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { status(false) }
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { status(false); return true }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (title == "mermaid-ready") status(true)
            if (title == "mermaid-failed") status(false)
        }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
    }
    setDownloadListener { _, _, _, _, _ -> }
    loadDataWithBaseURL("https://mermaid.invalid/", html, "text/html", "UTF-8", null)
}

/** One bounded WebView per requested preview; destruction also stops timed-out JavaScript. */
@Composable
internal fun OfflineMermaid(source: String, onFailure: () -> Unit) {
    val context = LocalContext.current
    var enlarged by remember(source) { mutableStateOf(false) }
    val failed by rememberUpdatedState(onFailure)
    val html = remember(source) {
        if (MermaidDocument.accepts(source)) runCatching {
            MermaidDocument.html(source,
                context.assets.open("mermaid/mermaid.min.js").bufferedReader().use { it.readText() },
                java.util.UUID.randomUUID().toString())
        }.getOrNull() else null
    }
    if (android.os.Build.VERSION.SDK_INT < 29 || html == null) { LaunchedEffect(source) { failed() }; return }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    @Composable fun View(modifier: Modifier) {
        var web by remember { mutableStateOf<WebView?>(null) }
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(html) { delay(MermaidDocument.TIMEOUT_MS); if (!ready) {
            if (android.os.Build.VERSION.SDK_INT >= 29) web?.webViewRenderProcess?.terminate()
            web?.stopLoading(); failed()
        } }
        DisposableEffect(Unit) { onDispose { web?.stopLoading(); web?.destroy(); web = null } }
        AndroidView(factory = { viewContext ->
            try {
                offlineMermaidView(viewContext, html) { ok -> if (ok) ready = true else failed() }.also { web = it }
            } catch (_: Exception) {
                // A missing/disabled system WebView must preserve source instead of crashing.
                failed()
                android.view.View(viewContext)
            }
        }, modifier = modifier)
    }
    if (active) {
        if (!enlarged) View(Modifier.fillMaxWidth().height(280.dp))
        TextButton(onClick = { enlarged = true }) { Text(stringResource(R.string.capture_diagram_zoom)) }
        if (enlarged) Dialog(onDismissRequest = { enlarged = false }) {
            Surface { Column {
                View(Modifier.fillMaxWidth().height(480.dp))
                Row {
                    TextButton(onClick = { enlarged = false }) { Text(stringResource(R.string.ot3_composer_back)) }
                    dev.avillagran.ot3android.ui.thread.CopyText(source, "mermaid_zoom")
                }
            } }
        }
    }
}
