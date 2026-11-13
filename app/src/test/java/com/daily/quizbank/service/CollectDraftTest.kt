package com.daily.quizbank.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收录草稿。
 *
 * 这份草稿是「框选屏幕识别」来回一趟时唯一的载体 —— 面板会被整个收掉去截图，
 * 用户在面板上填的东西全靠它带回来。字段一多就特别容易漏，所以单独测一遍。
 */
class CollectDraftTest {

    /**
     * 「按的是哪一栏的识别」→「默认框盖哪一块」。
     *
     * 用户 2026-10-12 复测的体感是「只有选项那个改了」—— 因为第一版把单题的
     * 「识别题干和选项」和多问的「识别这一问（连选项）」照旧留成了整道题。
     * 现在**每一栏都只框自己那一块**，这张表就是它的契约。
     */
    @Test
    fun 每一栏的识别按钮都只框自己那一块() {
        val 期望 = mapOf(
            CollectDraft.TARGET_STEM to "STEM",
            CollectDraft.TARGET_QUESTION to "STEM",
            CollectDraft.TARGET_OPTIONS to "OPTIONS",
            CollectDraft.partOptionsTarget(1) to "OPTIONS",
            CollectDraft.partTarget(0) to "PART",
            CollectDraft.TARGET_ANALYSIS to "AUTO",
            CollectDraft.partAnalysisTarget(0) to "AUTO",
            CollectDraft.answerTarget(0) to "AUTO",
        )
        for ((target, want) in 期望) {
            assertEquals("「" + target + "」该用 " + want, want, CollectDraft.cropFocusOf(target).name)
        }
        assertEquals("拍题 / 搜题那条路（没有栏）该是整道题", "AUTO", CollectDraft.cropFocusOf(null).name)
    }

    @Test
    fun 序列化能原样转回来() {
        val draft = CollectDraft(
            mode = CollectDraft.MODE_MULTI,
            sharedStem = "患者男，45岁，反复上腹痛3个月。",
            parts = listOf(
                PartDraft(label = "1", body = "1、最可能的诊断是\nA. 胃溃疡\nB. 十二指肠溃疡", answer = "A"),
                PartDraft(label = "2", body = "2、首选治疗是\nA. 奥美拉唑\nB. 阿莫西林", answer = "A"),
            ),
        )
        assertEquals(draft, CollectDraft.decode(draft.encode()))
    }

    /**
     * 滚动位置要能原样带回来。
     *
     * 面板是个 Activity，去屏幕上框一段就得关掉再拉起来，整个界面被销毁重建；
     * 表单又长，不记住滚动位置的话每绕一圈回来都得从头往下翻。
     */
    @Test
    fun 滚动位置能带回来() {
        val draft = CollectDraft(question = "题干", scrollY = 1280)
        assertEquals(1280, CollectDraft.decode(draft.encode())?.scrollY)
        // 没设过就是 0（刚打开面板时从头开始）
        assertEquals(0, CollectDraft().scrollY)
    }

    @Test
    fun 乱码或空串不会炸() {
        assertNull(CollectDraft.decode(null))
        assertNull(CollectDraft.decode(""))
        assertNull(CollectDraft.decode("这不是 json"))
    }

    @Test
    fun 框选结果写进指定字段() {
        val base = CollectDraft(
            mode = CollectDraft.MODE_MULTI,
            sharedStem = "材料第一行",
            parts = listOf(
                PartDraft(label = "1", body = "问题一", answer = ""),
                PartDraft(label = "2", body = "", answer = "B"),
            ),
        )

        // 大题干：替换，不是追加（追加会让识别歪了的那一栏永远纠正不回来）
        assertEquals(
            "识别到的材料",
            base.applyCapture(CollectDraft.TARGET_STEM, "识别到的材料").sharedStem,
        )
        // 第 2 小问的正文
        assertEquals(
            "问题二",
            base.applyCapture(CollectDraft.partTarget(1), "问题二").parts[1].body,
        )
        // 第 2 小问的答案
        assertEquals(
            "C",
            base.applyCapture(CollectDraft.answerTarget(1), "C").parts[1].answer,
        )
        // 第 1 小问的解析栏
        assertEquals(
            "因为 B 选项……",
            base.applyCapture(CollectDraft.partAnalysisTarget(0), "因为 B 选项……").parts[0].analysis,
        )
        // 解析和正文是两个不同的字段，写解析不能动到正文
        assertEquals(
            "问题一",
            base.applyCapture(CollectDraft.partAnalysisTarget(0), "解析").parts[0].body,
        )
        // 单题模式的三个字段
        assertEquals("题干", base.applyCapture(CollectDraft.TARGET_QUESTION, "题干").question)
        assertEquals("解析", base.applyCapture(CollectDraft.TARGET_ANALYSIS, "解析").analysis)
    }

