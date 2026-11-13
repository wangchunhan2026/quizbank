import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.import.XlsReader
import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemMatcher
import java.io.File

/**
 * 拿一份 .xls/.xlsx 题库做**全库自匹配**：每条题拿它自己（题干 / 题干+选项）去搜整个库，
 * 看能不能命中自己、会不会答错。
 *
 * 这就是「用户导入了新题库，某道题拍题搜题都说没有」这类报障的**批量体检** ——
 * 只测他报的那一道是不够的，得看整个库的命中率。
 *
 * 用法：XlsSelfMatchProbeKt <题库文件> [--no-options]
 *   --no-options 模拟**修之前**的行为（短条目不把选项带进索引），用来量差异。
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: return
    val withOptions = !args.contains("--no-options")
    // --noise N：把 N% 的字符换成别的汉字，模拟 ML Kit 的认错字
    val noise = args.indexOf("--noise").let { if (it >= 0) args.getOrNull(it + 1)?.toIntOrNull() ?: 0 else 0 }

    val sheets = XlsReader.readXls(File(path).readBytes())
    val drafts = sheets.flatMap { QuestionParser.parse(it.rows).drafts }
    println("题库: " + File(path).name + "  " + drafts.size + " 道（" + sheets.size + " 个工作表）")
    println("短条目带选项进索引: " + withOptions)

    val entries = drafts.mapIndexed { i, d ->
        StemEntry(
            id = i.toLong() + 1,
            bankId = 1L,
            stem = d.stem,
            options = if (withOptions && StemMatcher.isShortEntry(d.stem)) d.options else emptyList(),
        )
    }
    val matcher = StemMatcher(entries)
    val shortCount = drafts.count { StemMatcher.isShortEntry(it.stem) }
    println("其中短条目（归一化 < 30 字，判据会管到它们）: " + shortCount + " / " + drafts.size)

    fun key(s: String) = Similarity.normalize(s)

    /** 模拟 OCR 认错字：固定种子，可复现。 */
    fun noisy(text: String): String {
        if (noise <= 0) return text
        val rnd = java.util.Random(20261027L)
        val sb = StringBuilder(text.length)
        val pool = "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞"
        for (ch in text) {
            sb.append(if (ch.isLetter() && rnd.nextInt(100) < noise) pool[rnd.nextInt(pool.length)] else ch)
        }
        return sb.toString()
    }

    fun run(label: String, build: (com.daily.quizbank.data.import.QuestionDraft) -> String) {
        var hit = 0
        var hitButNotFirst = 0
        var unsure = 0        // 是自己的那条，但不敢采信
        var wrong = 0         // 采信了别的题 —— 最危险
        var none = 0          // 一条候选都没有
        val problems = ArrayList<String>()
        val unsureReasons = HashMap<String, Int>()

        for (d in drafts) {
            val input = build(d)
            if (input.isBlank()) continue
            val out = matcher.match(input, limit = 5)
            val best = out.best
            val selfKey = key(d.stem)
            when {
                best == null -> { none++; if (problems.size < 12) problems.add("无候选 | " + d.stem.take(40)) }
                out.confident && key(best.entry.stem) == selfKey -> hit++
                out.confident -> {
                    wrong++
                    if (problems.size < 12) problems.add("错答→" + best.entry.stem.take(30) + " | " + d.stem.take(40))
                }
                key(best.entry.stem) == selfKey -> {
                    unsure++
                    val r = out.hesitation ?: "分数不够"
                    unsureReasons[r] = (unsureReasons[r] ?: 0) + 1
                    if (problems.size < 12) problems.add("不敢采信(" + pct(best.score) + ") " + r + " | " + d.stem.take(30))
                }
                else -> {
                    // 分数不到 0.80 的近似命中：不算错答，但记一笔
                    unsure++
                    val r = "分数不够的近似：" + best.entry.stem.take(20)
                    unsureReasons[r] = (unsureReasons[r] ?: 0) + 1
                    if (problems.size < 12) problems.add("近似(" + pct(best.score) + ")→" + best.entry.stem.take(24) + " | " + d.stem.take(30))
                }
            }
        }
        val n = drafts.size
        println("")
        println("======== " + label + " ========")
        println("  采信且是它自己 : " + hit + " / " + n + "  (" + (hit * 100 / n) + "%)")
        println("  ✗ 错答（采信了别的题）: " + wrong)
        println("  · 是自己那条但不敢采信: " + unsure)
        println("  · 一条候选都没有    : " + none)
        if (unsureReasons.isNotEmpty()) {
            println("    不敢采信的原因:")
            unsureReasons.entries.sortedByDescending { it.value }.take(5).forEach { println("      " + it.value + " × " + it.key) }
        }
        if (problems.isNotEmpty()) {
            println("    样例:")
            problems.forEach { println("      " + it) }
        }
    }

    val suffix = if (noise > 0) "（掺 " + noise + "% OCR 错字）" else ""
    // ① 拍题形态：题干 + 全部选项（屏幕上就是一整块）
    run("拍题形态（题干 + 全部选项）" + suffix) { d -> noisy(d.stem + "\n" + d.options.joinToString("\n")) }
    // ② 搜题形态：只打题干（不掺噪声时才跑，噪声版只跑拍题形态）
    if (noise <= 0) run("搜题形态（只粘题干）") { d -> d.stem }
}

private fun pct(v: Double): String = ((v * 1000).toInt() / 10).toString() + "%"
