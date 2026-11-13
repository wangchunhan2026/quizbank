import com.daily.quizbank.util.FragmentRepair
import com.daily.quizbank.util.Similarity
import java.io.File

/**
 * 「为什么只有短题会丢」——按题干长度分档，用**2.15.0 修之前那条判据**重算一遍。
 *
 * 当年（2.12.0~2.14.0）的判据长这样，缺了最后那句「新收的必须比旧的更长」：
 *
 *   ① looksLikeFragment(旧)  = 归一化后不到 30 字
 *   ② 问法 ≥ 4 字
 *   ③ 归一化(新) ≥ 问法长度 × 1.5
 *   ④ 问法的字组在新题干里覆盖 ≥ 80%
 *
 * 自己跟自己比时 ④ 恒成立、② 是常事，只卡 ① 和 ③ —— 而 ③ 正是「短题 + 长题号」最容易成立的那条。
 */
private fun 旧判据自顶自(stem: String): Boolean {
    if (!FragmentRepair.looksLikeFragment(stem)) return false
    val ask = FragmentRepair.askOf(stem)
    if (ask.length < 4) return false
    val fresh = Similarity.normalize(stem)
    if (fresh.length < ask.length * 1.5) return false
    val askGrams = Similarity.bigramsOf(ask)
    if (askGrams.isEmpty()) return false
    val freshGrams = Similarity.bigramsOf(fresh)
    return askGrams.count { it in freshGrams }.toDouble() / askGrams.size >= 0.8
}

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: return
    val lines = File(path).readLines().filter { it.isNotBlank() }
    val buckets = linkedMapOf(
        "归一化 < 15 字" to intArrayOf(0, 0),
        "15 ~ 19 字" to intArrayOf(0, 0),
        "20 ~ 24 字" to intArrayOf(0, 0),
        "25 ~ 29 字" to intArrayOf(0, 0),
        "30 字以上（长题干）" to intArrayOf(0, 0),
    )
    var 会丢 = 0
    val 样例 = ArrayList<String>()
    for (line in lines) {
        val stem = line.substringBefore('\t').replace("\\n", "\n")
        if (stem.isBlank()) continue
        val n = Similarity.normalize(stem).length
        val key = when {
            n < 15 -> "归一化 < 15 字"
            n < 20 -> "15 ~ 19 字"
            n < 25 -> "20 ~ 24 字"
            n < 30 -> "25 ~ 29 字"
            else -> "30 字以上（长题干）"
        }
        buckets[key]!![0]++
        if (旧判据自顶自(stem)) {
            buckets[key]!![1]++
            会丢++
            if (样例.size < 8) {
                样例.add("      " + n + " 字｜问法 " + FragmentRepair.askOf(stem).length + " 字｜" +
                    stem.replace("\n", " ").take(30))
            }
        }
    }
    println("题库共 " + lines.size + " 道，按旧判据会被自己顶掉的：" + 会丢 + " 道")
    println("")
    for ((k, v) in buckets) {
        println("  " + k.padEnd(22) + " 共 " + v[0].toString().padStart(3) + " 道，会自顶自 " +
            v[1].toString().padStart(2) + " 道" +
            (if (v[0] > 0) "  占 " + (v[1] * 100 / v[0]) + "%" else ""))
    }
    println("")
    println("长题干那 103 道为什么一道都没事：判据第一关就是")
    println("「归一化后不到 30 字才算『半截』」—— 长题干连候选都进不去。")
    println("")
    println("会丢的样例（题号越长、题干越短，越容易中）：")
    样例.forEach(::println)
}
