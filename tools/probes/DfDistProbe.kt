import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.import.XlsReader
import com.daily.quizbank.util.Similarity
import java.io.File

/**
 * 量一个题库的「字组 DF 分布」，用来判断 StemMatcher.looksGenericOnly 那个
 * 「DF ≤ 1 才算特有」的门槛在**这种题库**上是不是全都过不去。
 *
 * 用法：DfDistProbeKt <xls | medstems.txt> [题干关键词]
 */
fun main(args: Array<String>) {
    val path = args[0]
    val keyword = args.getOrNull(1)
    val stems = if (path.endsWith(".xls")) {
        val sheets = XlsReader.readXls(File(path).readBytes())
        sheets.flatMap { QuestionParser.parse(it.rows).drafts }.map { it.stem }
    } else {
        File(path).readLines().filter { it.isNotBlank() }
            .map { it.trim('"').split('\t').first().replace("\\n", " ").trim() }
    }
    println("题库: " + stems.size + " 道")

    val gramOf = stems.map { Similarity.bigramsOf(Similarity.normalize(it)) }
    val df = HashMap<String, Int>()
    for (g in gramOf) for (x in g) df[x] = (df[x] ?: 0) + 1

    // 全库 DF 分布：有多少字组出现 1 次、2 次、3 次以上
    val buckets = HashMap<String, Int>()
    for (v in df.values) {
        val k = if (v >= 10) "10+" else v.toString()
        buckets[k] = (buckets[k] ?: 0) + 1
    }
    println("字组 DF 分布（DF=出现它的题数）: " + buckets.entries.sortedBy { it.key }.joinToString("  ") { it.key + ":" + it.value })
    println("字组总数: " + df.size + "，中位 DF 附近: DF≤1 占 " +
        (df.values.count { it <= 1 } * 100 / df.size) + "%，DF≤2 占 " +
        (df.values.count { it <= 2 } * 100 / df.size) + "%")

    // 对每个候选条目：它的字组里有没有 DF ≤ 1 / ≤ 2 / ≤ 3 的
    for (cutoff in listOf(1, 2, 3)) {
        var couldBeGuarded = 0
        for (g in gramOf) {
            if (g.isEmpty()) continue
            if (g.none { (df[it] ?: 0) <= cutoff }) couldBeGuarded++
        }
        println("  门槛 DF ≤ " + cutoff + "：全库有 " + couldBeGuarded + " 条题的每个字组都「不稀有」（=会被判通用问法）")
    }

    if (keyword != null) {
        println("")
        val target = stems.firstOrNull { it.contains(keyword) }
        if (target == null) { println("没找到含「" + keyword + "」的条目"); return }
        val grams = Similarity.bigramsOf(Similarity.normalize(target))
        println("目标条目: " + target)
        println("  归一化: " + Similarity.normalize(target) + "（" + Similarity.normalize(target).length + " 字）")
        println("  各字组 DF: " + grams.sorted().joinToString("  ") { it + "=" + (df[it] ?: 0) })
        println("  最小 DF: " + grams.minOf { df[it] ?: 0 } + "，最大 DF: " + grams.maxOf { df[it] ?: 0 })
    }
}
