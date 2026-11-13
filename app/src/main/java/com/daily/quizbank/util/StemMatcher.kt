package com.daily.quizbank.util

import com.daily.quizbank.util.OcrText

/**
 * 参与匹配的一条题干。只带匹配需要的字段，因此可以脱离 Room 做单元测试。
 *
 * [options] **只给「短题干」的条目填**（由调用方过滤，见 StemMatcher.isShortEntry）：
 * 它的用处只有一个 —— 判「这次命中是不是同一道题」时看输入里有没有这条题自己的选项。
 * 全长题干不需要它，几千道题的选项全塞进索引会白白多吃几个 MB。
 */
data class StemEntry(
    val id: Long,
    val bankId: Long,
    val stem: String,
    val options: List<String> = emptyList(),
)

/** 一次命中：哪条题干、多少分。 */
data class StemHit(val entry: StemEntry, val score: Double)

/**
 * 一次匹配的完整结果。
 *
 * 除了命中列表，还带上两样判断「能不能直接用题库答案」要用的东西：
 *
 *  - [margin] / [runnerUp]：分数最高的那条，和「分数最高的、题干不同的另一条」差多少。
 *  - [hesitation]：不能采信时，一句给人看的理由。
 *
 * 分数高只说明「文字像」，不说明「就是这道题」。题库里可能有只差一个字的近似题，
 * 也可能用户拍到的题根本不在题库里、只是问法相近（判断题加一个「没有」就换个答案）。
 * 这两种情况都必须退回 AI。
 */
data class MatchOutcome(
    val hits: List<StemHit>,
    val margin: Double,
    val runnerUp: StemHit?,
    val hesitation: String?,
    /**
     * 被「只是一句通用问法」这条判据挡掉的候选有几条。
     *
     * 不摆出来给用户看，但日志里要有个交代 —— 否则「明明看到一条相近的题，
     * 面板却说题库里没有」会让人以为匹配坏了。
     */
    val genericOnly: Int = 0,
    /**
     * **只给日志用**：分数最高的几条候选，**包括没到展示线的那些**。
     *
     * 2026-10-12 用户报「拍题说没收录、收录时又说库里有高度相似的」时，日志里只有
     * 「命中 0 条」—— 到底是一条候选都没有，还是都差一点，看不出来。
     * 这一份不影响任何判断，只让下一次报障有据可查。
     */
    val probe: List<StemHit> = emptyList(),
) {
    val best: StemHit? get() = hits.firstOrNull()

    /** 分数够高、和第二名拉开了距离、而且确认是同一个问法。 */
    val confident: Boolean
        get() {
            val hit = hits.firstOrNull() ?: return false
            return hit.score >= Similarity.MATCH_THRESHOLD && hesitation == null
        }

    /** 分数够高但不敢采信（题库里有近似题，或者问法被改过）。 */
    val ambiguous: Boolean
        get() {
            val hit = hits.firstOrNull() ?: return false
            return hit.score >= Similarity.MATCH_THRESHOLD && hesitation != null
        }
}

/**
 * 题干预索引 + 匹配。
 *
 * 之所以单独抽成一个纯 Kotlin 类（不碰 Room、不碰 Android）：匹配算法是整个应用里
 * 唯一「错了也看不出来」的部分 —— 它不崩不报错，只是悄悄给出另一道题的答案。
 * 抽出来之后就能拿真实题库 + 真实 OCR 文本在电脑上直接跑回归，见
 * `app/src/test/java/com/daily/quizbank/util/StemMatcherTest.kt`。
 *
 * 流程：归一化 → 滑动窗口取探针做粗筛 → 对候选精算 [Similarity.matchScoreNormalized] → 排序。
 */
class StemMatcher(private val entries: List<StemEntry>) {

    /** 索引里有多少条题干。日志用（跟库里题数对不上就是索引没跟上）。 */
    val size: Int get() = entries.size

    /** 预归一化的题干，粗筛和精算都直接用它，避免每个候选重复归一化。 */
    private val keys: List<String> = entries.map { Similarity.normalize(it.stem) }

