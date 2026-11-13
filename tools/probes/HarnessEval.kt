import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

class Report(val label: String) {
    var hit = 0
    var wrong = 0
    var fallback = 0
    var dup = 0
    var ambiguous = 0
    var total = 0
    val hes = HashMap<String, Int>()
    val wrongExamples = ArrayList<String>()
    fun done(): String {
        fun p(v: Int) = "%.1f".format(v * 100.0 / maxOf(total, 1))
        return "  " + label.padEnd(16) + " 命中 " + p(hit) + "%  错答 " + p(wrong) +
            "%  退回AI " + p(fallback) + "%   (n=" + total + ")"
    }
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val bankFile = File(dir, args[1])
    val mode = args[2]
    val step = args[3].toInt()

    val lines = bankFile.readLines().filter { it.isNotBlank() }
    val texts = lines.map { it.replace('\t', ' ') }
    val matcher = StemMatcher(texts.mapIndexed { i, s -> StemEntry(i.toLong(), 1L, s) })
    println("题库 " + bankFile.name + "  条目 " + texts.size + "  模式 " + mode)

    val reps = listOf(
        "整题" to Report("整题"),
        "3/4" to Report("前 3/4"),
        "1/2" to Report("前 1/2"),
        "20字" to Report("前 20 字"),
        "16字" to Report("前 16 字"),
        "12字" to Report("前 12 字"),
        "10字" to Report("前 10 字"),
        "8字" to Report("前 8 字"),
        "6字" to Report("前 6 字"),
        "5字" to Report("前 5 字"),
        "4字" to Report("前 4 字"),
        "中10" to Report("中间 10 字"),
        "中8" to Report("中间 8 字"),
    )

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val q = if (mode == "collected") raw.substringBefore('\t') else raw
        val n = q.length
        fun cut(k: Int) = q.substring(0, minOf(k, n))
        val mid = n / 3
        fun mid(k: Int) = q.substring(mid, minOf(mid + k, n))
        val queries = listOf(
            q, cut(n * 3 / 4), cut(n / 2), cut(20), cut(16), cut(12),
            cut(10), cut(8), cut(6), cut(5), cut(4), mid(10), mid(8),
        )
        queries.forEachIndexed { idx, query ->
            if (query.isBlank()) return@forEachIndexed
            val rep = reps[idx].second
            val outcome = matcher.match(query, limit = 5)
            rep.total++
            val best = outcome.best
            if (outcome.confident && best != null) {
                // 题库里有大量只差一个标点的重复题（normalize 之后完全一样），
                // 那不是「答错」，只是答到了同一道题的另一份拷贝上
                val 同题 = Similarity.normalize(texts[best.entry.id.toInt()]) == Similarity.normalize(texts[i])
                if (同题) rep.hit++ else {
                    rep.wrong++
                    if (rep.wrongExamples.size < 3) {
                        rep.wrongExamples.add(
                            "    [" + rep.label + "] 问=" + query +
                                "\n        答=" + texts[best.entry.id.toInt()] +
                                "\n        应=" + texts[i] + "   " + "%.3f".format(best.score) +
                                "\n        runnerUp=" + (outcome.runnerUp?.entry?.let { texts[it.id.toInt()] } ?: "无") +
                                "  margin=" + "%.3f".format(outcome.margin))
                    }
                }
            } else {
                rep.fallback++
                val h = outcome.hesitation
                if (h != null && best != null && best.score >= Similarity.MATCH_THRESHOLD) {
                    rep.ambiguous++
                    rep.hes[h] = (rep.hes[h] ?: 0) + 1
                }
            }
        }
        i += step
    }

    for ((_, r) in reps) {
        println(r.done())
        r.wrongExamples.forEach { println(it) }
        if (r.hes.isNotEmpty()) r.hes.forEach { (k, v) -> println("      · 不敢采信(" + v + "): " + k) }
    }
}