    @Test
    fun 空文本不会往字段里塞空行() {
        val base = CollectDraft(question = "原来就有")
        assertEquals(base, base.applyCapture(CollectDraft.TARGET_QUESTION, "   "))
        assertEquals(base, base.applyCapture(CollectDraft.TARGET_QUESTION, ""))
    }

    @Test
    fun 越界的下标不会动到别的字段() {
        val base = CollectDraft(parts = listOf(PartDraft(label = "1", body = "问题一")))
        assertEquals(base, base.applyCapture(CollectDraft.partTarget(7), "越界"))
        assertEquals(base, base.applyCapture(CollectDraft.answerTarget(7), "越界"))
        assertEquals(base, base.applyCapture("没见过的字段", "随便"))
    }

    @Test
    fun 再识别一次以最后一次为准() {
        val draft = CollectDraft(analysis = "")
        assertEquals(
            "第二段",
            draft.applyCapture(CollectDraft.TARGET_ANALYSIS, "第一段")
                .applyCapture(CollectDraft.TARGET_ANALYSIS, "第二段")
                .analysis,
        )
    }

    // ---------------------------------------------------------------- 接着上次识别

    private val stemPart1 = "67男,70岁,因突发胸痛2小时入院"
    private val stemPart2 = "查体:血压90/60mmHg,心率110次/分"

    /**
     * 题干长到一屏拍不完时，第二次拍到的要**接在后面**，前面的不能丢。
     *
     * 这就是用户报的那个问题：拍第二次，第一次拍到的东西整段没了。
     */
    @Test
    fun 分两次拍的题干会接在一起() {
        val first = CollectDraft().applyCapture(CollectDraft.TARGET_QUESTION, stemPart1)
        assertEquals(stemPart1, first.question)

        val second = first.applyCapture(
            CollectDraft.TARGET_QUESTION,
            stemPart2 + "\nA急性心肌梗死\nB不稳定型心绞痛",
            append = true,
        )
        assertEquals(stemPart1 + "\n" + stemPart2, second.question)
        assertEquals("A. 急性心肌梗死\nB. 不稳定型心绞痛", second.options)
    }

    /**
     * 选项拍了两屏时不能串位。
     *
     * 第二屏开头常常和第一屏末尾重合（用户怕漏行，故意往上压一点），
     * 重合的那个 B 不许来第二遍，新拍的 C、D 要接在它后面而不是跑到题干里去。
     */
    @Test
    fun 分两次拍的选项按顺序拼起来() {
        val first = CollectDraft().applyCapture(
            CollectDraft.TARGET_QUESTION,
            stemPart1 + "\nA急性心肌梗死\nB不稳定型心绞痛",
        )
        assertEquals(stemPart1, first.question)
        assertEquals("A. 急性心肌梗死\nB. 不稳定型心绞痛", first.options)

        val second = first.applyCapture(
            CollectDraft.TARGET_QUESTION,
            "B不稳定型心绞痛\nC主动脉夹层\nD肺栓塞",
            append = true,
        )
        assertEquals(stemPart1, second.question)
        assertEquals(
            "A. 急性心肌梗死\nB. 不稳定型心绞痛\nC. 主动脉夹层\nD. 肺栓塞",
            second.options,
        )
    }

    @Test
    fun 解析也能分几次接完() {
        val first = CollectDraft().applyCapture(
            CollectDraft.TARGET_ANALYSIS,
            "解析第一段:胸痛首先要排除急性冠脉综合征。",
        )
        val second = first.applyCapture(
            CollectDraft.TARGET_ANALYSIS,
            "解析第一段:胸痛首先要排除急性冠脉综合征。\n解析第二段:心电图和肌钙蛋白是主要依据。",
            append = true,
        )
        assertEquals(
            "解析第一段:胸痛首先要排除急性冠脉综合征。\n解析第二段:心电图和肌钙蛋白是主要依据。",
            second.analysis,
        )
    }

    @Test
    fun 只识别选项那一栏也按字母接着排() {
        val first = CollectDraft().applyCapture(CollectDraft.TARGET_OPTIONS, "A. 甲\nB. 乙")
        val second = first.applyCapture(CollectDraft.TARGET_OPTIONS, "C. 丙\nD. 丁", append = true)
        assertEquals("A. 甲\nB. 乙\nC. 丙\nD. 丁", second.options)
    }

    @Test
    fun 接续时某一问的题干和选项各归各位() {
        val base = CollectDraft(
            parts = listOf(PartDraft(label = "1", body = "第1问的题干前半", options = "")),
        )
        val bodyOnly = base.applyCapture(
            CollectDraft.partTarget(0),
            "第1问的题干后半",
            append = true,
        )
        assertEquals("第1问的题干前半\n第1问的题干后半", bodyOnly.parts[0].body)

        val withOptions = base
            .applyCapture(CollectDraft.partTarget(0), "第1问的题干前半\nA. 甲", append = true)
            .applyCapture(CollectDraft.partTarget(0), "B. 乙\nC. 丙", append = true)
        assertEquals("第1问的题干前半", withOptions.parts[0].body)
        assertEquals("A. 甲\nB. 乙\nC. 丙", withOptions.parts[0].options)
    }

