import com.daily.quizbank.util.CropFocus
import com.daily.quizbank.util.CropSuggest
import com.daily.quizbank.data.ocr.OcrLine
import java.io.File

/**
 * 拿**用户发来的真机截图**（先用 Windows OCR 取每行的框）跑一遍自动框选。
 *
 * 用法：CropRealProbeKt <桌面OCR-微信图.txt> [要看的图序号,从0开始]
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: return
    val only = args.getOrNull(1)?.toIntOrNull()

    var name = ""
    var w = 0
    var h = 0
    val lines = ArrayList<OcrLine>()
    val shots = ArrayList<Triple<String, Pair<Int, Int>, List<OcrLine>>>()

    fun flush() {
        if (name.isNotEmpty()) shots.add(Triple(name, w to h, ArrayList(lines)))
        lines.clear()
    }

    for (raw in File(path).readLines()) {
        if (raw.startsWith("=== ")) {
            flush()
            val parts = raw.removePrefix("=== ").split("  ")
            name = parts[0]
            val wh = parts.getOrNull(1)?.trim()?.split("x") ?: emptyList()
            w = wh.getOrNull(0)?.toIntOrNull() ?: 0
            h = wh.getOrNull(1)?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
            continue
        }
        val c = raw.split('\t')
        if (c.size < 5) continue
        val l = c[1].toIntOrNull() ?: continue
        val t = c[2].toIntOrNull() ?: continue
        val r = c[3].toIntOrNull() ?: continue
        val b = c[4].toIntOrNull() ?: continue
        lines.add(OcrLine(text = c[0], left = l, top = t, right = r, bottom = b))
    }
    flush()

    fun box(label: String, focus: CropFocus, shot: Triple<String, Pair<Int, Int>, List<OcrLine>>) {
        val (n, size, ls) = shot
        val box = CropSuggest.suggest(ls, size.first, size.second, focus)
        if (box == null) {
            println("  " + label + "：没框出来（保持整屏）")
            return
        }
        println(
            "  " + label + "：x " + (box.left * 100).toInt() + "%~" + (box.right * 100).toInt() +
                "%   y " + (box.top * 100).toInt() + "%~" + (box.bottom * 100).toInt() + "%" +
                "   （像素 y " + (box.top * size.second).toInt() + " ~ " + (box.bottom * size.second).toInt() + "）",
        )
    }

    shots.forEachIndexed { index, shot ->
        if (only != null && index != only) return@forEachIndexed
        println("=== " + shot.first + "  " + shot.second.first + "x" + shot.second.second +
            "  行数 " + shot.third.size)
        box("拍题默认（AUTO）", CropFocus.AUTO, shot)
        box("只识别题干（STEM）", CropFocus.STEM, shot)
        box("只识别选项（OPTIONS）", CropFocus.OPTIONS, shot)
        // 把框进去的行列出来，方便对着截图核
        listOf(CropFocus.STEM, CropFocus.OPTIONS).forEach { focus ->
            val b = CropSuggest.suggest(shot.third, shot.second.first, shot.second.second, focus) ?: return@forEach
            val inside = shot.third.filter {
                it.top >= b.top * shot.second.second && it.bottom <= b.bottom * shot.second.second
            }
            println("     " + focus + " 框进去的行：" + inside.joinToString(" / ") { it.text.replace(" ", "") })
        }
    }
}
