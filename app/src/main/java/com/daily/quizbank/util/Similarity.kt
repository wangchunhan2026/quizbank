package com.daily.quizbank.util

import kotlin.math.max
import kotlin.math.min

/**
 * 题干相似度。
 *
 * 这里有两个场景，判据完全不同，改代码前务必分清：
 *
 * 1. [score]：两段文本**长度相当、都不带噪声**时用。
 *    字符二元组（bigram）集合的 Jaccard 系数，短文本用长度比兜底。
 *
 * 2. [matchScore]：**一方是截图 OCR 的原始输出**（状态栏、选项行、时间、
 *    「发送 / 编辑 / 删除 / 更多」这些界面文字全混在里面），另一方是题库里干净的题干。
 *    这时绝不能再用整串 Jaccard：分母里塞满了噪声 bigram，分数被白白稀释。
 *
 *    实测数据（一个 1749 题的题库 + 一张 1080×2400 全屏截图的真实 ML Kit 输出，
 *    见 拍题匹配分析/eval1.js 与 eval7.js）：
 *
 *      只框住题干（理想裁剪）  整串 Jaccard 74.0%
 *      框到题干 + 四个选项     整串 Jaccard 58.7%
 *      全屏截图（带界面文字）  整串 Jaccard 42.0%   ← 低于 72% 命中线，永远走 AI
 *
 *    同一道题因为截图范围不同给出 42% / 59% 两个分数，正是「第一次提示相似度 42%、
 *    第二次变成非题库答案」的成因：分数在阈值附近抖动，命中与否成了掷骰子。
 *
 *    本函数同时算四条路线，取最高的那条：
 *
 *      1. 整串 Dice —— 管「题干比截图长、只截到一半」；
 *      2. 滑动窗口 Dice —— 管「噪声在题干外面」。噪声落在窗口外就完全不计分，
 *         全屏截图那道题因此从 42% 回到 86%，三种框选范围分数完全一致；
 *      3. 覆盖率 —— 「题库题干的 bigram 有多少在截图里出现过」，管
 *         **噪声插进题干中间**的情况。这一条是非加不可的：真机上「A 正确」和计时器
 *         会插在题干两行之间，窗口法在这种输入上直接掉到 72%（低于 0.80 而漏掉），
 *         覆盖率仍有 95%；
 *      4. 输入覆盖率 —— 「输入的 bigram 有多少能在题库题干里找到」，管
 *         **输入比题库题干短**的情况，也就是搜题。前三条的分母都是「题库题干的
 *         bigram 数」，只打了题干的一部分时分数会跟着输入长度线性塌下来：
 *         打一半就是 50 分，永远够不到 0.80。搜题恰恰天然是这种输入 ——
 *         用户只会打题目正文，不会把选项也打一遍，而收录进来的题干是带着选项的
 *         （见 CollectActivity 的「题干与选项」），于是整题打完也只有 47%
 *         （2026-10-08 真机日志：「搜题内容: 美托洛尔降低心肌收缩力的机制」
 *         对上「21.美托洛尔降低心肌收缩力的机制 + 五个选项」）。
 *         这一条反过来问，只要输入的内容基本都在题干里就算对上。
 *
 *    四条路线取最大值，所以在「噪声在外」「噪声在内」「只截到一半」
 *    「只打了题干的一部分」四种情况下都成立。
 */
object Similarity {

    /** 归一化：只保留中日韩文字、字母和数字，其余（标点、空格、OCR 噪声）一律丢弃。 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun bigrams(text: String): Set<String> = bigrams(text, 0, text.length)

    /**
     * 归一化文本的字符二元组。给 [StemMatcher] 判「这次对上的内容够不够特别」用。
     *
     * 入参必须是 [normalize] 过的（只留字母数字），否则标点和空白会各自变成二元组。
     */
    fun bigramsOf(normalized: String): Set<String> = bigrams(normalized)

