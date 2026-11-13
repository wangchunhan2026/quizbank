package com.daily.quizbank.util

import android.icu.text.Transliterator

/**
 * 把 OCR 原文里「不是题目内容」的字符清掉。
 *
 * 目前只干一件事：**去掉选项前面那个被认错的圆形单选按钮**。
 *
 * 刷题软件的选项前面有一个圆形单选按钮（○），那是个图形控件、不是文字。
 * ML Kit 认不出「这是个控件」，会把它当成字形照实转写，而且**认成什么完全看运气**。
 * 真机实测同一屏上的五个选项，同一个圈被认成了四种东西：
 *
 *     A.胰蛋白酶的激活          ← 圈被整个丢掉（运气好）
 *     OB吃食物后的胃酸分泌      ← 圈被认成 O
 *     (OD动脉血压的调节         ← 圈被认成 (O
 *     CE.胰岛素的分泌           ← 圈被认成 C
 *
 * 于是用户看到的是「D 和 E 变成了 CD 和 CE」。所以这里不能只防着字母 O 一种写法。
 *
 * 两条规则，都刻意收得很窄 —— 宁可漏清，也不能把题目内容吃掉：
 *
 *  1. **行首那一小截垃圾 + 紧跟一个选项字母**：把垃圾去掉，字母和后面的正文保留。
 *     垃圾最多 3 个字符，且必须全部落在 [JUNK] 里；字母后面必须跟空白、分隔符或者
 *     直接接中文（「OB吃食物」就是直接接中文，这一条是上一版漏掉 D/B 的原因）。
 *  2. **整行只有一个（无歧义的）圈**：直接丢掉这一行。ML Kit 经常把圆圈单独切成一行，
 *     留着就是一行孤零零的 O。孤零零的一个字母（比如 C）不算 —— 那可能是正经内容，
 *     除非它的下一行正好是别的选项行，那才说明它其实是上一个圈。
 *
 * 规则 1 要求「垃圾 + 选项字母 + 像样的后文」，所以「O型血」「B超」「C反应蛋白」
 * 「0.5 倍」「ECG 检查」这类正常内容一律不会被动到。
 */
object OcrText {

    /**
     * 无歧义的圈：整行只有它的时候，可以断定那是一行孤零零的圈。
     *
     * 除了 O/o/0 这三个最容易被认错的，还收了几种实测里常见的替代结果：
     * 空心圈可能被认成 ○/〇/◎/◉，实心（已选中）的圈可能被认成 ●；@ 也出现过。
     */
    private val CIRCLES = setOf(
        'O', 'o', '0', 'Q',
        'Ｏ', 'ｏ', '０',
        '○', '〇', '◯', '●', '◉', '◎', '⊙', '⭕', 'Ⓞ', 'ⓞ',
        '@', '＠',
    )

    /**
     * 选中 / 答错的那两个图标（✅ ❌）也会被照实转写，而且认成什么同样看运气。
     *
     * 真机 2026-10-09 的一屏里，B 选项前头就多了一个「x)」——那一行整块认成了
     * 「x)B.dADP、dGDP、…」，字母不在行首，选项于是一个都拆不出来，
     * 五个选项一起被塞进了题干。
     *
     * 但这一类比 [JUNK] 里那些符号危险：x 和 X 是**正经字母**。
     * 所以它们多受一条约束（见 [optionLetterIndex]）：只有**后面还隔着东西**时才算垃圾 ——
     * 「x)B.」成立（x 后面是括号），「xB超检查」不成立（x 紧贴着字母和中文，那是正文）。
     */
    private val ICON_JUNK: Set<Char> = setOf(
        'x', 'X', '✓', '✔', '√', '✗', '✘', '×', '✕',
    )