    /** 每条题干的字符二元组。判「这次对上的内容够不够特别」要用，见 [looksGenericOnly]。 */
    private val gramSets: List<Set<String>> = keys.map { Similarity.bigramsOf(it) }

    /**
     * 每条**短条目**自己那几个选项的字符二元组（没填 options 的条目这里是空集）。
     *
     * 判据「是不是同一道题」用它：输入里出现了这条题自己的选项 → 这就是同一道题，
     * 不是「撞上一句通用问法」。
     */
    private val optionGramSets: List<Set<String>> = entries.map { entry ->
        if (entry.options.isEmpty()) {
            emptySet()
        } else {
            // 分隔符用空格即可：Similarity.normalize 会把空白全丢掉
            Similarity.bigramsOf(Similarity.normalize(entry.options.joinToString(" ")))
        }
    }

    /**
     * 每个二字组出现在多少条题干里。
     *
     * 这是「一句通用问法」和「一个专用名词」之间的分界线，全部来自题库自身的统计 ——
     * 不写死任何词表，换一个学科的库照样成立。
     */
    private val documentFrequency: Map<String, Int> = buildMap {
        for (grams in gramSets) {
            for (gram in grams) put(gram, (get(gram) ?: 0) + 1)
        }
    }

    fun match(
        text: String,
        limit: Int = 5,
        threshold: Double = Similarity.DEFAULT_THRESHOLD,
    ): MatchOutcome {
        val observed = Similarity.normalize(text).take(MAX_TEXT_LENGTH)
        if (observed.length < Similarity.MIN_GRAMS) return EMPTY

        val candidates = pickCandidates(observed) ?: return EMPTY
        val observedGrams = Similarity.bigramsOf(observed)

        // 第二遍：把**选项行**切掉之后再用同一个题干比一次，两遍取高分。
        //
        // 为什么非要有这一遍：题库里收录的题，**选项是单独一栏存的**（题干里没有 A/B/C 那几行），
        // 而拍题框下来的一整块是「题干 + 选项」。两边一比，分母里凭空多出四五个选项，
        // 四条路线一起被稀释 —— 真机 2026-10-12 就是这么漏的：
        //
        //   拍题（整块，含选项）→「题库里没有这道题」（0.30~0.39，够不到 0.42 的展示线）
        //   收录（只剩题干）  →「题库里还有一道很像的题（相似度 100%）」
        //
        // 同一道题、同一个库，两条路说法相反，用户当然认定是 bug。
        //
        // 输入里本来就没有选项行时（搜题那条路，用户只打题干），这一遍的文本和第一遍**完全相同**，
        // 直接跳过 —— 所以搜题的全量回归一格都不会动。
        val stemOnly = try {
            val trimmed = stripOptions(text).take(MAX_TEXT_LENGTH)
            if (trimmed.length >= Similarity.MIN_GRAMS && trimmed != observed) trimmed else null
        } catch (error: Exception) {
            null
        }
        val stemOnlyGrams = stemOnly?.let { Similarity.bigramsOf(it) }

        val scored = ArrayList<Scored>(candidates.size)
        // 没到展示线、但分数最高的那几条。**只给日志用** —— 「库里明明有、拍题却说没有」
        // 这种报障，只有把「真正最像的那几条」打出来才能定性（2026-10-12 用户那次就是）。
        val weak = ArrayList<Scored>(PROBE_HITS * 4)
        for (i in candidates) {
            val key = keys[i]
            val full = Similarity.matchDetail(observed, key)
            val trimmed = if (stemOnly != null) Similarity.matchDetail(stemOnly, key) else full
            // 取高的那一遍，而且后面判「只是撞上一句通用问法」也要用**同一遍**的文本，
            // 两遍混着用会让那条判据自己跟自己打架
            val useTrimmed = stemOnly != null && trimmed.score > full.score
            val detail = if (useTrimmed) trimmed else full
            if (detail.score < DISPLAY_FLOOR) {
                if (detail.score > 0.0) {
                    weak.add(Scored(entries[i], key, detail, false))
                    if (weak.size > PROBE_HITS * 4) {
                        weak.sortByDescending { it.score }
                        while (weak.size > PROBE_HITS) weak.removeAt(weak.size - 1)
                    }
                }
                continue
            }
            val view = if (useTrimmed) stemOnly!! else observed
            val grams = if (useTrimmed) stemOnlyGrams!! else observedGrams
            // 第四个参数传**整段输入**的字组（不是切掉选项之后那一遍）：
            // 「输入里有没有这条题自己的选项」这句话，只能在整段原文上问。
            scored.add(Scored(entries[i], key, detail, looksGenericOnly(i, key, view, grams, observedGrams)))
        }

        // 「最像的几条」：先取过了展示线的，不够再用没到线的那几条补上
        val probeOf = {
            val top = ArrayList<StemHit>(PROBE_HITS)
            for (s in scored) {
                if (top.size >= PROBE_HITS) break
                top.add(StemHit(s.entry, s.score))
            }
            if (top.size < PROBE_HITS) {
                for (s in weak.sortedByDescending { it.score }) {
                    if (top.size >= PROBE_HITS) break
                    top.add(StemHit(s.entry, s.score))
                }
            }
            top
        }
        if (scored.isEmpty()) return EMPTY.copy(probe = probeOf())
        scored.sortByDescending { it.score }

        // 完全重复的题（题库里同一道题出现两次）不算「第二名」，否则 margin 会恒等于 0
        val best = scored[0]

        // 分最高的那条要是「只撞上一句通用问法」，这一轮就直接按「题库里没有这道题」处理：
        // 候选一个都不摆，更不会拿去答题。
        //
        // **注意这一步必须放在算 margin 之前、而且只作用于这一条。** 试过「先把所有
        // 通用问法的候选都筛掉、再算 margin」，结果全量回归里错答从 0% 涨到 2% ——
        // 那些被筛掉的候选本来正是「题库里还有一道几乎一样的题」这道闸门的依据，
        // 少了它们，margin 被撑开，本该退回 AI 的情况变成了直接答题。
        if (best.genericOnly) {
            return EMPTY.copy(genericOnly = scored.count { it.genericOnly }, probe = probeOf())
        }

        val runnerUp = scored.firstOrNull { it.key != best.key }
        val margin = best.score - (runnerUp?.score ?: 0.0)

        val hits = scored.asSequence()
            // 分最高的那条一定留（上面已经查过它不是通用问法），其余同样是通用问法的
            // 只是摆在列表里凑数，不摆
            .filter { !it.genericOnly }
            .filter { it.score >= threshold }
            .take(limit)
            .map { StemHit(it.entry, it.score) }
            .toList()

        return MatchOutcome(
            hits = hits,
            margin = margin,
            runnerUp = runnerUp?.let { StemHit(it.entry, it.score) },
            hesitation = hesitationOf(best, margin),
            genericOnly = scored.count { it.genericOnly },
            probe = probeOf(),
        )
    }

