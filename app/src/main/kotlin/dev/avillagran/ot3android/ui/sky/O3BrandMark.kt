package dev.avillagran.ot3android.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.avillagran.ot3android.ui.theme.LocalOt3Motion
import dev.avillagran.ot3android.ui.theme.Ot3MotionLevel
import kotlinx.coroutines.delay
import kotlin.math.*

/** Source port: Desktop ui/qml/T3BrandMark.qml and ui/js/BrandPixels.js.
 * The brand always reads left-to-right. Tap at rest replaces Desktop hover.
 * [elapsedMs] freezes the production renderer for deterministic animation fixtures.
 */
@Composable
fun O3BrandMark(
    foreground: Color,
    dim: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    config: O3BrandConfig = O3BrandConfig(),
    elapsedMs: Long? = null,
) {
    val motion = LocalOt3Motion.current
    val effective = config.copy(reducedMotion = config.reducedMotion || motion.level == Ot3MotionLevel.OFF)
    var elapsed by remember(effective) { mutableLongStateOf(0L) }
    var replay by remember { mutableIntStateOf(0) }
    LaunchedEffect(effective, elapsedMs, replay) {
        elapsed = if (replay == 0) 0 else 2400
        if (elapsedMs == null && effective.running && !effective.reducedMotion) {
            var last = withFrameNanos { it }
            while (true) {
                val frame = brandFrame(elapsed, effective)
                // Plain holds do not schedule GPU frames, just like Desktop's Timer.
                if (frame.scene == -1 || frame.scene == 4 || (frame.scene == 1 && frame.time > 2200)) {
                    val remaining = when(frame.scene) {
                        -1 -> 2400L - elapsed
                        1 -> ((7*95+900)*effective.effectivePace).roundToLong()+3600L-(frame.time*effective.effectivePace).roundToLong()
                        else -> 7000L-(frame.time*effective.effectivePace).roundToLong()
                    }
                    delay(remaining.coerceAtLeast(1))
                }
                val now = withFrameNanos { it }
                elapsed += (now-last)/1_000_000
                last = now
            }
        }
    }
    val frame = brandFrame(elapsedMs ?: elapsed, effective)
    val effects = effectFrame(frame, config)
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val textMeasurer = rememberTextMeasurer()
    val glowLayer = rememberGraphicsLayer()
    val code = remember(textMeasurer) {
        textMeasurer.measure("Code", TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold), density = Density(1f, 1f))
    }
    val unit = 32f / 94.3941f
    val markHeight = 56.96f * unit
    val room = if (config.glyph == O3BrandGlyph.OMARCHY) (15.5309f+56.96f+10f/3/unit-65.3653f)*unit else 0f
    val shift = room*(1-frame.morph)
    // Reserve the widest scene: no count/status layout jumps during a morph.
    val interaction = remember { MutableInteractionSource() }
    val tap = if (effective.running && !effective.reducedMotion && elapsedMs == null) {
        Modifier.clickable(indication = null, interactionSource = interaction) {
            if (frame.scene == -1 || frame.scene == 4) replay++
        }
    } else Modifier
    BoxWithConstraints(modifier.then(tap).clearAndSetSemantics { contentDescription = "O3 Code" }) {
        val naturalWidth = ((32+room+4+code.size.width)*fontScale).dp
        val fit = min(1f, maxWidth.value / naturalWidth.value)
        val wordHeight = max(markHeight, code.size.height.toFloat())
        val totalHeight = wordHeight+3+19*markHeight*.5f/17
        Box(Modifier.width(naturalWidth*fit).height((totalHeight*fit*fontScale).dp)) {
            Canvas(Modifier.fillMaxSize()) {
                scale(density.density*fit*fontScale, pivot = Offset.Zero) {
                    val gy = (wordHeight-markHeight)/2
                    val pulse = scenePulse(effects)
                    val squash = squash(effects, config.glyph == O3BrandGlyph.OMARCHY)
                    val recoil = recoil(effects)
                    // Desktop disables its MultiEffect on software renderers too.
                    val effectsSupported = android.os.Build.VERSION.SDK_INT >= 31 && drawContext.canvas.nativeCanvas.isHardwareAccelerated
                    if (!config.sober && pulse > .01f && effectsSupported) {
                        glowLayer.record(size = IntSize(96, 80)) {
                            translate(24f, 24f) {
                                scale(squash.first, squash.second, Offset((39.9f-15.5309f)*unit, markHeight)) {
                                    val glowInk = lerp(accent, Color.White, .4f)
                                    if (config.glyph == O3BrandGlyph.O) drawGlyph(frame.morph, glowInk, unit)
                                    else drawBlocks(frame.morph, glowInk, glowInk, unit)
                                }
                            }
                        }
                        glowLayer.renderEffect = BlurEffect(24f, 24f)
                        glowLayer.alpha = pulse
                        translate(-24f, gy-24f) { drawLayer(glowLayer) }
                    }
                    drawContext.canvas.saveLayer(Rect(-30f,-30f,32+room+4+code.size.width+30,wordHeight+30),Paint())
                    translate(0f, gy) {
                        scale(squash.first, squash.second, Offset((39.9f-15.5309f)*unit, markHeight)) {
                            if (!config.sober && config.glyph == O3BrandGlyph.O) {
                                val chroma = chroma(effects)
                                translate(-chroma*3,0f) { drawGlyph(frame.morph, accent.copy(alpha=chroma*.7f), unit) }
                                translate(chroma*3,0f) { drawGlyph(frame.morph, complement(accent).copy(alpha=chroma*.7f), unit) }
                            }
                            if (config.glyph == O3BrandGlyph.O) drawGlyph(frame.morph, foreground, unit)
                            else drawBlocks(frame.morph, foreground, accent, unit)
                        }
                        translate(shift+recoil-15.5309f*unit, -37f*unit) {
                            scale(unit, pivot=Offset.Zero) { drawPath(ThreePath, foreground) }
                        }
                        if (!config.sober && (effects.scene == 0 || effects.scene == 3)) {
                            val burst=((effects.time-(170+if(config.glyph==O3BrandGlyph.OMARCHY)920 else 620))/520).coerceIn(0f,1f)
                            if(burst>0 && burst<1) repeat(9) { i ->
                                val a=-PI*(.08+.84*i/8); val reach=14+(i%3)*4
                                drawCircle((if(i%2==1)accent else foreground).copy(alpha=1-burst), (1.4f-burst), Offset(8.26f+cos(a).toFloat()*reach*burst,markHeight*.45f+sin(a).toFloat()*reach*burst))
                            }
                        }
                    }
                    drawText(code, foreground, topLeft = Offset(32+shift+4+recoil, (wordHeight-code.size.height)/2))
                    if (!config.sober && effectsSupported && (effects.scene==0 || effects.scene==3)) {
                        val t=((effects.time-380)/720).coerceIn(0f,1f)
                        if(t>0 && t<1) {
                            val eased=if(t<.5f)2*t*t else 1-(-2*t+2).pow(2)/2
                            val x=(-.4f+1.8f*eased)*(32+shift+4+code.size.width)
                            for ((band,light) in listOf(16f to .45f,5f to 1f)) {
                                drawRect(Color.White.copy(alpha=light),Offset(x-band/2,0f),Size(band,wordHeight),blendMode=BlendMode.SrcAtop)
                            }
                        }
                    }
                    drawContext.canvas.restore()
                    val pixel=markHeight*.5f/17
                    val subtitleY=wordHeight+3-((wordHeight-markHeight)/2+3)/3
                    Letters.forEachIndexed { index, letter ->
                        val state=letterState(frame,index)
                        if(state.alpha>0) translate((33.4509f-15.5309f)*unit+letter.x*pixel, subtitleY+state.drop) {
                            rotate(state.tilt, Offset(letter.width*pixel/2,19*pixel/2)) {
                                letter.runs.forEach { run -> drawRect(lerp(dim,accent,state.heat).copy(alpha=state.alpha),Offset(run[0]*pixel,run[1]*pixel),Size(run[2]*pixel,pixel)) }
                            }
                            if(state.flash>0 && state.flash<1) drawRect(accent.copy(alpha=1-state.flash),Offset(letter.width*pixel*(.5f-(.4f+state.flash*1.4f)/2),17*pixel),Size(letter.width*pixel*(.4f+state.flash*1.4f),pixel))
                        }
                    }
                }
            }

        }
    }
}

