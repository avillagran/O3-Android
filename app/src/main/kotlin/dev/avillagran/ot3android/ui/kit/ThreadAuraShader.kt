package dev.avillagran.ot3android.ui.kit

/** AGSL port of ot3 7b5b1de ui/assets/shaders/threadaura.frag, MIT.
 * Only GLSL bindings, dp coordinates, modulo and loop syntax differ; effect math is unchanged.
 */
internal const val THREAD_AURA_AGSL = """
uniform float clock;
uniform float working;
uniform float attention;
uniform float burst;
uniform float radius;
uniform float seed;
uniform float intensity;
uniform float workingStyle;
uniform float attentionStyle;
uniform float burstStyle;
uniform float2 size;
uniform float pixelDensity;
layout(color) uniform half4 tone;
layout(color) uniform half4 alert;
layout(color) uniform half4 flash;
float modulo(float x, float y) { return x - y * floor(x / y); }
const float PI = 3.14159265;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Signed distance to the rounded card outline (negative inside).
float box(float2 p, float2 b, float r) {
    float2 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Arc length of the outline point nearest p, clockwise from the top-left.
float perimeter(float2 p, float2 b, float r) {
    float top = 2.0 * b.x, side = 2.0 * b.y, arc = 0.5 * PI * r;
    if (p.y < -b.y && abs(p.x) <= b.x) return p.x + b.x;
    if (p.x > b.x && p.y < -b.y) return top + (atan(p.y + b.y, p.x - b.x) + 0.5 * PI) * r;
    if (p.x > b.x && abs(p.y) <= b.y) return top + arc + p.y + b.y;
    if (p.x > b.x && p.y > b.y) return top + arc + side + atan(p.y - b.y, p.x - b.x) * r;
    if (p.y > b.y && abs(p.x) <= b.x) return top + 2.0 * arc + side + b.x - p.x;
    if (p.x < -b.x && p.y > b.y) return 2.0 * top + 2.0 * arc + side + (atan(p.y - b.y, p.x + b.x) - 0.5 * PI) * r;
    if (p.x < -b.x && abs(p.y) <= b.y) return 2.0 * top + 3.0 * arc + side + b.y - p.y;
    if (p.x < -b.x && p.y < -b.y) return 2.0 * top + 3.0 * arc + 2.0 * side + (atan(p.y + b.y, p.x + b.x) + PI) * r;
    // Deep interior: far from the outline, any value works.
    return 0.0;
}

// Four-point star glint.
float glint(float2 d, float s) {
    float2 a = abs(d);
    float rays = exp(-a.x * 6.0 / s) * exp(-a.y * 0.9 / s) + exp(-a.y * 6.0 / s) * exp(-a.x * 0.9 / s);
    return rays + exp(-dot(d, d) / (s * s * 0.5));
}

// Sparse twinkling dust; `live` fades it in and out.
float dust(float2 q, float cell, float density, float rate) {
    float2 id = floor(q / cell);
    float h = hash12(id + seed);
    if (h > density) return 0.0;
    float2 pos = (id + 0.2 + 0.6 * float2(hash12(id + 3.1), hash12(id + 7.7))) * cell;
    float tw = max(0.0, sin(clock * rate * (0.6 + h * 2.0) + h * 40.0));
    return exp(-dot(q - pos, q - pos) / 1.2) * tw * tw;
}

half4 main(float2 coord) {
    float2 q = coord / pixelDensity;
    float2 p = q - size * 0.5;
    float r = radius;
    float2 b = max(size * 0.5 - float2(r) - 0.75, float2(0.0));
    float d = box(p, b, r);
    float line = smoothstep(1.6, 0.0, abs(d));
    float inner = d < 0.0 ? exp(d / 5.0) : 0.0;

    float3 rgb = float3(0.0);
    float a = 0.0;

    if (working > 0.001 && workingStyle > 0.5) {
        float pulse = 0.5 + 0.5 * sin(clock * 1.8 + seed * 6.2832);
        float k = (line * (0.22 + 0.45 * pulse) + inner * (0.05 + 0.14 * pulse) + dust(q, 13.0, 0.16, 1.4) * 0.4) * working;
        rgb += tone.rgb * k;
        a += k;
    } else if (working > 0.001) {
        float s = perimeter(p, b, r);
        float len = 4.0 * (b.x + b.y) + 2.0 * PI * r;
        float head = fract(clock * (0.13 + 0.06 * seed) + seed) * len;
        float behind = modulo(head - s + len, len);
        float tail = behind < len * 0.32 ? pow(1.0 - behind / (len * 0.32), 2.2) : 0.0;
        float nose = exp(-behind * behind / 18.0) + exp(-(len - behind) * (len - behind) / 4.0);
        float comet = (line * (tail * 0.75 + nose) + inner * (tail * 0.25 + nose * 0.6));
        float sparkle = dust(q, 13.0, 0.16, 1.4) * 0.45;
        float k = (comet + sparkle) * working;
        float3 c = mix(tone.rgb, float3(1.0), clamp(nose * 0.6, 0.0, 0.6));
        rgb += c * k;
        a += k;
    }

    if (attention > 0.001) {
        float pulse = attentionStyle > 1.5 ? 0.6 : 0.5 + 0.5 * sin(clock * 2.6);
        float edge = line * (0.35 + 0.5 * pulse) + inner * (0.10 + 0.22 * pulse);
        // Glints take turns on the corners.
        float g = 0.0;
        for (int i = 0; i < 3; i++) {
            if (attentionStyle >= 0.5) break;
            float fi = float(i);
            float phase = fract(clock * 0.45 + fi / 3.0);
            float on = sin(phase * PI);
            float2 at = fi < 0.5 ? float2(b.x + r * 0.3, -b.y - r * 0.3)
                    : fi < 1.5 ? float2(-b.x - r * 0.3, b.y + r * 0.3)
                    : float2(b.x * 0.15, -b.y - r);
            g += glint(p - at, 1.4 + 1.4 * on) * on * on;
        }
        float k = (edge + g * 0.8) * attention;
        rgb += mix(alert.rgb, float3(1.0), clamp(g * 0.5, 0.0, 0.5)) * k;
        a += k;
    }

    if (burst > 0.0 && burst < 1.0 && burstStyle > 0.5) {
        float k = burst;
        float m = (line * 0.9 + inner * 0.45) * pow(1.0 - k, 1.6) * smoothstep(0.0, 0.06, k);
        rgb += flash.rgb * m;
        a += m;
    } else if (burst > 0.0 && burst < 1.0) {
        float k = burst;
        float e = 1.0 - pow(1.0 - k, 2.4);
        float2 dir = normalize(float2(1.0, -0.22));
        float2 head = float2(mix(-0.15, 1.15, e) * size.x, mix(0.78, 0.30, e) * size.y);
        float2 rel = q - head;
        float along = dot(rel, -dir);
        float perp = abs(dot(rel, float2(-dir.y, dir.x)));
        float tlen = size.x * 0.45;
        float t = clamp(1.0 - along / tlen, 0.0, 1.0);
        float streak = along > -1.5 ? smoothstep(0.7 + 1.3 * t, 0.0, perp) * t * t : 0.0;
        float star = glint(rel, 2.4);
        // Dust left behind the head, fading as the burst ends.
        float trail = q.x < head.x ? dust(q, 9.0, 0.35, 9.0) * smoothstep(1.0, 0.4, k) : 0.0;
        float ring = line * pow(1.0 - k, 2.0) * 0.7;
        float fade = smoothstep(0.0, 0.08, k) * smoothstep(1.0, 0.7, k);
        float m = (streak + star * 0.9) * fade + trail * 0.9 + ring;
        rgb += mix(flash.rgb, float3(1.0), clamp(star * fade, 0.0, 0.7)) * m;
        a += m;
    }

    rgb *= intensity;
    a = clamp(a * intensity, 0.0, 1.0);
    // Premultiplied: colour never exceeds coverage.
    return half4(min(rgb, float3(a)), a);
}

"""
