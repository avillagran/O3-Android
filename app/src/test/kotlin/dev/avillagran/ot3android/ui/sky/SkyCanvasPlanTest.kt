package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

class SkyCanvasPlanTest {
    private fun uniforms(palette: SkyPalette, energy: Float, clock: Float = 31.4f) =
        SkyMath.uniforms(palette, SkyMotion.Full, clock = clock, drift = 640f, energy = energy, widthDp = 411f, heightDp = 126f, pixelDensity = 2f)

    @Test
    fun starsComeFromTheGridAndStayOnScreen() {
        val u = uniforms(TokyoNight, energy = 0f)
        val plan = SkyCanvasPlan.build(u)
        // About 0.42·(411/29)(126/29) + 0.38·… + 0.32·… ≈ 40 stars, fewer under the fade.
        assertTrue("${plan.stars.size} stars", plan.stars.size in 15..70)
        for (s in plan.stars) {
            assertTrue(s.x >= -s.haloRadius && s.x <= 411f + s.haloRadius)
            assertTrue(s.y >= -s.haloRadius && s.y <= 126f + s.haloRadius)
            assertTrue(s.alpha in 0f..1f)
        }
        assertTrue(plan.comets.isEmpty())
    }

    @Test
    fun eachPlannedStarIsALitPixelOfTheShader() {
        val u = uniforms(TokyoNight, energy = 0f).copy(nebula = 0f)
        val plan = SkyCanvasPlan.build(u)
        val bg = SkyMath.shade(u.copy(brightness = 0f), 0f, 0f)
        for (s in plan.stars.filter { it.alpha > 0.2f && it.y < 50f }) {
            val px = SkyMath.shade(u, s.x, s.y)
            assertTrue("star at ${s.x},${s.y} is lit", px[0] + px[1] + px[2] > bg[0] + bg[1] + bg[2] + 0.1f)
        }
    }

    @Test
    fun cometsOnlyWhileWorking() {
        var seen = 0
        for (i in 0..200) {
            val plan = SkyCanvasPlan.build(uniforms(TokyoNight, energy = 1f, clock = i * 0.1f))
            seen += plan.comets.size
            for (c in plan.comets) {
                val len = kotlin.math.hypot(c.headX - c.tailX, c.headY - c.tailY)
                assertEquals(SkyMath.tailLength(411f, 126f), len, 1e-2f)
            }
            assertTrue(SkyCanvasPlan.build(uniforms(TokyoNight, energy = 0f, clock = i * 0.1f)).comets.isEmpty())
        }
        assertTrue(seen > 0)
    }

    @Test
    fun baseFadesOutBelowTheBrandRow() {
        val plan = SkyCanvasPlan.build(uniforms(CatppuccinLatte, energy = 0f))
        val stops = plan.baseStops
        assertEquals(0f to 1f, stops.first())
        assertEquals(1f, stops.last().first)
        assertEquals(0f, stops.last().second, 1e-6f)
        assertTrue(stops.zipWithNext().all { (a, b) -> a.first <= b.first && a.second >= b.second })
        val fade = SkyMath.fade(126f, 62f)
        assertEquals(1f - fade, stops[1].first, 1e-6f)
    }

    /**
     * Renders the shader reference and a software raster of the Canvas plan for both
     * themes to `app/build/sky/` for a look; asserts only that they were written.
     */
    @Test
    fun rendersReferenceImages() {
        val out = File("build/sky").apply { mkdirs() }
        for ((name, palette) in listOf("tokyo-night" to TokyoNight, "catppuccin-latte" to CatppuccinLatte)) {
            // A clock with a comet in flight.
            val clock = (0..400).map { it * 0.05f + 20f }.first { c ->
                (0..3).any { SkyMath.comet(it, c, 0.85f, 411f, 126f)?.let { m -> m.k in 0.3f..0.5f } == true }
            }
            val u = uniforms(palette, energy = 1f, clock = clock)
            val bg = palette.background.toSkyRgba()
            Raster(u).apply { shade(bg) }.writePng(File(out, "sky-$name-agsl-reference.png"))
            Raster(u).apply { plan(SkyCanvasPlan.build(u), bg) }.writePng(File(out, "sky-$name-canvas.png"))
            assertTrue(File(out, "sky-$name-canvas.png").length() > 0)
            File(out, "sky-$name.clock").writeText(clock.toString())
        }
    }

    /** RGB float raster in device pixels, drawn with source-over like Canvas. */
    private class Raster(val u: SkyUniforms) {
        val d = u.pixelDensity
        val w = (u.width * d).roundToInt()
        val h = (u.height * d).roundToInt()
        val px = FloatArray(w * h * 3)

        fun fill(c: SkyRgba) {
            for (i in 0 until w * h) { px[i * 3] = c.r; px[i * 3 + 1] = c.g; px[i * 3 + 2] = c.b }
        }

