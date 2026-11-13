import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else ".")
    val stems = File(dir, "bank_stems.txt").readLines().filter { it.isNotBlank() }
    val target = stems.first { it.contains("本规程适用于（）铁路集团") }
    println("题库题数: " + stems.size)

    val matcher = StemMatcher(stems.mapIndexed { i, s -> StemEntry(i.toLong(), 1L, s) })

    val cases = listOf(
        "全屏截图 1080x2400（真实 ML Kit 原文）" to "ocr_real.txt",
        "框到题干 + 四个选项" to "ocr_tight_real.txt",
        "只框到题干" to "ocr_stemonly.txt",
    )
    for ((label, file) in cases) {
        val text = File(dir, file).readText()
        val t0 = System.nanoTime()
        val outcome = matcher.match(text, limit = 5)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val best = outcome.best
        val percent = ((best?.score ?: 0.0) * 1000).toInt() / 10.0
        println("")
        println("------ " + label + " ------")
        println("  耗时 " + "%.1f".format(ms) + " ms   命中 " + outcome.hits.size + " 条")
        outcome.hits.forEachIndexed { i, h ->
            println("  " + (i + 1) + ". " + "%.1f%%".format(h.score * 100) + "  " + h.entry.stem.take(40))
        }
        println("  Top1 是本题: " + (best?.entry?.stem == target))
        println("  与第二道不同的题相差: " + "%.1f%%".format(outcome.margin * 100))
        println("  入库答案置信 confident = " + outcome.confident + " , ambiguous = " +
            (best != null && best.score >= Similarity.MATCH_THRESHOLD && outcome.margin < Similarity.MATCH_MARGIN))
    }

    // 性能：把整库题干预热成「全屏截图」形态，逐条匹配一遍，看单次耗时
    val noisy = stems.mapIndexed { i, s ->
        listOf(
            "个", "A国家", "单送1、" + s.take(20), "2026年10月02日",
            "B中国国家", "C合资", "D控服合资", "发送", "22:55", "編辑", "删除", "更多",
        ).joinToString("\n")
    }
    var worst = 0L
    var total = 0L
    for (n in noisy) {
        val t = System.nanoTime()
        matcher.match(n, limit = 5)
        val d = System.nanoTime() - t
        total += d
        if (d > worst) worst = d
    }
    println("")
    println("性能: 全库逐条匹配 " + noisy.size + " 次，平均 " + (total / noisy.size / 1_000_000.0) + " ms，最慢 " + (worst / 1_000_000.0) + " ms")
}
