package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 清洗：把选项前面那个被认成字母 O 的圆圈去掉。
 *
 * 样本全部来自真机截图（微信图片_20261008103925_22_13.jpg 那道生理题）的识别结果。
 */
class OcrTextTest {

    @Test
    fun 行首的圈连着选项字母一起去掉() {
        assertEquals("A. 成年女性的体液量约占体重的 60%", OcrText.clean("O A. 成年女性的体液量约占体重的 60%"))
        assertEquals("B ． 细胞内液量在男性约占体重的 40%", OcrText.clean("O B ． 细胞内液量在男性约占体重的 40%"))
    }

    @Test
    fun 圈和字母之间没有空格也能去掉() {
        assertEquals("A·成年女性", OcrText.clean("@A·成年女性"))
        assertEquals("A．成年女性", OcrText.clean("OA．成年女性"))
    }

    @Test
    fun 实心圈和空心圈都认() {
        assertEquals("C 血浆约占体重的 10%", OcrText.clean("● C 血浆约占体重的 10%"))
        assertEquals("D·脑脊液", OcrText.clean("◎ D·脑脊液"))
    }

    @Test
    fun 判断题那种字母后跟空格的也去掉() {
        assertEquals("A 正确", OcrText.clean("O A 正确"))
        assertEquals("B 错误", OcrText.clean("○ B 错误"))
    }

    @Test
    fun 整行只有一个圈的直接丢掉() {
        val raw = "O\nC 血浆约占体重的 10%\n●\nD·脑脊液"
        assertEquals("C 血浆约占体重的 10%\nD·脑脊液", OcrText.clean(raw))
    }

    @Test
    fun 正文里本来就是O或0开头的不能被误伤() {
        assertEquals("O型血的人", OcrText.clean("O型血的人"))
        assertEquals("0.5 倍", OcrText.clean("0.5 倍"))
        assertEquals("O 型血的分布", OcrText.clean("O 型血的分布"))
        // 「O」后面跟汉字，不是选项字母，一律不动
        assertEquals("◎ 考点还原", OcrText.clean("◎ 考点还原"))
    }

    @Test
    fun 字母后面不是分隔符的也不算选项() {
        // C9 是电量角标那种东西，不是选项 C；既然认不出是选项，圈也不能动它
        assertEquals("O C9", OcrText.clean("O C9"))
        assertEquals("O 1", OcrText.clean("O 1"))
    }

    @Test
    fun 没有圈的选项原样保留() {
        assertEquals("E. 细胞外液和细胞内液的渗透压", OcrText.clean("E. 细胞外液和细胞内液的渗透压"))
        assertEquals("确的", OcrText.clean("确的"))
    }

    @Test
    fun 整段真实识别结果清洗后与预期一致() {
        val raw = listOf(
            "2009U1-76 下列关于体液的叙述，哪项是正确的",
            "@ A·成年女性的体液量约占体重的 60％",
            "O B．细胞内液量在男性约占体重的 40％，绝大",
            "多数存在于骨骼肌中",
            "O",
            "C 血浆约占体重的 10％",
            "O",
            "D·脑脊液 关节液 消化液等属于功能性细胞外",
            "O",
            "E. 细胞外液和细胞内液的渗透压一般为 260、",
            "280mOsm/L",
        ).joinToString("\n")

        val expected = listOf(
            "2009U1-76 下列关于体液的叙述，哪项是正确的",
            "A·成年女性的体液量约占体重的 60％",
            "B．细胞内液量在男性约占体重的 40％，绝大",
            "多数存在于骨骼肌中",
            "C 血浆约占体重的 10％",
            "D·脑脊液 关节液 消化液等属于功能性细胞外",
            "E. 细胞外液和细胞内液的渗透压一般为 260、",
            "280mOsm/L",
        ).joinToString("\n")

        assertEquals(expected, OcrText.clean(raw))
    }

    @Test
    fun 同一个圈被认成四种东西都要清掉() {
        // 2026-10-08 那屏生理题的 ML Kit 原文（用 debug 里的 OcrProbe 抓的）。
        // 五个选项前面是同一个圆形单选按钮，被认成了四种写法：
        // 丢掉 / O / (O / C。用户看到的就是「D 和 E 变成了 CD 和 CE」。
        val raw = listOf(
            "2024ESU1-11 属于正反馈调节的是",
            "A.胰蛋白酶的激活",
            "OB吃食物后的胃酸分泌",
            "C.睡液的分泌",
            "(OD动脉血压的调节",
            "CE.胰岛素的分泌",
        ).joinToString("\n")

        val expected = listOf(
            "2024ESU1-11 属于正反馈调节的是",
            "A.胰蛋白酶的激活",
            "B吃食物后的胃酸分泌",
            "C.睡液的分泌",
            "D动脉血压的调节",
            "E.胰岛素的分泌",
        ).joinToString("\n")

        assertEquals(expected, OcrText.clean(raw))
    }

    @Test
    fun 孤零零一个可能被认错的圈要看下一行才敢丢() {
        // 下一行是另一个选项 → 这行其实就是上一个选项的圈，丢掉
        assertEquals("D. 动脉血压", OcrText.clean("C\nD. 动脉血压"))
        // 下一行也是 C → 它可能真的是个 C，留着别动
        assertEquals("C\nC. 睡液的分泌", OcrText.clean("C\nC. 睡液的分泌"))
    }

    @Test
    fun 以字母开头的正常内容不会被误伤() {
        assertEquals("B超检查", OcrText.clean("B超检查"))
        assertEquals("C反应蛋白", OcrText.clean("C反应蛋白"))
        assertEquals("ECG检查", OcrText.clean("ECG检查"))
        assertEquals("CD4 阳性", OcrText.clean("CD4 阳性"))
        assertEquals("OGTT 试验", OcrText.clean("OGTT 试验"))
    }

