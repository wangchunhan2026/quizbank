package com.daily.quizbank.util

/**
 * 导入题库这条路上「重复题该留哪一条」的全部判断。
 *
 * 为什么单独一个文件：这些判断的调用方是 [com.daily.quizbank.data.repo.QuestionRepository]
 * 和导入界面，而仓库层带 Room，第五节那条跑单测的 JVM 命令编译不了它 —— 于是这块最容易出错、
 * 出错就是**用户库里的题被顶掉或被少收**的逻辑，一直是没有测试的代码。这里只放不碰数据库的纯计算，
 * 配 DedupPolicyTest 一起跑。
 *
 * 术语（界面上也是这么说的）：
 *
 * - **新的那条**：这次文件里要导入的那道题（草稿）；
 * - **原来那条**：对照项，优先是题库里已有的那条，其次才是**同一份文件里更早出现**的那条；
 * - **一模一样**：去空白 + 转小写之后完全相同（QuestionRepository.dedupKey，**保留标点**）；
 * - **近似**：去掉题号取前 8 个字分桶、同桶内 [Similarity.score] ≥ [NEAR_DUPLICATE]。
 *   近似**只说明像**，不说明是同一道题 —— 库里真实存在只差两个字、答案却不同的两道题。
 */
object DedupPolicy {

    /** 分桶键取前几个字。和合并题库、找相似题同一套（三处共用 [bucketKey] 这一个实现）。 */
    const val BLOCK_SIZE = 8

    /** 分桶键短于这个长度就不参与近似比较：两三个字撞车是常态，不是同一道题。 */
    const val BLOCK_MIN_LENGTH = 5

    /** 相似度到多少算「疑似同一道」。 */
    const val NEAR_DUPLICATE = 0.88

    /**
     * 「一条几乎整个出现在另一条里」的覆盖率下限 —— 第三档判据。
     *
     * 管的是**同一条题、上次没收完**留下的那半截：材料只拍了一半、或者只填了小问没填大题干。
     * 这种两条的整串 Dice 只有五成上下（分母里挂着长的那条），够不到 0.88 那条线，
     * 所以必须单独有这一档 —— 2026-10-13 用户报的「找相似题说没有相似题」就是这个形态。
     */
    const val CONTAIN_COVERAGE = 0.90

    /**
     * 短的那条归一化后不到这个长度就不参与第三档。
     *
     * 「最可能的诊断是」这种通用问法到处都能被盖住，放进来只会刷屏；
     * 而他真正的半截条目（「2021U2-106 可能的诊断是」）归一化后有十几个字，正好在上限之上。
     */
    const val CONTAIN_MIN_LENGTH = 15

    /** 第三档的分桶步长：每 8 个字挂一个 8 字窗口（见 [containKeys]）。 */
    const val CONTAIN_STRIDE = 8

    /** 结尾比对取末尾几个字（见 [tailMatches]）。 */
    const val CONTAIN_TAIL_LENGTH = 10

    /** 结尾比对的宽严：末尾那几个字组里有多少要能在长的那条末尾找到。 */
    const val CONTAIN_TAIL_MATCH = 0.7

    /** 去分桶键时剥掉的开头题号（「30.」「(1)」这类）。 */
    private val LEADING_NUMBER = Regex("""^\d{1,4}\s*[).、．:：]?\s*""")

    /**
     * 归一化 + 剥掉开头题号之后的题干：分桶、包含判断都用它。
     *
     * 归一化会去掉所有标点和空白；题号会变（刷题软件的序号、OCR 多认的数字），不去掉的话
     * 同一道题会被分进两个桶。**只在这一处实现** —— 合并题库、找相似题、导入判重三处口径必须一样，
     * 否则同两条题会在「合并」里说重复、在「导入」里说不重复。
     */
    fun normalizedStem(stem: String): String =
        Similarity.normalize(stem).replaceFirst(LEADING_NUMBER, "")

    /** 取前几个字当分桶键。 */
    fun bucketKey(stem: String): String = normalizedStem(stem).take(BLOCK_SIZE)

    /** 分桶键太短就不参与近似比较。 */
    fun bucketUsable(key: String): Boolean = key.length >= BLOCK_MIN_LENGTH

    /** 近似档的判据。边界值算近似 —— 宁可多问用户一句，不可少问。 */
    fun isNear(score: Double): Boolean = score >= NEAR_DUPLICATE