    /**
     * 取 [from, to) 区间内的字符二元组。
     *
     * 直接按下标取，不做 [String.substring] —— 窗口滑动时每个候选都要建几十次集合，
     * 每次都复制一份子串会凭空多出一堆临时对象。
     */
    private fun bigrams(text: String, from: Int, to: Int): Set<String> {
        val start = max(0, from)
        val end = min(to, text.length)
        if (end - start < 2) return emptySet()
        val set = HashSet<String>(end - start)
        for (i in start until end - 1) set.add(text.substring(i, i + 2))
        return set
    }

    /** 两边共有的 bigram 个数。 */
    private fun sharedCount(a: Set<String>, b: Set<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        var inter = 0
        for (gram in smaller) if (larger.contains(gram)) inter++
        return inter
    }

    /** Dice 系数：2|A∩B| / (|A|+|B|)。比 Jaccard 温和，长度稍差一点也不会被重罚。 */
    private fun dice(a: Set<String>, b: Set<String>): Double {
        val total = a.size + b.size
        return if (total == 0) 0.0 else 2.0 * sharedCount(a, b) / total
    }

    /**
     * 两段「干净且等长」文本的相似度，返回 0.0 ~ 1.0。
     * 完全一致为 1.0；低于 [DEFAULT_THRESHOLD] 的结果调用方应当直接丢弃。
     */
    fun score(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        // 短题干（如判断题）信息量太低，bigram 会失真，改用长度比兜底。
        if (na.length < MIN_GRAMS || nb.length < MIN_GRAMS) {
            val shorter = min(na.length, nb.length)
            val longer = max(na.length, nb.length)
            return if (na.contains(nb) || nb.contains(na)) shorter.toDouble() / longer else 0.0
        }

        val sa = bigrams(na)
        val sb = bigrams(nb)
        val inter = sa.count { sb.contains(it) }
        val union = sa.size + sb.size - inter
        val jaccard = if (union == 0) 0.0 else inter.toDouble() / union

        // 一方包含另一方时给奖励：OCR 常常多识别或少识别几行，此时 Jaccard 会被稀释。
        val containment = if (na.contains(nb) || nb.contains(na)) 0.12 else 0.0

        // 长度悬殊说明多半不是同一题，做一次温和惩罚。
        val lenRatio = min(na.length, nb.length).toDouble() / max(na.length, nb.length)
        val lengthPenalty = if (lenRatio < 0.5) (0.5 - lenRatio) * 0.6 else 0.0

        return (jaccard + containment - lengthPenalty).coerceIn(0.0, 1.0)
    }

    /**
     * 匹配细节：分数，以及「用来核对问法的那一段」原文（已归一化）。
     *
     * [region] 是给 [looksLikeDifferentWording] 用的 —— 判断「拍到的这道题和题库那条
     * 是不是同一个问法」，要看真正对上的那段文字。走窗口路线时它是那个窗口，
     * 走覆盖率路线时（题干被界面文字打断，找不到连续窗口）它就是整段 OCR 文字。
     */
    data class MatchDetail(
        val score: Double,
        val region: String,
        /**
         * true 表示这个分数来自 [Similarity] 的第四条路线（输入覆盖率）——
         * 也就是「输入比题库题干短」。问法核对的尺度对这条路线不一样，见
         * [looksLikeDifferentWording]。
         */
        val fromQuery: Boolean = false,
    )

    /** [matchScore] 的字符串入参版本，内部先做归一化。 */
    fun matchScore(ocrText: String, stem: String): Double =
        matchDetail(normalize(ocrText), normalize(stem)).score

    /**
     * 题库匹配专用评分（入参必须已经归一化）。
     *
     * @param observed 截图 OCR 出来的整段文字（可能混着界面噪声）
     * @param target   题库里那一条题干（干净）
     */
    fun matchScoreNormalized(observed: String, target: String): Double =
        matchDetail(observed, target).score

