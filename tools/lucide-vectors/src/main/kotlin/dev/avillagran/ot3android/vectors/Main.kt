package dev.avillagran.ot3android.vectors

import java.io.File
import kotlin.system.exitProcess

/** Drawable name for an icon SVG: `git-pull-request.svg` → `ic_git_pull_request`. */
fun drawableName(svgFileName: String): String {
    val stem = svgFileName.removeSuffix(".svg")
    require(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(stem)) { "icon name must be lower-case kebab-case: $svgFileName" }
    return "ic_" + stem.replace('-', '_')
}

/**
 * `lucide-vectors <svg dir> <res dir>`: converts every `*.svg` in the SVG directory into
 * `<res dir>/drawable/ic_<name>.xml`, replacing what the directory held before.
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: lucide-vectors <svg dir> <res dir>")
        exitProcess(2)
    }
    val svgDir = File(args[0])
    val drawableDir = File(args[1], "drawable")
    val sources = svgDir.listFiles { file -> file.name.endsWith(".svg") }?.sortedBy { it.name }.orEmpty()
    require(sources.isNotEmpty()) { "no SVG files in $svgDir" }
    drawableDir.deleteRecursively()
    drawableDir.mkdirs()
    for (source in sources) {
        val label = "svg/${svgDir.name}/${source.name}"
        File(drawableDir, drawableName(source.name) + ".xml").writeText(SvgToVector.convert(source.readText(), label))
    }
}