    /**
     * 这条候选是不是「只靠一句通用问法」对上的。
     *
     * 用户报的毛病正是这个：一道新题的末句是「最可能的诊断是」，题库里有一条
     * **只有小问、没有材料**的短条目（「2021U2-106 可能的诊断是」），于是它以
     * 四十多个百分点被摆成「相近的题」，看着就像「匹配到小问上」。
     *
     * 判据用**题库自己的统计**：这次对上的那几段文字，在题库里有多少条题干用过。
     * 用的人少 → 是这条题干特有的说法（「烧伤分度」这种专用名词），算数；
     * 到处都是 → 只是一句问法（「最可能的诊断是」「明确诊断」），谁都能对上，不算。
     *
     * **只在「本身就短」的条目上查这一条。** 一整道题的题干（三十个字以上）不查：
     * 那种命中靠的是它自己的正文，而且题库里同一道题的多个副本会把字组的 DF 顶上去
     * （实测 1749 题的库里，6% 的题目连「最罕见的那个字组」都不止 DF3），
     * 对它们查这一条会误伤真命中。
     */
    private fun looksGenericOnly(
        index: Int,
        key: String,
        observed: String,
        observedGrams: Set<String>,
        /** **整段**输入的字组（不是切掉选项之后那一遍）——看「这条题自己的选项在不在里面」。 */
        wholeInputGrams: Set<String>,
    ): Boolean {
        if (key.length >= SHORT_ENTRY_CHARS) return false
        // 只在「这一次的输入比它长得多」时才查。
        //
        // 这一条把判据死死限在用户报的那个形态里：一道**整题**（几十个字，还可能带着
        // 选项和界面文字）撞上了题库里一条**残缺小问**。输入比条目本身还短、或者长度
        // 相当的情况（用户只打题干开头、拍到的就是同一道题）一律不在这里判 ——
        // 那些是「短输入搜题」的正经用法，2026-10-09 试过不设这一条，
        // 全量回归里「前 3/4」的命中直接从 84.9% 掉到 54.1%。
        if (observed.length < key.length * 2) return false

        val entryGrams = gramSets[index]
        if (entryGrams.isEmpty()) return false

        // 一、这次输入连它本身都没盖全。
        //
        // 残缺条目（只有一句小问、没有材料）本来就没什么内容，一句「为明确诊断应采取的
        // 检查方法是」就能盖住它三分之二 —— 没盖住的那三分之一（往往是题号）恰恰是
        // 唯一能说明「这是哪一道」的东西。
        var shared = 0
        for (gram in entryGrams) if (gram in observedGrams) shared++
        if (shared.toDouble() / entryGrams.size < SHORT_ENTRY_COVERAGE) return true

        // 二、**整条都盖住了**，但盖住它的全是题库里的通用说法 ——
        // 那它只是一句问法（「最可能的诊断是」这种），谁都能对上，对上也不算数。
        //
        // ⚠️ 「整条都盖住」必须真的是**整条**（一个字组的空子都不留）。
        //
        // 2026-10-12 用户报的那次就是栽在这一条上：库里那条题是「2004U4-10 骨囊好发于」，
        // **少了一个「肿」**（当年录入时认错的），屏幕上是「骨囊肿好发于」。两边一比，
        // 「囊好」这个字组对不上 —— 覆盖率 0.917，够过上面那道 0.85 的闸，
        // 于是掉进这一段；而它剩下的字组（骨囊、好发、发于…）在他那个库里
        // 恰好都被「同一道题的另外几份副本」顶到了 DF ≥ 2，
        // 于是被当成「只是撞上一句通用问法」，**91% 的真命中就这么被丢掉了**。
        //
        // 差一两个字是 OCR 的常态，不是「只对上了一句通用问法」的证据：
        // 覆盖率没到 1.0 就说明对上的不止是问法本身，这里直接放行。
        if (shared < entryGrams.size) return false
        val cutoff = rareCutoff()
        for (gram in entryGrams) {
            if (gram !in observedGrams) continue
            if ((documentFrequency[gram] ?: 0) <= cutoff) return false
        }

        // 三、**这次输入里出现了这条题自己的选项** —— 那就是同一道题，不是「撞上一句通用问法」。
        //
        // 这一条是 2026-10-27 补的，起因是一次真机报障：用户导入了「防寒过冬客运试题」，
        // 拍「20.(3分)降雪会商的决策内容主要包括?」这道多选题，拍题和搜题都显示「无」。
        // 查下来它的**分数是 100%**，却被这条判据丢了：那个库全都讲降雪、会商、雪情，
        // 于是那条题干的每个字组在库里的 DF 都 ≥ 2（降雪=21、会商=12…），
        // 「整条被盖住 + 没有一个 DF≤1 的字组」两条同时成立 —— 而它明明是一道完整的题。
        //
        // **这种库（主题高度集中）里 DF 普遍偏高**，拿 DF 当「通用」的唯一依据就会大面积误伤：
        // 量过那个 213 道的库，按原来的门槛有 **133 道（62%）** 会被判成「通用问法」，
        // 也就是说这个库拍题基本全废。判据要的不是「字组在库里不稀奇」，
        // 而是「这条东西本身是不是只有一句问法」—— 它自己带着选项、而且这次输入里
        // 也有这些选项时，答案已经很清楚了。
        //
        // 只放宽、不新增：这一条只会让原本被丢掉的候选重新参与排序，
        // 不会让原本不该命中的东西命中（选项是那条题自己存的，撞车概率极低）。
        val optionGrams = optionGramSets[index]
        if (optionGrams.size >= MIN_OPTION_GRAMS) {
            var hit = 0
            for (gram in optionGrams) if (gram in wholeInputGrams) hit++
            if (hit.toDouble() / optionGrams.size >= OPTION_SAME_QUESTION) return false
        }
        return true
    }

