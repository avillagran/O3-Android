package dev.avillagran.ot3android.ui.navigation

/** ot3 7b5b1de ui/assets/shaders/reveal.frag (MIT). Only texture/binding syntax and dp coordinates change. */
internal const val REVEAL_AGSL = """
uniform shader content;
uniform float progress;
uniform float style;
uniform float horizontal;
uniform float reverse;
uniform float seed;
uniform float2 size;
uniform float pixelDensity;
layout(color) uniform half4 uGlow;
float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
    float2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + float2(1.0, 0.0)), f.x),
               mix(hash12(i + float2(0.0, 1.0)), hash12(i + float2(1.0, 1.0)), f.x), f.y);
}

float fbm(float2 p) {
    return 0.55 * noise(p) + 0.3 * noise(p * 2.1 + 7.3) + 0.15 * noise(p * 4.3 + 1.7);
}

// Texture coordinate from (along, across), undoing the axis mapping.
float2 toUv(float along, float across) {
    float a = reverse > 0.5 ? 1.0 - along : along;
    return horizontal > 0.5 ? float2(a, across) : float2(across, a);
}

float4 tap(float along, float across) {
    if (along < 0.0 || along > 1.0 || across < 0.0 || across > 1.0) return float4(0.0);
    return content.eval(toUv(along, across) * size * pixelDensity);
}

half4 main(float2 coord) {
    float2 uv = coord / (size * pixelDensity);
    float along = horizontal > 0.5 ? uv.x : uv.y;
    float across = horizontal > 0.5 ? uv.y : uv.x;
    if (reverse > 0.5) along = 1.0 - along;
    float len = horizontal > 0.5 ? size.x : size.y;
    float wide = horizontal > 0.5 ? size.y : size.x;
    float p = clamp(progress, 0.0, 1.0);
    float3 glow = uGlow.rgb;
    float4 c = float4(0.0);

    if (style < 0.5) {
        // Nebula: a noisy front sweeps back towards the attached edge; pixels
        // past it burn out in the accent colour and leave twinkling dust.
        float2 px = uv * size;
        float n = fbm(px / 15.0 + seed * 13.0);
        float field = mix(n, along, 0.5);
        float t = p * 1.25 - 0.12;
        float d = t - field;
        float4 src = content.eval(uv * size * pixelDensity);
        float keep = smoothstep(0.0, 0.035, d);
        float edge = exp(-abs(d) * 40.0) * (1.0 - p * p);
        float2 cell = floor(px / 3.0);
        float h = hash12(cell + seed * 91.0);
        float dust = d < 0.0 && d > -0.22 && h > 0.86 ? (1.0 + d / 0.22) * (h - 0.86) / 0.14 : 0.0;
        float spark = (edge * 0.9 + dust) * max(src.a, 0.35);
        c = src * keep;
        c.rgb += mix(glow, float3(1.0), 0.35) * spark;
        c.a = max(c.a, spark);
    } else if (style < 1.5) {
        // Warp: the content is squeezed into the visible extent and smeared
        // along the axis, splitting into RGB streaks with hot light lines.
        if (along > p) { return half4(0.0); }
        float s = along / max(p, 0.0001);
        float k = 1.0 - p;
        float smear = k * 0.45;
        float4 acc = float4(0.0);
        for (int i = 0; i < 8; i++) {
            float o = float(i) / 7.0 * smear;
            acc.r += tap(s * (1.0 + 0.05 * k) - o, across).r;
            acc.g += tap(s - o, across).g;
            acc.b += tap(s * (1.0 - 0.05 * k) - o, across).b;
            acc.a += tap(s - o, across).a;
        }
        acc /= 8.0;
        float row = floor(across * wide / 2.0);
        float h = hash12(float2(row, seed * 17.0));
        float line = h > 0.9 ? (h - 0.9) * 10.0 * k * smoothstep(0.0, 0.3, along) : 0.0;
        c = acc * smoothstep(0.0, 0.2, p);
        c.rgb += mix(glow, float3(1.0), 0.5) * line * 0.8;
        c.a = max(c.a, line * 0.8);
    } else {
        // Fold: four panels share the visible extent; alternate panels tilt
        // away (darker, pinched across) and the creases catch the light.
        if (along > p) { return half4(0.0); }
        const float N = 4.0;
        float panel = p / N;
        float k = floor(along / max(panel, 0.0001));
        float f = fract(along / max(panel, 0.0001));
        float odd = (k - 2.0 * floor(k / 2.0));
        float tilt = 1.0 - p;
        float depth = odd > 0.5 ? f : 1.0 - f;
        float pinch = 1.0 - 0.12 * tilt * depth;
        float a2 = 0.5 + (across - 0.5) / pinch;
        float4 src = tap((k + f) / N, a2);
        float shade = odd > 0.5 ? 1.0 - 0.55 * tilt * (0.4 + 0.6 * f) : 1.0 - 0.25 * tilt * (1.0 - f);
        float crease = exp(-min(f, 1.0 - f) * panel * len / 2.5) * tilt;
        c = float4(src.rgb * shade, src.a);
        c.rgb += glow * crease * 0.45 * src.a;
    }

    c.rgb = min(c.rgb, float3(c.a));
    return half4(c);
}

"""
