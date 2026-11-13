package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 共用题干题的拆分。
 *
 * 判据宁紧勿松：把一道正常的题拆成两条残缺的题，比不拆更糟 ——
 * 用户会得到两条匹配不上的废题。
 */
class MultiPartSplitterTest {

    private val 病例题 = listOf(
        "患者男，45岁，因反复上腹痛3个月就诊。查体：上腹压痛，无反跳痛。",
        "胃镜示胃窦部溃疡，幽门螺杆菌阳性。",
        "",
        "1、该患者最可能的诊断是",
        "A. 胃溃疡",
        "B. 十二指肠溃疡",
        "C. 胃癌",
        "D. 慢性胃炎",
        "",
        "2、该患者首选的治疗药物是",
        "A. 奥美拉唑",
        "B. 阿莫西林",
        "C. 甲硝唑",
        "D. 硫糖铝",
    ).joinToString("\n")

    @Test
    fun 材料带两个小问能拆开() {
        val result = MultiPartSplitter.split(病例题)
        assertNotNull(result)
        result!!

        assertEquals(2, result.parts.size)
        assertEquals("1", result.parts[0].label)
        assertEquals("2", result.parts[1].label)

        // 大题干是材料那两行，不该把小问带进去
        assertEquals("患者男，45岁，因反复上腹痛3个月就诊。查体：上腹压痛，无反跳痛。\n胃镜示胃窦部溃疡，幽门螺杆菌阳性。", result.shared)

        // 每个小问自己带着选项
        assertEquals(true, result.parts[0].body.startsWith("1、该患者最可能的诊断是"))
        assertEquals(true, result.parts[0].body.contains("A. 胃溃疡"))
        assertEquals(true, result.parts[0].body.contains("D. 慢性胃炎"))
        assertEquals(false, result.parts[0].body.contains("首选的治疗药物"))

        assertEquals(true, result.parts[1].body.startsWith("2、该患者首选的治疗药物是"))
        assertEquals(true, result.parts[1].body.contains("D. 硫糖铝"))
    }

    /**
     * 实测那一屏（医考帮 A3/A4 型题）：一屏只显示一问，靠「第 1 问 / 第 2 问」标签页切换。
     * 这一条是这一版最要紧的用例 —— 刷题软件就是这个样子，
     * 之前那版要求「一屏至少两问」，在真机上永远不会触发。
     */
    private val 一屏一问 = listOf(
        "A3/A4型题",
        "男，70岁，直肠指诊前列腺有一硬结",
        "PSA20ng/ml",
        "第 1 问",
        "第 2 问",
        "2021U2-106 可能的诊断是",
        "A. 前列腺增生",
        "B. 前列腺癌",
        "C. 结肠癌",
        "D. 前列腺结石",
        "E. 纤维瘢痕",
    ).joinToString("\n")

    @Test
    fun 一屏一问的标签页形态也要认() {
        val result = MultiPartSplitter.split(一屏一问)
        assertNotNull(result)
        result!!

        assertEquals(1, result.parts.size)
        assertEquals("1", result.parts[0].label)

        // 材料 = 标签页之前的内容
        assertEquals(
            "A3/A4型题\n男，70岁，直肠指诊前列腺有一硬结\nPSA20ng/ml",
            result.shared,
        )
        // 当前这一问 = 最后那排标签之后的内容
        assertEquals(true, result.parts[0].body.startsWith("2021U2-106 可能的诊断是"))
        assertEquals(true, result.parts[0].body.contains("E. 纤维瘢痕"))
        // 标签页本身是界面元素，不能混进正文
        assertEquals(false, result.parts[0].body.contains("第 2 问"))
        assertEquals(false, result.shared.contains("第 1 问"))
    }

    /**
     * 真机上「第 1 问」被识别成了「第 1 间」—— 分界线于是落到了「第 2 问」上，
     * 材料里就混进一行「第 1 间」。这一条盯的就是它。
     */
    /**
     * 实测：两个标签会挤在同一行上（「第1间 第2问」），而且「问」被认成了「间」。
     * 这两件事叠在一起，早先那版就完全拆不开 —— 题干空着、整段原文全进了问题 1。
     */
    @Test
    fun 两个标签挤在同一行也要认出来() {
        // 原文照抄真机日志里的那一行，连 OCR 错字和断行位置都不改 ——
        // 判据要能吃下真实的脏数据，不是吃下我整理过的干净文本
        val text = listOf(
            "男,50岁。问歇歌性无痛性血尿三个月,有血",
            "块。B起见脐胱内有1.5cmx2.0cmxl.0cm新",
            "生物,有蒂。",
            "第1间 第2问",
            "2008U4-149 对诊断新最重要的检检查是",
            "A.尿常规",
            "B.尿脱落细胞",
            "C.膀胱镜+活检",
            "D.IVP",
            "E.CT",
        ).joinToString("\n")

        val result = MultiPartSplitter.split(text)
        assertNotNull(result)
        result!!
        assertEquals(1, result.parts.size)
        // 材料 = 标签栏之前那三行；标签栏本身一个字都不能混进去
        assertEquals(
            "男,50岁。问歇歌性无痛性血尿三个月,有血\n" +
                "块。B起见脐胱内有1.5cmx2.0cmxl.0cm新\n" +
                "生物,有蒂。",
            result.shared,
        )
        assertEquals(true, result.parts[0].body.startsWith("2008U4-149"))
        assertEquals(true, result.parts[0].body.contains("E.CT"))
    }