    fun matchDetail(observed: String, target: String): MatchDetail {
        if (observed.isEmpty() || target.isEmpty()) return MatchDetail(0.0, "")
        if (observed == target) return MatchDetail(1.0, observed)

        if (observed.length < MIN_GRAMS || target.length < MIN_GRAMS) {
            val shorter = min(observed.length, target.length)
            val longer = max(observed.length, target.length)
            val score = if (observed.contains(target) || target.contains(observed)) {
                shorter.toDouble() / longer
            } else {
                0.0
            }
            return MatchDetail(score, observed)
        }

        val targetGrams = bigrams(target)
        val observedGrams = bigrams(observed)

        // 路线一：整串比。管的是「题干比截图还长、只截到其中一段」。
        var windowScore = dice(targetGrams, observedGrams)
        var windowText = observed

        // 路线二：滑动窗口比。管的是「截图里除了题干还有一堆别的东西」。
        val window = min(target.length, observed.length)
        // OCR 特别长（整页文字）时把步长放大，避免 O(文本长度) 个窗口白算
        val stride = if (observed.length > target.length * 3) max(2, target.length / 8) else 1
        var start = 0
        while (start + window <= observed.length) {
            val d = dice(targetGrams, bigrams(observed, start, start + window))
            if (d > windowScore) {
                windowScore = d
                windowText = observed.substring(start, start + window)
            }
            start += stride
        }

        // 路线三：覆盖率 —— 题库题干的 bigram 有多少在截图里出现过，不管中间隔了什么。
        //
        // 这条路是非加不可的：真机上界面文字会**插进题干中间**。实测（2026-10-04 真机日志）：
        //   「判断96、上岗前应签订师徒合同,跟班作业期间」
        //   「严禁师徒分离、单独作业。未经鉴定会格严禁单」
        //   「A正确」            ← 选项行插进来了
        //   「O 00:55:56」        ← 计时器也插进来了
        //   「独作业。严禁低职代高职作业或监控。」
        // 前两条路线都假设「题干在截图里是一段连续文字」，遇到这种情况窗口法掉到 72%，
        // 而覆盖率仍有 95.1%。450 组合成用例里窗口法只命中 137 个、覆盖率命中 372 个。
        //
        // 覆盖率单独用会误判「短题被长题整段包含」——那种情况两个分数都是 100%，
        // 靠调用方的 margin（第二道不同的题差多少）拦下，见 StemMatcher。
        val coverage = sharedCount(targetGrams, observedGrams).toDouble() / targetGrams.size

        // 路线四：输入覆盖率 —— 「输入里有多少内容能在题库题干中找到」。
        //
        // 前三条路线的分母都带着题库题干的长度，所以它们回答的是「题库这道题有多少
        // 出现在输入里」。截图成立（截图必然包含整个题干），但**搜题不成立**：
        //
        //   - 用户只打题目正文，不打选项；而收录进来的题干是「题干与选项」一整块
        //     （CollectActivity 的输入框就叫这个名字），于是分母里凭空多出四五个选项；
        //   - 用户往往也只会打题干的一部分，懒得把整句抄完。
        //
        // 两种情况都是 observed ⊂ target，分数按长度比例线性掉下来 —— 真机实测
        // 「美托洛尔降低心肌收缩力的机制」（把题目一字不差打全了）对上
        // 「21.美托洛尔降低心肌收缩力的机制 + 五个选项」只有 47%。
        //
        // 这一条反过来问：输入基本都在题干里就算对上。代价是「短输入被长题干整段包含」
        // 会拿满分，所以按输入长度分两档、各自配一道闸：
        //
        //   12 字以上：看大字符二元组覆盖率（容忍错字和断行，用户手打的字难免有出入）；
        //   6~11 字 ：**必须原样连着出现在题干里**。这一段长度上用二元组会失真 ——
        //              八个字只有七个 bigram，在长题干里「凑巧都出现过」太容易了；
        //              「连着出现过」则是一个强得多的事实。
        //   6 字以下：不认。
        //
        // 真机上用户就是这么用的：只打题目开头那几个字（「在应激的认知评价中」，9 字），
        // 或者题目中间最有辨识度的一小截。这种输入要么原样在题干里、要么不在，
        // 没有中间地带，所以短档直接给 1.0 而不是按比例打分。
        //
        // 两档都还要过原本就有的 margin：一小截文字同时出现在好几道题里时
        // 大家都是满分、差值为 0，照样退回 AI。
        val queryCoverage = when {
            observed.length >= MIN_QUERY_LENGTH ->
                sharedCount(targetGrams, observedGrams).toDouble() / observedGrams.size

            observed.length >= MIN_SHORT_LENGTH -> {
                val coverage =
                    sharedCount(targetGrams, observedGrams).toDouble() / observedGrams.size
                when {
                    target.contains(observed) -> 1.0
                    // 原样找不到、但相似度已经过半时才多花一次模糊搜索 ——
                    // 那一步是 O(题干长度 × 输入长度)，不能对每个候选都跑
                    coverage >= FUZZY_MIN_BASE && fuzzyContained(observed, target) -> 1.0
                    else -> coverage
                }
            }

            else -> 0.0
        }

        return when {
            queryCoverage > windowScore && queryCoverage > coverage ->
                MatchDetail(queryCoverage, observed, fromQuery = true)

            windowScore >= coverage -> MatchDetail(windowScore, windowText)

            else -> MatchDetail(coverage, observed)
        }
    }

