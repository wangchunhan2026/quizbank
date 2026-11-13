package probe

import com.daily.quizbank.util.Similarity
import java.io.File

private fun bigrams(text: String): Set<String> {
    if (text.length < 2) return emptySet()
    val out = HashSet<String>(text.length)
    for (i in 0 until text.length - 1) out.add(text.substring(i, i + 2))
    return out
}

/**
 * 每个二字组「出现在多少条题干里」的分布 —— 用来看「够不够特别」这条线该划在哪。
 * 另一件事同样重要：真命中时，最短的那条边（题库题干）里最罕见的二字组 DF 是多少。
 * 他的库里有同一道题的多个副本，副本越多 DF 越大，线划得太紧会把真命中一起挡掉。
 */
fun main(args: Array<String>) {
    for (path in args) {
        val lines = File(path).readLines().filter { it.isNotBlank() }
        val keys = lines.map { Similarity.normalize(it.substringBefore('\t')) }
        val grams = keys.map { bigrams(it) }
        val df = HashMap<String, Int>()
        for (set in grams) for (g in set) df[g] = (df[g] ?: 0) + 1

        println("")
        println("=== " + File(path).name + "  条目 " + lines.size + " ===")
        val buckets = sortedMapOf<Int, Int>()
        for (set in grams) {
            val minDf = set.minOfOrNull { df[it] ?: 0 } ?: 0
            val key = if (minDf > 10) 11 else minDf
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        println("  每条题干里「最罕见的二字组」的 DF 分布：")
        buckets.forEach { (k, v) ->
            val label = if (k == 11) ">10" else k.toString()
            println("    DF=" + label.padEnd(4) + " " + v + " 条")
        }
    }
}
