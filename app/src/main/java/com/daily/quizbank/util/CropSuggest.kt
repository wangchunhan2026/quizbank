package com.daily.quizbank.util

import com.daily.quizbank.data.ocr.OcrLine

/** 归一化（0..1）的裁剪框，和 [ImageUtils.cropNormalized] 吃的是同一种坐标。 */
data class CropBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 这一轮框选**要盖哪一块** —— 由用户按的是哪一个「识别」按钮决定。
 *
 * 用户 2026-10-12 要的：「把拍题和收录时的默认识别框分开；收录时点『识别题干』就默认只框住题干，
 * 点『只识别选项』就默认只框住选项，人还可以再拖。」
 *
 * 拍题 / 搜题 / 拍照那条路上没有「哪一栏」这回事，一律 [AUTO]，行为和以前完全一样。
 */
enum class CropFocus {
    /** 整道题：题干 + 选项。拍题、搜题、拍照、以及「识别题干和选项」这类按钮都用它。 */
    AUTO,

    /**
     * 只要题干那一块，**不含选项**。
     *
     * 两种版式都认：普通题的「题干首行 → 第一个选项上面那一行」；
     * 共用题干题里则是**材料那一段**（材料在「第2问 / 题号行」上面，小问和它的选项都不该进来）。
     */
    STEM,

    /** 只要选项那一块：从第一个选项行到最后一行选项（含折行）。 */
    OPTIONS,

    /**
     * 只要**这一小问本身**（共用题干题的小问卡片用的）。
     *
     * 和 [STEM] 的区别：共用题干题里 [STEM] 给的是**材料那一段**（大题干要的就是它），
     * 而小问那一栏要的是「第2问」下面那句问题 —— 材料和小问都得按各自的栏分开框，
     * 否则两个框出来的是同一段东西。
     */
    PART,
}

/**
 * 从「这一屏上有哪些文字、各自在哪」猜出「题目大概在哪一块」。
 *
 * 要解决的问题：截屏之后默认整屏识别，用户每次都得自己拖一个框把题干和选项框住。
 * 夸克那种「拍完就已经框好了」的体验，靠的就是这一步。
 *
 * 这里刻意**不引入任何图像处理、也不多跑一个模型**：ML Kit 为了识别文字，
 * 本来就把每一行的位置算出来了（Text.Line.boundingBox），白扔了可惜。
 * 拿文字行的位置反过来读版式，又便宜又准 —— 题干和选项天然是「一坨挨在一起的宽行」，
 * 而状态栏、页眉、底部工具栏则是「又窄又散」。
 *
 * 主路径**以选项行为锚**，因为选项是整屏里最可靠的信号：
 *
 *  1. 找出所有「选项行」（[OcrText.optionLetterOf] 认出来的 A~H 开头的行）；
 *  2. 取其中字母严格递增的一段 —— 选项一定是 A 往后排的，
 *     解析正文里冒出来的「B 选项错误」会让字母往回退，正好被这条挡住；
 *  3. 从第一个选项往上，把挨得够近的行都吃进来，那就是题干；
 *     再从顶端剪掉「又窄又短」的行 —— 那是页眉、状态栏、App 自己的按钮；
 *  4. 从最后一个选项往下，只吃紧贴着的行（选项的折行）；
 *     再往下的「正确答案 B」隔着一段空白，自然进不来。
 *
 * 一条选项都没认出来时（填空题、题干一整段）退回备用路径：找第一行
 * 「靠左 + 够宽 + 够长」的文字当题干起点，往下吃到断开为止。
 *
 * 纯计算，不碰任何 Android 类，所以能直接在电脑上跑单元测试 —— 见 CropSuggestTest。
 */
object CropSuggest {

    /** 顶部这一条（状态栏 / 刘海）不参与，免得把电量和时间框进去。 */
    private const val TOP_STRIP = 0.06f

    /** 底部这一条（导航栏 / 底部工具栏）同理。 */
    private const val BOTTOM_STRIP = 0.05f

    /** 相邻两个选项之间允许的最大间距（占屏高比例）。 */
    private const val OPTION_GAP = 0.08f

