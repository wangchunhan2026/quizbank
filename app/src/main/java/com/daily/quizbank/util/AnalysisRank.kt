package com.daily.quizbank.util

/**
 * 搜题时「解析也参与排序」的那条规矩（2026-10-12 用户要的）。
 *
 * 用户的原话是：**题干 + 解析都命中 > 只命中题干 > 只命中解析**，
 * 而且**仅限搜题**、结果**不许重复**。这里实现的是那条次序里真正会发生的前两档，
 * 做法是**只加分、不硬分档**：
 *
 *  - 题干分照旧是 [Similarity.matchDetail] 给的那个数（也就是面板上那个「相似度」）；
 *  - 解析和目标题解析的相似度超过 [MIN] 之后，最多再加 [BONUS]（0.05）分用来排序。
 *
 * 为什么不照字面做严格的「都命中 > 只题干 > 只解析」三档：
 *
 *  1. 搜题粘进来的是用户自己复制的文字，题干一定是准的 ——「只命中解析」在搜题里几乎
 *     不会发生（题干真在库里就该是接近满分命中），为它设一档是给一个不存在的形态写代码；
 *  2. 硬分档会把一条 0.82 的「都命中」顶到 0.97 的「只命中题干」前面，而后者才是那道理。
 *     0.05 的加分只在两条候选**本来就咬得很紧**（题干分相差不到 0.05）时才改变先后，
 *     永远推翻不了一条明显更像的题。
 *
 * **采信与否不看这里。** 能不能直接拿题库答案（[Similarity.MATCH_THRESHOLD]）用的仍然是
 * 纯题干分数，所以这个函数只会改变「先看哪条」，不会让一道本来不该答的题被答出来。
 */
object AnalysisRank {

    /** 解析相似度低于这个数就一点都不加分 —— 解析是一大段话，两道不相干的题也常有半数字组重叠。 */
    const val MIN = 0.5

    /** 解析命中最多加这么多分，用来排序。定这么小是故意的，见类注释。 */
    const val BONUS = 0.05

    /** 解析那边给的加分，0 ~ [BONUS]。 */
    fun bonus(analysisScore: Double): Double {
        val boost = ((analysisScore - MIN) / (1.0 - MIN)).coerceIn(0.0, 1.0)
        return BONUS * boost
    }

    /** 排序用的分数 = 题干分 + 解析加分。**不要拿它当相似度显示给用户**。 */
    fun rankScore(stemScore: Double, analysisScore: Double): Double =
        stemScore + bonus(analysisScore)

    /**
     * 按 [rankScore] 从高到低排一遍。分数相同的保持原来的先后（[sortedByDescending] 是稳定排序，
     * 原序本来就是题干分从高到低，所以并列时仍然是题干分高的那条在前）。
     */
    fun <T> sorted(
        items: List<T>,
        stemScore: (T) -> Double,
        analysisScore: (T) -> Double,
    ): List<T> = items.sortedByDescending { rankScore(stemScore(it), analysisScore(it)) }
}