    /**
     * 行首那一小截「垃圾」的取值范围 = 无歧义的圈，再加上实测里出现过的变形。
     *
     * 比 [CIRCLES] 宽，是因为「圈被认错」没有下限：同一个圈在这一屏上是 O，
     * 下一屏就可能变成 C 或者「(O」。这些字符单独拎出来都有正经含义，
     * 所以**只在「后面紧跟一个选项字母」时**才敢当垃圾扔掉。
     */
    private val JUNK: Set<Char> = CIRCLES + setOf(
        'C', 'c', 'G', 'g', 'e',
        '(', '（', ')', '）', '[', ']', '｛', '｝', '{', '}',
        '|', '/', '·', '•', '*', '©', '®',
    ) + ICON_JUNK

    /** 选项字母后面允许出现的东西。 */
    private val AFTER_LETTER = setOf(
        '.', '．', '、', ')', '）', '·', '・', ':', '：', ',', '，', ' ',
        '\t', '-', '－', '—', '\'', '"', '’', '”',
    )

    /** 行首垃圾最长这么多个字符。再长就不像「一个圈被认错」了。 */
    private const val MAX_JUNK = 3

    /** 半角 / 全角的大写与小写字母，统一折算成 A~H 里的一个。 */
    private fun asOptionLetter(c: Char): Char? = when (c) {
        in 'A'..'H' -> c
        in 'a'..'h' -> c - 32
        in 'Ａ'..'Ｈ' -> c - 0xFEE0
        in 'ａ'..'ｈ' -> c - 0xFEE0 - 32
        else -> null
    }

    /** 汉字也算「像样的后文」：「OB吃食物」的字母后面就是直接接中文的。 */
    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    private fun skipSpaces(line: String, from: Int): Int {
        var i = from
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return i
    }

    /**
     * 这一行如果是「选项行」，返回选项字母在行里的下标；不是就返回 null。
     *
     * 认形状：【可选的垃圾】【可选的空白】选项字母【空白 / 分隔符 / 中文】。
     * 「C9」「45」这种字母后面既不空也不是分隔符、更不是中文的，不会被当成选项行 ——
     * 这一点同时被 [clean]（决定去不去垃圾）和 CropSuggest（决定哪几行是选项）
     * 依赖，两处必须保持一致，所以只有这一个实现。
     */
    private fun optionLetterIndex(line: String): Int? {
        val start = skipSpaces(line, 0)
        // 垃圾长度从短到长试。顺序不能反 —— 「C」既可能是选项字母本身，
        // 也可能是上一个圈被认错的样子。先按正当写法（行首就是字母）试，
        // 试不通才怀疑前面粘着垃圾：
        //   「C.睡液的分泌」 → 长度 0 就成立，C 是选项字母；
        //   「CE.胰岛素的分泌」→ 长度 0 时字母后面是 E（不成话），
        //                        长度 1 才成立，C 才是那个圈。
        for (junk in 0..MAX_JUNK) {
            if (junk > 0 && line.getOrNull(start + junk - 1)?.let { it in JUNK } != true) break
            val j = skipSpaces(line, start + junk)
            if (j >= line.length) break
            if (asOptionLetter(line[j]) == null) continue
            val after = line.getOrNull(j + 1) ?: continue
            // 字母后面是分隔符/空白，那是最标准的选项行；
            // 只有确实吃掉了垃圾时，才允许「字母直接接中文」（OB吃食物）。
            // 不这么分开的话，「B超检查」会被当成选项 B。
            //
            // 吃掉的是 [ICON_JUNK] 时再收紧一档：不许「字母直接接中文」。
            // 「x)B.」走的是「后面是分隔符」那条，照样成立；「xB超检查」不成立。
            val iconJunk = junk > 0 && line[start + junk - 1] in ICON_JUNK
            val ok = after.isWhitespace() || after in AFTER_LETTER ||
                (junk > 0 && isCjk(after) && !iconJunk)
            if (ok) return j
        }
        return null
    }