    @Test
    fun 标签页被认成形近字也要当分界线() {
        val text = listOf(
            "男，70岁，直肠指诊前列腺有一硬结",
            "PSA20ng/ml",
            "第 1 间",
            "第 2 问",
            "2021U2-106 可能的诊断是",
            "A. 前列腺增生",
            "B. 前列腺癌",
        ).joinToString("\n")

        val result = MultiPartSplitter.split(text)
        assertNotNull(result)
        result!!
        // 材料里不能留下标签那一行
        assertEquals("男，70岁，直肠指诊前列腺有一硬结\nPSA20ng/ml", result.shared)
        assertEquals(true, result.parts[0].body.startsWith("2021U2-106"))
    }

    @Test
    fun 标签页形态没有选项就不认() {
        val text = listOf(
            "某患者因发热咳嗽3天入院，胸片示右下肺片状阴影",
            "第 1 问",
            "请简述诊断依据",
        ).joinToString("\n")
        assertNull(MultiPartSplitter.split(text))
    }

    @Test
    fun 普通单题不能拆() {
        val single = listOf(
            "2011U4-46 关于钠泵生理作用的描述，不正确的是",
            "A. 钠泵活动使膜内钠、钾离子均匀分布",
            "B. 将钠离子移出膜外，将钾离子移入膜内",
            "C. 建立势能储备",
            "D. 细胞外高钠离子可维持细胞内外正常渗透压",
        ).joinToString("\n")
        assertNull(MultiPartSplitter.split(single))
    }

    @Test
    fun 材料里的编号列表不算小问() {
        // 编号后面没有选项 —— 是材料的条目，不是小问
        val text = listOf(
            "下列关于该病的描述，请判断下列各项说法：",
            "1、发病率随年龄增长而升高，这一点已经得到广泛证实",
            "2、与遗传因素有一定关系，家族聚集现象比较常见",
            "3、早期通常没有明显症状，需要靠体检发现",
            "A. 以上都对",
            "B. 以上都不对",
        ).joinToString("\n")
        assertNull(MultiPartSplitter.split(text))
    }

    @Test
    fun 只有一个小问时按普通题处理() {
        val text = listOf(
            "患者男，45岁，因反复上腹痛3个月就诊，胃镜示胃窦部溃疡。",
            "1、该患者最可能的诊断是",
            "A. 胃溃疡",
            "B. 十二指肠溃疡",
            "C. 胃癌",
        ).joinToString("\n")
        assertNull(MultiPartSplitter.split(text))
    }

    @Test
    fun 题库自带的题号不会被当成小问编号() {
        // 「2024ESU1-11」这种编号数字后面跟的是字母，不是分隔符
        val text = listOf(
            "2024ESU1-11 属于正反馈调节的是",
            "A. 胰蛋白酶的激活",
            "B. 吃食物后的胃酸分泌",
            "C. 唾液的分泌",
            "D. 动脉血压的调节",
            "E. 胰岛素的分泌",
        ).joinToString("\n")
        assertNull(MultiPartSplitter.split(text))
    }

    @Test
    fun 括号编号和顿号编号都认() {
        val text = listOf(
            "某患者因发热、咳嗽3天入院，胸片示右下肺片状阴影。",
            "（1）最可能的诊断是",
            "A. 肺炎",
            "B. 肺结核",
            "C. 肺癌",
            "（2）首选抗生素是",
            "A. 青霉素",
            "B. 头孢曲松",
            "C. 左氧氟沙星",
        ).joinToString("\n")
        val result = MultiPartSplitter.split(text)
        assertNotNull(result)
        assertEquals(2, result!!.parts.size)
        assertEquals("1", result.parts[0].label)
        assertEquals("2", result.parts[1].label)
    }
}
