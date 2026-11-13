import com.daily.quizbank.util.Similarity

/**
 * 量「残缺条目不许靠一句通用问法对上」那条判据里的**覆盖率**：
 * 这条线（SHORT_ENTRY_COVERAGE = 0.85）现在把一个真命中给挡掉了，先量清楚再动它。
 */
fun coverage(entry: String, observed: String): Double {
    val k = Similarity.normalize(entry)
    val o = Similarity.normalize(observed)
    val kg = Similarity.bigramsOf(k)
    val og = Similarity.bigramsOf(o)
    if (kg.isEmpty()) return 1.0
    return kg.count { it in og }.toDouble() / kg.size
}

fun main() {
    val 新题 = "男,38岁。右小腿被铁钉刺伤3天,伤口周围红肿热痛,今日出现张口困难、苦笑面容。查体:神志清,颈项强直。\n\n(1.0分)最可能的诊断是"
    val 真机OCR = "2004U4-10 骨囊肿好发于\n。A.长管骨千骶端\nB.长管骨骨端\nC.长管骨干部\nD.短管骨骨端\nE.短管骨骨干部"

    val cases = listOf(
        "① 残缺条目（该挡掉的那次报障）" to ("2021U2-106 可能的诊断是" to 新题),
        "② 残缺条目 2：为明确诊断应采取…" to ("2021U2-107 为明确诊断应采取的检查方法是" to 新题),
        "③ 残缺条目 3：目前最重要的治疗方法是" to ("2008U4-150 目前最重要的治疗方法是" to 新题),
        "④ 真机那条：库里「骨囊好发于」少了个肿" to ("2004U4-10 骨囊好发于" to 真机OCR),
        "⑤ 真机那条：库里是全对的题干" to ("2004U4-10 骨囊肿好发于" to 真机OCR),
        "⑥ 同一道短题、输入是纯题干（收录那条路）" to ("2004U4-10 骨囊好发于" to "2004U4-10 骨囊肿好发于"),
    )
    for ((label, pair) in cases) {
        val (entry, obs) = pair
        val normEntry = Similarity.normalize(entry)
        println(
            label + "\n   条目 " + normEntry.length + " 字、输入 " + Similarity.normalize(obs).length +
                " 字（输入/条目 = " + "%.2f".format(Similarity.normalize(obs).length.toDouble() / normEntry.length) +
                "）覆盖率 = " + "%.3f".format(coverage(entry, obs)) +
                "  相似度 = " + "%.3f".format(Similarity.matchScore(obs, entry)),
        )
    }
}