    /**
     * 对上的那一段与题干，是不是**同一个问法**。
     *
     * 分数只能回答「文字像不像」，回答不了「是不是同一个问法」。判断题最常见的改动
     * 就是加一个否定词：题库里是「…影响旅客使用时**设有**提示。」（答案：正确），
     * 手机上是「…影响旅客使用时**没有**提示。」（答案：错误）。两者只差两个字，
     * 窗口 Dice 高达 0.91，足以骗过任何按分数设的阈值 —— 但那道题根本不在题库里。
     *
     * 所以这里再核一道：对上的那一段和题库题干里，否定字的个数必须一致。
     * 不一致就说明问法被改过，不能用题库答案。
     *
     * 代价是可能误判（OCR 把「不」认错时也会判为不一致），但那种情况下本来就该交给 AI，
     * 属于宁可多花一分钱、也不要答错的取舍。
     */
    /**
     * 短输入专用的「模糊包含」：把输入当成**子序列**在题干里找，
     * 而且整段必须落在一个比输入略宽的窗口里（|输入| + [FUZZY_SLACK]）。
     *
     * 管的是「题库里那条被 OCR 认错了一两个字」——这是用户最常撞上的一种：
     * 他搜「甲硫氨酸循环」，题库里存的却是「甲硫复氨酸循环」（OCR 多认了一个「复」）。
     * 按原样找找不到；按二元组覆盖率算只有 0.8，勉强过线但再错一个字就掉下去；
     * 按子序列找，它整段就在那儿，只是中间多了一个字。
     *
     * 窗口限制是安全阀：不限制的话「这几个字在这道题里都出现过」太容易成立
     * （长题干几乎什么字都有）；限制成「挤在一小段里」之后，它才真的像一次包含。
     *
     * 只在短输入上启用，而且调用方会先用二元组覆盖率筛一道（见 [matchDetail]）——
     * 这个函数最坏是 O(题干长度 × 输入长度)，不能对每个候选都跑。
     */
    private fun fuzzyContained(observed: String, target: String): Boolean {
        if (target.length < observed.length) return false
        val width = observed.length + FUZZY_SLACK
        // 从输入首字在题干里的每一次出现试起；首字都对不上的位置不用试
        var from = 0
        while (from < target.length) {
            val start = target.indexOf(observed[0], from)
            if (start < 0) return false
            var cursor = start
            var last = start
            var ok = true
            for (index in observed.indices) {
                val at = target.indexOf(observed[index], cursor)
                if (at < 0) {
                    ok = false
                    break
                }
                last = at
                cursor = at + 1
            }
            if (ok && last - start + 1 <= width) return true
            // 这一次的窗口太宽：把起点往后挪一格再试
            from = start + 1
        }
        return false
    }

