package com.daily.quizbank.util

import com.daily.quizbank.util.DedupPolicy.Keep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入重复题的判断：分桶、默认留哪条、哪个按钮真的会删东西。
 *
 * 这一组盯的是**会动用户库里数据的那几条判断**（顶掉旧题、丢文件里更早那份），
 * 写错了不会报错，只会悄悄少一道题 —— 所以用例全部照着真机上会出现的形态写。
 */
class DedupPolicyTest {

    private val 库里那条 = DedupPolicy.Counterpart(
        id = 731L,
        inBank = true,
        stem = "2004U4-10 骨囊肿好发于",
        answer = "A",
        options = listOf("A.长管骨干骺端", "B.长管骨骨端"),
        analysis = "骨囊肿好发于长管骨干骺端。",
    )

    private val 文件里那条 = DedupPolicy.Newcomer(
        answer = "A",
        options = listOf("A.长管骨干骺端", "B.长管骨骨端"),
    )

    @Test
    fun 分桶键剥掉题号再取前八个字() {
        // 题号会变（刷题软件自己的序号、OCR 多认的数字），不剥掉同一道题会分进两个桶
        assertEquals("男70岁直肠指诊", DedupPolicy.bucketKey("26. 男，70岁，直肠指诊"))
        assertEquals("患者男30岁发热", DedupPolicy.bucketKey("(1) 患者，男，30岁。发热3天"))
        // 标点和空格在归一化那一步就去掉了
        assertEquals("abc甲状腺功能", DedupPolicy.bucketKey("123.ABC 甲状腺功能减退"))
    }

    @Test
    fun 开头八个字不一样就分不到一个桶里() {
        // 分桶的代价，写在 QuestionRepository 的注释里，这里钉住它：这是**已知**行为
        assertFalse(
            DedupPolicy.bucketKey("关于甲状腺功能减退症") ==
                DedupPolicy.bucketKey("甲状腺功能减退症的"),
        )
    }

    @Test
    fun 分桶键太短就不参与近似比较() {
        // 三五个字撞车是常态，不是同一道题（「诊断为」这种残缺问法）
        assertFalse(DedupPolicy.bucketUsable(DedupPolicy.bucketKey("1.诊断为")))
        assertTrue(DedupPolicy.bucketUsable(DedupPolicy.bucketKey("26.腺垂体分泌的激素是")))
    }

    @Test
    fun 相似度八十八算近似八十七点九不算() {
        assertTrue(DedupPolicy.isNear(0.88))
        assertTrue(DedupPolicy.isNear(1.0))
        assertFalse(DedupPolicy.isNear(0.879))
    }

    @Test
    fun 留新的会把库里那条顶掉() {
        // 不顶掉的话这一选等于没选：库里两条都在，而拍题取的是先入库那条（id 小的）
        val d = DedupPolicy.decide(Keep.NEW, draftIndex = 5, earlierIndex = -1, counterpart = 库里那条)
        assertEquals(-1, d.dropDraftIndex)
        assertEquals(731L, d.deleteQuestionId)
    }

    @Test
    fun 留新的会顺手丢掉文件里更早那份() {
        // 同一份文件里出现两次：留新的那条，旧的那份拷贝要跟着丢，不然库里还是两条
        val d = DedupPolicy.decide(Keep.NEW, draftIndex = 5, earlierIndex = 2, counterpart = 库里那条)
        assertEquals(2, d.dropDraftIndex)
        assertEquals(731L, d.deleteQuestionId)
    }

    @Test
    fun 别的题库里那条不许删() {
        // 跨库重复：导入动不了别的题库，只能让用户选「这条不入库」
        val 别库 = 库里那条.copy(id = 88L, inBank = false, otherBankName = "医学")
        val d = DedupPolicy.decide(Keep.NEW, draftIndex = 5, earlierIndex = -1, counterpart = 别库)
        assertEquals(-1, d.deleteQuestionId)
        assertEquals(-1, d.dropDraftIndex)
    }

    @Test
    fun 留原来那条就是这条不入库() {
        val d = DedupPolicy.decide(Keep.OLD, draftIndex = 5, earlierIndex = 2, counterpart = 库里那条)
        assertEquals(5, d.dropDraftIndex)
        assertEquals(-1L, d.deleteQuestionId)
    }

