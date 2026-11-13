package com.daily.quizbank.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 题型识别的回归测试：判断题在表格里长得和单选题一模一样（两个选项），只能靠选项内容认。 */
class QuestionParserTest {

    private fun parse(vararg rows: List<String>) = QuestionParser.parse(rows.toList()).drafts

    @Test
    fun 选项是对与错时判为判断题() {
        val drafts = parse(
            listOf("题干", "答案", "解析", "难度", "选项A", "选项B"),
            listOf("客车上水时，给水员应戴安全帽、穿防护服。", "A", "", "", "对", "错"),
        )
        println("题型 = " + drafts.first().type + "，选项 = " + drafts.first().options)
        assertEquals("这必须是判断题，不能因为「有两个选项」就当成单选题", "判断题", drafts.first().type)
    }

    @Test
    fun 四个普通选项仍然是单选题() {
        val drafts = parse(
            listOf("题干", "答案", "选项A", "选项B", "选项C", "选项D"),
            listOf("依据《铁路安全管理条例》，旅客应当凭（）购票乘车。", "B", "介绍信", "有效身份证件", "个人简历", "工作证"),
        )
        println("题型 = " + drafts.first().type)
        assertEquals("单选题", drafts.first().type)
    }

    @Test
    fun 答案是正确错误二字且没有选项时也判为判断题() {
        val drafts = parse(
            listOf("题干", "答案"),
            listOf("全国各线的列车运行方向，以国铁集团的规定为准。", "正确"),
        )
        println("题型 = " + drafts.first().type)
        assertEquals("判断题", drafts.first().type)
    }

    // ---------------------------------------------------------------- 题干/选项拆分
    //
    // 收录时用户框下来的是一整块「题干 + A. xx + B. xx…」，要当场分成两栏。
    // 「整理题库」也用它把历史上被塞进题干的选项搬回选项栏。
    // 下面两条文本都是真机日志里的原文，一字未改。

    /** 2026-10-08 真机日志：拍「30.在应激的认知评价中…」那一屏时的 ML Kit 输出。 */
    private val 应激题OCR = listOf(
        "30.在应激的认知评价中，分为初级评价和",
        "次级评价，以下哪项最能体现次级评价的",
        "内涵",
        "A.是否与自己有利害关系",
        "B.是否得到社会的支持",
        "C.是否能被自己所控制",
        "D.是否能解决",
        "E.是否需要主动应对",
    ).joinToString(NEWLINE)

    /** 2026-10-08 真机日志：医考帮「21.美托洛尔…」那一屏，OCR 把 D 的句点认成了逗号。 */
    private val 美托洛尔OCR = listOf(
        "21.美托洛尔降低心肌收缩力的机制",
        "A.al受体激动剂",
        "B.B2受体阻断剂",
        "C.M受体激动剂",
        "D,B1受体阻断剂",
        "E.a2受体激动剂",
    ).joinToString(NEWLINE)

    @Test
    fun 一整块题干加选项能被拆成两栏() {
        val (stem, options) = QuestionParser.splitTrailingOptions(应激题OCR)
        println("题干 = " + stem.replace('\n', '/'))
        println("选项 = " + options)
        assertEquals(
            "题干要原样保留，连换行都在",
            "30.在应激的认知评价中，分为初级评价和" + NEWLINE +
                "次级评价，以下哪项最能体现次级评价的" + NEWLINE + "内涵",
            stem,
        )
        assertEquals(5, options.size)
        assertEquals("A. 是否与自己有利害关系", options[0])
        assertEquals("E. 是否需要主动应对", options[4])
    }

    @Test
    fun 选项前的逗号被统一成点号() {
        val (stem, options) = QuestionParser.splitTrailingOptions(美托洛尔OCR)
        println("题干 = " + stem)
        println("选项 = " + options)
        assertEquals("21.美托洛尔降低心肌收缩力的机制", stem)
        assertEquals(5, options.size)
        assertEquals("D. B1受体阻断剂", options[3])
    }

    /** 纯题干不能被动手脚 —— 这是「整理题库」批量跑的前提。 */
    /**
     * 2026-10-09 真机日志：用户报「收录时选项被识别进题干」的那一屏。
     *
     * 关键是 OCR 把 A 选项的点整个吃掉了（「A粘液性水肿」），其余四个是正常的「B.」。
     * 上一版要求「字母后面必须有分隔符」，于是整块都拆不出来，五个选项全留在题干里。
     */
    private val 硫脲题OCR = listOf(
        "32.硫脉类抗甲状腺药司引起的严重不良",
        "|反应是",
        "A粘液性水肿",
        "B.心动过缓",
        "C.粒细胞缺乏症",
        "D.低蛋白血症",
        "E.再生障碍性贫血",
    ).joinToString(NEWLINE)

