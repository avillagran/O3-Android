package dev.avillagran.ot3android.ui.sky

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What the Canvas fallback (API < 33) draws for one frame, in dp: the base with its faded
 * edge, one disc and halo per visible star, one tapered streak and glow per comet. Same
 * grid hash as the shader, evaluated per star instead of per pixel, so there is no nebula
 * and a star's colour is its centre's (the shader tints per pixel).
 */
internal class SkyCanvasPlan(
    val base: SkyRgba,
    /** Vertical stops (fraction of the height, alpha of [base]). */
    val baseStops: List<Pair<Float, Float>>,
    val stars: List<Star>,
    val comets: List<Comet>,
) {
    data class Star(
        val x: Float,
        val y: Float,
        /** Disc of the core: where its smoothstep is halfway, 0.6·r + 0.45. */
        val coreRadius: Float,
        /** Halo out to exp(−4) of its peak. */
        val haloRadius: Float,
        val alpha: Float,
        val color: SkyRgba,
    )

    data class Comet(
        val headX: Float,
        val headY: Float,
        val tailX: Float,
        val tailY: Float,
        val headHalfWidth: Float,
        val tailHalfWidth: Float,
        val alpha: Float,
        val color: SkyRgba,
    )

    companion object {
        /** Halo profile `0.3·exp(−d²/7r²)` sampled over its radius `2·√7·r`: (fraction, relative alpha). */
        val HALO_STOPS: List<Pair<Float, Float>> = (0..6).map { i ->
            val f = i / 6f
            f to exp(-4f * f * f)
        }

        /** Tail brightness `tail²` along the streak: (fraction from the head, relative alpha). */
        val TAIL_STOPS: List<Pair<Float, Float>> = (0..6).map { i ->
            val f = i / 6f
            f to (1f - f) * (1f - f)
        }

        /** Comet head glow `exp(−d²/10)` out to exp(−4): radius √40 dp. */
        val GLOW_RADIUS: Float = sqrt(40f)

        fun build(u: SkyUniforms): SkyCanvasPlan {
            val w = u.width
            val h = u.height
            val stars = ArrayList<Star>()
            for (layer in SkyMath.LAYERS) {
                val ox = SkyMath.layerOffsetX(layer, u)
                val oy = SkyMath.layerOffsetY(layer, u)
                val x0 = floor(ox / layer.cell).toInt() - 1
                val x1 = floor((w + ox) / layer.cell).toInt() + 1
                val y0 = floor(oy / layer.cell).toInt() - 1
                val y1 = floor((h + oy) / layer.cell).toInt() + 1
                for (iy in y0..y1) for (ix in x0..x1) {
                    val star = SkyMath.star(layer, ix.toFloat(), iy.toFloat()) ?: continue
                    val x = star.centerX - ox
                    val y = star.centerY - oy
                    val halo = 2f * sqrt(7f) * star.radius
                    if (x < -halo || x > w + halo || y < -halo || y > h + halo) continue
                    val edge = SkyMath.edge(u.fade, y / h)
                    val intensity = layer.weight * SkyMath.twinkle(u.clock, star.rate, star.hash) * star.gain
                    val alpha = SkyMath.clamp(intensity * u.brightness * u.ink, 0f, 1f) * edge
                    if (alpha <= 0.004f) continue
                    stars += Star(
                        x = x,
                        y = y,
                        coreRadius = 0.6f * star.radius + 0.45f,
                        haloRadius = halo,
                        alpha = alpha,
                        color = SkyRgba.mix(u.star, u.accent, SkyMath.smoothstep(0.55f, 1f, tintAt(u, x, y)) * 0.6f),
                    )
                }
            }
            val comets = ArrayList<Comet>()
            if (u.meteors > 0.001f) {
                val color = SkyRgba.mix(u.meteor, u.accent, 0.25f)
                for (ch in 0 until SkyMath.COMET_CHANNELS) {
                    val c = SkyMath.comet(ch, u.clock, u.meteors, w, h) ?: continue
                    val alpha = SkyMath.clamp(c.fade * u.ink, 0f, 1f) * SkyMath.edge(u.fade, c.headY / h)
                    if (alpha <= 0.004f) continue
                    comets += Comet(
                        headX = c.headX,
                        headY = c.headY,
                        tailX = c.headX - c.dirX * c.length,
                        tailY = c.headY - c.dirY * c.length,
                        // Half of smoothstep(0.6 + 1.1·tail, 0, perp)'s reach at each end.
                        headHalfWidth = 0.85f,
                        tailHalfWidth = 0.3f,
                        alpha = alpha,
                        color = color,
                    )
                }
            }
            val stops = if (u.fade > 0f) {
                listOf(0f to 1f) + (0..8).map { i ->
                    val y = 1f - u.fade + u.fade * i / 8f
                    y to SkyMath.edge(u.fade, y)
                }
            } else {
                listOf(0f to 1f, 1f to 1f)
            }
            return SkyCanvasPlan(u.base, stops.map { (y, a) -> y.coerceIn(0f, 1f) to a * u.base.a }, stars, comets)
        }

        /** The shader's tint at a header point: the mean of the three layers' cell tints. */
        fun tintAt(u: SkyUniforms, qx: Float, qy: Float): Float =
            SkyMath.LAYERS.sumOf { layer ->
                val px = qx + SkyMath.layerOffsetX(layer, u)
                val py = qy + SkyMath.layerOffsetY(layer, u)
                SkyMath.cellTint(layer, floor(px / layer.cell), floor(py / layer.cell)).toDouble()
            }.toFloat() / 3f
    }
}