    @Test
    fun 两条都留什么都不动() {
        val d = DedupPolicy.decide(Keep.BOTH, draftIndex = 5, earlierIndex = 2, counterpart = 库里那条)
        assertEquals(-1, d.dropDraftIndex)
        assertEquals(-1L, d.deleteQuestionId)
    }

    @Test
    fun 近似的那几条不跟着剩下都按钮走() {
        // 「剩下都保留新导入的」点下去是批量顶掉旧题，近似的那几条要是跟着走，
        // 用户一句话就丢掉了自己半道本来就不同的题
        assertEquals(Keep.BOTH, DedupPolicy.restKeep(keepNew = true, near = true))
        assertEquals(Keep.BOTH, DedupPolicy.restKeep(keepNew = false, near = true))
        // 一模一样的才跟着按钮走
        assertEquals(Keep.NEW, DedupPolicy.restKeep(keepNew = true, near = false))
        assertEquals(Keep.OLD, DedupPolicy.restKeep(keepNew = false, near = false))
    }

    @Test
    fun 两边答案或选项对不上时不预设() {
        assertNull(DedupPolicy.defaultKeep(false, 库里那条.copy(answer = "B"), 文件里那条))
        assertNull(
            DedupPolicy.defaultKeep(
                false,
                库里那条.copy(options = listOf("A.长管骨骨端", "B.长管骨干骺端")),
                文件里那条,
            ),
        )
        // 两边信息一样多（算不上谁比谁全）→ 默认留新的，这正是「重导一份修好的文件」要的效果
        val 一样全的新版 = 文件里那条.copy(analysis = 库里那条.analysis)
        assertEquals(Keep.NEW, DedupPolicy.defaultKeep(false, 库里那条, 一样全的新版))
    }

    @Test
    fun 近似的一律默认两条都留() {
        assertEquals(Keep.BOTH, DedupPolicy.defaultKeep(true, 库里那条, 文件里那条))
    }

    @Test
    fun 库里那条还带着解析时不默认顶掉它() {
        // 解析/知识点/星标是用户后来补进去的，文件里通常没有 —— 顶掉就没了，
        // 而且他看不见丢了什么，所以默认留原来那条，让想换新版的自己点
        val 新版 = 文件里那条
        assertEquals(listOf("解析"), DedupPolicy.extraFields(库里那条, 新版))
        assertEquals(Keep.OLD, DedupPolicy.defaultKeep(false, 库里那条, 新版))
        // 新导入的这条自己也带解析时，两边都不缺东西 → 默认留新的
        assertEquals(
            emptyList<String>(),
            DedupPolicy.extraFields(库里那条, 新版.copy(analysis = "一样的解析")),
        )
        assertEquals(Keep.NEW, DedupPolicy.defaultKeep(false, 库里那条, 新版.copy(analysis = "一样的解析")))
    }

    @Test
    fun 星标是用户自己标的也算多出来的() {
        val 标了星的 = 库里那条.copy(analysis = "", starred = true)
        assertEquals(listOf("星标"), DedupPolicy.extraFields(标了星的, 文件里那条))
        assertEquals(Keep.OLD, DedupPolicy.defaultKeep(false, 标了星的, 文件里那条))
    }

    @Test
    fun 别的题库里那条默认照样导入() {
        val 别库 = 库里那条.copy(id = 88L, inBank = false, otherBankName = "医学")
        assertEquals(Keep.NEW, DedupPolicy.defaultKeep(false, 别库, 文件里那条))
    }

    @Test
    fun 同一条旧题同时被选了顶掉和保留时不许删() {
        // 文件里两条近似拷贝指着库里同一条题：一条选「留新的」，另一条选「留原来那条」
        // —— 这时候删掉旧题，选「留原来那条」的那道题就两边都没有了
        assertEquals(setOf(7L, 9L), DedupPolicy.replaceIds(listOf(7L, 9L, 8L), keptOld = listOf(8L)))
        assertEquals(emptySet<Long>(), DedupPolicy.replaceIds(listOf(8L), keptOld = listOf(8L)))
        // -1（没有对照项）永远不该进删除列表
        assertEquals(setOf(7L), DedupPolicy.replaceIds(listOf(7L, -1L), keptOld = emptyList()))
    }

    // ------------------------------------------------ 第三档：一条是另一条的一部分（同一条题没收完）