    @Test
    fun 选项字母后面丢了分隔符也要能拆开() {
        val (stem, options) = QuestionParser.splitTrailingOptions(硫脲题OCR)
        println("题干 = " + stem.replace('\n', '/'))
        println("选项 = " + options)
        assertEquals(5, options.size)
        assertEquals("A. 粘液性水肿", options[0])
        assertEquals("E. 再生障碍性贫血", options[4])
        assertTrue("题干里不该再有选项：" + stem, !stem.contains("粘液性水肿"))
    }

    /** 批量整理老题库走的是严格档：不允许行内切，枚举字母不能被当成选项。 */
    @Test
    fun 枚举字母不会被当成选项块() {
        val raw = "下列属于 A、B、C 三类的有" + NEWLINE + "A、B、C 三类各有各的处理办法"
        val (stem, options) = QuestionParser.splitTrailingOptions(raw, allowInline = false)
        assertEquals(raw, stem)
        assertEquals(0, options.size)
    }
    // ---- 下面几条全是 2026-10-09 真机日志里的原文，一字未改 ----

    /** 进度角标「67/150」插进了选项中间；而且 A、B、D 三个选项的点号都被 OCR 吃掉了。 */
    private val 围手术期题OCR = listOf(
        "67男,70岁。右腹殷沟区可复性肿物",
        "15年。查体:P84次/分,R20次/分,",
        "BP160/110mm Hg。糖尿病病史7年,口服",
        "降糖药治疗,空腹糖近1个月来维持在",
        "6.2~9.0mmol/L。吸烟20余年,20~30",
        "支/日。欲行右腹股沟无张力疝修补术,围",
        "手术期处理错误的是",
        "A.术前戒烟4周",
        "B.练习床上排便",
        "C.术前应用膜岛素降低血糖",
        "67/150",
        "D术前禁食12小时",
        "E.口服降压药控制血压",
    ).joinToString(NEWLINE)

    @Test
    fun 进度角标插进选项中间也要能拆开() {
        val (stem, options) = QuestionParser.splitTrailingOptions(围手术期题OCR)
        println("题干 = " + stem.replace('\n', '/'))
        println("选项 = " + options)
        assertEquals(5, options.size)
        assertEquals("A. 术前戒烟4周", options[0])
        assertEquals("D. 术前禁食12小时", options[3])
        assertTrue("题干里不该混进进度角标：" + stem, !stem.contains("67/150"))
    }

    /** 同一个号的多道题里，OCR 把「D.静息期」认成了「D静息期」。 */
    @Test
    fun 选项字母后面没有点号也要能拆开() {
        val raw = listOf(
            "68.男,24岁。不洁饮食后腹泻2天,心悸",
            "1天。心电图示频发提前发生的宽大畸形",
            "QRS波群,时限>0.12s。其最可能发生在",
            "心肌细胞的",
            "A.相对不应期",
            "B.快速复极初期",
            "C.有效不应期",
            "D静息期",
            "E.起常期",
        ).joinToString(NEWLINE)
        val (stem, options) = QuestionParser.splitTrailingOptions(raw)
        assertEquals(5, options.size)
        assertEquals("D. 静息期", options[3])
        assertTrue("题干应保留到最后一行：" + stem, stem.endsWith("心肌细胞的"))
    }

    /** 杂字粘在字母前面：「。E15天」「3E.抑胃肽」「O A32g/L」「DB.核黄素」。 */
    @Test
    fun 选项字母前面粘着杂字也能拆开() {
        val raw = listOf(
            "52.对于新的或严重的药品不良反应应于",
            "发现之日起几天内报告",
            "A.3天",
            "B.5天",
            "C.7天",
            "D及时报告",
            "。E15天",
        ).joinToString(NEWLINE)
        val (stem, options) = QuestionParser.splitTrailingOptions(raw)
        println("选项 = " + options)
        assertEquals(5, options.size)
        assertEquals("E. 15天", options[4])
        assertEquals("D. 及时报告", options[3])
    }

    /** 大题干里的「QRS波群」「CT 示」这类行不能被当成选项 —— 靠「只认期望的下一个字母」挡住。 */
    @Test
    fun 题干里的字母开头行不会被当成选项() {
        val raw = listOf(
            "68.心电图示宽大畸形QRS波群，其最可能发生在心肌细胞的",
            "A.相对不应期",
            "B.快速复极初期",
        ).joinToString(NEWLINE)
        val (stem, options) = QuestionParser.splitTrailingOptions(raw)
        assertEquals("68.心电图示宽大畸形QRS波群，其最可能发生在心肌细胞的", stem)
        assertEquals(2, options.size)
    }
    @Test
    fun 只有题干时原样返回() {
        val raw = "列车到达给水站前，列车乘务员应锁闭非集便厕所，非空调列车向旅客做好宣传。"
        val (stem, options) = QuestionParser.splitTrailingOptions(raw)
        assertEquals(raw, stem)
        assertEquals(0, options.size)
    }