private fun DrawScope.drawGlyph(m: Float, color: Color, unit: Float) {
    translate(-15.5309f*unit,-37*unit) { scale(unit,pivot=Offset.Zero) {
        val path=Path().apply { fillType=PathFillType.EvenOdd }
        val n=TOutline.size
        TOutline.forEachIndexed { i,p ->
            val angle=i.toFloat()/n*2*PI
            val x=(39.2+24.8*sin(angle)).toFloat(); val y=(65.2-28.6*cos(angle)).toFloat()
            if(i==0)path.moveTo(x+(p.x-x)*m,y+(p.y-y)*m) else path.lineTo(x+(p.x-x)*m,y+(p.y-y)*m)
        }
        path.close()
        repeat(40) { i ->
            val a=i/40f*2*PI; val h=min(1f,m*1.4f)
            val x=(39.2+12*sin(a)).toFloat(); val y=(65.2-16.2*cos(a)).toFloat()
            val px=x+(39.93f-x)*h; val py=y+(72-y)*h
            if(i==0)path.moveTo(px,py) else path.lineTo(px,py)
        }
        path.close(); drawPath(path,color)
    } }
}
private val TOutline: List<Offset> = run {
    val corners=listOf(Offset(39.9309f,37f),Offset(64.3309f,37f),Offset(64.3309f,47.56f),Offset(46.4109f,47.56f),Offset(46.4109f,93f),Offset(33.4509f,93f),Offset(33.4509f,47.56f),Offset(15.5309f,47.56f),Offset(15.5309f,37f))
    val lengths=corners.indices.map { (corners[(it+1)%corners.size]-corners[it]).getDistance() }
    corners.indices.flatMap { i -> val count=max(1,(96*lengths[i]/lengths.sum()).roundToInt()); (0 until count).map { k -> corners[i]+(corners[(i+1)%corners.size]-corners[i])*(k.toFloat()/count) } }
}
private data class Flight(val a: Offset,val b: Offset,val delay: Float,val arc: Float,val lift: Float)
private val Flights by lazy {
    val cell=56.96f/15; val cols=(48.8f/cell).roundToInt(); val rows=(56/cell).roundToInt()
    val targets=(0 until rows).flatMap { j -> (0 until cols).mapNotNull { i ->
        val x=15.5309f+i*48.8f/cols; val y=37+j*56f/rows
        if(y+cell/2<47.56f || x+cell/2>33.4509f && x+cell/2<46.4109f) Offset(x,y) else null
    } }
    val n=max(LogoCells.size,targets.size)
    (0 until n).map { i -> Flight(Offset(15.5309f,37f)+LogoCells[i*LogoCells.size/n]*cell,targets[i*targets.size/n],(i*37%n).toFloat()/n,((i*53%17)-8)*1.4f,4f+(i*29%7)) }
}
private fun DrawScope.drawBlocks(m: Float,fg: Color,accent: Color,unit: Float) {
    if(m<.002f) { scale(unit*56.96f/1200,pivot=Offset.Zero) { drawPath(LogoPath,fg) }; return }
    if(m>.998f) { drawGlyph(1f,fg,unit); return }
    Flights.forEach { f ->
        val t=((m-f.delay*.45f)/.55f).coerceIn(0f,1f); val bump=sin(PI*t).toFloat()
        val p=f.a+(f.b-f.a)*cubic(t)+Offset(f.arc*bump,-f.lift*bump)-Offset(15.5309f,37f)
        val side=56.96f/15*unit*(1+.6f*bump)
        translate(p.x*unit,p.y*unit) { rotate(90*bump*if(f.arc>0)1 else -1,Offset(side/2,side/2)) { drawRect(lerp(fg,accent,bump),size=Size(side,side)) } }
    }
}
private fun complement(c: Color): Color {
    val high=max(c.red,max(c.green,c.blue)); val low=min(c.red,min(c.green,c.blue))
    val delta=high-low; val light=(high+low)/2
    val hue=when { delta==0f -> 0f; high==c.red -> ((c.green-c.blue)/delta)%6; high==c.green -> (c.blue-c.red)/delta+2; else -> (c.red-c.green)/delta+4 }
    val saturation=if(delta==0f)0f else delta/(1-abs(2*light-1))
    return Color.hsl(((hue*60+180)%360+360)%360,max(.6f,saturation),.6f)
}
private fun effectFrame(f: O3BrandFrame,c: O3BrandConfig): O3BrandFrame = when(f.scene) {
    1 -> f.copy(scene=0,time=f.time+(c.morphMs+120)/c.effectivePace)
    4 -> f.copy(scene=3,time=f.time+c.morphMs/c.effectivePace)
    else -> f
}
private fun scenePulse(f: O3BrandFrame): Float = if(f.scene!=0 && f.scene!=3)0f else if(f.time<630)outQuad(((f.time-250)/380).coerceIn(0f,1f)) else (1+cos(PI*((f.time-630)/700).coerceIn(0f,1f))).toFloat()/2
private fun chroma(f: O3BrandFrame): Float = if(f.scene!=0 && f.scene!=3)0f else if(f.time<410)outQuad(((f.time-150)/260).coerceIn(0f,1f)) else 1-((f.time-410)/420).coerceIn(0f,1f).pow(2)
private fun squash(f: O3BrandFrame, blocks: Boolean): Pair<Float,Float> {
    if(f.scene!=0 && f.scene!=3)return 1f to 1f
    val morph=if(f.time<170)outQuad(f.time/170) else outQuad(((f.time-170)/260).coerceIn(0f,1f))
    if(f.time<170)return (1+.1f*morph) to (1-.14f*morph)
    val duration=if(blocks)920 else 620
    val settle=outBack(((f.time-(170+duration))/320).coerceIn(0f,1f),3f)
    return (1.1f-.18f*morph+.08f*settle) to (.86f+.26f*morph-.12f*settle)
}
private fun recoil(f: O3BrandFrame): Float {
    if(f.scene!=0 && f.scene!=3)return 0f
    if(f.time<370)return 2*outQuad(((f.time-170)/200).coerceIn(0f,1f))
    val t=((f.time-370)/460).coerceIn(0f,1f)
    if(t==1f)return 0f
    val shift=.4f/(2*PI.toFloat())*asin(1/1.2f)
    return -2*1.2f*2f.pow(-10*t)*sin((t-shift)*2*PI.toFloat()/.4f)
}
private data class LetterState(val alpha: Float=0f,val drop: Float=0f,val tilt: Float=0f,val heat: Float=0f,val flash: Float=0f)
private fun letterState(f: O3BrandFrame,i: Int): LetterState {
    if(f.spelled)return LetterState(alpha=1f)
    if(f.scene==1) {
        val t=f.time-i*95
        if(t<0)return LetterState()
        val drop=-26*(1-bounce((t/640).coerceIn(0f,1f)))
        val tilt=(if(i%2==1)1 else -1)*(14+i*3)*(1-outBack((t/560).coerceIn(0f,1f),2.4f))
        return LetterState((t/160).coerceIn(0f,1f),drop,tilt,if(t<640)1f else (1+cos(PI*((t-640)/900).coerceIn(0f,1f))).toFloat()/2,if(t<230)0f else 1-(1-((t-230)/380).coerceIn(0f,1f)).pow(3))
    }
    if(f.scene==2) {
        if (f.time < i*70) return LetterState(alpha=1f)
        val t=((f.time-i*70)/480).coerceIn(0f,1f)
        val back=1-outBack(1-t,2.2f)
        return LetterState(1-t*t,22*back,(if(i%2==1)-1 else 1)*(20+i*4)*t*t,.6f*(1-t))
    }
    return LetterState()
}