    /** 共用题干那类题的形态：一段材料 + 一句小问。 */
    private val 材料 = "男，38岁。右小腿被铁钉刺伤3天，伤口周围红肿热痛，今日出现张口困难、苦笑面容。" +
        "查体：神志清，颈项强直，角弓反张。"

    private val 材料长 = "患者男62岁，因反复上腹痛3年、加重1周入院。患者3年前无明显诱因出现上腹部隐痛，" +
        "多于进食后加重，无放射痛，无发热，无反酸嗳气，自服胃药可缓解。近1周疼痛加重呈持续性，" +
        "伴恶心呕吐胃内容物。查体：上腹部压痛，无反跳痛，肝脾肋下未触及，移动性浊音阴性，肠鸣音正常。" +
        "实验室检查：血常规示白细胞计数轻度升高，血红蛋白及血小板正常，大便隐血试验阳性。"

    /** Kotlin 里把换行写成常量：正文里再也不出现 "\n"，省得又被工具这一层吃掉（第四节第 2 条）。 */
    private val 换行 = "\n\n"

    private val 问甲 = "该患者最可能的诊断是什么请选出最恰当的一项"
    private val 问乙 = "为明确诊断首选的检查方法是什么请选出最佳答案"

    @Test
    fun 材料没拍全的那条算同一条题() {
        // 用户 2026-10-13 报的形态：同一道共用题干题收了两三次，前面几次材料只拍了一半
        val 完整 = 材料 + 换行 + 问甲
        val 半截 = 材料.take(20) + 换行 + 问甲
        println("半截那条的覆盖率 = " + DedupPolicy.partCoverage(半截, 完整))
        assertTrue(DedupPolicy.isPartOf(半截, 完整))
        // 传参顺序不该影响结果
        assertTrue(DedupPolicy.isPartOf(完整, 半截))
    }

    @Test
    fun 同一个材料下的不同小问不算同一条题() {
        // 这一档最重要的守门用例：共用题干题每条都带着同一段材料，覆盖率能到 0.9 上下，
        // 只看覆盖率会把同一道题好好儿的几个小问判成重复、建议用户删掉
        val 第一问 = 材料长 + 换行 + 问甲
        val 第二问 = 材料长 + 换行 + 问乙
        println("两个小问之间的覆盖率 = " + DedupPolicy.partCoverage(第一问, 第二问))
        assertTrue(DedupPolicy.partCoverage(第一问, 第二问) >= DedupPolicy.CONTAIN_COVERAGE)
        assertFalse(DedupPolicy.tailMatches(第一问, 第二问))
        assertFalse(DedupPolicy.isPartOf(第一问, 第二问))
    }

    @Test
    fun 太短的条目不进这一档() {
        // 「最可能的诊断是」这种通用问法能被任何长题干盖住，放进来只会刷屏
        assertEquals(0.0, DedupPolicy.partCoverage("最可能的诊断是", 材料长), 0.0)
        assertFalse(DedupPolicy.isPartOf("最可能的诊断是", 材料长 + 问甲))
    }

    @Test
    fun 不相干的两条覆盖率很低() {
        assertFalse(
            DedupPolicy.isPartOf(
                "腺垂体分泌的激素是什么请选出正确的一项",
                "本案中施工单位应当承担的法律责任包括哪些",
            ),
        )
    }

    @Test
    fun 长题干挂在好几个桶下面() {
        // 半截那条可能从中间某个字开始，所以除了开头，每 8 个字还要挂一个窗口
        val keys = DedupPolicy.containKeys(材料长)
        assertEquals(DedupPolicy.bucketKey(材料长), keys.first())
        assertTrue(keys.size > 5)
        assertTrue(keys.all { it.length == DedupPolicy.BLOCK_SIZE })
        assertEquals(keys.size, keys.distinct().size)
        // 短题干只挂一个键
        assertEquals(1, DedupPolicy.containKeys("26.腺垂体分泌的激素是").size)
    }

    @Test
    fun 分桶键那一套没被改动() {
        // 第三档的归一化抽出来之后，前两档的分桶必须一个字都没变
        assertEquals("男70岁直肠指诊", DedupPolicy.bucketKey("26. 男，70岁，直肠指诊"))
        assertEquals("患者男30岁发热", DedupPolicy.normalizedStem("(1) 患者，男，30岁。发热3天").take(8))
    }
}