    /**
     * 选项行的通用形状：只用来判断「这行看着像不像选项」。
     *
     * 字母后面**必须**有分隔符，不放宽成「可选」—— 放宽的话「DNA」这种正文
     * 也会被当成「认废了的选项行」，白跑一次放大重认（不会改坏东西，
     * 但重认次数是有上限的，不该浪费在这种地方）。
     */
    private val OPTION_SHAPE = Regex("""^\s*[（(]?\s*[A-Ha-h]\s*[).、．:：,，]\s*(\S.*)$""")

    /**
     * 这一行**看着像被认废了** —— 交给调用方去做「放大重认一次」。
     *
     * 判据只有一条，但很硬：**整行是「选项字母 + 一串没有中文的正文」**。
     *
     * 为什么专挑这一种：真机 2026-10-09 的日志里出现过两次
     *
     *     E.梨状肌   →  认成了  E.NAL
     *     C.沙门菌   →  认成了  C.JÜ
     *
     * 而那一屏上**一个拉丁字母都没有**（题面、选项、答案行全是中文），
     * 所以 NAL / JÜ 不是「认错了字」，是模型凭空编出来的。
     * 这种行放大重认一次常常能救回来；中文行偶尔认错一个字（「弧」认成「孤」）
     * 则属于正常损耗，放大也没用，不在这里管。
     *
     * 会不会误伤本来就用拉丁文写的选项（「C.ATP」「D.DNA」）？会命中，
     * 但调用方只在「重认之后中文变多了」时才采纳，所以最多白跑一次识别。
     */
    fun looksBrokenOptionLine(text: String): Boolean {
        val body = OPTION_SHAPE.matchEntire(text.trim())?.groupValues?.get(1)?.trim().orEmpty()
        if (body.isEmpty()) return false
        if (body.any { isCjk(it) }) return false
        return body.count { it.isLetter() } >= 2
    }

    /** 这一行是不是选项行；是的话给出选项字母。CropSuggest 用它挑选项。 */
    fun optionLetterOf(line: String): Char? =
        optionLetterIndex(line)?.let { asOptionLetter(line[it]) }

    /**
     * 把选项行拆成「字母 + 正文」；不是选项行就返回 null。
     *
     * 和 [optionLetterOf] 走的是同一个 [optionLetterIndex]，所以「哪几行算选项」这件事
     * 全工程只有一处实现 —— 框选猜版面、清洗、收录拆题干都依赖它，
     * 各写一份的话迟早会分头漂移。
     *
     * 正文里已经去掉了字母后面那个分隔符（「.」「、」「)」……）和空白。
     * 认不出来就返回 null，调用方自己决定怎么办（收录那边会再看它是不是「期望的下一个字母」）。
     */
    fun splitOptionLine(line: String): Pair<Char, String>? {
        val index = optionLetterIndex(line) ?: return null
        val letter = asOptionLetter(line[index]) ?: return null
        val body = line.substring(index + 1).trimStart { it.isWhitespace() || it in AFTER_LETTER }.trim()
        return letter to body
    }

    /** 整行只有（无歧义的）圈。 */
    private fun isCircleOnly(line: String): Boolean =
        line.isNotEmpty() && line.length <= 2 && line.all { it in CIRCLES }

    /** 整行只有 1~2 个「可能被认错的圈」。要再结合下一行才敢丢。 */
    private fun isLoneJunk(line: String): Boolean =
        line.isNotEmpty() && line.length <= 2 && line.all { it in JUNK }

    /**
     * 「DD.」「EE.」这种把圈认成选项字母本身、和真正的选项字母粘在一起的情况。
     *
     * 和「OB吃食物」是同一个毛病，只是圈这次恰好被认成了同一个字母：
     * 真机上 2025ESU1-24 那道题的选项 D 就存成了「DD.化学感受性呼吸反射」。
     */
    private fun stripDoubledLetter(line: String): String {
        val i = skipSpaces(line, 0)
        val first = line.getOrNull(i)?.let { asOptionLetter(it) } ?: return line
        val second = line.getOrNull(i + 1)?.let { asOptionLetter(it) } ?: return line
        if (first != second) return line
        // 第二个字母后面得是个像样的分隔符，才敢确定前面那个是圈
        val after = line.getOrNull(i + 2)
        if (after != null && !after.isWhitespace() && after !in AFTER_LETTER) return line
        return line.substring(0, i) + line.substring(i + 1)
    }

