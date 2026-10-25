package dev.avillagran.ot3android.ui.sky

import android.graphics.RuntimeShader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AGSL compiles only on the device: constructing the [RuntimeShader] parses it (and
 * throws on an error), and setting every uniform fails if one is missing or of another
 * size. Nothing is drawn.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class StarfieldShaderCompileTest {
    @Test
    fun starfieldCompilesAndTakesEveryUniform() {
        val shader = RuntimeShader(STARFIELD_AGSL)
        val u = SkyMath.uniforms(PreviewTokyoNight, SkyMotion.Full, 1f, 10f, 1f, 411f, 126f, 2.625f)
        u.forEachUniform { name, values -> shader.setFloatUniform(name, values) }
    }
}
