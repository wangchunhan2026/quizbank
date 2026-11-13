package com.daily.quizbank.util

import kotlin.math.max
import kotlin.math.min

/**
 * 「接着上一次识别」：把新拍到的一段文字接在已有内容后面。
 *
 * 为什么需要这一步：题干长的题**一屏拍不完**。用户把屏幕往下拨一段再拍一次，
 * 原来那段就被新的整段覆盖掉了 —— 拍几次下来只剩最后一段，前面全白拍。
 * 解析太长、选项多到要翻屏时是同一个毛病。
 *
 * 但两段直接摞起来也不行：用户多半会**故意重叠**一点再拍（怕漏掉中间几行），
 * 摞起来重合的那些行就会来第二遍。所以这里先找「上一段的结尾和这一段的开头
 * 是不是同一段内容」，找到就从重合处接上；整段早就在里面了（用户滚回去又拍了一遍），
 * 就什么都不加。
 *
 * 两条判据都刻意**宁可重复，不可误删**：
 * 接错了用户一眼能看见、能删；误删掉一行，用户根本不知道少了什么。
 */
object CaptureMerge {

    private const val NEWLINE = "\n"

    /**
     * 一行至少要有这么多实义字符，才敢做「这两行是同一行」的模糊判断。
     *
     * 「A」「正确」这种短行单独比较毫无意义 —— 一行字太少，什么都像。
     * 误判一次就是白吃一行内容，所以宁可不判。
     */
    private const val FUZZY_MIN_CHARS = 4

    /**
     * 模糊判定的分数线。
     *
     * 定在 0.6 而不是更高：同一个区域拍两次，OCR 常常差一两个字
     * （第一次「C.粒细胞缺乏症」、第二次「C.粒細细胞缺乏症」），
     * 卡到 0.8 就认不出是同一行，重合的那几行会重复一遍。
     *
     * 0.6 已经足够把两条不同的选项分开 —— 「A.动脉血压升高」对上
     * 「B.动脉血压降低」只有 0.33（见 [Similarity.score] 的 bigram 口径）。
     */
    private const val FUZZY_SCORE = 0.6

    /** 这一段的多少行能在上一段里连着找到，就算「整段早就有了」。 */
    private const val CONTAINED_RATIO = 0.6

    /**
     * 把 [piece] 接在 [base] 后面。
     *
     * 返回的仍然是「一行一段」的文本（行序不变），和 OCR 原文的形态一致 ——
     * 收录面板拿到它之后还要再走一遍「拆题干 / 拆选项」，形态不一致会拆歪。
     */
    fun text(base: String, piece: String): String {
        val a = lines(base)
        val b = lines(piece)
        if (b.isEmpty()) return a.joinToString(NEWLINE)
        if (a.isEmpty()) return b.joinToString(NEWLINE)

        val overlap = overlapLength(a, b)
        if (overlap > 0) {
            // 重合的那几行取「更长的那一版」：上一段的末尾常常正好被截图边缘切掉半行，
            // 这一段的同一行是完整的，接上完整的那一版才不会留一个半截字条。
            val head = a.subList(0, a.size - overlap)
            val shared = (0 until overlap).map { pickFuller(a[a.size - overlap + it], b[it]) }
            return (head + shared + b.drop(overlap)).joinToString(NEWLINE)
        }

        // 没有重合：要么是接着往下拍的（照单全收），要么是用户滚回去把同一段又拍了一遍 ——
        // 后者什么都不该加，否则每一行都会来第二遍。
        if (isAlreadyThere(a, b)) return a.joinToString(NEWLINE)

        return (a + b).joinToString(NEWLINE)
    }

    private fun lines(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 最大的 k：上一段的末尾 k 行和这一段的开头 k 行是同一段内容。
     *
     * 从大到小试，取第一个成立的 —— 取最大的那个才不会只接上后半截。
     */
    private fun overlapLength(a: List<String>, b: List<String>): Int {
        for (k in min(a.size, b.size) downTo 1) {
            var same = true
            for (i in 0 until k) {
                if (!sameLine(a[a.size - k + i], b[i])) {
                    same = false
                    break
                }
            }
            if (same) return k
        }
        return 0
    }

    /**
     * 这两行是不是同一行。
     *
     * 先比归一化之后的原文（去标点、去空格、字母小写），一样就是一样；
     * 不一样再看相似度 —— 同一块屏幕拍两次，OCR 差一两个字很常见。
     */
    private fun sameLine(x: String, y: String): Boolean {
        val nx = Similarity.normalize(x)
        val ny = Similarity.normalize(y)
        if (nx.isEmpty() || ny.isEmpty()) return false
        if (nx == ny) return true
        if (min(nx.length, ny.length) < FUZZY_MIN_CHARS) return false
        return Similarity.score(x, y) >= FUZZY_SCORE
    }

    /**
     * 同一行的两个版本，取实义字符多的那个。
     *
     * 平手时保留原来那一版：两版一样长就说明谁也不是被切掉的那一版，
     * 没有理由把用户已经看见过的文字换掉。
     */
    private fun pickFuller(old: String, fresh: String): String =
        if (Similarity.normalize(fresh).length > Similarity.normalize(old).length) fresh else old

    /**
     * 这一段是不是早就躺在上一段里了。
     *
     * 只看「从这一段的头一行开始、能连着对上多少行」。用户滚回去重拍，
     * 拍到的就是从某个位置开始的一整段连续内容；而「接着往下拍」时，
     * 重合的只是它开头的一两行，那点重合在 [overlapLength] 那一步就处理掉了，
     * 走不到这儿。
     *
     * 要求对上这一段的大部分（[CONTAINED_RATIO]），且至少两行 ——
     * 只有一行对得上就说「早就有」，太容易把新内容当成旧的扔掉。
     */
    private fun isAlreadyThere(a: List<String>, b: List<String>): Boolean {
        if (b.size < 2) return false
        val need = max(2, (b.size * CONTAINED_RATIO).toInt())
        for (start in a.indices) {
            var i = 0
            while (i < b.size && start + i < a.size && sameLine(a[start + i], b[i])) i++
            if (i >= need) return true
        }
        return false
    }
}
