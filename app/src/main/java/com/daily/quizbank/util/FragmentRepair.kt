package com.daily.quizbank.util

/**
 * 「这条旧题只有半截，新收的这道像是它的完整版」—— 收录完之后据此问用户要不要顶掉旧的。
 *
 * 为什么要这个：早期版本的收录还没有「共用题干题」那套流程，一个材料带几个小问的题
 * 被当成普通题收了下来，**材料没存进去**，题库里就留下一条只有一句小问的残缺条目
 * （用户库里实测有 5 条：`2021U2-106 可能的诊断是` 这种）。
 *
 * 这种条目有两个坏处：一是它信息不全、答不了题；二是它很短，匹配时容易被一句通用问法
 * 整条盖住（见 StemMatcher.looksGenericOnly）。用户要重新收一遍补上材料，
 * 收完之后旧的半截就该让位 —— 但**删东西这事不能替他决定**，所以这里只负责「看出来
 * 新的是旧的完整版」，删不删由他在面板上点。
 *
 * 判据刻意保守：宁可漏（他自己删那一条），也不要在他每收一道题时都弹一次没用的框。
 */
object FragmentRepair {

    /** 题干短于这么多字（归一化后）就算「半截」—— 多半只有一句小问、没有材料。 */
    private const val FRAGMENT_CHARS = 30

    /**
     * 「这一问问的是什么」至少要有这么多字，才值得拿它去比对新旧两条。
     *
     * 太短的问法（「诊断为」三个字）在别的题里到处都有，比对上了也说明不了什么。
     */
    private const val MIN_ASK_CHARS = 4

    /**
     * 那句问法要有这么大比例的字组出现在新收的题干里，才算「就是这一问」。
     *
     * 不用「原样连着出现」：新旧两条都是 OCR 出来的，同一个字认成两个样是常事
     * （「迭到」/「送到」）。0.8 容得下一两个字不一样，又远高于巧合能撞上的水平。
     */
    private const val ASK_COVERAGE = 0.8

    /**
     * 新收的这条至少要比**那句问法本身**长这么多倍，才算「完整版」而不只是同题又收了一遍。
     *
     * 比的是问法、不是整条旧题干：旧题干里往往还带着题号（`2008U4-150 目前最重要的治疗方法是`），
     * 拿整条去比，长度被题号撑大，真正补全了材料的那种反而会被判成「没长多少」。
     */
    private const val GROWTH_RATIO = 1.5

    /**
     * 行首的题号：「2021U2-106」「26.」「(3)」……
     *
     * 必须要有一位数字才算数（`[0-9A-Za-z]*\d`），否则「DNA 聚合酶…」这种正经开头
     * 也会被当成题号削掉。新旧两条的题号往往对不上（新的是从屏幕上 OCR 出来的，
     * 题号可能没拍进去），所以比对之前要把它摘掉。
     */
    private val LEADING_NUMBER =
        Regex("""^\s*[(\[]?\s*[0-9A-Za-z]*\d[0-9A-Za-z-]*\s*[-.、)）\]．:：]?\s*""")

    /** 这条题干是不是「只有半截」。 */
    fun looksLikeFragment(stem: String): Boolean =
        Similarity.normalize(stem).length < FRAGMENT_CHARS

    /**
     * 把题号剥掉，只留「这一问问的是什么」（已归一化）—— **只看第一行**。
     *
     * 为什么只看第一行：残缺条目几乎都是「一行小问」，后面那些行是当初 OCR 时粘上来的
     * 半个选项或界面文字（他库里那条就是 `2023U2-115 首选的治疗` + `X A右侧侧斜疝修补术`，
     * 那一行前头还带着勾叉符号，连 [OcrText.optionLetterOf] 都认不出来）。
     * 把它们拼进来，等于要求新收的题干里也得有那些垃圾 —— 那就永远比不上了。
     */
    fun askOf(stem: String): String {
        val first = stem.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return ""
        return Similarity.normalize(LEADING_NUMBER.replace(first, ""))
    }

    /**
     * 新收的这道 [newStem] 是不是旧的那条 [oldStem] 的完整版。
     *
     * 三条都要成立：旧的是半截、新的明显更长、而且**旧的那句问法原样出现在新的里面**。
     */
    fun supersedes(oldStem: String, newStem: String): Boolean {
        if (!looksLikeFragment(oldStem)) return false
        val ask = askOf(oldStem)
        if (ask.length < MIN_ASK_CHARS) return false
        val fresh = Similarity.normalize(newStem)
        if (fresh.isEmpty()) return false
        // 「顶掉」的前提是新收的**比旧的更长**（补上了材料）。
        //
        // 这一条是 2026-10-12 补的兜底：那天的毛病是「新收的短题把自己认成半截旧条目」，
        // 用户一点「顶掉」就把刚收的题删了。自己跟自己比，长度当然一样 —— 这一句直接断掉
        // 那种情况，不管调用方把顺序写成什么样。
        if (fresh.length <= Similarity.normalize(oldStem).length) return false
        if (fresh.length < ask.length * GROWTH_RATIO) return false

        val askGrams = Similarity.bigramsOf(ask)
        if (askGrams.isEmpty()) return false
        val freshGrams = Similarity.bigramsOf(fresh)
        val covered = askGrams.count { it in freshGrams }
        return covered.toDouble() / askGrams.size >= ASK_COVERAGE
    }
}
