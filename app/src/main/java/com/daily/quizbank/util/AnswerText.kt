package com.daily.quizbank.util

/**
 * 把答案还原成人能直接读的形式。
 *
 * 题库里的答案绝大多数是一个字母（判断题是 A/B，选择题是 A~D）。只显示「答案：C」
 * 对用户是没有用的 —— 他还得自己跑到题目里数到第三个选项，而拍题时题目已经在屏幕外面了；
 * 更别说面板经常同时给出好几条候选（相似度 100% / 53% / …），要用户自己挑一条，
 * 就必须让他看到每条候选各自的选项和答案。
 *
 * 所以凡是展示答案的地方，都过一遍这里：字母 + 选项表 → 「C. 合资」。
 */
object AnswerText {

    /** 选项序号 → 字母。第 0 个是 A。 */
    fun letterOf(index: Int): Char = ('A' + index)

    /** 只能取到 A~H，多选答案也用这个范围。 */
    private val ANSWER_LETTERS = 'A'..'H'

    /** 去掉选项自带的「A.」「A、」「A)」前缀，只留正文。 */
    fun body(option: String): String {
        val trimmed = option.trim()
        val match = PREFIX.matchEntire(trimmed) ?: return trimmed
        return match.groupValues[2].trim()
    }

    /** 选项行最终显示成什么样：「A. 合资」。 */
    fun optionLine(index: Int, option: String): String {
        val text = body(option)
        return if (text.isEmpty()) option.trim() else letterOf(index) + ". " + text
    }

    /**
     * 「C」+ 选项表 → 「C. 合资」；多选题答案「AB」→ 「A. xx、B. yy」。
     *
     * 答案不是字母时（比如判断题填了「正确」，或者简答题本来就是一整段话）原样返回。
     */
    fun resolve(answer: String, options: List<String>): String {
        val raw = answer.trim()
        if (raw.isEmpty() || options.isEmpty()) return raw

        val picked = ArrayList<String>()
        for (ch in raw.uppercase()) {
            if (ch !in ANSWER_LETTERS) continue
            val index = ch - 'A'
            val option = options.getOrNull(index) ?: continue
            picked.add(optionLine(index, option))
        }
        if (picked.isNotEmpty()) return picked.joinToString("、")

        // 答案是整段文字（「正确」「错误」）时，按内容在选项里找一遍。
        // 注意「正确」和「对」是同一个意思的两套写法：导入时 对/是/√ 会被统一成「正确」，
        // 而判断题的选项往往写的是「对」，直接比字符串是对不上的。
        val byText = options.indexOfFirst { sameMeaning(body(it), raw) }
        if (byText >= 0) return optionLine(byText, options[byText])

        return raw
    }

    private fun sameMeaning(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val x = a.uppercase()
        val y = b.uppercase()
        return (x in TRUTHY && y in TRUTHY) || (x in FALSY && y in FALSY)
    }

    private val PREFIX = Regex("""^\s*[（(]?\s*([A-Ha-h])\s*[).、．:：,，]\s*(.+)$""")

    /** 「对」的各种写法。与 QuestionParser 里的口径保持一致。 */
    private val TRUTHY = setOf("正确", "对", "是", "√", "T", "TRUE", "YES", "Y")
    private val FALSY = setOf("错误", "错", "否", "×", "X", "F", "FALSE", "NO", "N")
}
