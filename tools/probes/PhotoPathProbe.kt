import com.daily.quizbank.util.CropFocus
import com.daily.quizbank.util.CropSuggest
import com.daily.quizbank.util.OcrText
import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import com.daily.quizbank.data.ocr.OcrLine
import java.io.File

/**
 * 拿**真机截图**把「拍题 / 拍照」那条链路在电脑上走一遍（只少 ML Kit 那一步）。
 *
 * 链路：Windows OCR 的行框 → CropSuggest 默认框（AUTO，和真机上框选界面初始摆的那个一样）
 *      → 框内的行 → 去掉 Windows OCR 在汉字之间插的空格 → OcrText.clean
 *      → StemMatcher 匹配用户自己的库。
 *
 * 为什么要这个探针：ML Kit 只能在手机上跑，但**框选**和**匹配**这两步是纯计算，
 * 而它们恰恰是最容易悄悄坏掉的两步（真机上十次报障有九次出在前两步）。
 * 拿真截图跑一遍，至少能把「框错了 / 分数塌了」这两类问题在装机之前挡住。
 *
 * 用法：PhotoPathProbeKt <桌面OCR-全部微信图.txt> <medstems.txt> [只看第几张]
 */
fun main(args: Array<String>) {
    val ocrPath = args.getOrNull(0) ?: return
    val bankPath = args.getOrNull(1) ?: return
    val only = args.getOrNull(2)?.toIntOrNull()

    val stems = File(bankPath).readLines()
        .filter { it.isNotBlank() }
        .map { raw -> raw.trim('"').split('\t').first().replace("\\n", " ").trim() }
        .filter { it.isNotBlank() }
    val matcher = StemMatcher(stems.mapIndexed { i, s -> StemEntry(i.toLong(), 1L, s) })
    println("题库: " + stems.size + " 道（" + File(bankPath).name + "）")

    class Shot(val name: String, val w: Int, val h: Int, val lines: List<OcrLine>)

    val shots = ArrayList<Shot>()
    var name = ""
    var w = 0
    var h = 0
    val cur = ArrayList<OcrLine>()
    fun flush() {
        if (name.isNotEmpty()) shots.add(Shot(name, w, h, ArrayList(cur)))
        cur.clear()
    }
    for (raw in File(ocrPath).readLines()) {
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
        cur.add(OcrLine(text = c[0], left = l, top = t, right = r, bottom = b))
    }
    flush()

    /**
     * Windows OCR 是**按词**切的，汉字之间会插空格；ML Kit 的中文模型不插。
     * 不去掉的话喂给匹配的就是一串带缝的文本 —— 分数会被稀释，测出来的东西不作数。
     */
    fun despace(text: String): String {
        val out = StringBuilder(text.length)
        for ((i, ch) in text.withIndex()) {
            if (ch == ' ' || ch == '\u3000') {
                val prev = out.lastOrNull()
                val next = text.getOrNull(i + 1)
                if (prev != null && next != null && prev != '\n' && next != '\n' &&
                    (isCjk(prev) || isCjk(next))
                ) {
                    continue
                }
            }
            out.append(ch)
        }
        return out.toString()
    }

    var ok = 0
    var boxed = 0
    var hit = 0
    shots.forEachIndexed { index, shot ->
        if (only != null && index != only) return@forEachIndexed
        val box = CropSuggest.suggest(shot.lines, shot.w, shot.h, CropFocus.AUTO)
        println("")
        println("=== [" + index + "] " + shot.name + "  " + shot.w + "x" + shot.h + "  行 " + shot.lines.size)
        if (box == null) {
            println("  默认框：没框出来（保持整屏）")
            return@forEachIndexed
        }
        boxed++
        val topPx = box.top * shot.h
        val bottomPx = box.bottom * shot.h
        val inside = shot.lines.filter { it.top >= topPx && it.bottom <= bottomPx }
        println(
            "  默认框：x " + pct(box.left) + "~" + pct(box.right) + "%   y " + pct(box.top) + "~" +
                pct(box.bottom) + "%   （像素 y " + topPx.toInt() + " ~ " + bottomPx.toInt() + "）",
        )
        println("  框内 " + inside.size + " 行，首行「" + inside.firstOrNull()?.text?.replace(" ", "") + "」")
        println("              末行「" + inside.lastOrNull()?.text?.replace(" ", "") + "」")

        // 框对不对的粗判据：不该把最顶上那行（状态栏/时间）框进来，且要框到最后一行选项
        val firstLine = shot.lines.firstOrNull()
        val hasTitle = firstLine != null && inside.any { it.text == firstLine.text }
        val hasLastOption = inside.any { it.text.trimStart().startsWith("E") }
        val shapeOk = !hasTitle && hasLastOption && inside.size >= 6
        if (shapeOk) ok++
        println("  形态：" + (if (shapeOk) "✅ 没带标题栏、连最后一行的选项 E 也框进来了" else
            "⚠ 拿不准（带标题栏=" + hasTitle + " 含 E 选项=" + hasLastOption + " 行数=" + inside.size + "）"))

        val text = OcrText.clean(despace(inside.joinToString("\n") { it.text })).trim()
        val outcome = matcher.match(text, limit = 3)
        val best = outcome.best
        if (best == null) {
            println("  匹配：一条候选都没有（展示线 " + (Similarity.DEFAULT_THRESHOLD * 100).toInt() + "%）")
        } else {
            val good = outcome.confident
            if (good) hit++
            println(
                "  匹配：" + outcome.hits.size + " 条，最高 " + pct(best.score) + "%  confident=" + good +
                    "  margin=" + pct(outcome.margin) + "  → " + best.entry.stem.take(34),
            )
            // 分数够高却没采信时，把理由打出来 —— 「看起来明明对上了，为什么还去问 AI」就靠这一句
            outcome.hesitation?.let { println("        没采信的理由：" + it) }
        }
    }
    println("")
    println("=== 汇总：看了 " + (if (only != null) 1 else shots.size) + " 张，框出 " + boxed + " 张，形态像样 " + ok + " 张，匹配采信 " + hit + " 张")
}

private fun pct(v: Float): Int = (v * 100).toInt()

private fun pct(v: Double): Int = (v * 100).toInt()

private fun isCjk(ch: Char): Boolean =
    ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF
