import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else ".")
    val stems = File(dir, "bank_stems.txt").readLines().filter { it.isNotBlank() }
    val matcher = StemMatcher(stems.mapIndexed { i, s -> StemEntry(i.toLong(), 1L, s) })
    val files = dir.listFiles { f -> f.isFile && f.name.startsWith("ocr_") && f.name.endsWith(".txt") }!!.sortedBy { it.name }
    for (f in files) {
        val outcome = matcher.match(f.readText(), limit = 3)
        val best = outcome.best
        println("")
        println("=== " + f.name + " ===")
        println("  Top1: " + "%.1f%%".format((best?.score ?: 0.0) * 100) + "  " + (best?.entry?.stem ?: "无"))
        outcome.hits.forEachIndexed { i, h -> println("    " + (i + 1) + ". " + "%.1f%%".format(h.score * 100) + " " + h.entry.stem) }
        println("  margin=" + "%.1f%%".format(outcome.margin * 100) + "  confident=" + outcome.confident)
    }
}