    /**
     * 上一行是不是「选项字母粘着下一行的内容」——
     * 也就是「E行」紧跟着「行」这种情况。
     *
     * 真机上那道钠泵题的选项 E 折成两行，圈被认成字母 E 之后粘到了折行的尾巴上，
     * 而那一行本身也还在，于是「行」在题库里出现了两遍。判据收得很紧：
     * 必须是「本行 = 至多 3 个字母/垃圾字符 + 下一行的全部内容」。
     */
    private fun isLetterGluedToNext(line: String, next: String?): Boolean {
        if (next.isNullOrEmpty()) return false
        if (line.length <= next.length || !line.endsWith(next)) return false
        val prefix = line.dropLast(next.length)
        if (prefix.isEmpty() || prefix.length > MAX_JUNK) return false
        return prefix.all { it in JUNK || asOptionLetter(it) != null }
    }

    /** 行首那一根被认成竖线的标记线。 */
    private val BARS = setOf('|', '｜', '丨')

    /**
     * 去掉粘在行首的竖线。
     *
     * 2026-10-09 真机日志里有一行「|反应是」——那是刷题软件题干左边那条红色标记线
     * （「|A1型题」前头那根也是它）被 OCR 当成了竖线，粘在了折行的行首。
     *
     * 只删**行首这一根**，而且删完不能变空；行内的竖线一律不动 ——
     * 那种多半是表格或者分隔符，动了就是把内容弄丢。
     */
    private fun stripLeadingBar(line: String): String {
        if (line.isEmpty() || line[0] !in BARS) return line
        val rest = line.substring(1).trimStart()
        return rest.ifEmpty { line }
    }

    /**
     * 去掉行首那一小截垃圾。
     *
     * 只有「行首确实有垃圾」并且「垃圾后面紧跟选项字母」时才动手；
     * 任何一处不像，就把原行原样返回 —— 这个函数只做减法，拿不准的时候不做。
     */
    private fun stripLeadingJunk(line: String): String {
        val i = skipSpaces(line, 0)
        if (i >= line.length || line[i] !in JUNK) return line
        val letterIndex = optionLetterIndex(line) ?: return line
        if (letterIndex <= i) return line
        return line.substring(0, i) + line.substring(letterIndex)
    }

    /**
     * 繁体 → 简体。
     *
     * 为什么需要这一步：ML Kit 的中文模型是简繁一起训的，**同一个字它有时给简体、
     * 有时给繁体**，而且不是偶尔 —— 真机 16 道已录题里有 6 道中招：
     *
     *     納泵 / 細胞內液 / 不均衝分佈   ←  应该是  钠泵 / 细胞内液 / 不均匀分布
     *
     * 这些字人眼一看就知道该是哪个，但存进题库就是错的，还会跟着相似度一起歪。
     * ICU 的 Traditional-Simplified 转换就是干这个的，比自己维护一张对照表靠谱。
     *
     * 拿不到转换器时原样返回 —— 少一层加工，好过把文字弄丢。
     */
    private val traditionalToSimplified: Transliterator? = runCatching {
        Transliterator.getInstance("Traditional-Simplified")
    }.getOrNull()

    private val simplifiedToTraditional: Transliterator? = runCatching {
        Transliterator.getInstance("Simplified-Traditional")
    }.getOrNull()

    /**
     * 绝对不许转换的字。
     *
     * 实测（62 个简体常用字逐个过 ICU 的 Traditional-Simplified）只有「著」一个被误伤：
     * 显著 → 显着。它和「内/学/体/实/应/后/发/种/干……」不一样 ——
     * 那些字 ICU 原样放过，只有「著」是简繁两边都用、但用法不同的字。
     * 以后要是发现别的，往这个串里加就行。
     */
    private const val NEVER_CONVERT = "著"