    /**
     * 字组的 DF 超过这个数就算「题库里的通用说法」。
     *
     * 取「题库的 0.2%，至少 1」：175 道题的库里量过 —— 放宽到 2%（3 条）时，
     * 「为明确诊断应采取的检查方法」这种**问法**也会因为只被两三条题用过而被当成
     * 「这条题干自己的说法」，判据当场失效。
     *
     * 收到 1（也就是「这个字组全库只有这一条题干有」）之所以不会误伤真命中：
     * 这道判据只在**残缺条目**上、而且只在**输入比它长一倍以上**时才启用；
     * 真命中对上的正是那条题干里别人没有的说法，本来就该有 DF=1 的字组。
     */
    private fun rareCutoff(): Int = maxOf(1, entries.size / 500)

    /**
     * 分数够高时，再问一句「真的敢用题库答案吗」。
     *
     * 分数不到 [Similarity.MATCH_THRESHOLD] 时返回 null —— 那种情况由调用方按
     * 「相似度不足」处理，不需要额外解释。
     */
    private fun hesitationOf(best: Scored, margin: Double): String? {
        if (best.score < Similarity.MATCH_THRESHOLD) return null
        if (margin < Similarity.MATCH_MARGIN) {
            return "题库里还有一道几乎一样的题，两个答案未必相同"
        }
        if (Similarity.looksLikeDifferentWording(best.detail, best.key)) {
            return "题库里最接近的那道题和这次拍到的问法不一样（多半改过措辞），答案未必对得上"
        }
        return null
    }

