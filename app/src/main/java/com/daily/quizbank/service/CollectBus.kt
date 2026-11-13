package com.daily.quizbank.service

import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.util.CaptureMerge
import com.daily.quizbank.util.CropFocus
import com.daily.quizbank.util.OcrText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 共用题干题里的一个小问，收录界面上的可编辑状态。 */
@Serializable
data class PartDraft(
    val label: String = "",
    /** 小问正文，**不含**它自己的选项（选项在 [options] 里，一行一个「A. xxx」）。 */
    val body: String = "",
    val options: String = "",
    val answer: String = "",
    val analysis: String = "",
)

/**
 * 收录界面的一整份草稿。
 *
 * 为什么会有一份「草稿」而不是几个字符串：收录面板是个 Activity 窗口，挡住要截的屏幕，
 * 所以「框选屏幕识别」必须先把面板整个关掉、截完再重新拉起来。来回一趟，
 * 用户在面板上填的东西——现在还包括共用题干题里每个小问的答案——全都得原样带回来。
 * 字段一多，靠几个 Intent extra 拼就很容易漏，所以整份状态序列化成一个字符串走。
 *
 * [mode] 为空表示用户还没选过「普通题 / 共用题干题」—— 界面据此弹出那次确认。
 */
@Serializable
data class CollectDraft(
    val mode: String = "",
    val question: String = "",
    /**
     * 选项，一行一个（「A. 是否与自己有利害关系」）。
     *
     * 以前没有这一栏：整个面板只有一个叫「题干与选项」的输入框，一整块 OCR 原文
     * 直接进了 stem，而 options 永远传空列表。后果是搜题只打题目正文时匹配被稀释、
     * 结果面板还原不出「C. 合资」、题库列表里那道题显示成一大坨。
     * 现在题干和选项分开存，但**拍的时候还是一起拍**（见 [applyCapture] 的自动拆分）。
     */
    val options: String = "",
    val answer: String = "",
    val analysis: String = "",
    val sharedStem: String = "",
    val parts: List<PartDraft> = emptyList(),
    /**
     * 面板内部滚到了哪里（像素）。
     *
     * 面板是个 Activity，去屏幕上框一段就得先关掉、截完再拉起来 —— 整个界面被销毁重建，
     * 滚动位置本身没人替它记着。表单又长（材料 + 每小问的正文/识别/答案/解析/按钮），
     * 不记住的话每绕一圈回来都从头开始翻，用户得一次次往下拖。
     * 所以离开前把它写进草稿，回来再落回原处。
     */
    val scrollY: Int = 0,
) {

    /**
     * 把一次「框选屏幕识别」的结果写进对应字段。
     *
     * 默认是**替换**，不是追加。之前图省事做成了追加（想着「别覆盖用户手打的内容」），
     * 结果真机上很难受：某一栏识别歪了想重框一次，新内容只会接在错内容后面，
     * 越修越长，根本纠正不回来。按钮写的是「识别这一栏」，那就该是这一栏变成框到的东西。
     *
     * [append] 管的是另一件事：题干太长、选项或解析要翻屏才拍得完时，
     * 用户会**故意**分几次拍（每次把屏幕往下拨一段）。这时框选界面上那颗
     * 「接着上次识别」开关是开着的，这一栏只往后接 —— 两次重合的那几行由
     * [CaptureMerge] 去掉，不会来两遍。
     */
    fun applyCapture(target: String, text: String, append: Boolean = false): CollectDraft {
        val piece = text.trim()
        if (piece.isBlank()) return this
        fun merge(base: String) = if (append) CaptureMerge.text(base, piece) else piece

        /**
         * 题干（或某一问的正文）那一栏怎么合。
         *
         * 接续时**先把已有内容拼回「一整块原文」再和新的一段合并，合并完重新拆一次选项**。
         * 分头合（题干并题干、选项并选项）看着更直接，但「上一段正好停在选项 B 中间」
         * 时新拍到的 C、D 会因为找不到起始的 A 而留在题干里，串位串得没边。
         *
         * 拆不动时返回空选项串：那一刻整块都被当成题干了，选项栏再留一份就是重复。
         */
        fun mergeBlock(body: String, blockOptions: String): Pair<String, String> {
            if (!append) {
                val (stem, parsed) = QuestionParser.splitTrailingOptions(piece)
                return stem to if (parsed.isEmpty()) blockOptions else parsed.joinToString(NEWLINE)
            }
            val block = listOf(body, blockOptions).filter { it.isNotBlank() }.joinToString(NEWLINE)
            val (stem, parsed) = QuestionParser.splitTrailingOptions(CaptureMerge.text(block, piece))
            return stem to if (parsed.isEmpty()) "" else parsed.joinToString(NEWLINE)
        }

        // 题干和选项是**一次框、分开存**：用户框下来的通常是一整块「题干 + A. xx + B. xx…」，
        // 这里当场把它拆成两栏。拆不动就整块当题干（原来的行为）。
        if (target == TARGET_QUESTION) {
            val (stem, parsed) = mergeBlock(question, options)
            return copy(question = stem, options = parsed)
        }
        if (target == TARGET_STEM) {
            val (stem, parsed) = mergeBlock(sharedStem, options)
            return copy(sharedStem = stem, options = parsed)
        }
        if (target == TARGET_OPTIONS) {
            return copy(options = if (append) mergeOptions(options, piece) else piece)
        }
        if (target == TARGET_ANALYSIS) return copy(analysis = merge(analysis))
        if (target.startsWith(TARGET_PART)) {
            val index = target.removePrefix(TARGET_PART).toIntOrNull() ?: return this
            val part = parts.getOrNull(index) ?: return this
            val (body, parsed) = mergeBlock(part.body, part.options)
            return copy(
                parts = parts.mapIndexed { i, p ->
                    if (i == index) p.copy(body = body, options = parsed) else p
                },
            )
        }
        if (target.startsWith(TARGET_PART_OPTIONS)) {
            val index = target.removePrefix(TARGET_PART_OPTIONS).toIntOrNull() ?: return this
            val part = parts.getOrNull(index) ?: return this
            val merged = if (append) mergeOptions(part.options, piece) else piece
            return copy(
                parts = parts.mapIndexed { i, p ->
                    if (i == index) p.copy(options = merged) else p
                },
            )
        }
        if (target.startsWith(TARGET_PART_ANALYSIS)) {
            val index = target.removePrefix(TARGET_PART_ANALYSIS).toIntOrNull() ?: return this
            return copy(
                parts = parts.mapIndexed { i, part ->
                    if (i == index) part.copy(analysis = merge(part.analysis)) else part
                },
            )
        }
        // 答案永远覆盖，接续对它没有意义：它就是一个字母或者「正确」两个字，
        // 接起来只会变成「A B」这种谁也读不通的东西。
        if (target.startsWith(TARGET_ANSWER)) {
            val index = target.removePrefix(TARGET_ANSWER).toIntOrNull() ?: return this
            return copy(
                parts = parts.mapIndexed { i, part ->
                    if (i == index) part.copy(answer = piece) else part
                },
            )
        }
        return this
    }

    /**
     * 选项栏的接续。
     *
     * 两边各自规范成「A. 正文」一行一个，再**按字母**并起来，而不是照样摞一遍 ——
     * 同一批选项被拍两次时，光靠行级去重（字母那一点点差别 OCR 每拍一次飘一次）
     * 拦不住，按字母合才稳。两次都拍到的那个字母保留**正文更长的那一版**：
     * 短的十有八九是被截图边缘切掉的那一次。
     */
    private fun mergeOptions(base: String, piece: String): String {
        val merged = CaptureMerge.text(base, piece)
        val parsed = QuestionParser.splitOptionBlob(merged)
        if (parsed.isEmpty()) return merged
        val byLetter = LinkedHashMap<String, String>()
        var unknown = 0
        for (raw in parsed) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val letter = OcrText.optionLetterOf(line)
            val key = letter?.toString() ?: ("?" + unknown++)
            val existing = byLetter[key]
            if (existing == null || line.length > existing.length) byLetter[key] = line
        }
        return byLetter.values.joinToString(NEWLINE)
    }

    /**
     * 某个 target 现在对应的那一栏文字。
     *
     * 只给「这次识别接上了多少个字」这类回显用：接续是**悄悄发生**的，
     * 面板上不说一句，用户根本分不清那颗开关到底起没起作用。
     */
    fun fieldOf(target: String): String = when {
        target == TARGET_QUESTION -> question
        target == TARGET_STEM -> sharedStem
        target == TARGET_OPTIONS -> options
        target == TARGET_ANALYSIS -> analysis
        target.startsWith(TARGET_PART_OPTIONS) -> partOf(target, TARGET_PART_OPTIONS)?.options ?: ""
        target.startsWith(TARGET_PART_ANALYSIS) -> partOf(target, TARGET_PART_ANALYSIS)?.analysis ?: ""
        target.startsWith(TARGET_ANSWER) -> partOf(target, TARGET_ANSWER)?.answer ?: ""
        target.startsWith(TARGET_PART) -> partOf(target, TARGET_PART)?.body ?: ""
        else -> ""
    }

    private fun partOf(target: String, prefix: String): PartDraft? {
        val index = target.removePrefix(prefix).toIntOrNull() ?: return null
        return parts.getOrNull(index)
    }

    fun encode(): String = runCatching { json.encodeToString(serializer(), this) }.getOrDefault("")

    companion object {

        /**
         * 选项在草稿里一行一个。
         *
         * 抽成常量只因为一个很实际的坑：这个文件里的 Kotlin 换行转义，
         * 在生成代码的那条链路上被吃过好几次（写进文件就成了字符串字面量中间断行）。
         * 写成常量之后，只有这一处需要盯着。
         */
        private const val NEWLINE = "\n"

        const val MODE_SINGLE = "single"
        const val MODE_MULTI = "multi"

        /** 「框选屏幕识别」往哪个字段里填。 */
        const val TARGET_QUESTION = "question"
        const val TARGET_STEM = "stem"
        const val TARGET_OPTIONS = "options"
        const val TARGET_ANALYSIS = "analysis"
        const val TARGET_PART = "part:"
        const val TARGET_PART_OPTIONS = "partOptions:"
        const val TARGET_ANSWER = "answer:"
        const val TARGET_PART_ANALYSIS = "partAnalysis:"

        fun answerTarget(index: Int) = TARGET_ANSWER + index
        fun partTarget(index: Int) = TARGET_PART + index

        /** 某一小问的选项栏。 */
        fun partOptionsTarget(index: Int) = TARGET_PART_OPTIONS + index

        /** 某一小问的解析栏。加了它，「识别这一栏」才覆盖得到每一小问的解析。 */
        fun partAnalysisTarget(index: Int) = TARGET_PART_ANALYSIS + index

        /**
         * 这一栏的「识别」按钮，默认框该盖哪一块（见 [CropFocus]）。
         *
         * 用户要的：**点哪一栏的「识别」，默认框就只套住那一块** ——
         * 题干那一栏只框题干、选项那一栏只框选项，人才在框上自己调。
         *
         * 2026-10-12 第一版只给「识别题干」（大题干）和「只识别选项」换了框，
         * 单题那句「识别题干和选项」和多问那句「识别这一问（连选项）」照旧框整道题 ——
         * 理由是「名字里写着连选项」。用户复测的体感是**没改进**（只有选项那个变了），
         * 这一版把这两栏也改了，并把按钮名字跟着改成实话实说。
         *
         * 顺带说一句：**框成半截并不影响「一次框、分开存」** ——
         * 用户真把框拖大了、里面带着选项行，[CollectDraft.applyCapture] 照样会把选项拆到选项栏。
         */
        fun cropFocusOf(target: String?): CropFocus = when {
            target == null -> CropFocus.AUTO
            target == TARGET_STEM || target == TARGET_QUESTION -> CropFocus.STEM
            target == TARGET_OPTIONS || target.startsWith(TARGET_PART_OPTIONS) -> CropFocus.OPTIONS
            target.startsWith(TARGET_PART) -> CropFocus.PART
            else -> CropFocus.AUTO
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): CollectDraft? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}