    /**
     * 答案永远覆盖。
     *
     * 它就是一个字母或者「正确」两个字，接起来只会变成「A B」这种谁读不通的东西。
     */
    @Test
    fun 接续模式对答案栏无效() {
        val base = CollectDraft(parts = listOf(PartDraft(label = "1", answer = "A")))
        assertEquals("B", base.applyCapture(CollectDraft.answerTarget(0), "B", append = true).parts[0].answer)
    }

    @Test
    fun 接续模式下重复拍同一屏不会堆两遍() {
        val first = CollectDraft().applyCapture(
            CollectDraft.TARGET_QUESTION,
            stemPart1 + "\nA急性心肌梗死\nB不稳定型心绞痛",
        )
        val again = first.applyCapture(
            CollectDraft.TARGET_QUESTION,
            stemPart1 + "\nA急性心肌梗死\nB不稳定型心绞痛",
            append = true,
        )
        assertEquals(first.question, again.question)
        assertEquals(first.options, again.options)
    }

    /**
     * 真机样本：2026-10-09 拍 133 题（青霉素过敏 / 医疗事故鉴定）的那两次。
     *
     * 两段 OCR 原文照抄日志里 @@OCR 内容:@@ 那两行。第二次是同一道题的下半屏，
     * 开头把第一段整段重复了一遍，而且同一行常有 OCR 抖动（「迭到」/「送到」、
     * 「青霉素」/「青零素」、「A.」/「A」）—— 真机上「接着上次识别」碰到的
     * 最多的就是这种输入，所以拿它当回归样本。
     */
    @Test
    fun 真机样本两段重合的题干接成一条() {
        val piece1 = listOf(
            "133.某患者因上呼吸道感染静点青霉素。",
            "护士接照操作规程给药。第二天用完药",
            "后,患者回到家中感到不适,三小时后加",
            "重,紧急迭到医院抢教,后抢救无效死",
            "亡。家属认为是青霉素过敏致死,反映到",
            "市卫生局。市卫生局委托市医学会组织专",
            "家进行医疗事故鉴定,鉴定结论为:不属",
            "手医疗事故。如果家属不服市医学会的鉴",
            "定结论,则可以",
            "A.自收到首次鉴定结论之日起5日内",
            "向医疗机构所在地卫生行政部门提出",
            "再次鉴定的申清",
        ).joinToString("\n")

        val piece2 = listOf(
            "133.某患者因上呼吸道感染静点青霉素。",
            "护士接照操作规程给药。第二天用完药",
            "后,患者回到家中感到不适,三小时后加",
            "重,紧急送到医院抢教,后抢救无效死",
            "亡。家属认为是青零素过敏致死,反映到",
            "市卫生局。市卫生局委托市医学会组织专",
            "家进行医疗事故鉴定,鉴定结论为;不属",
            "于医疗事故。如果家属不服市医学会的鉴",
            "定结论,则可以",
            "A自收到首次鉴定结论之日起5日内",
            "向医疗机构所在地卫生行政部门提出",
            "再次鉴定的申清",
            "B.自收到首次鉴定结论之日起10日内",
            "向医疗机构所在地卫生行政部门堤出",
            "再次鉴定的中请",
            "C.自收到首次鉴定结论之日起15日内",
            "向医疗",
        ).joinToString("\n")

        val merged = CollectDraft()
            .applyCapture(CollectDraft.TARGET_QUESTION, piece1)
            .applyCapture(CollectDraft.TARGET_QUESTION, piece2, append = true)

        // 题干里只许有一个「133.」—— 接续接歪的话这里会冒出第二个
        assertEquals(1, Regex("133[.]").findAll(merged.question).count())
        assertEquals(
            listOf(
                "133.某患者因上呼吸道感染静点青霉素。",
                "护士接照操作规程给药。第二天用完药",
                "后,患者回到家中感到不适,三小时后加",
                "重,紧急迭到医院抢教,后抢救无效死",
                "亡。家属认为是青霉素过敏致死,反映到",
                "市卫生局。市卫生局委托市医学会组织专",
                "家进行医疗事故鉴定,鉴定结论为:不属",
                "手医疗事故。如果家属不服市医学会的鉴",
                "定结论,则可以",
            ).joinToString("\n"),
            merged.question,
        )
        // A、B 完整，C 只拍到了半句；顺序不能乱，也不该重复
        assertEquals(3, merged.options.split("\n").size)
        assertTrue(merged.options.startsWith("A. 自收到首次鉴定结论之日起5日内"))
        assertTrue(merged.options.contains("B. 自收到首次鉴定结论之日起10日内"))
        assertTrue(merged.options.contains("C. 自收到首次鉴定结论之日起15日内"))
    }
}