    /**
     * 把 OCR 文本里的繁体写法转成简体。给 debug 探针也留着，方便随时验证。
     *
     * 两个安全阀，缺一不可：
     *
     *  1. **逐字决策**，不整段照搬 ICU 的结果。整段转会把「显著」转成「显着」。
     *  2. 一个字只有在「ICU 认为它是繁体」（T→S 会改它）**并且**
     *     「ICU 认为它本来就是繁体」（S→T 不改它）时才转。
     *     后面这半句是关键：如果连 Simplified→Traditional 都打算把它换成别的字，
     *     说明它本来就是简体里常用的写法，动不得。
     *
     * 拿不到转换器（比如在电脑上跑单元测试，android.icu 是空壳）时原样返回，
     * 少一层加工，好过把文字弄丢。
     */
    fun toSimplified(text: String): String {
        val t2s = traditionalToSimplified ?: return text
        val s2t = simplifiedToTraditional ?: return text
        // 先整段试一次：结果没变就说明这段文字本来就没繁体字，逐字那步根本不用跑。
        if (t2s.transliterate(text) == text) return text

        val out = StringBuilder(text.length)
        for (c in text) {
            out.append(convertOne(c, t2s, s2t))
        }
        return out.toString()
    }

    /**
     * 逐字转换的结果缓存。
     *
     * 「整理题库」要一次过掉上千道题，而常用汉字就那么几千个 —— 同一个「内」字
     * 会被反复问几十万次。不缓存的话，一次整理要跑上百万次 ICU 调用，白等好几十秒。
     * 用并发表是因为清洗既可能跑在主线程的收录流程里，也可能跑在 IO 线程的批处理里。
     */
    private val conversionCache = java.util.concurrent.ConcurrentHashMap<Char, String>()

    private fun convertOne(c: Char, t2s: Transliterator, s2t: Transliterator): String {
        conversionCache[c]?.let { return it }
        val result = if (c in NEVER_CONVERT) {
            c.toString()
        } else {
            val original = c.toString()
            val simplified = t2s.transliterate(original)
            when {
                simplified == original -> original
                // ICU 认为它本身就是繁体时才认这次转换；见 [toSimplified] 的说明
                s2t.transliterate(original) != original -> original
                else -> simplified
            }
        }
        conversionCache[c] = result
        return result
    }

    /**
     * 清洗整段 OCR 文本。
     *
     * 除了「整行是圈」的被删掉，其余行原样保留（行序不变），
     * 这样日志里能直接和识别原文对照着看。
     */
    fun clean(raw: String): String {
        if (raw.isBlank()) return raw
        val lines = raw.split('\n').map { it.trim() }

        val out = ArrayList<String>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            if (line.isEmpty()) continue
            if (isCircleOnly(line)) continue

            // 孤零零一个「可能被认错的圈」：只有当下一行是别的选项行时，
            // 才能断定它其实是上一个选项的圈 —— 否则（比如单独一个 C）
            // 它可能是正经内容，不动它。
            if (isLoneJunk(line)) {
                val next = (index + 1 until lines.size)
                    .firstOrNull { lines[it].isNotEmpty() }
                    ?.let { optionLetterOf(lines[it]) }
                val self = if (line.length == 1) asOptionLetter(line[0]) else null
                if (next != null && next != self) continue
            }

            val next = (index + 1 until lines.size).firstOrNull { lines[it].isNotEmpty() }
            if (isLetterGluedToNext(line, next?.let { lines[it] })) continue

            out.add(stripLeadingJunk(stripDoubledLetter(stripLeadingBar(line))))
        }
        // 繁→简放在最后整段做：这个转换是看上下文的，逐行做容易切错。
        return toSimplified(out.joinToString("\n"))
    }
}