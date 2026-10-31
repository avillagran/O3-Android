package dev.avillagran.ot3android.ui.thread.rendering

import androidx.compose.foundation.layout.Column
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.thread.MarkdownTable
import org.json.JSONObject
import org.junit.Test

class VisualRenderingScreenshotTest : ScreenshotTest() {
    @Test fun formulaCodeAndTable() = snapshot("formula-code-table") {
        Column {
            VisualMath("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2} \\quad \\alpha \\in \\mathbb{R}")
            VisualMath("\\sqrt[3]{x} + \\beta_i^2")
            VisualCodeBlock(JSONObject().put("language", "kotlin").put("code", "val title = \"Hello π\"\n// Exact source copy\nfun twice(x: Int) = x * 2"), 0)
            MarkdownTable(JSONObject("""{"header":[[{"text":"Name"}],[{"text":"Value"}]],"rows":[[[{"text":"Alpha","strong":true}],[{"text":"12"}]],[[{"text":"Beta"}],[{"text":"a, b"}]]],"alignments":["left","right"],"markdown":"| Name | Value |","csv":"Name,Value"}"""), 0)
            VisualPreview(VisualRequest(VisualKind.Mermaid, "graph TD; A-->B", "Diagram"), "diagram")
        }
    }
}
