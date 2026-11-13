package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题干匹配的回归测试。
 *
 * 全部用例来自一次真实的失败：用户在手机上拍一道给水员的单选题，
 * 结果面板显示「题库相似度 42%」，于是绕过题库去调了 AI（多花 token，且答案不如题库权威）。
 *
 * 那条 OCR 原文、题库原文都是从真机的日志和题库文件里原样拷过来的，一个字没改。
 * 当年那个 42% 就是这么算出来的，见 [整串Jaccard在全屏截图上会被稀释到命中线以下]。
 */
class StemMatcherTest {

    /** 题库原文（给水员题库 1749 题中那一道，题干带全角括号）。 */
    private val 题干 = "本规程适用于（）铁路集团有限公司（以下简称国铁集团）所属铁路运输企业和控股合资铁路公司在境内的铁路旅客运输。"

    /**
     * 真机 ML Kit 对一张 1080×2400 全屏截图的 OCR 输出（用户从日志里原样摘出来的）。
     *
     * 里面混着状态栏、选项行「A国家 / B中国国家 / C合资 / D控服合资」、
     * 日期时间，以及底部的「发送 / 编辑 / 删除 / 更多」工具栏。
     * OCR 还认错了三个字：轶路（铁路）、控服（控股）、运輸（运输）。
     */
    private val 全屏OCR = listOf(
        "个",
        "A国家",
        "单送1、本规程适用于()铁路集团有限公司(以",
        "下简称国铁集团)所属轶路运輸企业和控服合资",
        "轶路公司在境内的铁路旅客运输。 ▶读题",
        "2026年10月02日",
        "B中国国家",
        "C合资",
        "D控服合资",
        "发送",
        "22:55",
        "編辑",
        "删除",
        "更多",
    ).joinToString("\n")

    /** 只框住题干、但把四个选项也框进来了（多数人手一抖就是这个结果）。 */
    private val 题干加选项OCR = listOf(
        "单送1、本规程适用于()铁路集团有限公司(以",
        "下简称国铁集团)所属轶路运輸企业和控服合资",
        "轶路公司在境内的铁路旅客运输。",
        "A国家",
        "B中国国家",
        "C合资",
        "D控服合资",
    ).joinToString("\n")

    /** 理想情况：干干净净只有题干。 */
    private val 只有题干OCR =
        "1、本规程适用于()铁路集团有限公司(以下简称国铁集团)所属轶路运輸企业和控服合资轶路公司在境内的铁路旅客运输。"

    /** 一小撮足以暴露问题的题库：本题 + 两道会被误匹配的近似题。 */
    private val 题库 = listOf(
        StemEntry(1L, 1L, 题干),
        StemEntry(2L, 1L, "委托国铁集团及所属运输企业管理所辖线路的（）运输企业办理境内旅客运输时，以及地方铁路运输企业与国铁集团办理境内旅客过轨运输时，均应按照约定执行本办法。"),
        StemEntry(3L, 1L, "本规程（）国铁集团客运部负责具体解释工作。"),
        StemEntry(4L, 1L, "《旅客列车给水工作管理办法》适用于中国铁路总公司所属（）.（以下简称铁路运输企业）。"),
    )

    private fun matcher() = StemMatcher(题库)

    @Test
    fun 整串Jaccard在全屏截图上会被稀释到命中线以下() {
        val 分数 = Similarity.score(全屏OCR, 题干)
        println("整串 Jaccard（旧算法）= " + "%.1f%%".format(分数 * 100))
        // 42% —— 正是手机上看到、也是用户报的那个数字
        assertTrue("旧算法在全屏截图上的分数应当低于 0.72 的命中线", 分数 < 0.72)
        assertTrue("旧算法的分数应当落在 42% 附近", 分数 in 0.38..0.46)
    }

    @Test
    fun 新算法在三种截图范围下都稳定命中同一道题() {
        for ((名字, 文本) in listOf(
            "全屏截图(带界面噪声)" to 全屏OCR,
            "框到题干+选项" to 题干加选项OCR,
            "只框到题干" to 只有题干OCR,
        )) {
            val outcome = matcher().match(文本, limit = 5)
            val best = outcome.best
            println(名字 + " → " + "%.1f%%".format((best?.score ?: 0.0) * 100) + " " + (best?.entry?.stem?.take(24) ?: "无"))
            assertTrue(名字 + "：应当命中题库第一道题", best?.entry?.id == 1L)
            assertTrue(名字 + "：分数应当高于 0.80 的入库线", (best?.score ?: 0.0) >= Similarity.MATCH_THRESHOLD)
            assertTrue(名字 + "：应当被判定为可信命中", outcome.confident)
        }
    }

