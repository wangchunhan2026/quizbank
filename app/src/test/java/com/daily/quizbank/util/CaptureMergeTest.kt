package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「接着上一次识别」的接续逻辑。
 *
 * 用例里的文字都取自真机那几道生理题的 OCR 输出形态
 * （选项字母后面的点常常没了、一行一个选项）。
 */
class CaptureMergeTest {

    private val stem1 = "67男,70岁,因突发胸痛2小时入院"
    private val stem2 = "查体:血压90/60mmHg,心率110次/分"

    @Test
    fun 上一段是空的就原样接上() {
        assertEquals(stem1 + "\n" + stem2, CaptureMerge.text("", stem1 + "\n" + stem2))
    }

    @Test
    fun 新的一段整段空着就什么都不变() {
        assertEquals(stem1, CaptureMerge.text(stem1, "   \n  "))
    }

    @Test
    fun 不重合就直接接在后面() {
        val base = stem1 + "\n" + stem2
        val piece = "心电图示ST段抬高\nA急性心肌梗死\nB不稳定型心绞痛"
        assertEquals(base + "\n" + piece, CaptureMerge.text(base, piece))
    }

    @Test
    fun 故意重叠的那两行不会重复() {
        val base = stem1 + "\n" + stem2
        val piece = stem2 + "\nA急性心肌梗死\nB不稳定型心绞痛"
        assertEquals(
            stem1 + "\n" + stem2 + "\nA急性心肌梗死\nB不稳定型心绞痛",
            CaptureMerge.text(base, piece),
        )
    }

    @Test
    fun 重叠处那一行被切掉半截时用完整的版本() {
        // 上一次截图的下边缘正好把选项 A 切成半行，这一次开头是完整的
        val base = stem1 + "\nA胰蛋白酶的激活"
        val piece = "A胰蛋白酶的激活需要肠激酶\nB胃酸分泌"
        assertEquals(
            stem1 + "\nA胰蛋白酶的激活需要肠激酶\nB胃酸分泌",
            CaptureMerge.text(base, piece),
        )
    }

    @Test
    fun 滚回去把拍过的一段又拍一遍不会重复() {
        // 上一段已经拍到 D，用户往上翻又拍了一次 A~C
        val base = "题干第一段\nA选项甲\nB选项乙\nC选项丙\nD选项丁"
        val piece = "A选项甲\nB选项乙\nC选项丙"
        assertEquals(base, CaptureMerge.text(base, piece))
    }

    @Test
    fun 只有一行重复不算整段重复() {
        // 一行「A选项甲」也可能是正经要加的东西，不能凭它判重
        val base = stem1 + "\nA选项甲"
        val piece = "A选项甲\nB选项乙"
        assertEquals(base + "\nB选项乙", CaptureMerge.text(base, piece))
    }

    @Test
    fun 两条不同的选项不会被误判成同一行() {
        // 「宁可重复，不可误删」的守门用例：内容不同的两行任何时候都不许被当成同一行
        val base = "A动脉血压升高"
        val piece = "B动脉血压降低"
        assertEquals(base + "\n" + piece, CaptureMerge.text(base, piece))
    }

    @Test
    fun 标点半角全角不一样也算同一行() {
        // 同一块屏幕拍两次，逗号经常会飘；归一化之后是同一行
        val base = "納泵活动增强,细胞内液增多"
        val piece = "納泵活动增强，细胞内液增多\nB细胞外液减少"
        assertEquals(base + "\nB细胞外液减少", CaptureMerge.text(base, piece))
    }

    @Test
    fun 空行不参与比对也不写进结果() {
        assertEquals("题干第一段\nA选项甲", CaptureMerge.text("题干第一段\n\n", "\nA选项甲\n"))
    }
}