    /**
     * 把输入里的**选项行**切掉，只留题干那一段（返回值已归一化）。
     *
     * 「哪几行算选项」全工程只有一处实现（[OcrText.optionLetterOf]）—— 这里也必须走它，
     * 否则框选、清洗、拆分三处对「选项」的理解会分叉（第六节开头那条主线）。
     *
     * 只从**第一个选项行**往下切：单题的屏幕上，选项行下面就是答案/统计/解析，
     * 那些本来也不该参与「是不是这道题」的判断。第一行本身就是选项行时（用户只框了选项），
     * 返回整段 —— 等于没切，第二遍自动跳过。
     */
    private fun stripOptions(raw: String): String = stemOnlyKey(raw)

    /**
     * 粗筛：在**整段** OCR 文字上滑动取样，而不是只取开头。
     *
     * 之前只取开头 32 个字，而全屏截图的开头恰恰是状态栏、「A 选项」和「单选 1、」，
     * 四个探针全是噪声 —— 预筛必然落空，只能靠「全表扫描」兜底。
     * 题库一旦超过 FULL_SCAN_LIMIT，兜底也被关掉，就彻底匹配不上了。
     *
     * 返回 null 表示「探针全落空、题库又大到不能全扫」，调用方应当放弃匹配。
     */
    private fun pickCandidates(observed: String): IntArray? {
        val picked = LinkedHashSet<Int>()
        for (probe in probesOf(observed)) {
            for (i in keys.indices) {
                if (keys[i].contains(probe)) {
                    picked.add(i)
                    if (picked.size >= CANDIDATE_CAP) return picked.toIntArray()
                }
            }
        }
        if (picked.isNotEmpty()) return picked.toIntArray()

        // 探针全落空：要么 OCR 错得离谱，要么题干短得凑不出一个探针。
        // 题库不大时干脆全量精算，宁可慢几十毫秒也别漏题。
        return if (entries.size <= FULL_SCAN_LIMIT) IntArray(entries.size) { it } else null
    }