    @Test
    fun 分数不因截图范围而剧烈抖动() {
        val 分数集 = listOf(全屏OCR, 题干加选项OCR, 只有题干OCR)
            .map { matcher().match(it, limit = 1).best?.score ?: 0.0 }
        val 极差 = 分数集.max() - 分数集.min()
        println("三种截图的分数: " + 分数集.joinToString(" / ") { "%.1f%%".format(it * 100) }
            + "，极差 " + "%.1f%%".format(极差 * 100))
        // 旧算法在 42%~74% 之间来回跳，命中与否全看用户框了多大范围
        assertTrue("同一道题的分数极差应当小于 5%", 极差 < 0.05)
    }

    /**
     * 题库里只差一两个字的近似题。
     *
     * 真实题库里确实存在这种题（「防堵、防冻、（）」与「防堵、防冻、解冻」各是一道），
     * 而且答案不同。两者对同一张截图会算出几乎一样的分数 —— 任何字符级算法都分不开，
     * 所以 margin 会很小，这时必须退回 AI，而不是赌一个 50% 的答案。
     */
    @Test
    fun 题库里有只差一两个字的近似题时不敢直接采信() {
        val a = StemEntry(1L, 1L, "列车乘务员应做好防堵、防冻、（）工作，不得随意关闭总阀，影响客车给水和正常使用。")
        val b = StemEntry(2L, 1L, "列车乘务员应做好防堵、防冻、解冻工作，不得随意关闭总阀，影响客车给水和正常使用。")
        // 拍到的是第二道，但 OCR 认错两个字：解冻 → 解涷、使用 → 使甪
        val ocr = "列车乘务员应做好防堵、防冻、解涷工作，不得随意关闭总阀，影响客车给水和正常使甪。"
        val outcome = StemMatcher(listOf(a, b)).match(ocr)
        println("近似题 margin = " + "%.1f%%".format(outcome.margin * 100) + "，confident = " + outcome.confident)
        assertTrue("两道近似题的间距应当很小", outcome.margin < Similarity.MATCH_MARGIN)
        assertTrue("这类题必须判为不可信，退回 AI", !outcome.confident)
        assertTrue("但两道题都要列出来供用户自己核对", outcome.hits.size >= 2)
    }

    /**
     * 短题干被长题干整段包含的情况。
     *
     * 「旅客列车及生产生活用水，须进行净化消毒处理。」本身就是另一道更长的题的完整前缀，
     * 于是只要框选范围够大，短题也能拿满分。纯按分数排序会把短题排到前面、
     * 给出一个属于另一道题的答案 —— 靠 margin 才能挡住。
     */
    @Test
    fun 短题干被长题干整段包含时也不会误命中() {
        val short = StemEntry(1L, 1L, "旅客列车及生产生活用水，须进行净化消毒处理。")
        val long = StemEntry(2L, 1L, "旅客列车及生产生活用水，须进行净化消毒处理；固定动力锅炉用水应进行炉内处理。")
        val ocr = "个\n单送1、旅客列车及生产生活用水，须进行净化消毒处理；固定动力锅炉用水应进行炉内处理。\n发送\n更多"
        val outcome = StemMatcher(listOf(short, long)).match(ocr)
        println("短/长题 margin = " + "%.1f%%".format(outcome.margin * 100) + "，confident = " + outcome.confident)
        assertTrue("必须判为不可信", !outcome.confident)
    }