    /**
     * 往上找题干时，两行之间允许的最大间距。
     *
     * 2026-10-13 从 0.06 提到 **0.08**：拿他 18 张真机截图（Windows OCR 的行框）量过，
     * 题干最后一行到选项 A 之间的空档是 66~85px（2.8%~3.5%），**但其中一屏是 162px（6.8%）**
     * —— 题干排成两行时，刷题软件会在选项块前面多留一段空白。0.06（144px）刚好卡在这个值下面，
     * 那一屏的框就只框住了 A~E，**题干整个漏在框外面**：拍题显示「题库里没有这道题」，
     * 而那道题（「30.在应激的认知评价中…」）就在他库里（medstems.txt 第 58 行）。
     *
     * 往上多吃一点有兜底：撞上题号行 + 它上面像页眉就定住框顶（见 [locateOptions]）；
     * OCR 把题号吃掉的那种，还有「页眉块」那段往回剪。
     */
    private const val STEM_GAP = 0.08f

    /** 选项折行：和上一行贴得这么近，才算同一块。 */
    private const val FOLD_GAP = 0.025f

    /** 备用路径里，题干往下延伸时允许的间距。 */
    private const val FALLBACK_GAP = 0.035f

    /** 备用路径最多吃到屏高的这个比例。 */
    private const val FALLBACK_MAX_HEIGHT = 0.45f

    /** 一行宽度不到屏宽的这么多，就算「窄行」，多半是页眉或角标。 */
    private const val NARROW_RATIO = 0.40f

    /**
     * 「这一行是题干的第一行」的判据。
     *
     * 刷题软件的题干几乎总是以题号开头（「32.硫脲类…」「2024ESU1-11 属于…」），
     * 而它**上面**那一行是 App 的标题栏（「4月临床执医模考-第一单元」）和
     * 「A1型题 1分    32/150」这类角标。
     *
     * 真机 2026-10-09 实测：标题栏和角标都**不算窄行**（标题居中但连着左右两个图标，
     * 角标横跨左右两侧），靠 [isNarrow] 一条都拦不住，于是默认框每次都要把这两行框进去，
     * 用户每次都得手动往上拖 —— 他报的就是这个。
     *
     * 题号后面**不能紧跟数字**，否则「1.5米以内」「2023.05 起施行」这种正文会被当成题号。
     */
    private val QUESTION_START = Regex("""^\s*[（(]?\s*(?:\d{1,4}\s*[.、．)）]\s*[^\d\s]|第\s*\d{1,4}\s*题)""")

    /**
     * 「32/150」这种进度角标。
     *
     * 卡得很死：**行尾**的一小串「数字 / 数字」，而且前面要么是空白、要么就在行首。
     * 不这么卡的话，「单程运行时间在1/2小时以上」这种正文会被当成角标剪掉 ——
     * 中文正文里的斜杠两侧通常没有空格，这一条正好把它们分开。
     */
    private val PROGRESS = Regex("""(^|\s)\d{1,3}\s*/\s*\d{1,4}\s*$""")

    /** 题干首行的判定：够长、靠左、够宽。 */
    private const val STEM_MIN_CHARS = 10
    private const val STEM_MAX_LEFT = 0.15f
    private const val STEM_MIN_WIDTH = 0.50f

    /** 选项最多认这么多个，防止解析里的编号把范围拖长。 */
    private const val MAX_OPTIONS = 12


    /** 外扩一点边距，别让字贴着框边被切掉。 */
    private const val PAD_X = 0.015f
    private const val PAD_Y = 0.008f

    /** 猜出来的框至少要有这么大；太小就是猜歪了，不如退回整屏。 */
    private const val MIN_WIDTH = 0.30f
    private const val MIN_HEIGHT = 0.05f

    /**
     * 「只框这一小问」那一档的最小高度。
     *
     * 小问常常就是**一行**（「2023U2-114 诊断为」），而一行只有屏高的 2% 上下 ——
     * 拿整道题那条 5% 的底线去卡，它会直接被判成「猜歪了」而整屏退回。
     * 那一档本来就是「照着这一行框」，小是正常的。
     */
    private const val MIN_HEIGHT_PART = 0.02f

    /**
     * @param focus 这一轮要盖哪一块，见 [CropFocus]。默认 [CropFocus.AUTO] = 和以前一样框整道题。
     */
    fun suggest(
        lines: List<OcrLine>,
        width: Int,
        height: Int,
        focus: CropFocus = CropFocus.AUTO,
    ): CropBox? {
        if (width <= 0 || height <= 0) return null
        val w = width.toFloat()
        val h = height.toFloat()

        val topLimit = h * TOP_STRIP
        val bottomLimit = h * (1f - BOTTOM_STRIP)
        // 只留「完整地待在中段」的行：压在状态栏/导航栏上的直接不要
        val usable = lines
            .filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
            .filter { it.top >= topLimit && it.bottom <= bottomLimit }
            .sortedBy { it.top }
        if (usable.isEmpty()) return null

        val anchored = locateOptions(usable, w, h)
        val span = when (focus) {
            CropFocus.AUTO -> wholeSpan(usable, anchored, w, h)

            // 只框题干。材料那一段找不出来时（普通题）退回「题干首行 → 选项上面」，
            // 再不行才退回整道题 —— 框大一点用户能拖小，框空了等于每次都白框。
            CropFocus.STEM -> stemSpan(usable, anchored, w, h)
                ?: wholeSpan(usable, anchored, w, h)

            // 只框选项。一条选项都没认出来时退回整道题：那时候往下拖一下就是选项，
            // 而拿「题干那一段」当默认框里一个选项都没有，等于白框。
            CropFocus.OPTIONS -> anchored?.let { it.firstOption..it.end }
                ?: wholeSpan(usable, anchored, w, h)

            // 只框这一小问（共用题干题的小问栏）
            CropFocus.PART -> {
                // 小问常常只有一行，所以这一档单独放宽高度底线（见 MIN_HEIGHT_PART）
                val span = partSpan(usable, anchored) ?: return null
                return boxOf(usable, span, w, h, MIN_HEIGHT_PART)
            }
        } ?: return null
        return boxOf(usable, span, w, h)
    }

    /** 整道题那一段（题干 + 选项）；没有选项锚时退回备用路径。 */
    private fun wholeSpan(
        usable: List<OcrLine>,
        anchored: Anchored?,
        w: Float,
        h: Float,
    ): IntRange? = anchored?.let { it.start..it.end } ?: locateByStem(usable, w, h)

    /**
     * 「第 2 问 / 2023U2-114 诊断为」这种**下一问的开头** —— 共用题干题里材料和小问的分界线。
     *
     * 两种写法都要认：刷题软件里是「第2问」，题干自己则常常带题号，也就是 [QUESTION_START]
     * 那一套。判断题号后面不许紧跟数字的约束照旧（「1.5 倍」不是题号）。
     */
    /**
     * 「第五十四章 …」「第一章 绪论」这种章节标题。
     *
     * 它横跨整行，[isHeaderLike] 的「窄行」判据拦不住（真机那一屏上它宽到 983px），
     * 但它显然不是共用题干题的材料。只在 [stemSpan] 找材料起点时用它。
     */
    private val CHAPTER_TITLE = Regex("""^\s*第\s*[0-9一二三四五六七八九十百]{1,4}\s*[章节讲]""")

    private val NEXT_PART_START = Regex(
        """^\s*[（(]?\s*(?:\d{1,4}\s*[.、．)）]\s*[^\d\s]|第\s*\d{1,4}\s*[问题])""",
    )

    /**
     * 「只要这一小问」那一段：共用题干题里「第2问」下面那句问题，**到第一个选项上面那一行为止**。
     *
     * 起点取「第一个选项上面、最后一个『第N问 / 题号行』的下一行」：
     * 小问首行自己就是题号行时（「114.诊断为」这种）就不切它，从它本身开始。
     */
    private fun partSpan(usable: List<OcrLine>, anchored: Anchored?): IntRange? {
        val a = anchored ?: return null
        val marker = (a.start until a.firstOption)
            .lastOrNull { NEXT_PART_START.containsMatchIn(usable[it].text) }
        val top = when {
            marker == null -> a.start
            marker == a.start -> a.start
            else -> marker + 1
        }
        return if (a.firstOption > top) top..(a.firstOption - 1) else null
    }

