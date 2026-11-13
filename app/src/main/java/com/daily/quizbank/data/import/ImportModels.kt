package com.daily.quizbank.data.import

import com.daily.quizbank.data.db.QuestionType

/** 解析出来的一张表（xlsx 的 sheet / docx 的表格 / csv 整体）。 */
data class ImportedSheet(
    val name: String,
    val rows: List<List<String>>,
) {
    val dataRowCount: Int get() = rows.count { row -> row.any { it.isNotBlank() } }
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

/** 待入库的题目草稿。此时还没有 bankId，允许用户在预览页改完再入库。 */
data class QuestionDraft(
    val stem: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    val analysis: String = "",
    val type: String = QuestionType.UNKNOWN,
    val tags: String = "",
    val difficulty: Int = 0,
    val selected: Boolean = true,
) {
    val blank: Boolean get() = stem.isBlank()
}

/** 表头识别结果，用于在预览页告诉用户「我是怎么理解你的表格的」。 */
data class ColumnMapping(
    val headerRowIndex: Int,
    val stemColumn: Int,
    val optionColumns: List<Int>,
    val combinedOptionColumn: Int,
    val answerColumn: Int,
    val analysisColumn: Int,
    val typeColumn: Int,
    val tagColumn: Int,
    val difficultyColumn: Int,
    val headers: List<String>,
) {
    val usedHeader: Boolean get() = headerRowIndex >= 0

    fun describe(): String {
        if (!usedHeader) return "未识别到表头，按「第一列=题干、第二列起=选项」处理"
        val parts = ArrayList<String>()
        parts.add("题干 ← 「" + headers.getOrElse(stemColumn) { "第" + (stemColumn + 1) + "列" } + "」")
        if (optionColumns.isNotEmpty()) {
            parts.add("选项 ← 「" + optionColumns.joinToString("、") { headers.getOrElse(it) { "?" } } + "」")
        } else if (combinedOptionColumn >= 0) {
            parts.add("选项 ← 「" + headers.getOrElse(combinedOptionColumn) { "?" } + "」")
        }
        if (answerColumn >= 0) parts.add("答案 ← 「" + headers.getOrElse(answerColumn) { "?" } + "」")
        if (analysisColumn >= 0) parts.add("解析 ← 「" + headers.getOrElse(analysisColumn) { "?" } + "」")
        if (typeColumn >= 0) parts.add("题型 ← 「" + headers.getOrElse(typeColumn) { "?" } + "」")
        if (tagColumn >= 0) parts.add("标签 ← 「" + headers.getOrElse(tagColumn) { "?" } + "」")
        return parts.joinToString("；")
    }
}

data class ParseResult(
    val drafts: List<QuestionDraft>,
    val mapping: ColumnMapping,
    val warnings: List<String>,
)

data class ImportSummary(
    val inserted: Int,
    /** 用户决定不入库的重复题（逐条核对时选的「留原来那条」+ 直接舍去的那些）。 */
    val duplicates: Int,
    val blank: Int,
    /**
     * 被判定为重复而跳过的题干样本。
     * 「跳过 12 道重复题」这句话用户没法凭空相信，得把具体是哪些列出来让他自己核对。
     */
    val duplicateSamples: List<String> = emptyList(),
    /**
     * 被**顶掉**的库里旧题数（用户在核对页选了「保留新导入的这条」）。
     *
     * 顶掉是删除，和「跳过」不是一回事：跳过只是这次没收，顶掉是库里那条真没了。
     * 所以完成页要单独报一句、把题干列出来 —— 删掉的东西必须让他看得见。
     */
    val replaced: Int = 0,
    val replacedSamples: List<String> = emptyList(),
    /** 近似重复（未必是同一道题）里选了「两条都留」的条数。 */
    val keptBoth: Int = 0,
    /**
     * 这次真正入库那几条的 id，顺序和入库时一致。
     *
     * 收录那条路要它才能「留旧的、把刚收的删掉」：用户点完确定才发现库里早就有一条更全的，
     * 得能把刚存进去的那条撤回来（见 CollectResult.savedIds）。
     */
    val insertedIds: List<Long> = emptyList(),
)