    /**
     * 第三档的分桶键：**开头一个，往后每 8 个字再挂一个 8 字窗口**。
     *
     * 这一档不能只用开头八个字分桶：半截那条可能从中间某个字开始（材料只拍到了后半段，
     * 或者是「只有小问、没有材料」的那种）。两条只要有一段连续重合超过 8 个字，
     * 就一定会挂在同一个桶下面。
     */
    fun containKeys(stem: String): List<String> {
        val s = normalizedStem(stem)
        if (s.length < BLOCK_SIZE) return listOf(s)
        val keys = ArrayList<String>(s.length / CONTAIN_STRIDE + 1)
        keys.add(s.take(BLOCK_SIZE))
        var start = CONTAIN_STRIDE
        while (start + BLOCK_SIZE <= s.length) {
            keys.add(s.substring(start, start + BLOCK_SIZE))
            start += CONTAIN_STRIDE
        }
        return keys
    }

    /**
     * 「两条里短的那条，有多少能整条在长的那条里找到」（0~1）—— 第三档的判据本体。
     *
     * 拿字组（bigram）覆盖率算，和 [Similarity.matchDetail] 里「覆盖率」那条路线是同一个套路：
     * 整串 Dice 会被长度差拖垮，覆盖率不会。短的那条太短就直接返回 0（见 [CONTAIN_MIN_LENGTH]）。
     */
    fun partCoverage(one: String, other: String): Double {
        val a = Similarity.normalize(one)
        val b = Similarity.normalize(other)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        if (short.length < CONTAIN_MIN_LENGTH) return 0.0
        val grams = Similarity.bigramsOf(short)
        if (grams.isEmpty()) return 0.0
        val pool = Similarity.bigramsOf(long)
        var hit = 0
        for (gram in grams) if (gram in pool) hit++
        return hit.toDouble() / grams.size
    }

    /**
     * 两条的结尾对不对得上：短的那条末尾那一小段，长的那条的**末尾**里也有。
     *
     * 这一条是拿来挡**同一个材料下的不同小问**的：共用题干题每条都拼着同一段材料，
     * 于是「材料 + 第 1 问」和「材料 + 第 2 问」互相的覆盖率也能到 0.9 上下
     * （材料比小问长得多），光看覆盖率会把同一道题的几个小问判成「重复」——
     * 那就等于建议用户把好好的小问删掉。而真正没收完的那两条，尾巴是**同一句小问**，
     * 所以只有它们过得了这一关。
     */
    fun tailMatches(one: String, other: String): Boolean {
        val a = Similarity.normalize(one)
        val b = Similarity.normalize(other)
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        if (short.length < CONTAIN_TAIL_LENGTH / 2) return false
        val tail = short.substring(short.length - minOf(CONTAIN_TAIL_LENGTH, short.length))
        // 长的那边末尾留出两倍长度去找：尾巴上多认/漏认一两个字也容得下
        val region = long.substring(maxOf(0, long.length - tail.length * 2))
        val grams = Similarity.bigramsOf(tail)
        if (grams.isEmpty()) return false
        val pool = Similarity.bigramsOf(region)
        var hit = 0
        for (gram in grams) if (gram in pool) hit++
        return hit.toDouble() / grams.size >= CONTAIN_TAIL_MATCH
    }

    /**
     * 短的那条是不是几乎整个都在长的那条里，而且**两条说的是同一句小问** ——
     * 也就是「同一条题、上次没收完留下的那半截」。
     */
    fun isPartOf(one: String, other: String): Boolean =
        partCoverage(one, other) >= CONTAIN_COVERAGE && tailMatches(one, other)

    /** 用户在核对页上的三个选择。 */
    enum class Keep {
        /** 留这次文件里的这条。库里那条要是同一道题，会被顶掉。 */
        NEW,

        /** 留原来那条，这次这条不入库。 */
        OLD,

        /** 两条都留（近似的多半不是同一道题，一模一样的两条也允许都留）。 */
        BOTH,
    }

    /**
     * 对照的那条题，只带判断和摆卡片用得上的几栏 ——
     * 刻意不直接用 QuestionEntity：那样这个文件就得依赖 Room，也就进不了单测。
     */
    data class Counterpart(
        /** 题库里那条的 id；文件内部对照时为 -1。 */
        val id: Long = -1L,
        /** true = 就在这次要导入的目标题库里。false = 文件里更早那条，或者别的题库里那条。 */
        val inBank: Boolean = false,
        /** 在别的题库里时，那个题库的名字；其余为 null。 */
        val otherBankName: String? = null,
        val stem: String = "",
        val answer: String = "",
        val options: List<String> = emptyList(),
        val analysis: String = "",
        val tags: String = "",
        val starred: Boolean = false,
    ) {
        /** 对照项在别的题库里 —— 导入动不了它，只能用「这条不入库」来避开重复。 */
        val otherBank: Boolean get() = otherBankName != null
    }

    /** 这次要导入的那条题，同样只带用得上几栏。 */
    data class Newcomer(
        val answer: String = "",
        val options: List<String> = emptyList(),
        val analysis: String = "",
        val tags: String = "",
    )