    /**
     * 题干里出现「A、B、C 三类」这种写法时不能被切成三个选项。
     *
     * 这条是批量整理老题库的安全底线：那条路上没人在旁边复核，切错了就是静默改坏数据。
     * 所以整理时走的是 allowInline = false，只认「选项从某个整行开始」。
     */
    @Test
    fun 题干里的枚举字母不会被当成选项() {
        val raw = "旅客列车分为 A、B、C 三类，请判断下列说法的正误。"
        val strict = QuestionParser.splitTrailingOptions(raw, allowInline = false)
        assertEquals("严格档必须原样返回", raw, strict.first)
        assertEquals(0, strict.second.size)
    }

    @Test
    fun 只认从A开始的选项行() {
        // 「B.」开头的块多半是题干正文，不是选项
        val raw = "下列说法正确的是？" + NEWLINE + "B.这是题干里的一行"
        val (stem, options) = QuestionParser.splitTrailingOptions(raw, allowInline = false)
        assertEquals(raw, stem)
        assertEquals(0, options.size)
    }

    // ---------------------------------------------------------------- 答案归一化 / 题型推断
    //
    // 收录这条路没有「题型」列可抄，只有用户敲的一个答案字符串。
    // 以前 saveCollected 是「有选项就算单选题」，多选题和判断题全被贴错了标签。

    @Test
    fun 答案统一成大写字母() {
        assertEquals("B", QuestionParser.normalizeAnswer("b"))
        assertEquals("AC", QuestionParser.normalizeAnswer("a,c"))
        assertEquals("AC", QuestionParser.normalizeAnswer("A、C"))
        assertEquals("ABC", QuestionParser.normalizeAnswer("c a b"))
        assertEquals("ABCD", QuestionParser.normalizeAnswer(" A B C D "))
    }

    @Test
    fun 重复和乱序的字母会被理成升序去重() {
        assertEquals("AB", QuestionParser.normalizeAnswer("B,A,A"))
        assertEquals("A", QuestionParser.normalizeAnswer("AA"))
    }

    @Test
    fun 判断题的对错写法统一成正误二字() {
        for (raw in listOf("对", "是", "√", "T", "true", "正确")) {
            assertEquals(raw, "正确", QuestionParser.normalizeAnswer(raw))
        }
        for (raw in listOf("错", "否", "×", "false", "错误")) {
            assertEquals(raw, "错误", QuestionParser.normalizeAnswer(raw))
        }
        // 「F」是个例外，而且必须是例外：它是合法的选项字母（选项可以有 A~H 八个）。
        // 单个字母一律按选项字母走，不能因为 F/FALSE 的联想就把它当成「错误」。
        assertEquals("F", QuestionParser.normalizeAnswer("f"))
        assertEquals("F", QuestionParser.normalizeAnswer("F"))
    }

    /**
     * 答案本来就是一段话（简答 / 填空 / 用户直接抄了选项正文）时不动它。
     *
     * 注意最后一条：不是「字母答案」的时候连大小写都不动。这是有意的 ——
     * 那种答案里可能是一整段话，统一转大写会把英文缩写之类的东西改得面目全非；
     * 而**录入框本身在键入时就把字母转成大写了**（CollectActivity.asAnswerInput），
     * 所以真正会存进去的仍然是「C. 合资」。这里保的是「别的路径进来的老数据别被乱改」。
     */
    @Test
    fun 非字母答案原样保留() {
        assertEquals("北京", QuestionParser.normalizeAnswer("北京"))
        assertEquals("c. 合资", QuestionParser.normalizeAnswer("c. 合资"))
    }

    @Test
    fun 收录时按选项和答案推出真正的题型() {
        val 四选项 = listOf("A. 甲", "B. 乙", "C. 丙", "D. 丁")
        assertEquals("单选题", QuestionParser.inferType("题干", 四选项, "B"))
        assertEquals("多选题", QuestionParser.inferType("题干", 四选项, "AB"))
        assertEquals("多选题", QuestionParser.inferType("题干", 四选项, "ACD"))
        // 两个选项正好是「对 / 错」就是判断题，不是单选题
        assertEquals("判断题", QuestionParser.inferType("题干", listOf("A. 对", "B. 错"), "A"))
        // 没框选项、答案写「对」的判断题
        assertEquals("判断题", QuestionParser.inferType("题干", emptyList(), "正确"))
        // 没选项时按题干本身判形态：带下划线/括号空位是填空题，否则当简答题。
        // 这条规则和导入那条路共用（resolveType），口径必须一致 ——
        // 「收录的题能筛出来、导入的筛不出来」这种分裂比标签不准更难受。
        assertEquals("填空题", QuestionParser.inferType("本规程适用于（ ）的铁路。", emptyList(), ""))
        assertEquals("简答题", QuestionParser.inferType("简述旅客列车给水作业的流程。", emptyList(), ""))
    }

    private companion object {
        /** 只因为本文件里要拼多行文本；写成常量免得被生成链路上的转义坑吃掉。 */
        const val NEWLINE = "\n"
    }
}