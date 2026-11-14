package com.daily.quizbank.util

/** 共用题干题里的一个小问。 */
data class SubQuestion(
    /** 原文里的小问编号（"1"、"（2）"…），只用来显示。 */
    val label: String,
    /** 这一小问自己的内容：**含**它自己的选项，**不含**大题干和界面标签。 */
    val body: String,
)

/** 一道共用题干题：一个大题干（材料 / 病例）带若干小问。 */
data class MultiPart(
    val shared: String,
    val parts: List<SubQuestion>,
)

/**
 * 认出「共用题干题」，把大题干和各个小问拆开。
 *
 * 刷题软件里这类题有**两种长相**，都得认：
 *
 * **一、一屏显示一问**（医考帮的 A3/A4 型题就是这样，实测确认过）：
 *
 *     A3/A4 型题
 *     男，70岁，直肠指诊前列腺有一硬结
 *     PSA 20ng/ml
 *     第 1 问      第 2 问        ← 标签页，用户自己点着切换
 *     例 A-106 可能的诊断是
 *     A. 前列腺增生  B. 前列腺癌  ...
 *
 * 一屏只有一个问，靠「第 N 问」这排标签页当分界线：标签之前是材料，之后是当前这一问。
 * 用户想收第 2 问，得自己在刷题软件里切过去再拍一次。
 *
 * **二、一屏显示好几问**：材料底下接「1、…」带自己的选项，再「2、…」也带自己的选项。
 *
 * 两种都按「每小问各存一条、每条把大题干拼在自己题干前面」收 ——
 * 用户多半是分开拍的，整段存成一条的话，单独拍某一问时题库里没有任何一条能对上。
 *
 * 纯计算，不碰 Android，见 MultiPartSplitterTest。
 */
object MultiPartSplitter {

    /** 一屏多问时的小问编号：行首的 1、/ 2. / （3）/ (4) / 5． */
    private val MARKER = Regex(
        "^\\s*(?:" +
            "[（(]\\s*(\\d{1,2})\\s*[）)]\\s*[、.．,，:：]?" +
            "|" +
            "(\\d{1,2})\\s*[、.．,，:：]" +
            ")\\s*(.+)$",
    )

    /** 标签页里的一个「第 N 问」。 */
    private val TAB_TOKEN = Regex("第\\s*(\\d{1,2})\\s*[问間间問门题題]")

    /**
     * 这一行是不是标签栏；是的话给出里面所有的小问编号。
     *
     * 要求**整行除了标签什么都不剩**，免得把题干正文里的引用误判成标签。
     * 但**不能要求「整行只有一个标签」** —— 实测两个标签会挤在同一行上：
     *
     *     第1间 第2问
     *
     * 早先按「整行 =「第 N 问」」匹配，这一行就整个認不出来，于是拆不开：
     * 题干空着、整段原文全塞进问题 1。末字的形近字（问/间）也是实测逼出来的。
     */
    private fun tabLabelsOf(line: String): List<String> {
        val matches = TAB_TOKEN.findAll(line).toList()
        if (matches.isEmpty()) return emptyList()
        if (TAB_TOKEN.replace(line, "").isNotBlank()) return emptyList()
        return matches.map { it.groupValues[1] }
    }

    private const val MIN_BODY = 4
    private const val MIN_OPTIONS = 2
    private const val MIN_SHARED = 10

    fun split(text: String): MultiPart? {
        if (text.isBlank()) return null
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return null
        // 先试「一屏多问」——它的证据更强（每一问后面都跟着自己的选项）
        splitByNumberedParts(lines)?.let { return it }
        return splitByTabLabels(lines)
    }

    /** 长相二：一屏里「1、…」「2、…」各带选项。至少两问才算，只认出一问不足以定性。 */
    private fun splitByNumberedParts(lines: List<String>): MultiPart? {
        val marks = ArrayList<Pair<Int, String>>()
        for (index in lines.indices) {
            val match = MARKER.matchEntire(lines[index]) ?: continue
            val label = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (label.isEmpty()) continue
            if (match.groupValues[3].trim().length < MIN_BODY) continue
            marks.add(index to label)
        }
        if (marks.size < 2) return null

        val parts = ArrayList<SubQuestion>(marks.size)
        for (k in marks.indices) {
            val from = marks[k].first
            val to = if (k + 1 < marks.size) marks[k + 1].first else lines.size
            val body = lines.subList(from, to)
            if (body.count { OcrText.optionLetterOf(it) != null } < MIN_OPTIONS) return null
            parts.add(SubQuestion(label = marks[k].second, body = body.joinToString("\n")))
        }

        val shared = lines.subList(0, marks.first().first).joinToString("\n").trim()
        if (shared.length < MIN_SHARED) return null
        return MultiPart(shared = shared, parts = parts)
    }

    /**
     * 长相一：一屏一问，靠「第 N 问」标签页分界。
     *
     * 只返回**当前这一问**：切到第几问是用户在刷题软件里操作的，这边看不到，
     * 所以标签页取第一个的编号当名字，正文取最后一排标签之后的全部内容。
     */
    private fun splitByTabLabels(lines: List<String>): MultiPart? {
        val labels = ArrayList<Pair<Int, String>>()
        lines.forEachIndexed { index, line ->
            val found = tabLabelsOf(line)
            if (found.isEmpty()) return@forEachIndexed
            labels.add(index to found.first())
        }
        if (labels.isEmpty()) return null

        val shared = lines.subList(0, labels.first().first).joinToString("\n").trim()
        val bodyLines = lines.subList(labels.last().first + 1, lines.size)
        val body = bodyLines.joinToString("\n").trim()
        if (shared.length < MIN_SHARED) return null
        if (body.length < MIN_BODY) return null
        if (bodyLines.count { OcrText.optionLetterOf(it) != null } < MIN_OPTIONS) return null

        return MultiPart(
            shared = shared,
            parts = listOf(SubQuestion(label = labels.first().second, body = body)),
        )
    }
}
