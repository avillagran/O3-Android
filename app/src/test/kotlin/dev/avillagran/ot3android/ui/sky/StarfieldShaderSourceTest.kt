package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The AGSL only compiles on an API 33+ device (see `StarfieldShaderCompileTest` in
 * androidTest); this checks on the JVM what can be checked: the uniforms the Kotlin side
 * sets, the constants [SkyMath] mirrors, and that no GLSL survived the port.
 */
class StarfieldShaderSourceTest {
    private val src = STARFIELD_AGSL
    private val code = src.lines().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun declaredUniformsAreExactlyTheOnesKotlinSets() {
        val arity = mapOf("float" to 1, "float2" to 2, "float4" to 4)
        val declared = Regex("""^\s*uniform\s+(\w+)\s+(\w+)\s*;""", RegexOption.MULTILINE)
            .findAll(code).map { it.groupValues[2] to (arity[it.groupValues[1]] ?: -1) }.toList()
        val set = mutableListOf<Pair<String, Int>>()
        SkyMath.uniforms(TokyoNight, SkyMotion.Full, 0f, 0f, 1f, 411f, 126f, 2f)
            .forEachUniform { name, values -> set += name to values.size }
        assertEquals(declared, set)
    }

    @Test
    fun layerCallsUseSkyMathsConstants() {
        SkyMath.LAYERS.forEachIndexed { i, l ->
            val call = String.format(
                Locale.ROOT,
                "layer(p + flow * %.2f + lift * %.2f, %s, %s, %s, %s, t%d) * %s",
                l.driftFactor, l.scrollFactor, l.cell, l.density, l.seed, l.radius, i + 1, l.weight,
            )
            assertTrue("missing: $call", call in code)
        }
        assertTrue("float2 flow = float2(drift, drift * ${SkyMath.FLOW_Y});" in code)
    }

    @Test
    fun starAndCometFormulasMirrorSkyMath() {
        val snippets = listOf(
            "tint = hash12(id + seed + 5.3);",
            "float2 pos = 0.2 + 0.6 * hash22(id + seed * 1.7);",
            "float r = radius * mix(0.55, 1.25, hash12(id + seed + 3.1));",
            "float rate = 0.6 + 2.2 * hash12(id + seed + 7.0);",
            "float twinkle = 0.55 + 0.45 * sin(clock * rate + h * 61.0);",
            "float core = smoothstep(r + 0.9, r * 0.2, d);",
            "float halo = exp(-d * d / (r * r * 7.0)) * 0.3;",
            "mix(0.45, 1.0, hash12(id + seed + 11.0))",
            "float period = 2.1 + channel * 1.37;",
            "float2 key = float2(slot, channel * 17.0);",
            "if (hash12(key) > meteors) return 0.0;",
            "float duration = 0.75 + 0.5 * hash12(key + 2.0);",
            "float len = clamp(span * 0.22, 60.0, 240.0);",
            "if (meteors > 0.001)",
            "float3 meteorColor = mix(meteor.rgb, accent.rgb, 0.25);",
            "smoothstep(0.55, 1.0, tint) * 0.6",
            "float2 q = fragCoord / pixelDensity;",
        )
        for (s in snippets) assertTrue("missing: $s", s in code)
    }

    @Test
    fun noGlslLeftovers() {
        for (word in listOf("vec2", "vec3", "vec4", "ubuf", "qt_", "#version", "fragColor", "layout", "texture")) {
            assertFalse("GLSL left: $word", Regex("""\b${Regex.escape(word)}""").containsMatchIn(code))
        }
        assertTrue("half4 main(float2 fragCoord)" in code)
    }

    @Test
    fun bracketsBalanceAndStatementsEnd() {
        assertEquals(code.count { it == '{' }, code.count { it == '}' })
        assertEquals(code.count { it == '(' }, code.count { it == ')' })
        var depth = 0
        for (ch in code) {
            if (ch == '(') depth++ else if (ch == ')') depth--
            assertTrue(depth >= 0)
        }
    }

    @Test
    fun everyCalledFunctionIsDefinedOrAnAgslBuiltin() {
        val builtins = setOf(
            "fract", "floor", "dot", "length", "normalize", "smoothstep", "exp", "sin", "clamp", "mix", "abs",
            "float", "float2", "float3", "float4", "half4",
        )
        val defined = Regex("""^(?:float|float2|half4)\s+(\w+)\s*\(""", RegexOption.MULTILINE)
            .findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("hash12", "hash22", "layer", "meteorAt", "main"), defined)
        val keywords = setOf("if", "return", "for", "while")
        val called = Regex("""\b(\w+)\s*\(""").findAll(code).map { it.groupValues[1] }.toSet() - keywords
        assertEquals(emptySet<String>(), called - builtins - defined)
    }

    @Test
    fun noIdentifierShadowsAUniform() {
        val uniforms = Regex("""uniform\s+\w+\s+(\w+)\s*;""").findAll(code).map { it.groupValues[1] }.toSet()
        val functionNames = setOf("hash12", "hash22", "layer", "meteorAt")
        assertEquals(emptySet<String>(), functionNames.intersect(uniforms))
        // Declarations, not uses: a uniform name must never be re-declared.
        val redeclared = Regex("""^\s+(?:float|float2|float3)\s+(\w+)\s*=""", RegexOption.MULTILINE)
            .findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), redeclared.intersect(uniforms))
        val paramNames = Regex("""^\w+\s+\w+\(([^)]*)\)""", RegexOption.MULTILINE).findAll(code)
            .flatMap { it.groupValues[1].split(",") }.map { it.trim().substringAfterLast(' ') }.toSet()
        assertEquals(emptySet<String>(), paramNames.intersect(uniforms))
    }
}