        fun blend(x: Int, y: Int, c: SkyRgba, a: Float) {
            if (x !in 0 until w || y !in 0 until h || a <= 0f) return
            val i = (y * w + x) * 3
            val k = a.coerceIn(0f, 1f)
            px[i] = px[i] * (1 - k) + c.r * k
            px[i + 1] = px[i + 1] * (1 - k) + c.g * k
            px[i + 2] = px[i + 2] * (1 - k) + c.b * k
        }

        fun shade(page: SkyRgba) {
            for (y in 0 until h) for (x in 0 until w) {
                val c = SkyMath.shade(u, (x + 0.5f) / d, (y + 0.5f) / d)
                val i = (y * w + x) * 3
                px[i] = c[0] + page.r * (1f - c[3])
                px[i + 1] = c[1] + page.g * (1f - c[3])
                px[i + 2] = c[2] + page.b * (1f - c[3])
            }
        }

        /** Each pixel within [radiusDp] of a dp point, with its distance in dp. */
        private inline fun around(cx: Float, cy: Float, radiusDp: Float, f: (Int, Int, Float, Float) -> Unit) {
            for (y in ((cy - radiusDp) * d).toInt() - 1..((cy + radiusDp) * d).toInt() + 1)
                for (x in ((cx - radiusDp) * d).toInt() - 1..((cx + radiusDp) * d).toInt() + 1) {
                    val qx = (x + 0.5f) / d
                    val qy = (y + 0.5f) / d
                    f(x, y, qx, qy)
                }
        }

        fun plan(plan: SkyCanvasPlan, page: SkyRgba) {
            fill(page)
            for (y in 0 until h) {
                val f = (y + 0.5f) / h
                val (lo, hi) = plan.baseStops.zipWithNext().firstOrNull { (a, b) -> f in a.first..b.first } ?: (plan.baseStops.last() to plan.baseStops.last())
                val t = if (hi.first > lo.first) (f - lo.first) / (hi.first - lo.first) else 0f
                val a = lo.second + (hi.second - lo.second) * t
                for (x in 0 until w) blend(x, y, plan.base, a)
            }
            for (s in plan.stars) {
                around(s.x, s.y, s.haloRadius) { x, y, qx, qy ->
                    val dist = kotlin.math.hypot(qx - s.x, qy - s.y)
                    if (dist < s.haloRadius) blend(x, y, s.color, 0.3f * s.alpha * kotlin.math.exp(-4f * (dist / s.haloRadius).let { it * it }))
                    val cover = ((s.coreRadius - dist) * d + 0.5f).coerceIn(0f, 1f)
                    blend(x, y, s.color, s.alpha * cover)
                }
            }
            for (c in plan.comets) {
                val dx = c.tailX - c.headX
                val dy = c.tailY - c.headY
                val len = kotlin.math.hypot(dx, dy)
                around((c.headX + c.tailX) / 2, (c.headY + c.tailY) / 2, len / 2 + 2f) { x, y, qx, qy ->
                    val f = ((qx - c.headX) * dx + (qy - c.headY) * dy) / (len * len)
                    if (f in 0f..1f) {
                        val perp = kotlin.math.abs((qx - c.headX) * dy - (qy - c.headY) * dx) / len
                        val half = c.headHalfWidth + (c.tailHalfWidth - c.headHalfWidth) * f
                        val cover = ((half - perp) * d + 0.5f).coerceIn(0f, 1f)
                        blend(x, y, c.color, c.alpha * (1 - f) * (1 - f) * cover)
                    }
                }
                around(c.headX, c.headY, SkyCanvasPlan.GLOW_RADIUS) { x, y, qx, qy ->
                    val r = kotlin.math.hypot(qx - c.headX, qy - c.headY) / SkyCanvasPlan.GLOW_RADIUS
                    if (r < 1f) blend(x, y, c.color, c.alpha * kotlin.math.exp(-4f * r * r))
                }
            }
        }

        fun writePng(file: File) {
            val raw = ByteArrayOutputStream()
            for (y in 0 until h) {
                raw.write(0)
                for (x in 0 until w) for (k in 0..2) raw.write((px[(y * w + x) * 3 + k].coerceIn(0f, 1f) * 255f).roundToInt())
            }
            val png = DataOutputStream(file.outputStream().buffered())
            png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                png.writeInt(data.size)
                val body = type.toByteArray(Charsets.US_ASCII) + data
                png.write(body)
                png.writeInt(CRC32().apply { update(body) }.value.toInt())
            }
            chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
            chunk("IDAT", ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(raw.toByteArray()) } }.toByteArray())
            chunk("IEND", ByteArray(0))
            png.close()
        }
    }
}