    /**
     * 「只要题干」那一段。
     *
     * 一、先往上看：[Anchored.start] 上面要是还接着正文，那就是**共用题干题的病人材料**
     *     （材料在「第2问 / 题号行」上面），把它整段框进去。
     * 二、上面没有正文（撞上页眉、角标、章节标题，或者隔得太远），那就是普通题：
     *     题干从 [Anchored.start] 到第一个选项上面那一行。
     *
     * **这里第一版写错过，教训值得留着**：当时是「从顶端往下找第一行题号/第N问，它上面全算材料」——
     * 于是**普通题**（题干首行本身就带题号，比如「67.男，70岁…」）被当成了「小问」，
     * 框出来的是上面的标题栏和角标。用户发来的真机截图（微信图片_20261009173240_42_13.jpg）
     * 一跑就露馅了。现在改成从 [Anchored.start] 往上走：普通题上一步就撞到页眉，直接停。
     */
    private fun stemSpan(
        usable: List<OcrLine>,
        anchored: Anchored?,
        w: Float,
        h: Float,
    ): IntRange? {
        if (anchored == null) return locateByStem(usable, w, h)

        // 一、中间要是冒出「第2问 / 题号行」这种**下一问的开头**，材料就到它上面一行为止。
        //     [Anchored.start] 在日常那道题上是题干首行，在共用题干题上就是**材料首行**
        //     （那条路会把页眉块剪掉），两种版式正好都从这儿开始。
        val lastBeforeOption = anchored.firstOption - 1
        val partAt = (anchored.start + 1..lastBeforeOption)
            .firstOrNull { NEXT_PART_START.containsMatchIn(usable[it].text) }
        if (partAt != null && partAt - 1 >= anchored.start) {
            // 材料上面偶尔还压着章节标题（「第五十四章 泌尿、男生殖系统的其他疾病」这种整行都在、
            // 又不算窄行的）—— 它不是材料，从框里剪掉
            var top = anchored.start
            while (top < partAt - 1 &&
                (isHeaderLike(usable[top], w) || CHAPTER_TITLE.containsMatchIn(usable[top].text))
            ) {
                top++
            }
            if (top <= partAt - 1) return top..(partAt - 1)
        }

        // 二、没有下一问的开头 —— 这就是一道普通题：题干从 start 到第一个选项上面那一行
        return if (anchored.firstOption > anchored.start) anchored.start..lastBeforeOption else null
    }

    /** 主路径：以选项为锚，返回 [开始, 第一个选项, 结束]；一行选项都没找到就返回 null。 */
    private class Anchored(val start: Int, val firstOption: Int, val end: Int)

    private fun locateOptions(usable: List<OcrLine>, w: Float, h: Float): Anchored? {
        val letters = usable.map { OcrText.optionLetterOf(it.text) }

        val picked = ArrayList<Int>()
        for (i in usable.indices) {
            val letter = letters[i] ?: continue
            if (picked.isEmpty()) {
                picked.add(i)
                continue
            }
            if (picked.size >= MAX_OPTIONS) break
            val prev = picked.last()
            val previousLetter = letters[prev] ?: continue
            // 字母必须往前排：解析里的「B 选项错误」会让字母退回去，就此打住
            if (letter <= previousLetter) break
            val gap = usable[i].top - usable[prev].bottom
            if (gap > h * OPTION_GAP) break
            picked.add(i)
        }
        if (picked.isEmpty()) return null

        val first = picked.first()
        val last = picked.last()

        // 往上吃题干
        var start = first
        var anchored = false
        while (start > 0 && usable[start].top - usable[start - 1].bottom <= h * STEM_GAP) {
            start--
            // 撞上题号、而且它上面那一行是页眉/角标，就把框顶定在这儿。
            // 见 [QUESTION_START] 与 [isHeaderLike] 里的说明。
            if (start > 0 &&
                QUESTION_START.containsMatchIn(usable[start].text) &&
                isHeaderLike(usable[start - 1], w)
            ) {
                anchored = true
                break
            }
        }
        // 题号那条路走不通时，改用「页眉块」定框顶。
        //
        // 为什么要留着这条路：OCR 经常把题号后面的点吃掉 —— 真机日志里
        // 「67.男,70岁…」认成了「67男,70岁…」、「30.…」认成了「30…」，
        // 于是 [QUESTION_START] 不匹配，框顶又滑回标题栏上。
        //
        // 做法：从顶端往下找**第一段连续的页眉行**（窄行，或者带「34/150」这种进度数字），
        // 题干从这段的下一行开始。标题栏本身可能很宽（连着左右两个图标）不算页眉，
        // 但它在这段**上面**，跟着一起被排除掉。
        //
        // 只吃一段、而且要求这段后面还留得下至少一行题干（[first]），
        // 免得题干本身短得像页眉时把整道题都吃掉。
        if (!anchored) {
            val headerStart = (start..first).firstOrNull { isHeaderLike(usable[it], w) }
            if (headerStart != null) {
                var headerEnd = headerStart
                while (headerEnd + 1 < first && isHeaderLike(usable[headerEnd + 1], w)) headerEnd++
                if (headerEnd + 1 < first) start = headerEnd + 1
            }
        }

        // 往下吃选项的折行
        var end = last
        while (end + 1 < usable.size && usable[end + 1].top - usable[end].bottom <= h * FOLD_GAP) {
            end++
        }
        return Anchored(start, first, end)
    }

