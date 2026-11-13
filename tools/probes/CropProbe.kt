import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher

/** 试几种「库里那条」的可能形态，看哪种能同时解释「拍题 0 条」和「收录时又说很像」。 */
fun main() {
    val 整段 = "2004U4-10 骨囊肿好发于\n。A.长管骨千骶端\nB.长管骨骨端\nC.长管骨干部\n" +
        "D.短管骨骨端\nE.短管骨骨干部"
    val 题干 = "2004U4-10 骨囊肿好发于"

    val 候选库 = linkedMapOf(
        "① 短条目：2010U3-11 骨囊肿好发于" to "2010U3-11 骨囊肿好发于",
        "② 短条目带选项：2010U3-11 骨囊肿好发于+选项" to
            "2010U3-11 骨囊肿好发于\nA.长管骨干骶端\nB.长管骨骨端\nC.长管骨干部\nD.短管骨骨端\nE.短管骨骨干部",
        "③ 同题号短条目：2004U4-10 骨囊肿好发于" to "2004U4-10 骨囊肿好发于",
        "④ 材料+小问：63岁男性…+2004U4-10 骨囊肿好发于" to
            "63岁男性,进行性右侧睾丸肿大一年,无疼痛,行走不便。查体:睾丸6cmx5cmx4cm。\n\n2004U4-10 骨囊肿好发于",
    )

    for ((名字, 库) in 候选库) {
        val m = StemMatcher(listOf(StemEntry(1, 1, 库)))
        val 长 = m.match(整段)
        val 短 = m.match(题干)
        println(名字)
        println("   拍题(整段)  命中 " + 长.hits.size + " 条 margin=" + "%.2f".format(长.margin) +
            " genericOnly=" + 长.genericOnly + "  分数=" + 长.hits.joinToString { "%.2f".format(it.score) })
        println("   收录(只题干) 命中 " + 短.hits.size + " 条 margin=" + "%.2f".format(短.margin) +
            " genericOnly=" + 短.genericOnly + "  分数=" + 短.hits.joinToString { "%.2f".format(it.score) })
        println("   matchScore(整段→库)=" + "%.2f".format(Similarity.matchScore(整段, 库)) +
            "   matchScore(题干→库)=" + "%.2f".format(Similarity.matchScore(题干, 库)))
    }
}
