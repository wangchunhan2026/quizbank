package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜题的候选排序：题干分是主、解析只是最多 0.05 的加分。
 *
 * 这里锁的是**不变量**，不是某次排序的具体结果 —— 用户要的是「都命中的排前面」，
 * 但绝不能让一条明显更像的题被解析顶下去（见 [AnalysisRank] 的类注释）。
 */
class AnalysisRankTest {

    private data class Cand(val name: String, val stem: Double, val analysis: Double)

    private fun order(vararg items: Cand): List<String> =
        AnalysisRank.sorted(items.toList(), { it.stem }, { it.analysis }).map { it.name }

    @Test
    fun 咬得很紧时都命中的排在前面() {
        // 题干只差 0.04（0.90 对 0.86），解析那条是整段对上的 —— 用户要的就是它排前面
        val order = order(
            Cand("只命中题干", 0.90, 0.0),
            Cand("题干和解析都命中", 0.86, 1.0),
        )
        println("次序: " + order)
        assertEquals(listOf("题干和解析都命中", "只命中题干"), order)
    }

    @Test
    fun 题干明显更强的推不掉() {
        // 0.97 对 0.80，差得比 0.05 大 —— 解析满分也翻不过来（这是这条规矩的安全阀）
        val order = order(
            Cand("题干几乎一模一样", 0.97, 0.0),
            Cand("解析也对上但题干差得多", 0.80, 1.0),
        )
        println("次序: " + order)
        assertEquals(listOf("题干几乎一模一样", "解析也对上但题干差得多"), order)
    }

    @Test
    fun 解析相似度不到一半不加分() {
        assertEquals(0.0, AnalysisRank.bonus(0.0), 0.0001)
        assertEquals(0.0, AnalysisRank.bonus(0.49), 0.0001)
        assertEquals(0.0, AnalysisRank.bonus(AnalysisRank.MIN), 0.0001)
        // 刚过半加一点点，满分才是 0.05
        assertTrue(AnalysisRank.bonus(0.75) > 0.0)
        assertTrue(AnalysisRank.bonus(0.75) < AnalysisRank.BONUS)
        assertEquals(AnalysisRank.BONUS, AnalysisRank.bonus(1.0), 0.0001)
    }

    @Test
    fun 加分永远不超过那条小小的上限() {
        // 不管解析多像，加分都封在 BONUS 里 —— 这是「解析推翻不了明显更强的那条」的根据
        var s = 0.0
        while (s <= 1.0) {
            val bonus = AnalysisRank.bonus(s)
            assertTrue("解析 " + s + " 的加分 " + bonus + " 超了", bonus <= AnalysisRank.BONUS + 1e-9)
            s += 0.01
        }
    }
}
