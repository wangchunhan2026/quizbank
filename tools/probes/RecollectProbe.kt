package probe

import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

/** 题库文件一行：题干 TAB 选项 TAB 答案，换行写成 \n。 */
private class Row(val stem: String, val options: String, val answer: String)

private fun load(path: String): List<Row> =
    File(path).readLines().filter { it.isNotBlank() }.map {
        val parts = it.split('\t')
        Row(
            parts.getOrElse(0) { "" }.replace("\\n", "\n"),
            parts.getOrElse(1) { "" }.replace("\\n", "\n"),
            parts.getOrElse(2) { "" },
        )
    }

fun main(args: Array<String>) {
    val rows = load(args[0])
    val all = rows.mapIndexed { i, r -> StemEntry(i.toLong(), 0L, r.stem) }
    val matcher = StemMatcher(all)
    println("题库 " + rows.size + " 条")

    // 模拟「重新收这一道题」：截到的画面里既有题干也有选项（真实拍题就是这个形态）
    var ok = 0
    val lost = ArrayList<String>()
    rows.forEachIndexed { i, row ->
        val query = if (row.options.isBlank()) row.stem else row.stem + "\n" + row.options
        val outcome = matcher.match(query, limit = 3, threshold = Similarity.DEFAULT_THRESHOLD)
        val hit = outcome.hits.firstOrNull()
        // 只要正确那一条还在候选里就算没误伤（confident 还要看 margin，副本多的题本来就会犹豫）
        if (hit != null && Similarity.normalize(hit.entry.stem) == Similarity.normalize(row.stem)) {
            ok++
        } else {
            lost.add(
                row.stem.replace("\n", " / ").take(40) + "   → " +
                    (hit?.let { (it.score * 100).toInt().toString() + "% " + it.entry.stem.replace("\n", " / ").take(28) } ?: "没有候选"),
            )
        }
    }
    println("  拿「题干+选项」去搜，正确那条仍在候选里: " + ok + " / " + rows.size)
    lost.take(20).forEach { println("    丢了: " + it) }
}
