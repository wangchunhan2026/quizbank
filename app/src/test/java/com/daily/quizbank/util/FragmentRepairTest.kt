package com.daily.quizbank.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新的这道是不是旧的半截的完整版」。
 *
 * 用例里的旧条目全部照抄用户 2026-10-09 导出的真题库（他库里那 5 条残缺条目）。
 */
class FragmentRepairTest {

    private val 新收的 = "男,38岁。右小腿被铁钉刺伤3天,伤口周围红肿热痛,今日出现张口困难、苦笑面容。" +
        "查体:神志清,颈项强直,角弓反张。\n\n(1.0分)最可能的诊断是"

    @Test
    fun 题号对不上也能认出是同一问() {
        // 旧的是「2021U2-106 可能的诊断是」，新收的题号是 2021U2-105（或者根本没拍进屏幕）
        assertTrue(FragmentRepair.supersedes("2021U2-106 可能的诊断是", 新收的))
    }

    @Test
    fun 老格式的题号也认() {
        assertTrue(
            FragmentRepair.supersedes(
                "2008U4-150 目前最重要的治疗方法是",
                "男,45岁。反复上腹痛3年。\n\n(1.0分)目前最重要的治疗方法是",
            ),
        )
        // 短题补上材料之后也算完整版（新收的题干里带着材料）
        assertTrue(
            FragmentRepair.supersedes(
                "26.腺垂体分泌的激素是",
                "某患者垂体术后出现激素水平异常。查体:生长发育迟缓。\n\n26.腺垂体分泌的激素是",
            ),
        )
    }

    @Test
    fun 残缺条目里粘着半个选项也不影响() {
        // 他库里那条「2023U2-115 首选的治疗 / X A右侧侧斜疝修补术」就是这个形态：
        // 题干里粘着半个选项，摘问法时要把那行去掉
        assertTrue(
            FragmentRepair.supersedes(
                "2023U2-115 首选的治疗\nX A右侧侧斜疝修补术",
                "男,2岁。右侧腹股沟区可复性肿物2年,平卧后消失。查体:右侧腹股沟可触及肿物。\n\n(1.0分)首选的治疗",
            ),
        )
    }

    @Test
    fun 问法太短的半截不去打扰用户() {
        // 「诊断为」只有三个字，别的题里到处都是，比对上了也说明不了什么
        assertFalse(FragmentRepair.supersedes("2023U2-114 诊断为", 新收的))
    }

    @Test
    fun 新收的和旧的差不多长就不算完整版() {
        // 同一道短题又收了一遍（题干长度没变）：谈不上「补上了材料」，不打扰用户
        assertFalse(FragmentRepair.supersedes("26.腺垂体分泌的激素是", "27.腺垂体分泌的激素是"))
    }

    @Test
    fun 新旧两条都是OCR出来的差一两个字也认() {
        // 旧条目里是「为明确诊断应采取的检查方法是」，重收时 OCR 把「采取」认成了「采用」
        assertTrue(
            FragmentRepair.supersedes(
                "2021U2-107 为明确诊断应采取的检查方法是",
                "男,50岁。反复上腹痛3年。查体:上腹压痛。\n\n(1.0分)为明确诊断应采用 的检查方法是",
            ),
        )
    }

    @Test
    fun 问法对不上就不能顶掉() {
        assertFalse(
            FragmentRepair.supersedes(
                "2008U4-150 目前最重要的治疗方法是",
                "男,38岁。右小腿被铁钉刺伤3天,今日出现张口困难。\n\n(1.0分)最可能的诊断是",
            ),
        )
    }

    @Test
    fun 长题干本来就不是半截() {
        val 完整的 = "男,25岁。右胫前皮肤红肿2天,畏寒、发热1天。局部表皮发红,微隆起,指压稍褪色。\n\n(1.0分)最可能的诊断是"
        assertFalse(FragmentRepair.looksLikeFragment(完整的))
        assertFalse(FragmentRepair.supersedes(完整的, 完整的 + "补充说明"))
    }

    @Test
    fun 自己不会被自己顶掉() {
        // 2026-10-12 用户报的毛病：他每收一道**短题**（归一化不到 30 字的单选题，他库里占四成），
        // 收完都弹「要不要顶掉旧的」，他一点「顶掉」，**刚收的那道题自己就没了** ——
        // 后面重拍就显示「题库里没有这道题」。
        //
        // 成因：入库之后再查「谁被我补全了」，新存的这条自己也是一条「不到 30 字的半截」，
        // 而它当然完整包含它自己。下面这几条全是他导出的真题库里**原样**的题干
        // （拿全库量过：175 道里 21 道会自顶自）。
        val 会自顶自的 = listOf(
            // 2026-10-12 真机日志里那条（他复测时就是它显示「没有收录过」）
            "2023U3-142 粪隐血试验阳性的消化道出血\n至少大于",
            "2021U2-106 可能的诊断是",
            "2021U1-8休液调节的特点不包括",
            "2026U1-3人体内含量最多的体液是",
            "2008U3-15最有助于确诊肾动脉狭窄的检查是",
            "2024ESU1-11 属于正反馈调节的是",
            "2024U1-26下列生理功能活动中,负反馈调节叁与的",
        )
        for (stem in 会自顶自的) {
            assertFalse("「" + stem.replace('\n', ' ') + "」不该把自己顶掉", FragmentRepair.supersedes(stem, stem))
        }
    }

    @Test
    fun 新收的没有比旧的更长就不算补全() {
        // 「顶掉」的前提是新收的补上了材料、比旧的更长；长度没涨就什么都不做
        assertFalse(FragmentRepair.supersedes("2021U2-106 可能的诊断是", "2021U2-106 可能的诊断是"))
        assertFalse(
            FragmentRepair.supersedes(
                "2024U1-26下列生理功能活动中,负反馈调节参 与的",
                "2024U1-26下列生理功能活动中,负反馈调节叁与的",
            ),
        )
    }

    @Test
    fun 摘问法时只取第一行并把题号去掉() {
        assertEquals("可能的诊断是", FragmentRepair.askOf("2021U2-106 可能的诊断是"))
        assertEquals("腺垂体分泌的激素是", FragmentRepair.askOf("26.腺垂体分泌的激素是"))
        // 第一行后面粘着的选项行不参与比对
        assertEquals("首选的治疗", FragmentRepair.askOf("2023U2-115 首选的治疗\nX A右侧侧斜疝修补术"))
        // 开头是拉丁字母但没数字的，不许被当成题号削掉
        assertTrue(FragmentRepair.askOf("DNA聚合链中最稳定的碱基对是").startsWith("dna"))
    }
}
