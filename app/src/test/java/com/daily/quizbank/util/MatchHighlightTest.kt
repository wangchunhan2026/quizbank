package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结果面板上把题干里「和这次输入对得上」的片段标红。 */
class MatchHighlightTest {

    @Test
    fun 对上的那一段标出来() {
        val source = "36.甲硫氨酸循环的生理意义是"
        val stem = "36.甲硫复氨酸循环的生理意义是（多认了一个字）"
        val ranges = MatchHighlight.ranges(source, stem)
        val marked = ranges.joinToString(" | ") { stem.substring(it.first, it.last + 1) }
        println("标红: " + marked)
        assertTrue("应当标出对上的那段", marked.contains("甲硫"))
        assertTrue("多认的那个字不该算对上", !marked.contains("复氨"))
    }

    @Test
    fun 标点是标在原文上的() {
        val stem = "本规程适用于（）铁路集团有限公司"
        val ranges = MatchHighlight.ranges("本规程适用于铁路集团", stem)
        val marked = ranges.joinToString("") { stem.substring(it.first, it.last + 1) }
        println("标红: " + marked)
        // 归一化把（）去掉了，但标红要落回**原文**的下标上，否则整体会错位一位。
        // 对上的是连着的那 9 个字，中间那个（）留在标红段里不算错 —— 删了它反而会
        // 在「于」和「铁」之间留一对黑括号。
        assertEquals("本规程适用于（）铁路集团", marked)
    }

    @Test
    fun 太短的巧合不算() {
        // 只有一个「的」字相同，不该标
        assertEquals(0, MatchHighlight.ranges("完全不一样的题干", "此题说的是另一回事").size)
    }
}
