package dev.avillagran.ot3android.ui.sky

/**
 * One frame of the sky's uniforms: `starfield.frag`'s uniform block
 * (`ot3:ui/assets/shaders/starfield.frag:7-24`) without `qt_Matrix`, `qt_Opacity` renamed
 * `opacity`, plus Android's [pixelDensity]. Lengths are dp.
 */
internal data class SkyUniforms(
    val pixelDensity: Float,
    val opacity: Float,
    val drift: Float,
    val clock: Float,
    val meteors: Float,
    val brightness: Float,
    val nebula: Float,
    val scroll: Float,
    val fade: Float,
    val width: Float,
    val height: Float,
    val originX: Float,
    val originY: Float,
    val star: SkyRgba,
    val accent: SkyRgba,
    val base: SkyRgba,
    val meteor: SkyRgba,
    val ink: Float,
) {
    /** Every uniform by its AGSL name, in declaration order. */
    fun forEachUniform(set: (name: String, values: FloatArray) -> Unit) {
        set("pixelDensity", floatArrayOf(pixelDensity))
        set("opacity", floatArrayOf(opacity))
        set("drift", floatArrayOf(drift))
        set("clock", floatArrayOf(clock))
        set("meteors", floatArrayOf(meteors))
        set("brightness", floatArrayOf(brightness))
        set("nebula", floatArrayOf(nebula))
        set("scroll", floatArrayOf(scroll))
        set("fade", floatArrayOf(fade))
        set("size", floatArrayOf(width, height))
        set("origin", floatArrayOf(originX, originY))
        set("star", star.toArray())
        set("accent", accent.toArray())
        set("base", base.toArray())
        set("meteor", meteor.toArray())
        set("ink", floatArrayOf(ink))
    }
}

/**
 * AGSL port of `ot3:ui/assets/shaders/starfield.frag` (7b5b1de), uniform for uniform.
 * Changes, all mechanical: the uniform block became globals; `qt_Opacity` is `opacity`;
 * `qt_TexCoord0` is `fragCoord / pixelDensity / size`, so every length stays in dp
 * (ot3 px = dp); `meteor()` is `meteorAt()` because AGSL globals and functions share a
 * namespace with the `meteor` colour, and its local `fade` is `fadeIn` so it does not
 * shadow the `fade` uniform. Colours are plain float4 (no `layout(color)`), passed as encoded
 * sRGB like Qt does, and the output is premultiplied like ot3's. [SkyMath] mirrors
 * every function; JVM tests check the constants and uniform names match.
 */