/**
 * 收录界面和悬浮窗服务之间的回话通道。
 *
 * 为什么要有这么个单例：收录是一个独立的 Activity，而结果卡片是 overlay 窗口 ——
 * overlay 永远浮在所有 Activity 之上，不先收掉的话收录界面会被它整个盖住。
 * 收掉之后，用户在收录界面里干什么，服务得知道；但服务不是 Activity，
 * 拿不到 startActivityForResult，只能用这个把意愿带回来。
 */
object CollectBus {

    /** 收录界面关掉时，它想干什么。 */
    sealed interface Outcome {

        /**
         * 收录到这里就结束了，没有别的后续要求。把结果卡片还回去。
         *
         * 三条路都会走到这儿：点「取消」、存完之后点「确定」、以及不告而别。
         * 以前还有一个「继续拍题」—— 存完直接再拍一题。去掉了：
         * 截屏那条链路上原题还在屏幕上没翻页，再拍一次抓到的还是同一道题，
         * 除了让人以为点错了之外没有任何用处。
         */
        data object Cancelled : Outcome

        /**
         * 用户要 App 去截屏幕、把某一段文字识别进某个字段。
         *
         * 截屏之前面板必须自己关掉 —— 不关的话它就挡在要截的那一屏前面，
         * 截到的会是它自己。所以整份草稿靠 [draft] 带过去、再带回来。
         *
         * @param target 填哪个字段，取值见 [CollectDraft] 里的 TARGET_* 常量。
         */
        data class FieldCapture(
            val draft: String,
            val target: String,
        ) : Outcome

        /**
         * 用户想先翻一翻屏幕上的原题，待会儿再回来接着填。
         *
         * 收录面板是 Activity 窗口，**整个屏幕的触摸都归它**（哪怕卡片只占下面一半，
         * 上面那半也只是透明的、照样吃掉点击）。原题的解析常常在屏幕下面，
         * 用户不把面板收掉就翻不动，等于看不到。
         *
         * 所以给一条「面板先让开」的路：面板关掉、把结果卡片还回去
         * （卡片是悬浮窗，不吃触摸），用户自由翻页；想接着填，
         * 再点一次卡片上的「存入题库」，整份草稿原样带回来。
         */
        data class StepAside(val draft: String) : Outcome
    }

    /** 服务拉起收录界面之前挂上，界面结束时调用一次并清空。 */
    @Volatile
    var onFinished: ((Outcome) -> Unit)? = null

    fun finish(outcome: Outcome) {
        val callback = onFinished
        onFinished = null
        callback?.invoke(outcome)
    }
}