    /**
     * 备用路径：没认出选项时，用「第一行像题干的宽行」当锚。
     *
     * 判据是「靠左 + 够宽 + 够长」—— 页眉和角标都是居中或贴边的窄行，
     * 一屏里同时满足这三条、又最靠上的那一行，基本就是题干首行。
     */
    private fun locateByStem(usable: List<OcrLine>, w: Float, h: Float): IntRange? {
        val start = usable.indexOfFirst { line ->
            val text = line.text.trim()
            text.length >= STEM_MIN_CHARS &&
                line.left <= w * STEM_MAX_LEFT &&
                (line.right - line.left) >= w * STEM_MIN_WIDTH
        }
        if (start < 0) return null

        val anchorTop = usable[start].top
        var end = start
        while (end + 1 < usable.size) {
            val next = usable[end + 1]
            if (next.top - usable[end].bottom > h * FALLBACK_GAP) break
            if (next.bottom - anchorTop > h * FALLBACK_MAX_HEIGHT) break
            end++
        }
        return start..end
    }

    private fun isNarrow(line: OcrLine, w: Float): Boolean =
        (line.right - line.left) < w * NARROW_RATIO

    /**
     * 这一行像不像「页眉 / 角标」。
     *
     * 只有两条件，都收得很窄，因为它决定的是「要不要把框顶剪掉」：
     * 判错了要么把题干切掉一截，要么页眉又回来了。
     *
     *  1. 里面有「32/150」这种进度数字；
     *  2. 或者它本身就很窄（「生理学」「A1型题 1分」「3/100」）。
     *
     * 反例（必须判成「不是页眉」）：共用题干题的材料首行 ——
     * 它又宽又靠左、里面也没有进度数字，上面紧接着才是「1.最可能的诊断是」这种题号行。
     * 要是把材料行当成页眉剪掉，整段材料就丢了。
     */
    private fun isHeaderLike(line: OcrLine, w: Float): Boolean =
        PROGRESS.containsMatchIn(line.text) || isNarrow(line, w)

    /** 把一段行并成一个框，外扩边距后归一化。 */
    private fun boxOf(
        usable: List<OcrLine>,
        span: IntRange,
        w: Float,
        h: Float,
        minHeight: Float = MIN_HEIGHT,
    ): CropBox? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (i in span) {
            val line = usable[i]
            if (line.left < left) left = line.left
            if (line.top < top) top = line.top
            if (line.right > right) right = line.right
            if (line.bottom > bottom) bottom = line.bottom
        }
        if (right <= left || bottom <= top) return null

        val box = CropBox(
            left = ((left - w * PAD_X) / w).coerceIn(0f, 1f),
            top = ((top - h * PAD_Y) / h).coerceIn(0f, 1f),
            right = ((right + w * PAD_X) / w).coerceIn(0f, 1f),
            bottom = ((bottom + h * PAD_Y) / h).coerceIn(0f, 1f),
        )
        if (box.right - box.left < MIN_WIDTH || box.bottom - box.top < minHeight) return null
        return box
    }
}
