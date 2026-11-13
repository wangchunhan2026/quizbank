package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 答案还原：把「C」变成「C. 合资」，把「A」变成「A. 对」。 */
class AnswerTextTest {

    private val 单选选项 = listOf("A. 国家", "B. 中国国家", "C. 合资", "D. 控股合资")
    private val 判断选项 = listOf("A. 对", "B. 错")

    @Test
    fun 单选题的字母答案还原成选项正文() {
        assertEquals("C. 合资", AnswerText.resolve("C", 单选选项))
        assertEquals("B. 中国国家", AnswerText.resolve("B", 单选选项))
    }

    @Test
    fun 判断题的字母答案还原成对或错() {
        assertEquals("A. 对", AnswerText.resolve("A", 判断选项))
        assertEquals("B. 错", AnswerText.resolve("B", 判断选项))
    }

    @Test
    fun 多选题答案全部列出来() {
        assertEquals("A. 国家、C. 合资", AnswerText.resolve("AC", 单选选项))
    }

    @Test
    fun 已经在选项里的文字答案也能还原() {
        assertEquals("A. 对", AnswerText.resolve("对", 判断选项))
        assertEquals("B. 错", AnswerText.resolve("错误", 判断选项))
    }

    /**
     * 判断题只填了「A」时，展示要能还原成「A. 正确」（2026-10-09 用户提的）。
     *
     * 前提是**选项表里有那一对**。收录判断题时如果只框了题干、没框到「A正确 B错误」，
     * 选项表是空的 —— 那种情况下无论怎么算都还原不出来，所以
     * QuestionRepository.saveCollected 在题型判为判断题、选项又为空时，
     * 会补一对标准的「A. 正确 / B. 错误」进去。
     */
    @Test
    fun 判断题只填A也能还原成A正确() {
        val 标准判断选项 = listOf("A. 正确", "B. 错误")
        assertEquals("A. 正确", AnswerText.resolve("A", 标准判断选项))
        assertEquals("B. 错误", AnswerText.resolve("B", 标准判断选项))
        // 答案写成「正确」「对」「√」时也要落到同一个选项上
        assertEquals("A. 正确", AnswerText.resolve("正确", 标准判断选项))
        assertEquals("A. 正确", AnswerText.resolve("对", 标准判断选项))
        assertEquals("B. 错误", AnswerText.resolve("错", 标准判断选项))
    }

    @Test
    fun 没有选项或答案不是字母时原样返回() {
        assertEquals("C", AnswerText.resolve("C", emptyList()))
        assertEquals("正确", AnswerText.resolve("正确", emptyList()))
        // 选项不带字母前缀也要能对上
        assertEquals("C. 合资", AnswerText.resolve("C", listOf("国家", "中国国家", "合资", "控股合资")))
        // 选项正文本身以字母开头时不能被误当成前缀剥掉
        assertEquals("A. A型车", AnswerText.resolve("A", listOf("A型车", "B型车")))
    }
}