private val ThreePath = PathParser().parsePathString("M86.7253 93.96C82.832 93.96 78.9653 93.4533 75.1253 92.44C71.2853 91.3733 68.032 89.88 65.3653 87.96L70.4053 78.04C72.5386 79.5867 75.0186 80.8133 77.8453 81.72C80.672 82.6267 83.5253 83.08 86.4053 83.08C89.6586 83.08 92.2186 82.44 94.0853 81.16C95.952 79.88 96.8853 78.12 96.8853 75.88C96.8853 73.7467 96.0586 72.0667 94.4053 70.84C92.752 69.6133 90.0853 69 86.4053 69H80.4853V60.44L96.0853 42.76L97.5253 47.4H68.1653V37H107.365V45.4L91.8453 63.08L85.2853 59.32H89.0453C95.9253 59.32 101.125 60.8667 104.645 63.96C108.165 67.0533 109.925 71.0267 109.925 75.88C109.925 79.0267 109.099 81.9867 107.445 84.76C105.792 87.48 103.259 89.6933 99.8453 91.4C96.432 93.1067 92.0586 93.96 86.7253 93.96Z").toPath()
private val LogoPath = PathParser().parsePathString("m1200 1200h-480v-80h400v-1040h-479.996v160h-400v720h720v-720h-80v-80h159.996v880h-400v160h-640v-1200h1200zm-1120-80h480v-80h-400l.004-400h-80.004zm0-560h80.004v-400h400v-80h-480.004z").toPath()
private data class PixelLetter(val x: Int, val width: Int, val runs: List<List<Int>>)
private val Letters = listOf(
    PixelLetter(0, 9, listOf(listOf(2,1,5),listOf(1,2,7),listOf(0,3,3),listOf(6,3,3),listOf(0,4,3),listOf(6,4,3),listOf(0,5,3),listOf(6,5,3),listOf(0,6,3),listOf(6,6,3),listOf(0,7,3),listOf(6,7,3),listOf(0,8,3),listOf(6,8,3),listOf(0,9,3),listOf(6,9,3),listOf(0,10,3),listOf(6,10,3),listOf(0,11,3),listOf(6,11,3),listOf(0,12,3),listOf(6,12,3),listOf(0,13,3),listOf(6,13,3),listOf(0,14,3),listOf(6,14,3),listOf(1,15,7),listOf(2,16,5))),
    PixelLetter(11, 15, listOf(listOf(6,0,3),listOf(2,1,11),listOf(1,2,13),listOf(0,3,3),listOf(6,3,3),listOf(12,3,3),listOf(0,4,3),listOf(6,4,3),listOf(12,4,3),listOf(0,5,3),listOf(6,5,3),listOf(12,5,3),listOf(0,6,3),listOf(6,6,3),listOf(12,6,3),listOf(0,7,3),listOf(6,7,3),listOf(12,7,3),listOf(0,8,3),listOf(6,8,3),listOf(12,8,3),listOf(0,9,3),listOf(6,9,3),listOf(12,9,3),listOf(0,10,3),listOf(6,10,3),listOf(12,10,3),listOf(0,11,3),listOf(6,11,3),listOf(12,11,3),listOf(0,12,3),listOf(6,12,3),listOf(12,12,3),listOf(0,13,3),listOf(6,13,3),listOf(12,13,3),listOf(0,14,3),listOf(6,14,3),listOf(12,14,3),listOf(1,15,2),listOf(6,15,3),listOf(12,15,2),listOf(2,16,1),listOf(6,16,3),listOf(12,16,1))),
    PixelLetter(27, 10, listOf(listOf(3,1,7),listOf(2,2,8),listOf(1,3,3),listOf(7,3,3),listOf(1,4,3),listOf(7,4,3),listOf(1,5,3),listOf(7,5,3),listOf(1,6,3),listOf(7,6,3),listOf(1,7,3),listOf(7,7,3),listOf(0,8,10),listOf(0,9,10),listOf(1,10,3),listOf(7,10,3),listOf(1,11,3),listOf(7,11,3),listOf(1,12,3),listOf(7,12,3),listOf(1,13,3),listOf(7,13,3),listOf(1,14,3),listOf(7,14,3),listOf(1,15,3),listOf(7,15,2),listOf(1,16,3),listOf(7,16,1))),
    PixelLetter(38, 10, listOf(listOf(3,1,7),listOf(2,2,8),listOf(1,3,3),listOf(7,3,3),listOf(1,4,3),listOf(7,4,3),listOf(1,5,3),listOf(7,5,3),listOf(1,6,3),listOf(7,6,3),listOf(1,7,3),listOf(7,7,3),listOf(0,8,9),listOf(0,9,8),listOf(1,10,3),listOf(0,11,10),listOf(0,12,10),listOf(1,13,3),listOf(7,13,3),listOf(1,14,3),listOf(7,14,3),listOf(1,15,3),listOf(7,15,3),listOf(1,16,3),listOf(7,16,3),listOf(1,17,3),listOf(7,17,2),listOf(1,18,3),listOf(7,18,1))),
    PixelLetter(50, 9, listOf(listOf(2,1,7),listOf(1,2,8),listOf(0,3,3),listOf(6,3,3),listOf(0,4,3),listOf(6,4,3),listOf(0,5,3),listOf(6,5,2),listOf(0,6,3),listOf(6,6,1),listOf(0,7,3),listOf(0,8,3),listOf(0,9,3),listOf(0,10,3),listOf(0,11,3),listOf(6,11,1),listOf(0,12,3),listOf(6,12,2),listOf(0,13,3),listOf(6,13,3),listOf(0,14,3),listOf(6,14,3),listOf(0,15,8),listOf(0,16,7))),
    PixelLetter(59, 12, listOf(listOf(4,1,1),listOf(8,1,1),listOf(3,2,2),listOf(8,2,2),listOf(2,3,3),listOf(8,3,3),listOf(2,4,3),listOf(8,4,3),listOf(2,5,3),listOf(8,5,3),listOf(2,6,3),listOf(8,6,3),listOf(2,7,3),listOf(8,7,3),listOf(1,8,11),listOf(0,9,11),listOf(2,10,3),listOf(8,10,3),listOf(2,11,3),listOf(8,11,3),listOf(2,12,3),listOf(8,12,3),listOf(2,13,3),listOf(8,13,3),listOf(2,14,3),listOf(8,14,3),listOf(2,15,3),listOf(8,15,2),listOf(2,16,3),listOf(8,16,1))),
    PixelLetter(72, 9, listOf(listOf(2,1,1),listOf(6,1,1),listOf(1,2,2),listOf(6,2,2),listOf(0,3,3),listOf(6,3,3),listOf(0,4,3),listOf(6,4,3),listOf(0,5,3),listOf(6,5,3),listOf(0,6,3),listOf(6,6,3),listOf(0,7,3),listOf(6,7,3),listOf(0,8,9),listOf(0,9,9),listOf(6,10,3),listOf(1,11,2),listOf(6,11,3),listOf(0,12,3),listOf(6,12,3),listOf(0,13,3),listOf(6,13,3),listOf(0,14,3),listOf(6,14,3),listOf(1,15,7),listOf(2,16,5))),
)
private val LogoCells = listOf(Offset(0f,0f),Offset(1f,0f),Offset(2f,0f),Offset(3f,0f),Offset(4f,0f),Offset(5f,0f),Offset(6f,0f),Offset(7f,0f),Offset(8f,0f),Offset(9f,0f),Offset(10f,0f),Offset(11f,0f),Offset(12f,0f),Offset(13f,0f),Offset(14f,0f),Offset(0f,1f),Offset(7f,1f),Offset(14f,1f),Offset(0f,2f),Offset(2f,2f),Offset(3f,2f),Offset(4f,2f),Offset(5f,2f),Offset(6f,2f),Offset(7f,2f),Offset(11f,2f),Offset(12f,2f),Offset(14f,2f),Offset(0f,3f),Offset(2f,3f),Offset(12f,3f),Offset(14f,3f),Offset(0f,4f),Offset(2f,4f),Offset(12f,4f),Offset(14f,4f),Offset(0f,5f),Offset(2f,5f),Offset(12f,5f),Offset(14f,5f),Offset(0f,6f),Offset(2f,6f),Offset(12f,6f),Offset(14f,6f),Offset(0f,7f),Offset(1f,7f),Offset(2f,7f),Offset(12f,7f),Offset(14f,7f),Offset(0f,8f),Offset(2f,8f),Offset(12f,8f),Offset(14f,8f),Offset(0f,9f),Offset(2f,9f),Offset(12f,9f),Offset(14f,9f),Offset(0f,10f),Offset(2f,10f),Offset(12f,10f),Offset(14f,10f),Offset(0f,11f),Offset(2f,11f),Offset(12f,11f),Offset(14f,11f),Offset(0f,12f),Offset(2f,12f),Offset(3f,12f),Offset(4f,12f),Offset(5f,12f),Offset(6f,12f),Offset(7f,12f),Offset(8f,12f),Offset(9f,12f),Offset(10f,12f),Offset(11f,12f),Offset(12f,12f),Offset(14f,12f),Offset(0f,13f),Offset(7f,13f),Offset(14f,13f),Offset(0f,14f),Offset(1f,14f),Offset(2f,14f),Offset(3f,14f),Offset(4f,14f),Offset(5f,14f),Offset(6f,14f),Offset(7f,14f),Offset(9f,14f),Offset(10f,14f),Offset(11f,14f),Offset(12f,14f),Offset(13f,14f),Offset(14f,14f))