internal const val STARFIELD_AGSL: String = """
uniform float pixelDensity;
uniform float opacity;
uniform float drift;
uniform float clock;
uniform float meteors;
uniform float brightness;
uniform float nebula;
uniform float scroll;
uniform float fade;
uniform float2 size;
uniform float2 origin;
uniform float4 star;
uniform float4 accent;
uniform float4 base;
uniform float4 meteor;
uniform float ink;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// One star per occupied cell, kept off the cell edges so it never clips.
float layer(float2 p, float cell, float density, float seed, float radius, out float tint) {
    float2 id = floor(p / cell);
    float2 f = fract(p / cell);
    float h = hash12(id + seed);
    tint = hash12(id + seed + 5.3);
    if (h > density) return 0.0;
    float2 pos = 0.2 + 0.6 * hash22(id + seed * 1.7);
    float d = length((f - pos) * cell);
    float r = radius * mix(0.55, 1.25, hash12(id + seed + 3.1));
    float rate = 0.6 + 2.2 * hash12(id + seed + 7.0);
    float twinkle = 0.55 + 0.45 * sin(clock * rate + h * 61.0);
    float core = smoothstep(r + 0.9, r * 0.2, d);
    float halo = exp(-d * d / (r * r * 7.0)) * 0.3;
    return (core + halo) * twinkle * mix(0.45, 1.0, hash12(id + seed + 11.0));
}

// Shooting stars: each channel rolls once per period whether it fires.
float meteorAt(float2 q, float channel) {
    float period = 2.1 + channel * 1.37;
    float slot = floor(clock / period + channel * 0.31);
    float t = fract(clock / period + channel * 0.31) * period;
    float2 key = float2(slot, channel * 17.0);
    if (hash12(key) > meteors) return 0.0;
    float duration = 0.75 + 0.5 * hash12(key + 2.0);
    if (t > duration) return 0.0;
    float k = t / duration;
    float2 dir = normalize(float2(-1.0, 0.28 + 0.35 * hash12(key + 4.0)));
    float span = length(size);
    float2 start = float2(size.x * (0.35 + 0.75 * hash12(key + 6.0)),
                          size.y * (-0.15 + 0.6 * hash12(key + 8.0)));
    float2 head = start + dir * span * 0.9 * k;
    float len = clamp(span * 0.22, 60.0, 240.0);
    float2 rel = q - head;
    float along = dot(rel, -dir);
    float perp = abs(dot(rel, float2(-dir.y, dir.x)));
    float fadeIn = smoothstep(0.0, 0.12, k) * smoothstep(1.0, 0.55, k);
    float tail = clamp(1.0 - along / len, 0.0, 1.0);
    float streak = along > -1.5 ? smoothstep(0.6 + 1.1 * tail, 0.0, perp) * tail * tail : 0.0;
    float glow = exp(-dot(rel, rel) / 10.0);
    return (streak + glow) * fadeIn;
}

half4 main(float2 fragCoord) {
    float2 q = fragCoord / pixelDensity;
    float2 uv = q / size;
    float2 p = q + origin;
    float2 flow = float2(drift, drift * 0.18);
    // Scroll parallax: nearer layers follow the content further.
    float2 lift = float2(0.0, scroll);
    float t1;
    float t2;
    float t3;
    float s = layer(p + flow * 0.30 + lift * 0.06, 29.0, 0.42, 1.0, 0.55, t1) * 0.55
            + layer(p + flow * 0.62 + lift * 0.12, 53.0, 0.38, 19.0, 0.8, t2) * 0.8
            + layer(p + flow * 1.00 + lift * 0.22, 97.0, 0.32, 43.0, 1.15, t3) * 1.0;
    float tint = (t1 + t2 + t3) / 3.0;
    float3 starColor = mix(star.rgb, accent.rgb, smoothstep(0.55, 1.0, tint) * 0.6);
    float m = 0.0;
    if (meteors > 0.001)
        m = meteorAt(q, 0.0) + meteorAt(q, 1.0) + meteorAt(q, 2.0) + meteorAt(q, 3.0);
    // Optional soft bottom edge: the instance dissolves into the sky below.
    float edge = fade > 0.0 ? 1.0 - smoothstep(1.0 - fade, 1.0, uv.y) : 1.0;
    // ink > 1 on light skies: dark stars need more coverage to read on white.
    float light = clamp(s * brightness * ink, 0.0, 1.0) * edge;
    float shoot = clamp(m * ink, 0.0, 1.0) * edge;

    // Base sky with a faint accent nebula toward the leading edge.
    float cloud = nebula * (0.55 * (1.0 - uv.x) + 0.25 * (1.0 - uv.y))
                * (0.8 + 0.2 * sin(clock * 0.25 + uv.x * 3.0));
    float3 sky = mix(base.rgb, accent.rgb, clamp(cloud, 0.0, 1.0) * 0.35);
    float skyA = base.a * edge;

    float3 rgb = sky * skyA;
    float a = skyA;
    rgb = rgb * (1.0 - light) + starColor * light;
    a = a + light * (1.0 - a);
    float3 meteorColor = mix(meteor.rgb, accent.rgb, 0.25);
    rgb = rgb * (1.0 - shoot) + meteorColor * shoot;
    a = a + shoot * (1.0 - a);
    return half4(float4(rgb, a) * opacity);
}
"""
