package probe

import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

private fun load(path: String): List<StemEntry> =
    File(path).readLines().filter { it.isNotBlank() }.mapIndexed { index, line ->
        StemEntry(index.toLong(), 0L, line.substringBefore('\t').replace("\\n", "\n"))
    }

fun main(args: Array<String>) {
    val all = load(args[0])
    val matcher = StemMatcher(all)
    println("题库 " + all.size + " 条")

    fun line(title: String, query: String) {
        val outcome = matcher.match(query, limit = 4, threshold = Similarity.DEFAULT_THRESHOLD)
        val top = outcome.hits.firstOrNull()
        println(
            "  " + title.padEnd(26) +
                (if (top == null) "没有候选" else (top.score * 100).toInt().toString() + "% 可信=" + outcome.confident +
                    "  " + top.entry.stem.replace("\n", " / ").take(38)),
        )
    }

    line("新题(最可能的诊断是)", "男,38岁。右小腿被铁钉刺伤3天,伤口周围红肿热痛,今日出现张口困难、苦笑面容。查体:神志清,颈项强直,角弓反张。\n\n(1.0分)最可能的诊断是")
    line("新题(明确诊断检查方法)", "男,50岁。反复上腹痛3年,加重1周。查体:上腹压痛,无反跳痛。胃镜示胃窦部溃疡,边缘整齐。\n\n(1.0分)为明确诊断应采取的检查方法是")
    line("搜「烧伤分度」", "烧伤分度")
    line("搜「青霉素」", "青霉素")

    var ok = 0
    val lost = ArrayList<String>()
    for (entry in all) {
        val outcome = matcher.match(entry.stem, limit = 3, threshold = Similarity.DEFAULT_THRESHOLD)
        if (outcome.confident) ok++ else lost.add(entry.stem.replace("\n", " / ").take(40))
    }
    println("  自匹配仍然可信 " + ok + " / " + all.size)
    lost.forEach { println("    丢了: " + it) }

    // 诊断：那条短条目里，DF 最小的几个字组，以及它们在不在新题里
    val keys = all.map { Similarity.normalize(it.stem) }
    val grams = keys.map { Similarity.bigramsOf(it) }
    val df = HashMap<String, Int>()
    for (set in grams) for (g in set) df[g] = (df[g] ?: 0) + 1
    val idx = all.indexOfFirst { it.stem.contains("2021U2-107") }
    val query = Similarity.normalize("男,50岁。反复上腹痛3年,加重1周。\n\n(1.0分)为明确诊断应采取的检查方法是")
    val qGrams = Similarity.bigramsOf(query)
    println("  诊断「2021U2-107 …」这条：" + keys[idx])
    grams[idx].map { it to (df[it] ?: 0) }.sortedBy { it.second }.take(12).forEach {
        println("    " + it.first + " DF=" + it.second + (if (it.first in qGrams) "  在输入里" else "  不在输入里"))
    }
}