    /**
     * 手机上那道题**根本不在题库里**，只是题库里有一道措辞相近的。
     *
     * 这两条来自工作区里的两张真实截图（考试宝的判断题）：
     *   题库里是「…影响旅客使用时**设有**提示。」（判断题，答案：正确）
     *   手机上是「…影响旅客使用时**没有**提示。」（问法被改过，答案：错误）
     * 两者只差两个字，窗口 Dice 高达 0.91，光看分数一定会采信 —— 然后给出一个错的答案。
     * 这正是「分数高」不等于「就是这道题」的典型。
     */
    @Test
    fun 问法被改过的题不能被当成题库里那道题() {
        val 库里那条 = StemEntry(1L, 1L, "发生故障立即报告，及时维修，影响旅客使用时设有提示。")
        val 手机上拍的 = listOf(
            "考试宝", "10:15", "4G", "81", "00:56:36",
            "判断 83、发生故障立即报告，及时维修，影响旅",
            "客使用时没有提示。 ▶读题",
            "A 正确", "B 错误", "3/100", "已做题", "收藏", "标记", "1", "2", "交卷",
        ).joinToString("\n")

        val outcome = StemMatcher(listOf(库里那条)).match(手机上拍的)
        println("措辞不同的题 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident + "，理由：" + outcome.hesitation)

        assertTrue(
            "分数确实很高",
            (outcome.best?.score ?: 0.0) >= Similarity.MATCH_THRESHOLD,
        )
        assertTrue("但绝不能拿题库答案", !outcome.confident)
        assertTrue("而且系统里那道题的答案确实是「正确」，直接用就答反了", true)
    }

    /**
     * 真机第二次拍题（2026-10-04）：题干被界面文字从中间劈成了两段。
     *
     * ML Kit 的阅读顺序把「A 正确」选项行和顶部的计时器排到了题干两行之间：
     *   判断96、上岗前应签订师徒合同,跟班作业期间
     *   严禁师徒分离、单独作业。未经鉴定会格严禁单
     *   A正确            ← 插进题干中间
     *   O 00:55:56       ← 也插进来了
     *   独作业。严禁低职代高职作业或监控。
     *
     * 只算「连续窗口」的写法在这条输入上只有 72%（真机日志实测），低于 0.80 的入库线，
     * 结果就是「题库里明明有这道题，却显示没命中」。所以这里加了覆盖率路线。
     */
    @Test
    fun 题干被界面文字从中间打断时仍然命中() {
        val 库里那条 = StemEntry(
            1L, 1L,
            "上岗前应签订师徒合同，跟班作业期间严禁师徒分离、单独作业。未经鉴定合格严禁单独作业。严禁低职代高职作业或监控。",
        )
        val 手机上拍的 = listOf(
            "く", "10:16",
            "判断96、上岗前应签订师徒合同,跟班作业期间",
            "严禁师徒分离、单独作业。未经鉴定会格严禁单",
            "A正确", "O 00:55:56",
            "独作业。严禁低职代高职作业或监控。 ▶读题",
            "B错误", "交卷", "收藏", "ll 4G 80", "标记", "1", "2", "3/100", "已做题",
        ).joinToString("\n")

        val outcome = StemMatcher(listOf(库里那条)).match(手机上拍的)
        println("题干被打断 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("即使题干被劈开也要命中", outcome.best?.entry?.id == 1L)
        assertTrue("分数要过 0.80 的入库线", (outcome.best?.score ?: 0.0) >= Similarity.MATCH_THRESHOLD)
        assertTrue("应当直接用题库答案", outcome.confident)
    }

    /** 同一个截图模板，题干在题库里逐字一致时应当时命中。 */
    @Test
    fun 逐字一致的判断题会被命中() {
        val 库里那条 = StemEntry(1L, 1L, "次月6日前，客运段应对给上月给水登记情况进行查看，确保无遗漏。")
        val 手机上拍的 = listOf(
            "考试宝", "10:16", "4G", "80", "00:55:58",
            "判断 95、次月6日前，客运段应对给上月给水登",
            "记情况进行查看，确保无遗漏。 ▶读题",
            "A 正确", "B 错误", "3/100", "已做题", "收藏", "标记", "1", "2", "交卷",
        ).joinToString("\n")

        val outcome = StemMatcher(listOf(库里那条)).match(手机上拍的)
        println("逐字一致的题 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当被判定为可信", outcome.confident)
    }

    @Test
    fun 完全不相关的题不会被误命中() {
        val outcome = matcher().match("依据《铁路安全管理条例》相关规定，旅客应当凭（）购票乘车。")
        println("无关题最高分 = " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100))
        assertTrue("无关题不应当被判定为可信命中", !outcome.confident)
    }

    /**
     * 搜题：用户只打题目正文，不打选项（2026-10-08 真机日志，报的就是这一条）。
     *
     * 收录进来的题干是「题干与选项」一整块（见 CollectActivity 里那个输入框的名字），
     * 而搜题时没人会把选项也打一遍。于是「题库题干有多少出现在输入里」这个口径
     * 按长度比例塌下来：题目一字不差打全了，也只有 47%（真机实测）。
     */
    private val 带选项的题库条目 = StemEntry(
        1L, 1L,
        listOf(
            "21.美托洛尔降低心肌收缩力的机制",
            "A.al受体激动剂",
            "B.B2受体阻断剂",
            "C.M受体激动剂",
            "D,B1受体阻断剂",
            "E.a2受体激动剂",
        ).joinToString("\n"),
    )

    @Test
    fun 搜题只打题目正文也要能命中带选项的题库条目() {
        val outcome = StemMatcher(listOf(带选项的题库条目)).match("美托洛尔降低心肌收缩力的机制")
        val 分数 = outcome.best?.score ?: 0.0
        println("只打题目（14 字）→ " + "%.1f%%".format(分数 * 100) + "，confident = " + outcome.confident)
        assertTrue("应当命中题库第一道题", outcome.best?.entry?.id == 1L)
        assertTrue("分数要过 0.80 的入库线（改之前这里是 47%）", 分数 >= Similarity.MATCH_THRESHOLD)
        assertTrue("应当直接用题库答案，不必花 token 问 AI", outcome.confident)
    }

    /** 同一个题库条目，用户在搜题框里把整道题连选项一起粘进来 —— 老算法能过，改完也不能退步。 */
    @Test
    fun 搜题把选项一起打进来同样命中() {
        val outcome = StemMatcher(listOf(带选项的题库条目))
            .match("21.美托洛尔降低心肌收缩力的机制 A.al受体激动剂 B.B2受体阻断剂 C.M受体激动剂 D,B1受体阻断剂 E.a2受体激动剂")
        println("连选项一起打 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) + "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当可信", outcome.confident)
    }

    /**
     * **库里那条只有题干（选项单独一栏存着），框下来的却是「题干 + 选项」一整块。**
     *
     * 2026-10-12 真机报障：这种组合下，四条路线被多出来的五个选项一起稀释，掉到 0.3~0.39，
     * 够不到 0.42 的展示线 —— 面板说「题库里没有这道题」；而同一道题走收录那条路时
     * 拿的是**纯题干**，分数 100%，又说「库里有高度相似的」。同一道题、同一个库，两条路说法相反。
     *
     * 修法：拿输入再切一次「去掉选项行」的版本，两遍取高分（见 StemMatcher.match 里的说明）。
     */
    @Test
    fun 库里只有题干时拍到的整块也要能对上() {
        val 库里那条 = StemEntry(
            1L, 1L,
            "63岁男性,进行性右侧睾丸肿大一年,无疼痛,行走不便。查体:睾丸6cmx5cmx4cm。\n\n" +
                "2004U4-10 骨囊肿好发于",
        )
        val 整块 = "2004U4-10 骨囊肿好发于\nA.长管骨干骶端\nB.长管骨骨端\nC.长管骨干部\n" +
            "D.短管骨骨端\nE.短管骨干部"
        val outcome = StemMatcher(listOf(库里那条)).match(整块)
        println("整块（题干+选项）→ " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，命中 " + outcome.hits.size + " 条")
        assertTrue("应当命中（修之前这里是 0 条候选）", outcome.best?.entry?.id == 1L)
        assertTrue("分数要够采信线", (outcome.best?.score ?: 0.0) >= Similarity.MATCH_THRESHOLD)
    }

    /** 长题干只打了前三成，一样要能对上。 */
    @Test
    fun 只打长题干的一小部分也能命中() {
        val 库里那条 = StemEntry(
            1L, 1L,
            "国际列车、往返香港列车、非控股企业担当列车等铁路指定列车车票暂不参与乘车积分累积。",
        )
        val outcome = StemMatcher(listOf(库里那条)).match("国际列车，往返香港列车，非控股企业担当")
        println("打 17/38 字 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) + "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当可信（真机日志里这条只有 61%，低于入库线）", outcome.confident)
    }

    /**
     * 输入太短就不走「输入覆盖率」那条路线。
     *
     * 每条题干里都有的碎片（「铁路运输企业」）谁都能包含，给它满分毫无意义。
     * 阈值取 12 个字是量出来的：1749 道真题上前 N 字横扫，N=10 就开始误答。
     */
    @Test
    fun 输入太短时不启用输入覆盖率这条路线() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "铁路运输企业应当按照国务院铁路行业监督管理部门的规定实施火车票实名购买、查验制度。"),
            StemEntry(2L, 1L, "铁路运输企业应当为旅客提供安全、便捷、优质的服务，不断改进旅客运输服务工作。"),
        )
        val outcome = StemMatcher(题库).match("铁路运输企业应当")
        println("8 字输入 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) + "，confident = " + outcome.confident)
        assertTrue("这么短的输入不该被判定为可信命中", !outcome.confident)
    }

    /** 一个碎片同时出现在好几道题里 → margin 为 0 → 老老实实退回 AI。 */
    @Test
    fun 碎片同时命中好几道题时退回AI() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "依据《铁路安全管理条例》相关规定，铁路运输企业的从业人员应当按照（）使用、管理铁路设施设备。"),
            StemEntry(2L, 1L, "依据《铁路安全管理条例》相关规定，铁路运输企业未派员对铁路线路安全保护区内施工现场进行安全监督的，由铁路监督管理机构责令改正。"),
            StemEntry(3L, 1L, "依据《铁路安全管理条例》相关规定，旅客应当凭（）购票乘车。"),
        )
        val outcome = StemMatcher(题库).match("依据《铁路安全管理条例》相关规定，铁路运输企业")
        println("共同前缀 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，margin = " + "%.1f%%".format(outcome.margin * 100) + "，confident = " + outcome.confident)
        assertTrue("不该赌一个答案", !outcome.confident)
    }

    /**
     * 走「输入覆盖率」时，**输入里多出否定字**这一方向仍然要拦。
     *
     * 题库那条是「设有提示」，用户屏幕上是「没有提示」—— 问法被改过，题库答案会答反。
     * 反方向（题库计数更高）不拦，因为题库题干里常常还带着选项，
     * 选项里的「不 / 没 / 无」会把计数抬高，那是噪声不是证据。
     */
    @Test
    fun 输入覆盖率路线下多出否定字仍然不采信() {
        val 库里那条 = StemEntry(1L, 1L, "发生故障立即报告，及时维修，影响旅客使用时设有提示。")
        val outcome = StemMatcher(listOf(库里那条)).match("发生故障立即报告，及时维修，影响旅客使用时没有提示")
        println("多了个「没」→ " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident + "，理由：" + outcome.hesitation)
        assertTrue("分数确实够高", (outcome.best?.score ?: 0.0) >= Similarity.MATCH_THRESHOLD)
        assertTrue("但不能采信题库答案", !outcome.confident)
    }

    /**
     * 短片段：只打题目开头几个字、或者题目中间最有辨识度的一小截（2026-10-09 用户报的）。
     *
     * 题库条目来自用户当天在真机上测的那道题（医考帮「30.在应激的认知评价中…」），
     * 两个问法都是他实际打过的 —— 改之前两个都提示「未命中题库」。
     */
    private val 应激题 = StemEntry(
        1L, 1L,
        "30.在应激的认知评价中，分为初级评价和次级评价，以下哪项最能体现次级评价的内涵",
    )

    @Test
    fun 只打题目开头九个字也能命中() {
        val outcome = StemMatcher(listOf(应激题)).match("在应激的认知评价中")
        println("九字开头 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当直接给题库答案", outcome.confident)
    }

    @Test
    fun 只打题目中间一小截也能命中() {
        val outcome = StemMatcher(listOf(应激题)).match("以下哪项最能体现次级评价的内涵")
        println("中间一截 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当直接给题库答案", outcome.confident)
    }

    /** 短档走的是「原样连着出现」的判据，所以差一个字就不认，不会硬凑。 */
    @Test
    fun 短片段打错字就不会硬凑() {
        val outcome = StemMatcher(listOf(应激题)).match("在应激的认知评介中")
        println("错一个字 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("这么短的输入不该被判定为可信命中", !outcome.confident)
    }

    /**
     * 三个字就是一句普通话了，不该拿去撞题库（阈值是 4 个字）。
     *
     * 四个字是允许的 —— 用户真的会打四个字（「甲硫氨酸」），
     * 那一段靠模糊包含的窗口限制和 margin 一起兜着。
     */
    @Test
    fun 三个字以下的输入不参与匹配() {
        val outcome = StemMatcher(listOf(应激题)).match("在应激")
        println("三个字 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("不该被判定为可信命中", !outcome.confident)
    }

    /** 一小截文字同时出现在两道题里 → margin 为 0 → 退回 AI。 */
    @Test
    fun 短片段同时命中两道题时退回AI() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "30.在应激的认知评价中，分为初级评价和次级评价，以下哪项最能体现次级评价的内涵"),
            StemEntry(2L, 1L, "在应激的认知评价中，个体首先进行的是初级评价，其判断依据是（）。"),
        )
        val outcome = StemMatcher(题库).match("在应激的认知评价中")
        println("共同片段 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，margin = " + "%.1f%%".format(outcome.margin * 100) + "，confident = " + outcome.confident)
        assertTrue("不该赌一个答案", !outcome.confident)
        assertTrue("但两道题都要列出来", outcome.hits.size >= 2)
    }

    /**
     * 题库里那条被 OCR 认错了一个字时，短输入也要能命中（2026-10-09 用户报的）。
     *
     * 真机实录：用户搜「甲硫氨酸循环」，题库里存的却是「36.甲硫复氨酸循环的生理意义是」——
     * OCR 多认了一个「复」。当时只给了 42%，退回 AI 了。
     */
    @Test
    fun 题库里多认了一个字时短输入靠模糊包含命中() {
        val 库里那条 = StemEntry(1L, 1L, "36.甲硫复氨酸循环的生理意义是")
        val outcome = StemMatcher(listOf(库里那条)).match("甲硫氨酸循环")
        println("六字、库里多一个字 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当直接用题库答案", outcome.confident)
    }

    /** 四个字也要能用（用户就是这么搜的）。 */
    @Test
    fun 四个字的模糊包含也能命中() {
        val 库里那条 = StemEntry(1L, 1L, "36.甲硫复氨酸循环的生理意义是")
        val outcome = StemMatcher(listOf(库里那条)).match("甲硫氨酸")
        println("四字、库里多一个字 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("应当命中", outcome.best?.entry?.id == 1L)
        assertTrue("应当直接用题库答案", outcome.confident)
    }

    /** 模糊包含不能变成「字凑巧都出现过就算」：窗口限制要挡得住散落的字。 */
    @Test
    fun 散落各处的字不算模糊包含() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "甲患者因发热就诊，硫唑嘌呤过敏，复查血氨偏高，酸中毒纠正后行循环支持治疗。"),
        )
        val outcome = StemMatcher(题库).match("甲硫氨酸")
        println("散落 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) +
            "，confident = " + outcome.confident)
        assertTrue("字散在各处就不该算命中", !outcome.confident)
    }

    @Test
    fun 归一化保留中文与数字但丢弃标点与空白() {
        assertEquals("本规程适用于铁路集团2026", Similarity.normalize("本规程适用于（）铁路集团， 2026"))
    }

    // ------------------------------------------------ 残缺条目（只有一句小问、没有材料）

    /**
     * 用户 2026-10-09 报的那次，题库用的是他导出的真题里那几条。
     *
     * 题库里有一条**只有小问、没有材料**的短条目（`2021U2-106 可能的诊断是`）。
     * 一道新题的末句同样是「最可能的诊断是」，于是那条短条目以 46% 被摆成
     * 「相近的题」—— 对上它的其实只有那一句通用问法，唯一能说明「这是哪一道」的
     * 题号（2021U2-106）根本没对上。
     */
    @Test
    fun 只有小问的短条目不许靠一句通用问法对上() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "2021U2-106 可能的诊断是"),
            StemEntry(2L, 1L, "男,25岁。右胫前皮肤红肿2天,畏寒、发热1天。局部表皮发红,边界清,右腹股沟淋巴结肿痛。\n\n(1.0分)最可能的诊断是"),
            StemEntry(3L, 1L, "男,12岁。发热2天,伴头痛、神志不清1天,8月中旬来诊。查体:T39.5℃,神志不清。\n\n(1.0分)最可能的诊断是"),
            StemEntry(4L, 1L, "2008U4-150 目前最重要的治疗方法是"),
        )
        val 新题 = "男,38岁。右小腿被铁钉刺伤3天,伤口周围红肿热痛,今日出现张口困难、苦笑面容。查体:神志清,颈项强直。\n\n(1.0分)最可能的诊断是"
        val outcome = StemMatcher(题库).match(新题)
        println("残缺条目 → 候选 " + outcome.hits.size + " 条")
        outcome.hits.forEach { println("   " + (it.score * 100).toInt() + "% " + it.entry.stem.replace("\n", " / ")) }
        assertTrue(
            "只有小问的短条目不该被摆出来",
            outcome.hits.none { it.entry.stem.startsWith("2021U2-106") },
        )
        assertTrue("也不该当成命中", !outcome.confident)
    }

    /**
     * **短条目差一两个字（OCR 认错）时不许被当成「只撞上一句通用问法」丢掉。**
     *
     * 2026-10-12 真机：库里那条是「2004U4-10 骨囊好发于」——**少了一个「肿」**（录入时认错的），
     * 屏幕上是「骨囊肿好发于」，算出来 **91%**，却被那句判据挡掉，面板说「题库里没有这道题」；
     * 而同一道题走收录那条路（输入只有题干、不到 2 倍长）又完全正常，于是出现
     * 「收录说已经收过、拍题说没有」这种自相矛盾。
     *
     * 判据的原意是「整条都盖住了、盖住的却全是通用说法」，
     * 差一两个字说明对上的并不只是那句问法 —— 现在覆盖率不到 1.0 就直接放行。
     */
    @Test
    fun 短条目差一两个字时不算只撞上通用问法() {
        val 库里的那条 = StemEntry(1L, 1L, "2004U4-10 骨囊好发于")
        val 真机OCR = "2004U4-10 骨囊肿好发于\n。A.长管骨千骶端\nB.长管骨骨端\nC.长管骨干部\n" +
            "D.短管骨骨端\nE.短管骨骨干部"
        val outcome = StemMatcher(listOf(库里的那条)).match(真机OCR)
        println("真机那条 → 候选 " + outcome.hits.size + " 条：" +
            outcome.hits.map { (it.score * 100).toInt().toString() + "%" })
        assertTrue(
            "差一个字的真命中要摆出来（修之前是 91% 被挡掉、0 条候选）",
            outcome.hits.any { it.entry.id == 1L },
        )
    }

    /** 短条目**整条**都对上时照旧算候选：拍到的就是这一条，只是它本身没材料。 */
    @Test
    fun 短条目整条都对上时仍然算候选() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "2021U2-106 可能的诊断是"),
            StemEntry(2L, 1L, "男,12岁。发热2天,伴头痛、神志不清1天,8月中旬来诊。查体:T39.5℃,神志不清。\n\n(1.0分)最可能的诊断是"),
        )
        val outcome = StemMatcher(题库).match("2021U2-106 可能的诊断是")
        println("整条对上 → " + outcome.hits.map { (it.score * 100).toInt().toString() + "% " + it.entry.stem })
        assertTrue("整条都对上就该摆出来", outcome.hits.any { it.entry.stem.startsWith("2021U2-106") })
    }

    /**
     * **主题集中的题库里，短题干 + 它自己的选项 = 同一道题，不许判成「通用问法」。**
     *
     * 2026-10-27 真机报障：用户导入了「防寒过冬客运试题」那个 .xls（213 道），
     * 拍「20.(3分)降雪会商的决策内容主要包括?」这道多选题，**拍题和搜题都显示「无」**。
     * 复现下来它的分数是 100%，却被 [StemMatcher] 里那条「只撞上一句通用问法」的判据丢掉了 ——
     * 那个库全都讲降雪、会商、雪情，这条题干的每个字组在库里的 DF 都 ≥ 2
     * （降雪=21、会商=12、决策=4…），于是「整条被盖住 + 没有一个 DF≤1 的字组」两条同时成立。
     *
     * 量过那个库：按原来的门槛有 **133/213 道（62%）** 会被判成通用问法 —— 等于这个库拍题全废。
     * 判据要的不是「字组在库里不稀奇」，而是「这条东西本身是不是只有一句问法」；
     * 它自己带着选项、这次输入里也有这些选项，答案就很清楚了。
     */
    @Test
    fun 主题集中的库里短题干带选项时不许判成通用问法() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "降雪会商的决策内容主要包括?", listOf(
                "制定落实集团公司旅客列车调整方案、货运组织调整方案、影响运输的装卸车和车流调整等临时性安排、施工计划调整安排及有关重点工作提示和要求的工作措施",
                "严格落实旅客列车禁停线路、禁停区段、禁停车站，制定管内旅客列车和货运组织等调整方案，确定有关旅客列车停运、折返、变更径路、换乘组织、降速运行等总体性安排",
                "施工（维修）计划调整安排",
                "确定干部包保和值班方案。五是有关重点工作提示和要求。集团公司降雪预报预警信息明确的各类要求",
            )),
            StemEntry(2L, 1L, "降雪会商形成的（）、（）、（）和（）等方案，经领导小组组长（或副组长）批准后，由指挥中心在段内通知公布实施。"),
            StemEntry(3L, 1L, "降雪会商基本原则?"),
            StemEntry(4L, 1L, "对于集团公司降雪会商确定的各类调整方案，纳入决策内容，原则上不得更改。"),
            StemEntry(5L, 1L, "根据降雪预报等级将响应划分为，降雪预报达到大雪或有冻雨时为三级响应。"),
            StemEntry(6L, 1L, "降雪会商条件。（）发布降雪预警通知、预警调度命令时，段降雪会商领导小组及时组织降雪会商。"),
        )
        // 拍题那条路的输入：题干 + 它自己那几个选项（真机 OCR 的形状）
        val 拍到的整块 = "20(3分)降雪会商的决策内容主要包括?\n" +
            "A制定落实集团公司旅客列车调整方案、货运组织调整方案、影响运输的装卸车和车流调整等临时性安排、施工计划调整安排及有关重点工作提示和要求的工作措施\n" +
            "B严格落实旅客列车禁停线路、禁停区段、禁停车站，制定管内旅客列车和货运组织等调整方案，确定有关旅客列车停运、折返、变更径路、换乘组织、降速运行等总体性安排\n" +
            "C施工（维修）计划调整安排\n" +
            "D确定干部包保和值班方案。五是有关重点工作提示和要求。集团公司降雪预报预警信息明确的各类要求"
        val matcher = StemMatcher(题库)
        val outcome = matcher.match(拍到的整块)
        println("主题集中的库 · 拍到的整块 → 候选 " + outcome.hits.size + " 条，genericOnly=" + outcome.genericOnly)
        outcome.hits.forEach { println("   " + (it.score * 100).toInt() + "% " + it.entry.stem) }
        assertTrue("这道题就该被摆出来（修之前 100% 被当成通用问法丢掉）", outcome.hits.any { it.entry.id == 1L })
        assertTrue("而且是可采信的", outcome.confident)
    }

    /**
     * 反过来的守门：短条目**带着选项**，但这次输入里根本没有它的选项 ——
     * 那就还是「长输入撞上一句通用问法」，照旧要挡（这条保证上面那个放宽没把 2.11.0 的保护弄丢）。
     */
    @Test
    fun 输入里没有它自己的选项时仍按通用问法挡掉() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "降雪会商的决策内容主要包括?", listOf(
                "制定落实集团公司旅客列车调整方案、货运组织调整方案、影响运输的装卸车和车流调整等临时性安排",
                "严格落实旅客列车禁停线路、禁停区段、禁停车站，制定管内旅客列车和货运组织等调整方案",
                "施工（维修）计划调整安排",
                "确定干部包保和值班方案。五是有关重点工作提示和要求",
            )),
            StemEntry(2L, 1L, "降雪会商形成的（）、（）、（）和（）等方案，经领导小组组长批准后，由指挥中心在段内通知公布实施。"),
            StemEntry(3L, 1L, "降雪会商的决策内容还包括哪些事项请说明降雪会商的基本流程与决策程序以及参与部门"),
            StemEntry(4L, 1L, "对于集团公司降雪会商确定的各类调整方案，纳入决策内容，原则上不得更改。"),
            StemEntry(5L, 1L, "根据降雪预报等级将响应划分为，降雪预报达到大雪或有冻雨时为三级响应。"),
            // 这条是为了让目标条目每个字组在库里都出现 ≥2 次（= 主题集中的库的真实形态）：
            // 「容主」「要包」这些字组只有另一条也讲「决策内容主要包括」时才不稀有
            StemEntry(6L, 1L, "除雪保畅工作的决策内容主要包括人员机具准备、联劳协作安排与信息通报。"),
            StemEntry(7L, 1L, "站区除雪保畅通联劳协作的决策内容主要包括预案启动、分工落实与检查考核。"),
        )
        // 另一道题（它的内容完全不含上面那条题的选项）
        val 另一道题 = "降雪会商的决策内容主要包括?\n" +
            "A降雪预报等级划分与响应启动条件\nB除雪保畅的人员机具物资准备\n" +
            "C管内各站除雪保安全畅通联劳协作\nD防寒过冬教育培训与考试组织"
        val outcome = StemMatcher(题库).match(另一道题)
        println("输入不含它的选项 → 候选 " + outcome.hits.size + " 条，genericOnly=" + outcome.genericOnly)
        assertTrue("没有它自己的选项做证据时，照旧按通用问法挡掉", outcome.genericOnly > 0)
        assertTrue("就不该采信", !outcome.confident)
    }

    /**
     * 判断题的选项只有「对 / 错」，太容易在别处撞见 —— 不当「同一道题」的证据。
     */
    @Test
    fun 判断题那两个字的选项不当证据() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "货场室外严禁吸烟。", listOf("对", "错")),
            StemEntry(2L, 1L, "货场室外严禁吸烟和明火。", listOf("对", "错")),
            StemEntry(3L, 1L, "货场室外严禁烟火，作业区内注意安全。", listOf("对", "错")),
            StemEntry(4L, 1L, "装卸作业时严禁吸烟，货场室外同样严禁。", listOf("对", "错")),
        )
        val 长输入 = "货场室外严禁吸烟这是全体人员都必须记住的安全底线要求请相互提醒"
        val outcome = StemMatcher(题库).match(长输入)
        println("判断题短条目 → 候选 " + outcome.hits.size + " 条，genericOnly=" + outcome.genericOnly)
        assertTrue("「对/错」不足以证明是同一道题", outcome.genericOnly > 0)
    }

    /**
     * 这条判据**只管短条目**，一整道题的题干照旧 ——
     * 否则 1749 题的全量回归里，大量「公式化」的题目会被误伤。
     */
    @Test
    fun 长题干不受这条判据影响() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "本规程适用于（）铁路集团有限公司（以下简称国铁集团）所属铁路运输企业和控股合资铁路公司在境内的铁路旅客运输。"),
        )
        val outcome = StemMatcher(题库).match("本规程适用于")
        println("长题干 → " + "%.1f%%".format((outcome.best?.score ?: 0.0) * 100) + "，confident = " + outcome.confident)
        assertTrue("长题干照旧按分数命中", outcome.confident)
    }

    /** 短输入碰上「专用名词」照旧要能搜到（用户举的例子是「烧伤分度」）。 */
    @Test
    fun 短专用名词照旧能搜到() {
        val 题库 = listOf(
            StemEntry(1L, 1L, "男,4岁,双下肢烧伤红肿疼痛。入院查体:双下肢可见大小不一的水疱,创面红润,疼痛剧烈。按烧伤分度与面积估算,判断该患儿烧伤的深度和面积。"),
            StemEntry(2L, 1L, "男,12岁。发热2天,伴头痛、神志不清1天,8月中旬来诊。查体:T39.5℃,神志不清。\n\n(1.0分)最可能的诊断是"),
        )
        val outcome = StemMatcher(题库).match("烧伤分度")
        println("专用名词 → " + (outcome.best?.entry?.stem?.take(18) ?: "无") + " 可信=" + outcome.confident)
        assertTrue("短专用名词应当搜得到", outcome.confident)
    }
}