package com.daily.quizbank.util

/**
 * 找出题干里**与这次拍/搜的原文对得上**的那些片段，给结果面板标红用。
 *
 * 为什么需要：题库里那一条和屏幕上这一条常常只差一两个字（OCR 认错、题库录入时少打一个），
 * 光看两段文字很难发现 —— 用户要的是一眼看出「哪一段对上了、哪一段对不上」。
 *
 * 判据故意简单：把题干切成字符，凡是**连着三个字以上**都能在这次输入里找到的，算对上。
 * 三个字起是量的结果：「的」「是」这种单字到处都是，两个字也常撞，三个字以上才说明
 * 真的是一段相同的话。
 */
object MatchHighlight {

    /** 连着这么多字能在输入里找到，才算「这一段对上了」。 */
    private const val MIN_RUN = 3

    /**
     * @param source 这次拍到的 OCR 原文 / 用户搜题打的字
     * @param stem 要标的那条题干（返回的下标是**原始字符串**里的下标，可直接拿去加 span）
     */
    fun ranges(source: String, stem: String, minRun: Int = MIN_RUN): List<IntRange> {
        val src = Similarity.normalize(source)
        if (src.isEmpty() || minRun < 1) return emptyList()

        // 题干去标点之后的下标 → 原始下标。标红要标在原文上，所以这张表必须有。
        val norm = StringBuilder(stem.length)
        val rawIndex = ArrayList<Int>(stem.length)
        for (i in stem.indices) {
            val c = stem[i]
            if (c.isLetterOrDigit()) {
                norm.append(c.lowercaseChar())
                rawIndex.add(i)
            }
        }
        val text = norm.toString()
        if (text.length < minRun) return emptyList()

        val out = ArrayList<IntRange>()
        var i = 0
        while (i <= text.length - minRun) {
            if (!src.contains(text.substring(i, i + minRun))) {
                i++
                continue
            }
            // 对上了就从这里尽量往右延伸
            var end = i + minRun
            while (end < text.length && src.contains(text.substring(i, end + 1))) end++
            out.add(rawIndex[i]..rawIndex[end - 1])
            i = end
        }
        return out
    }
}