    @Test
    fun 圈被认成选项字母本身时要去掉多出来的那一个() {
        // 真机 2025ESU1-24 那道题，选项 D 存成了「DD.化学感受性呼吸反射」
        assertEquals("D.化学感受性呼吸反射", OcrText.clean("DD.化学感受性呼吸反射"))
        // 去掉多出来的那一个，留下真正的选项字母
        assertEquals("E 选项 E", OcrText.clean("EE 选项 E"))
        // 正常选项不能动
        assertEquals("D.肺牵张反射", OcrText.clean("D.肺牵张反射"))
    }

    @Test
    fun 圈粘在折行尾巴上时不留下重复的一行() {
        // 真机 2011U4-46 那道题，题库里存成了「…反应进 / E行 / 行」三行
        assertEquals("E.细胞内高钾离子保证许多组细胞代谢反应进\n行", OcrText.clean("E.细胞内高钾离子保证许多组细胞代谢反应进\n行"))
        assertEquals("行", OcrText.clean("E行\n行"))
        assertEquals("B超\n检查", OcrText.clean("B超\n检查"))
    }

    /**
     * 2026-10-09 真机：题干折行的行首粘着一根竖线（「|反应是」）。
     * 那是刷题软件题干左边那条红色标记线被认成了竖线，整段清洗时要去掉。
     */
    @Test
    fun 行首的竖线会被去掉() {
        assertEquals("反应是", OcrText.clean("|反应是"))
        assertEquals("A1型题 1分", OcrText.clean("|A1型题 1分"))
        // 行内的竖线不动 —— 那多半是表格或分隔符
        assertEquals("甲|乙", OcrText.clean("甲|乙"))
        // 整行本来就是一根竖线时原样留着，免得得到空行
        assertEquals("|", OcrText.clean("|"))
    }

    /**
     * 2026-10-09 真机：选中/答错的那个图标被认成了「x)」，粘在 B 选项前面。
     *
     * 那一行整块是「x)B.dADP、dGDP、dCDP、dTDP」。字母不在行首，
     * 于是选项一个都拆不出来 —— 五个选项全被塞进了题干（用户就是这么报的）。
     */
    @Test
    fun 选项前的勾叉图标也要清掉() {
        assertEquals("B.dADP、dGDP、dCDP、dTDP", OcrText.clean("x)B.dADP、dGDP、dCDP、dTDP"))
        assertEquals("A. 正确", OcrText.clean("✗A. 正确"))
        assertEquals("C. 丙", OcrText.clean("√) C. 丙"))
        // 「xB超检查」这种本来就没有选项字母的行不能被误伤
        assertEquals("xB超检查", OcrText.clean("xB超检查"))
        // 「x = 3」是正文，不能动
        assertEquals("x = 3，代入得", OcrText.clean("x = 3，代入得"))
    }

    /**
     * 「这一行看着像被认废了」——2026-10-09 真机，那一屏上一个拉丁字母都没有。
     *
     *     E.梨状肌   →  认成了  E.NAL
     *     C.沙门菌   →  认成了  C.JÜ
     *
     * 这种不是「认错一个字」，是模型凭空编出来的，所以单独挑出来交给上面放大重认。
     */
    @Test
    fun 认成拉丁乱码的选项行会被挑出来() {
        assertTrue(OcrText.looksBrokenOptionLine("E.NAL"))
        assertTrue(OcrText.looksBrokenOptionLine("C.JÜ"))
        // 中文选项行是正常的，不该被当成乱码
        assertTrue(!OcrText.looksBrokenOptionLine("A.闭孔内肌"))
        assertTrue(!OcrText.looksBrokenOptionLine("B.副溶血性孤菌"))
        // 本来就用拉丁文写的选项会命中（C.ATP），但重认之后中文不会变多，
        // 所以最多白跑一次识别，不会被改坏 —— 这条判据只需要「不放过」
        assertTrue(OcrText.looksBrokenOptionLine("C.ATP"))
        // 不是选项行的一律不管：题面里的英文、纯数字都不该触发重认
        assertTrue(!OcrText.looksBrokenOptionLine("QRS波群时限大于0.12s"))
        assertTrue(!OcrText.looksBrokenOptionLine("DNA"))
        assertTrue(!OcrText.looksBrokenOptionLine("90/150"))
        assertTrue(!OcrText.looksBrokenOptionLine("答案： 正确答案E"))
    }

    @Test
    fun 选项字母的识别同时对清洗和框选负责() {
        assertEquals('A', OcrText.optionLetterOf("@ A·成年女性"))
        assertEquals('C', OcrText.optionLetterOf("C 血浆约占体重的 10％"))
        assertEquals('E', OcrText.optionLetterOf("E. 细胞外液"))
        // 这些都不是选项行
        assertEquals(null, OcrText.optionLetterOf("C9"))
        assertEquals(null, OcrText.optionLetterOf("确的"))
        assertEquals(null, OcrText.optionLetterOf("45"))
        assertEquals(null, OcrText.optionLetterOf("@考点还原"))
        assertEquals(null, OcrText.optionLetterOf("O"))
        // 圈被认错之后仍然要认出这是选项行 —— CropSuggest 靠它找题目范围
        assertEquals('B', OcrText.optionLetterOf("OB吃食物后的胃酸分泌"))
        assertEquals('D', OcrText.optionLetterOf("(OD动脉血压的调节"))
        assertEquals('E', OcrText.optionLetterOf("CE.胰岛素的分泌"))
        assertEquals(null, OcrText.optionLetterOf("B超检查"))
        assertEquals(null, OcrText.optionLetterOf("|A1型题"))
    }
}