    private class Scored(
        val entry: StemEntry,
        val key: String,
        val detail: Similarity.MatchDetail,
        /** 这次对上的只是一句通用问法（见 [looksGenericOnly]）。 */
        val genericOnly: Boolean,
    ) {
        val score: Double get() = detail.score
    }

    companion object {
        private const val PROBE_SIZE = 8
        private const val PROBE_STEP = 2
        private const val MAX_PROBES = 20
        private const val CANDIDATE_CAP = 600

        /** 超过这个题量就放弃「全表精算」兜底，否则一次匹配要算到几百毫秒。 */
        private const val FULL_SCAN_LIMIT = 20_000

        /** 连这个分数都不到的候选不必留在内存里排序。 */
        private const val DISPLAY_FLOOR = 0.2

        /** 日志里回带几条「最像的」候选，见 [MatchOutcome.probe]。 */
        private const val PROBE_HITS = 3

        /**
         * 这段输入「去掉选项行」之后长什么样（已归一化）。
         *
         * **只给诊断日志用**：出「库里有却说没有」时，拿它去库里逐条对一下，
         * 就能分清是「库里真没有这条题干」还是「算法 / 索引漏了」。
         */
        fun stemOnlyKey(text: String): String {
            val lines = text.split('\n')
            val firstOption = lines.indexOfFirst { OcrText.optionLetterOf(it) != null }
            val kept = if (firstOption > 0) lines.subList(0, firstOption) else lines
            return Similarity.normalize(kept.joinToString("\n"))
        }

        /**
         * 题干短于这么多字（归一化后）的条目，才去查「这次对上的内容够不够特别」。
         *
         * 三十个字以下的题干多半是「只有一句小问、没有材料」那种残缺条目，
         * 一句通用问法就能把它整条盖住；一整道题的题干比这长得多，不在核查范围内。
         */
        const val SHORT_ENTRY_CHARS = 30

        /**
         * 这条题干的「短条目」判定 —— 和内部判据用的是同一个数。
         *
         * 仓库层建索引时靠它决定「哪些条目要带上选项」（见 [StemEntry.options]）。
         */
        fun isShortEntry(stem: String): Boolean =
            Similarity.normalize(stem).length < SHORT_ENTRY_CHARS

        /**
         * 短条目的「选项字组」至少要有这么多，才拿它当「同一道题」的证据。
         *
         * 判断题的选项只有「对」「错」（一个字组），太容易在别处撞见 ——
         * 少于这个数就当没有证据，判据照旧走。
         */
        private const val MIN_OPTION_GRAMS = 8

        /** 输入里出现这条题选项的这么多比例，就认定是同一道题（见 [looksGenericOnly] 第三条）。 */
        private const val OPTION_SAME_QUESTION = 0.5

        /**
         * 短条目要被摆出来，至少得有这么大比例的字组对上了。
         *
         * 0.85 = 「几乎整条都在这段输入里」。定这么高是因为这条判据只用于残缺条目：
         * 它们本来就短，掉一两句就什么都不剩了，而**没对上的那部分恰恰是它唯一的身份**。
         */
        private const val SHORT_ENTRY_COVERAGE = 0.85

        /** OCR 文本上限，防止整页长文把滑窗代价顶穿。 */
        private const val MAX_TEXT_LENGTH = 4000

        private val EMPTY = MatchOutcome(emptyList(), 0.0, null, null)

        /** 在整段文字上按固定步长取定长探针，尽量让每个探针都落在不同的句子里。 */
        fun probesOf(observed: String, count: Int = MAX_PROBES, size: Int = PROBE_SIZE): List<String> {
            if (observed.length <= size) return listOf(observed)
            val probes = ArrayList<String>(count)
            var start = 0
            while (probes.size < count && start + size <= observed.length) {
                probes.add(observed.substring(start, start + size))
                start += PROBE_STEP
            }
            return probes
        }
    }
}