    fun looksLikeDifferentWording(detail: MatchDetail, target: String): Boolean {
        // 逐字一致，没什么可核的
        if (detail.score >= VERBATIM_SCORE) return false
        if (detail.region.isEmpty()) return false
        val inRegion = detail.region.count { it in NEGATION_CHARS }
        val inTarget = target.count { it in NEGATION_CHARS }

        // 走「输入覆盖率」那条路线时（fromQuery）只认一个方向：**输入里多了否定字**。
        //
        // 理由是那条路线的目标文本常常是「题干与选项」一整块（收录进来的就是这样），
        // 选项里带「不 / 没 / 无」的很多，会把 target 的计数凭空抬高；再用「两边计数
        // 不等就退回 AI」的对称判据，会把一堆本来对得上的题无谓地推给 AI。
        // 而真正要拦的是把答案改反的那种改动 —— 题库「设有提示」→ 屏幕上「没有提示」——
        // 恰好就是「输入比题干多了一个否定字」。
        return if (detail.fromQuery) inRegion > inTarget else inRegion != inTarget
    }

    /** 少于这么多字符就不足以支撑 bigram，退回长度比。 */
    const val MIN_GRAMS = 4

    /**
     * 否定字。判断题里改动它们就会让答案反转，见 [looksLikeDifferentWording]。
     * 只列单字，不列「禁止」「不得」这类词 —— 那些词里已经含了这里的字。
     */
    private val NEGATION_CHARS = "不没无未非勿禁莫别".toSet()

    /** 高到这个程度就认为是逐字一致，不再做问法核对。 */
    private const val VERBATIM_SCORE = 0.995

    /**
     * 「输入覆盖率」这条路线至少要有多长的输入才算数（归一化后的字数）。
     *
     * 取 12 是量出来的：在 1749 道真题上，输入取题干前 N 字跑一遍，
     *   N=10 时误答开始冒头（「视觉信号中的黄色表示」对上「铁路视觉信号中的黄色表示（）」）；
     *   N=12 还是 0 误答，N=14 起开始有真命中。
     * 低于这个长度就只用前三路线，宁可交给 AI。
     */
    const val MIN_QUERY_LENGTH = 12

    /**
     * 「原样连着出现在题干里」这条判据适用的最短输入（归一化后的字数）。
     *
     * 取 4：再短（「在应激的」三个字）就是一句普通话了，随机撞上题库里某条题干只是时间问题。
     * 4~5 个字这一段靠 [fuzzyContained] 的窗口限制和 margin 一起兜着，误答率量过，见
     * 拍题匹配分析/README.md 第 9 节。
     */
    const val MIN_SHORT_LENGTH = 4

    /** 模糊包含允许比输入宽出几个字（题库里被 OCR 多认 / 漏认的字数）。 */
    private const val FUZZY_SLACK = 3

    /** 二元组覆盖率低于这个数就不值得再跑一次模糊搜索了。 */
    private const val FUZZY_MIN_BASE = 0.5

    /** 结果面板里愿意展示的最低分（低于它的连列出来都没意义）。 */
    const val DEFAULT_THRESHOLD = 0.42

    /**
     * 达到这个分数才认为「题库里就是这道题」，直接给题库答案、不调 AI。
     *
     * 取值依据（450 组合成用例 + 真实截图的阈值网格，见 拍题匹配分析/eval7.js）：
     * 0.80 时 409/450 正确命中、0 次答错、41 次退回 AI。
     */
    const val MATCH_THRESHOLD = 0.80

    /**
     * 与「第二道题干不同的题」至少要拉开的差距。
     *
     * 题库里本来就有近似重复题（例如「防冻、防冻」与「防冻、解冻」只差一个字，
     * 却分别是不同答案），任何字符级算法都分不开。这种情况下宁可退回 AI，
     * 也不能赌一个答案 —— 加上这道闸门后，450 例中的 4 次答错全部被拦下。
     */
    const val MATCH_MARGIN = 0.03
}
