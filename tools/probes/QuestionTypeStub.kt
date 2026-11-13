package com.daily.quizbank.data.db

/** 只为在电脑上跑解析器用的替身；内容与 Entities.kt 里的真身一致（去掉 Room 注解）。 */
object QuestionType {
    const val SINGLE = "单选题"
    const val MULTI = "多选题"
    const val JUDGE = "判断题"
    const val FILL = "填空题"
    const val ESSAY = "简答题"
    const val UNKNOWN = "未分类"

    val ALL = listOf(SINGLE, MULTI, JUDGE, FILL, ESSAY, UNKNOWN)

    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return UNKNOWN
        return when {
            t.contains("单选") || t.contains("single") -> SINGLE
            t.contains("多选") || t.contains("multiple") -> MULTI
            t.contains("判断") || t.contains("judge") || t.contains("对错") -> JUDGE
            t.contains("填空") || t.contains("fill") -> FILL
            t.contains("简答") || t.contains("问答") || t.contains("论述") || t.contains("essay") -> ESSAY
            else -> t
        }
    }
}