    /** 一条重复题核对完之后该做什么。 */
    data class Decision(
        /** 该丢掉的草稿下标（在文件里的位置）；-1 = 不丢。 */
        val dropDraftIndex: Int = -1,
        /** 该从题库里删掉的旧题 id；-1 = 不删。 */
        val deleteQuestionId: Long = -1L,
    )

    /**
     * 用户点了哪一个，对应到具体动作。
     *
     * 「留新的」要**真的把库里那条顶掉**，否则这一选等于没选：库里两条都在，
     * 而拍题匹配取的是先入库的那条（id 小的优先），答题时用的反而是他刚否掉的那一份。
     */
    fun decide(
        keep: Keep,
        draftIndex: Int,
        earlierIndex: Int,
        counterpart: Counterpart,
    ): Decision = when (keep) {
        // 文件里更早那份是同一道题的第二份拷贝，跟着一起丢；
        // 别的题库里那条不归这次导入管，不能删。
        Keep.NEW -> Decision(
            dropDraftIndex = earlierIndex,
            deleteQuestionId = if (counterpart.inBank) counterpart.id else -1L,
        )
        Keep.OLD -> Decision(dropDraftIndex = draftIndex)
        Keep.BOTH -> Decision()
    }

    /**
     * 「剩下都保留新导入的 / 剩下都保留原来那条」这两个快捷出口，作用在某一条上时实际按哪个处理。
     *
     * **近似的那几条不跟着按钮走，一律「两条都留」**：近似只说明像，题库里真实存在
     * 只差两个字、答案却不同、本来就是两道题的情况（「防堵、防冻、（）」和「防堵、防冻、解冻」）。
     * 让一个按钮把它们批量顶掉，等于用户一句话丢掉了自己半道题，而且他还看不见丢了什么。
     */
    fun restKeep(keepNew: Boolean, near: Boolean): Keep =
        if (near) Keep.BOTH else if (keepNew) Keep.NEW else Keep.OLD

    /** 两边答案或选项对不上 —— 对不上的必须用户自己点，程序不替他默认。 */
    fun hasConflict(counterpart: Counterpart, newcomer: Newcomer): Boolean =
        counterpart.answer.trim() != newcomer.answer.trim() ||
            counterpart.options != newcomer.options

    /**
     * 题库那条比这次这条多出来的栏目 —— 顶掉它就会跟着没了的那些。
     *
     * 解析、知识点、星标这些多半是用户后来一点点补进去的（文件里通常没有），
     * 是**他手里唯一的一份**。有这些栏目时默认选「留原来那条」（见 [defaultKeep]）。
     */
    fun extraFields(counterpart: Counterpart, newcomer: Newcomer): List<String> {
        val out = ArrayList<String>(4)
        if (counterpart.analysis.isNotBlank() && newcomer.analysis.isBlank()) out.add("解析")
        if (counterpart.tags.isNotBlank() && newcomer.tags.isBlank()) out.add("知识点")
        if (counterpart.answer.isNotBlank() && newcomer.answer.isBlank()) out.add("答案")
        if (counterpart.options.isNotEmpty() && newcomer.options.isEmpty()) out.add("选项")
        if (counterpart.starred) out.add("星标")
        return out
    }

    /**
     * 用户还没点之前，这一条默认选哪个；**null = 必须他自己点**。
     *
     * - **近似 → 两条都留。** 近似不是判据是提醒，替用户删掉一条可能完全不同的问题题不可接受；
     * - **对照项在别的题库里 → 留新的。** 那条不会被删，导入照旧，重复的代价只是两个库里各一份；
     * - **答案/选项对不上 → 不预设。** 真实题库里同一道题两份答案不同是常事，留哪条只有他知道；
     * - **旧的那条还带着文件里没有的栏目 → 留原来那条。** 别把解析、知识点、星标连旧题一起删掉；
     * - **其余（一模一样、信息不多不少）→ 留新的。** 这正是「重导一份修好的文件」要的效果。
     */
    fun defaultKeep(near: Boolean, counterpart: Counterpart, newcomer: Newcomer): Keep? = when {
        near -> Keep.BOTH
        counterpart.otherBank -> Keep.NEW
        hasConflict(counterpart, newcomer) -> null
        extraFields(counterpart, newcomer).isNotEmpty() -> Keep.OLD
        else -> Keep.NEW
    }

    /**
     * 最后到底顶掉哪几条旧题。
     *
     * 库里同一条题可能被文件里好几道题同时指着（OCR 抖动让文件里留下了两条近似拷贝）。
     * 只要还有一道选了「保留原来那条」，这条旧题就不能删 —— 删了它，那道题的对照项也没了，
     * 变成两边都没有。
     */
    fun replaceIds(picked: Collection<Long>, keptOld: Collection<Long>): Set<Long> =
        picked.filter { it > 0L && it !in keptOld }.toSet()
}
