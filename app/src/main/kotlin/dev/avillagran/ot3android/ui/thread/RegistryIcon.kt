package dev.avillagran.ot3android.ui.thread

import android.graphics.*
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.UUID

internal val LocalRegistryTransport = staticCompositionLocalOf<T3Transport?> { null }

/** A bounded SVG path renderer. No WebView, scripting, entities, CSS, links or resources. */
internal fun registryBitmap(bytes: ByteArray): Bitmap {
    require(bytes.size <= 256 * 1024)
    val source = bytes.toString(Charsets.UTF_8)
    require(!source.contains("<!", ignoreCase = true))
    val parser = android.util.Xml.newPullParser().apply { setInput(StringReader(source)) }
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    var paths = 0
    var root = false
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG) {
            require(parser.depth <= 16)
            for (i in 0 until parser.attributeCount) {
                val name = parser.getAttributeName(i)
                require(!name.startsWith("on", true) && name !in setOf("href", "style", "transform", "filter", "mask", "clip-path"))
            }
            fun attr(name: String) = parser.getAttributeValue(null, name)
            when (parser.name) {
                "svg" -> {
                    require(!root); root = true
                    val view = attr("viewBox")?.trim()?.split(Regex("[ ,]+"))?.map(String::toFloat)
                        ?: listOf(0f, 0f, attr("width")?.toFloatOrNull() ?: 24f, attr("height")?.toFloatOrNull() ?: 24f)
                    require(view.size == 4 && view.all(Float::isFinite) && view[2] > 0 && view[3] > 0)
                    canvas.scale(96f / view[2], 96f / view[3]); canvas.translate(-view[0], -view[1])
                }
                "g", "title", "desc" -> require(attr("fill") == null && attr("stroke") == null)
                "path" -> {
                    require(root && ++paths <= 512)
                    val path = androidx.core.graphics.PathParser.createPathFromPathData(attr("d") ?: error("invalid_path"))
                    path.fillType = if (attr("fill-rule") == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    fun color(raw: String?): Int = when (raw) { null, "currentColor" -> Color.BLACK; else -> Color.parseColor(raw) }
                    if (attr("fill") != "none") canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(attr("fill")); style = Paint.Style.FILL })
                    attr("stroke")?.takeIf { it != "none" }?.let { stroke ->
                        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(stroke); style = Paint.Style.STROKE; strokeWidth = attr("stroke-width")?.toFloatOrNull() ?: 1f })
                    }
                }
                else -> error("unsupported_svg")
            }
        }
        parser.next()
    }
    require(root && paths > 0)
    return bitmap
}

@Composable
internal fun RegistryIcon(url: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    val transport = LocalRegistryTransport.current
    var bitmap by remember(url, transport) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, transport) {
        if (url.isNotBlank() && transport != null) {
            val id = UUID.randomUUID().toString()
            try { bitmap = withContext(Dispatchers.Default) { registryBitmap(transport.registryIconBytes(url, id)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { bitmap = null }
            finally { withContext(NonCancellable) { transport.cancelRegistryIcon(id) } }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(dev.avillagran.ot3android.ui.theme.Ot3Theme.colors.dim)) } ?: fallback()
}
