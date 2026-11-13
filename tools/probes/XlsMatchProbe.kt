import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.import.XlsReader
import com.daily.quizbank.util.OcrText
import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

/**
 * 「用户导入了某个 .xls 题库，某道题拍题/搜题都说没有」的复现工具。
 *
 * 做三件事：① 用 App 自己的解析链路把 .xls 解析成题目列表（= 库里会长什么样）；
 * ② 拿真 OCR 原文 / 只粘题干 / 题干+选项 三种输入分别跑 StemMatcher；
 * ③ 把「通用问法」那条判据依赖的 DF 也算出来，看它是不是被那条判据挡掉的。
 *
 * 用法：XlsMatchProbeKt <xls> <桌面OCR.txt> [题干关键词]
 */
fun main(args: Array<String>) {
    val xlsPath = args[0]
    val ocrPath = args.getOrNull(1)
    val keyword = args.getOrNull(2) ?: "降雪会商"

    val sheets = XlsReader.readXls(File(xlsPath).readBytes())
    val drafts = sheets.flatMap { QuestionParser.parse(it.rows).drafts }
    val stems = drafts.map { it.stem }
    println("题库: " + stems.size + " 道（" + sheets.size + " 个工作表：" +
        sheets.joinToString("、") { it.name + " " + QuestionParser.parse(it.rows).drafts.size } + "）")

    val target = drafts.firstOrNull { it.stem.contains("决策内容主要包括") }
        ?: drafts.filter { it.stem.contains(keyword) && it.stem.length <= 40 }.minByOrNull { it.stem.length }
    println("目标条目: " + target?.stem + "  (选项 " + (target?.options?.size ?: 0) + " 个, 答案 " + target?.answer + ")")

    // 和仓库层建索引的方式保持一致：**短条目**把选项一起带上（判「是不是同一道题」要用）
    val matcher = StemMatcher(drafts.mapIndexed { i, d ->
        StemEntry(i.toLong(), 1L, d.stem, if (StemMatcher.isShortEntry(d.stem)) d.options else emptyList())
    })

    fun report(label: String, raw: String) {
        val input = OcrText.clean(raw).trim()
        val out = matcher.match(input, limit = 5)
        println("")
        println("======== " + label + " ========")
        println("  输入归一化后 " + Similarity.normalize(input).length + " 字")
        println("  命中 " + out.hits.size + " 条 | margin=" + pct(out.margin) +
            " | confident=" + out.confident + " | ambiguous=" + out.ambiguous +
            " | genericOnly=" + out.genericOnly)
        out.hesitation?.let { println("  ✗ 没采信的理由: " + it) }
        out.hits.take(5).forEachIndexed { i, h -> println("   " + (i + 1) + ". " + pct(h.score) + "  " + h.entry.stem.take(50)) }
        if (out.hits.isEmpty()) {
            out.probe.take(3).forEachIndexed { i, h -> println("   (没到展示线) " + (i + 1) + ". " + pct(h.score) + "  " + h.entry.stem.take(50)) }
        }
        if (target != null) {
            val d = Similarity.matchDetail(Similarity.normalize(input), Similarity.normalize(target.stem))
            println("  ▸ 对目标条目: score=" + pct(d.score) + " fromQuery=" + d.fromQuery +
                " 对上的那段=" + d.region.take(40))
            println("     判「问法不一样」= " + Similarity.looksLikeDifferentWording(d, Similarity.normalize(target.stem)))
        }
    }

    // ① 搜题：只粘题干（用户最可能的用法）
    target?.let { report("搜题 · 只粘题干", it.stem) }

    // ② 搜题：题干 + 选项整块（从别处复制过来常见）
    target?.let { t -> report("搜题 · 题干+全部选项", t.stem + "\n" + t.options.joinToString("\n")) }

    // ③ 拍题：整屏 OCR 原文
    if (ocrPath != null && File(ocrPath).exists()) {
        val lines = File(ocrPath).readLines()
            .mapNotNull { line ->
                val m = Regex("""^\s*\d+\s+\d+\s{2}(.*)$""").find(line)
                m?.groupValues?.get(1)
            }
        val despace = lines.joinToString("\n") { despace(it) }
        report("拍题 · 整屏 OCR（" + lines.size + " 行）", despace)
        File(System.getProperty("java.io.tmpdir"), "xls-ocr-input.txt").writeText(despace)
    }

    // ④ 「通用问法」判据依赖的 DF：目标条目的每个字组在库里出现几次
    if (target != null) {
        val grams = Similarity.bigramsOf(Similarity.normalize(target.stem))
        val df = HashMap<String, Int>()
        for (s in stems) {
            val g = Similarity.bigramsOf(Similarity.normalize(s))
            for (x in grams) if (g.contains(x)) df[x] = (df[x] ?: 0) + 1
        }
        println("")
        println("======== 目标条目各字组在本库里的 DF（出现它的题数）========")
        println("  " + df.entries.sortedBy { it.key }.joinToString("  ") { it.key + "=" + it.value })
        println("  DF ≤ 1 的字组个数: " + df.values.count { it <= 1 } + " / " + grams.size)
        println("  （「通用问法」判据在「整条都盖住」之后，要求存在 DF≤1 的字组；全是 DF≥2 就会被判成通用问法）")
    }
}

private fun pct(v: Double): String = (v * 1000).toInt().let { (it / 10).toString() + "." + (it % 10) + "%" }

private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

/** Windows OCR 在汉字之间插空格，ML Kit 不插 —— 去掉，免得分数被稀释。 */
private fun despace(text: String): String {
    val out = StringBuilder(text.length)
    for ((i, ch) in text.withIndex()) {
        if (ch == ' ' || ch == '\u3000') {
            val prev = out.lastOrNull()
            val next = text.getOrNull(i + 1)
            if (prev != null && next != null && (isCjk(prev) || isCjk(next))) continue
        }
        out.append(ch)
    }
    return out.toString()
}
