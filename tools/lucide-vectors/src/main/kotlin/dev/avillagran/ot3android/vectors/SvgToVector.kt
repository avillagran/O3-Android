package dev.avillagran.ot3android.vectors

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Converts one icon SVG (Lucide, or an ot3 brand mark) into an Android VectorDrawable.
 *
 * ot3 draws Lucide on a 24 viewBox with stroke 1.75 (UI_SPEC 3.8); every stroke is drawn
 * at that width, scaled to the viewport. Colours become white so `Icon(tint = …)` and
 * `android:tint` recolour the whole icon. A non-square or offset viewBox (brand marks) is
 * centred in a square viewport so the drawable stays 24 × 24 dp.
 *
 * Supported: `svg`, `g` (presentation attributes only), `path`, `circle`, `ellipse`,
 * `rect` (with `rx`/`ry`), `line`, `polyline`, `polygon`. Anything else, and any
 * `transform`, fails the conversion rather than drawing something different.
 */
object SvgToVector {
    const val LUCIDE_STROKE_WIDTH = 1.75
    private const val LUCIDE_VIEWPORT = 24.0
    private const val WHITE = "@android:color/white"

    private val ignoredElements = setOf("title", "desc", "defs")

    private data class Paint(
        val fill: Boolean,
        val stroke: Boolean,
        val evenOdd: Boolean,
        val lineCap: String,
        val lineJoin: String,
    )

    private data class Shape(val pathData: String, val paint: Paint)

    fun convert(svg: String, source: String): String {
        val root = parse(svg)
        require(root.tagName == "svg") { "$source: root element is <${root.tagName}>, expected <svg>" }
        val viewBox = viewBox(root, source)
        val rootPaint = paint(root, Paint(fill = true, stroke = false, evenOdd = false, lineCap = "round", lineJoin = "round"))
        val shapes = mutableListOf<Shape>()
        collect(root, rootPaint, shapes, source)
        require(shapes.isNotEmpty()) { "$source: no drawable elements" }

        val side = maxOf(viewBox.width, viewBox.height)
        val translateX = -viewBox.minX + (side - viewBox.width) / 2
        val translateY = -viewBox.minY + (side - viewBox.height) / 2
        val strokeWidth = LUCIDE_STROKE_WIDTH * side / LUCIDE_VIEWPORT
        val grouped = translateX != 0.0 || translateY != 0.0
        val indent = if (grouped) "        " else "    "

        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<!-- Generated from $source by tools/lucide-vectors. Do not edit. -->")
            appendLine("""<vector xmlns:android="http://schemas.android.com/apk/res/android"""")
            appendLine("""    android:width="24dp"""")
            appendLine("""    android:height="24dp"""")
            appendLine("""    android:viewportWidth="${number(side)}"""")
            appendLine("""    android:viewportHeight="${number(side)}">""")
            if (grouped) {
                appendLine("""    <group android:translateX="${number(translateX)}" android:translateY="${number(translateY)}">""")
            }
            for (shape in shapes) {
                append(indent).append("""<path android:pathData="${shape.pathData}"""")
                if (shape.paint.fill) {
                    append(""" android:fillColor="$WHITE"""")
                    if (shape.paint.evenOdd) append(""" android:fillType="evenOdd"""")
                }
                if (shape.paint.stroke) {
                    append(""" android:strokeColor="$WHITE" android:strokeWidth="${number(strokeWidth)}"""")
                    append(""" android:strokeLineCap="${shape.paint.lineCap}" android:strokeLineJoin="${shape.paint.lineJoin}"""")
                }
                appendLine("/>")
            }
            if (grouped) appendLine("    </group>")
            appendLine("</vector>")
        }
    }

    private data class ViewBox(val minX: Double, val minY: Double, val width: Double, val height: Double)

    private fun viewBox(root: Element, source: String): ViewBox {
        val value = root.getAttribute("viewBox")
        if (value.isNotBlank()) {
            val parts = value.trim().split(Regex("[\\s,]+")).map { it.toDouble() }
            require(parts.size == 4 && parts[2] > 0 && parts[3] > 0) { "$source: invalid viewBox \"$value\"" }
            return ViewBox(parts[0], parts[1], parts[2], parts[3])
        }
        val width = root.getAttribute("width").removeSuffix("px").toDoubleOrNull()
        val height = root.getAttribute("height").removeSuffix("px").toDoubleOrNull()
        require(width != null && height != null) { "$source: no viewBox and no width/height" }
        return ViewBox(0.0, 0.0, width, height)
    }

    private fun paint(element: Element, inherited: Paint): Paint {
        fun attribute(name: String) = element.getAttribute(name).trim().takeIf { it.isNotEmpty() }
        return Paint(
            fill = attribute("fill")?.let { it != "none" } ?: inherited.fill,
            stroke = attribute("stroke")?.let { it != "none" } ?: inherited.stroke,
            evenOdd = attribute("fill-rule")?.let { it == "evenodd" } ?: inherited.evenOdd,
            lineCap = attribute("stroke-linecap") ?: inherited.lineCap,
            lineJoin = attribute("stroke-linejoin") ?: inherited.lineJoin,
        )
    }

    private fun collect(parent: Element, inherited: Paint, shapes: MutableList<Shape>, source: String) {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            val name = element.tagName
            if (name in ignoredElements) continue
            require(!element.hasAttribute("transform")) { "$source: <$name transform> is not supported" }
            val paint = paint(element, inherited)
            if (name == "g") {
                collect(element, paint, shapes, source)
                continue
            }
            val pathData = pathData(element, source)
            if (paint.fill || paint.stroke) shapes += Shape(pathData, paint)
        }
    }

    internal fun pathData(element: Element, source: String): String {
        fun value(name: String, default: Double? = null): Double {
            val raw = element.getAttribute(name).trim()
            if (raw.isEmpty()) return default ?: throw IllegalArgumentException("$source: <${element.tagName}> lacks $name")
            return raw.removeSuffix("px").toDouble()
        }
        return when (element.tagName) {
            "path" -> SvgPathData.normalize(element.getAttribute("d"))
            "circle" -> ellipse(value("cx", 0.0), value("cy", 0.0), value("r"), value("r"))
            "ellipse" -> ellipse(value("cx", 0.0), value("cy", 0.0), value("rx"), value("ry"))
            "line" -> "M${number(value("x1", 0.0))} ${number(value("y1", 0.0))} " +
                "L${number(value("x2", 0.0))} ${number(value("y2", 0.0))}"
            "polyline", "polygon" -> polyline(element.getAttribute("points"), closed = element.tagName == "polygon", source)
            "rect" -> rect(element, ::value)
            else -> throw IllegalArgumentException("$source: <${element.tagName}> is not supported")
        }
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): String {
        // Two half arcs; one arc cannot draw a full ellipse.
        val r = "${number(rx)} ${number(ry)}"
        return "M${number(cx - rx)} ${number(cy)} A$r 0 1 0 ${number(cx + rx)} ${number(cy)} " +
            "A$r 0 1 0 ${number(cx - rx)} ${number(cy)} Z"
    }

    private fun polyline(points: String, closed: Boolean, source: String): String {
        val values = points.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toDouble() }
        require(values.size >= 4 && values.size % 2 == 0) { "$source: invalid points \"$points\"" }
        val pairs = values.chunked(2).map { (x, y) -> "${number(x)} ${number(y)}" }
        return "M" + pairs.first() + pairs.drop(1).joinToString("") { " L$it" } + if (closed) " Z" else ""
    }

    private fun rect(element: Element, value: (String, Double?) -> Double): String {
        val x = value("x", 0.0)
        val y = value("y", 0.0)
        val width = value("width", null)
        val height = value("height", null)
        // SVG 1.1, 9.2: a missing rx or ry takes the other's value; both are capped at half the side.
        val rxAttribute = element.getAttribute("rx").trim().takeIf { it.isNotEmpty() }?.toDouble()
        val ryAttribute = element.getAttribute("ry").trim().takeIf { it.isNotEmpty() }?.toDouble()
        val rx = minOf(rxAttribute ?: ryAttribute ?: 0.0, width / 2)
        val ry = minOf(ryAttribute ?: rxAttribute ?: 0.0, height / 2)
        if (rx <= 0.0 || ry <= 0.0) {
            return "M${number(x)} ${number(y)} H${number(x + width)} V${number(y + height)} H${number(x)} Z"
        }
        val r = "${number(rx)} ${number(ry)}"
        return "M${number(x + rx)} ${number(y)} H${number(x + width - rx)} " +
            "A$r 0 0 1 ${number(x + width)} ${number(y + ry)} V${number(y + height - ry)} " +
            "A$r 0 0 1 ${number(x + width - rx)} ${number(y + height)} H${number(x + rx)} " +
            "A$r 0 0 1 ${number(x)} ${number(y + height - ry)} V${number(y + ry)} " +
            "A$r 0 0 1 ${number(x + rx)} ${number(y)} Z"
    }

    /** Shortest decimal for [value], rounded to 1e-4 so derived coordinates stay stable. */
    internal fun number(value: Double): String {
        val rounded = java.math.BigDecimal(value).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        val text = rounded.toPlainString()
        return if (text == "-0") "0" else text
    }

    private fun parse(svg: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(svg))).documentElement
    }
}
