import com.daily.quizbank.util.Similarity
import java.io.File

fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else ".")
    val stems = File(dir, "bank_stems.txt").readLines().filter { it.isNotBlank() }
    val target = stems.first { it.contains("本规程适用于（）铁路集团") }
    println("题库题数: " + stems.size)

    fun report(label: String, text: String) {
        val scored = stems.map { it to Similarity.score(text, it) }.sortedByDescending { it.second }
        val rank = scored.indexOfFirst { it.first == target } + 1
        println("")
        println("------ " + label + " ------")
        println("输入字符数=" + text.length + "  归一化后=" + Similarity.normalize(text).length)
        println("Top5:")
        scored.take(5).forEachIndexed { i, p ->
            println("  " + (i + 1) + ". " + "%.1f%%".format(p.second * 100) + "  " + p.first.take(46))
        }
        println("正确答案排名=" + rank + "  得分=" + "%.1f%%".format(scored[rank - 1].second * 100))
    }

    report("全屏截图 OCR（1080x2400，真实 ML Kit 输出）", File(dir, "ocr_real.txt").readText())
    report("紧贴题干裁剪（理想情况）", File(dir, "ocr_tight_real.txt").readText())
}
