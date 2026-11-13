package com.daily.quizbank.data.import

import com.daily.quizbank.data.db.QuestionType
import com.daily.quizbank.util.OcrText

/**
 * 把「行 × 列」的原始表格翻译成题目草稿。
 *
 * 设计原则：宁可多留一条 warning，也不要悄悄丢数据或错位。
 * 所有启发式结论都通过 [ColumnMapping.describe] 回显给用户确认后才入库。
 *
 * 两条路径都要能走通：
 *  - 规整的 Excel：有表头，列对列直取。
 *  - Word 段落 / TXT 块：一行一列，题干、选项、答案、解析全糊在一段文字里，靠标记词切分。
 */
object QuestionParser {

    private const val NO_COLUMN = -1

    private enum class Marker { ANSWER, ANALYSIS, TYPE }

    private data class MarkerSpec(val text: String, val kind: Marker)

    /**
     * 选项正文至少要有几个字。
     *
     * 取 1（也就是「不能是空的」）：真机上 OCR 会把选项正文整坨认丢 ——
     * 「D.不伤害原则」认成了「D.m」，正文只剩一个字母。要求两个字的话，
     * **整块拆不出来**，五个选项又全被塞回题干；而放过去的话，题干是干净的，
     * 只有那一个选项要用户顺手补一下 —— 两害相权取其轻。
     * 防「枚举被当成选项」靠的是上面那条「只认期望的下一个字母」，不是这条长度。
     */
    private const val MIN_OPTION_BODY = 1

    /** 单独占一行的进度角标（「67/150」）。它经常插在选项中间，先剔掉再拆。 */
    private val PROGRESS_ONLY = Regex("""^\d{1,4}\s*/\s*\d{1,4}$""")

    private val OPTION_LINE = Regex("""^\s*[（(]?\s*([A-Ha-h])\s*[).、．:：,，]\s*(.+)$""")
    private val OPTION_PATTERN = Regex("""(^|\s)[（(]?[A-Ha-h]\s*[).、．:：,，]\s*\S""")
    private val OPTION_SPLIT = Regex("""(?:^|[\s;；|])([A-Ha-h])\s*[).、．:：,]\s*""")
    private val LEADING_NUMBER = Regex("""^\s*[（(]?\s*\d{1,4}\s*[).、．:：]\s*""")
    private val ANSWER_LETTERS = Regex("""[\s,，、;；/|]""")

    /** 按长度倒序，保证「答案解析」先于「答案」命中，否则解析会被当成答案吞掉。 */
    private val MARKERS: List<MarkerSpec> = buildList {
        listOf("标准答案", "正确答案", "参考答案", "答案", "answer")
            .forEach { add(MarkerSpec(it, Marker.ANSWER)) }
        listOf("答案解析", "试题解析", "题目解析", "解析", "分析", "解答", "说明", "explanation")
            .forEach { add(MarkerSpec(it, Marker.ANALYSIS)) }
        listOf("题型", "类型")
            .forEach { add(MarkerSpec(it, Marker.TYPE)) }
    }.sortedByDescending { it.text.length }

    /**
     * 标记词后面必须紧跟这些字符之一才算标记。
     * 没有这条约束，「解答下列各题」「分析下列说法」这类正常题干会被误切开。
     */
    private val STRIP_CHARS = charArrayOf(
        ':', '：', '）', ')', '】', ']', '，', ',', '、', ' ', '\t', '为', '是', '＝', '='
    )

    private val STEM_KEYS = listOf("题干", "题目", "问题", "试题", "内容", "question", "stem", "title")
    private val ANSWER_KEYS = listOf("正确答案", "参考答案", "答案", "answer", "key")
    private val ANALYSIS_KEYS = listOf("答案解析", "试题解析", "解析", "分析", "解答", "说明", "explanation", "analysis")
    private val TYPE_KEYS = listOf("题型", "类型", "type")
    private val TAG_KEYS = listOf("知识点", "标签", "分类", "章节", "考点", "tag", "category", "chapter")
    private val DIFFICULTY_KEYS = listOf("难度", "difficulty", "level")
    private val OPTION_BLOB_KEYS = listOf("选项", "options", "option", "备选")
    private val LETTER_KEYS: Map<Char, List<String>> = ('A'..'H').associate { letter ->
        letter to listOf("选项" + letter, letter.toString())
    }

    private val ALL_HEADER_KEYS: List<String> = (
        STEM_KEYS + ANSWER_KEYS + ANALYSIS_KEYS + TYPE_KEYS + TAG_KEYS +
            DIFFICULTY_KEYS + OPTION_BLOB_KEYS + LETTER_KEYS.values.flatten()
        ).map { it.lowercase() }.distinct()

    private val TRUTHY = setOf("正确", "对", "是", "√", "T", "TRUE", "YES", "Y")
    private val FALSY = setOf("错误", "错", "否", "×", "X", "F", "FALSE", "NO", "N")

    data class SplitCell(
        val stem: String,
        val options: List<String>,
        val answer: String,
        val analysis: String,
        val typeHint: String,
    )

    // ------------------------------------------------------------------ 入口

    fun parse(rows: List<List<String>>): ParseResult {
        val warnings = ArrayList<String>()
        val clean = rows.filter { row -> row.any { it.isNotBlank() } }
        if (clean.isEmpty()) {
            return ParseResult(emptyList(), emptyMapping(), listOf("这个文件里没有可读的文本内容"))
        }

        val headerIndex = findHeaderRow(clean)
        val mapping = if (headerIndex >= 0) {
            buildMappingFromHeader(clean[headerIndex], headerIndex)
        } else {
            inferMapping(clean)
        }

        val dataRows = if (headerIndex >= 0) clean.drop(headerIndex + 1) else clean
        val drafts = ArrayList<QuestionDraft>()
        for (row in dataRows) {
            val draft = draftFromRow(row, mapping) ?: continue
            if (draft.stem.isBlank()) continue
            drafts.add(draft)
        }

        if (drafts.isEmpty()) warnings.add("没有解析出任何题目，请检查表格列是否对得上")
        if (mapping.answerColumn == NO_COLUMN) warnings.add("没有找到「答案」列，已尝试从题干文本里提取")
        if (!mapping.usedHeader) warnings.add("没有识别到表头，列映射是按内容猜的，请在预览里核对")

        return ParseResult(drafts, mapping, warnings)
    }

    fun emptyMapping() = ColumnMapping(
        headerRowIndex = NO_COLUMN,
        stemColumn = 0,
        optionColumns = emptyList(),
        combinedOptionColumn = NO_COLUMN,
        answerColumn = NO_COLUMN,
        analysisColumn = NO_COLUMN,
        typeColumn = NO_COLUMN,
        tagColumn = NO_COLUMN,
        difficultyColumn = NO_COLUMN,
        headers = emptyList(),
    )

    // ------------------------------------------------------------------ 表头

    /** 命中至少两个已知表头关键词才算表头，避免把第一道题误当表头吃掉。 */
    private fun findHeaderRow(rows: List<List<String>>): Int {
        for (index in 0 until minOf(rows.size, 5)) {
            val hits = rows[index].count { cell -> looksLikeHeaderCell(cell) }
            if (hits >= 2) return index
        }
        return NO_COLUMN
    }

    private fun looksLikeHeaderCell(cell: String): Boolean {
        val key = keyOf(cell)
        if (key.isEmpty() || key.length > 8) return false
        return ALL_HEADER_KEYS.any { known -> key == known || key == known + "1" }
    }

    private fun keyOf(cell: String): String =
        cell.trim().lowercase().replace(" ", "").replace("　", "")

    private fun buildMappingFromHeader(header: List<String>, headerIndex: Int): ColumnMapping {
        var stem = NO_COLUMN
        var answer = NO_COLUMN
        var analysis = NO_COLUMN
        var type = NO_COLUMN
        var tag = NO_COLUMN
        var difficulty = NO_COLUMN
        var optionBlob = NO_COLUMN
        val optionColumns = ArrayList<Int>()

        header.forEachIndexed { index, raw ->
            val key = keyOf(raw)
            if (key.isEmpty()) return@forEachIndexed

            // 顺序即优先级：「答案解析」必须先判成解析列，不能被「答案」抢先
            val letter = LETTER_KEYS.entries.firstOrNull { (_, keys) ->
                keys.any { k -> key == k.lowercase() }
            }
            when {
                letter != null -> optionColumns.add(index)
                OPTION_BLOB_KEYS.any { key == it.lowercase() } -> if (optionBlob == NO_COLUMN) optionBlob = index
                ANALYSIS_KEYS.any { key.contains(it.lowercase()) } -> if (analysis == NO_COLUMN) analysis = index
                ANSWER_KEYS.any { key.contains(it.lowercase()) } -> if (answer == NO_COLUMN) answer = index
                TYPE_KEYS.any { key.contains(it.lowercase()) } -> if (type == NO_COLUMN) type = index
                TAG_KEYS.any { key.contains(it.lowercase()) } -> if (tag == NO_COLUMN) tag = index
                DIFFICULTY_KEYS.any { key.contains(it.lowercase()) } -> if (difficulty == NO_COLUMN) difficulty = index
                STEM_KEYS.any { key.contains(it.lowercase()) } -> if (stem == NO_COLUMN) stem = index
            }
        }

        if (stem == NO_COLUMN) {
            val taken = setOf(answer, analysis, type, tag, difficulty, optionBlob) + optionColumns
            stem = header.indices.firstOrNull { it !in taken } ?: 0
        }

        return ColumnMapping(
            headerRowIndex = headerIndex,
            stemColumn = stem,
            optionColumns = optionColumns.sorted(),
            combinedOptionColumn = if (optionColumns.isEmpty()) optionBlob else NO_COLUMN,
            answerColumn = answer,
            analysisColumn = analysis,
            typeColumn = type,
            tagColumn = tag,
            difficultyColumn = difficulty,
            headers = header.map { it.trim() },
        )
    }

    // ------------------------------------------------------------------ 无表头推断

    /**
     * 没有表头时：逐列统计「平均文本长度」和「像不像选项行」。
     * 最长且不像选项的那列当题干，比它靠右的选项列归入选项，纯答案字母列当答案。
     */
    private fun inferMapping(rows: List<List<String>>): ColumnMapping {
        val sample = rows.take(30)
        val width = sample.maxOfOrNull { it.size } ?: 1

        var stem = 0
        var bestScore = -1.0
        val optionish = HashSet<Int>()

        for (col in 0 until width) {
            var total = 0
            var count = 0
            var optionLike = 0
            for (row in sample) {
                val value = row.getOrNull(col).orEmpty()
                if (value.isBlank()) continue
                count++
                total += value.length
                if (OPTION_PATTERN.containsMatchIn(value)) optionLike++
            }
            if (count == 0) continue
            val avg = total.toDouble() / count
            if (optionLike.toDouble() / count >= 0.6) {
                optionish.add(col)
                continue
            }
            val penalty = if (isPureAnswerColumn(sample, col)) 0.5 else 1.0
            val score = avg * penalty
            if (score > bestScore) {
                bestScore = score
                stem = col
            }
        }

        val optionColumns = optionish.filter { it > stem }.sorted()
        val answer = (0 until width).firstOrNull { it != stem && isPureAnswerColumn(sample, it) } ?: NO_COLUMN

        return ColumnMapping(
            headerRowIndex = NO_COLUMN,
            stemColumn = stem,
            optionColumns = optionColumns,
            combinedOptionColumn = NO_COLUMN,
            answerColumn = answer,
            analysisColumn = NO_COLUMN,
            typeColumn = NO_COLUMN,
            tagColumn = NO_COLUMN,
            difficultyColumn = NO_COLUMN,
            headers = emptyList(),
        )
    }

    private fun isPureAnswerColumn(sample: List<List<String>>, col: Int): Boolean {
        var count = 0
        var hits = 0
        for (row in sample) {
            val value = row.getOrNull(col).orEmpty().trim()
            if (value.isEmpty()) continue
            count++
            if (looksLikeAnswer(value)) hits++
        }
        return count >= 2 && hits.toDouble() / count >= 0.7
    }

    private fun looksLikeAnswer(value: String): Boolean {
        if (value.length > 12) return false
        val upper = value.uppercase()
        if (TRUTHY.contains(upper) || FALSY.contains(upper)) return true
        val compact = value.replace(ANSWER_LETTERS, "")
        return compact.isNotEmpty() && compact.all { it.uppercaseChar() in 'A'..'H' }
    }

    // ------------------------------------------------------------------ 单行 -> 草稿

    private fun draftFromRow(row: List<String>, mapping: ColumnMapping): QuestionDraft? {
        fun cell(index: Int): String =
            if (index == NO_COLUMN) "" else row.getOrNull(index).orEmpty().trim()

        val stemRaw = cell(mapping.stemColumn)
        var options = ArrayList<String>()
        // 选项来自「选项A/选项B/…」这种分列时，单元格里只有正文没有字母。
        // 不给它补上字母，用户看到「答案=D」就得自己数到第四个，体验很差。
        mapping.optionColumns.forEachIndexed { index, col ->
            val value = cell(col)
            if (value.isNotBlank()) {
                options.add(
                    if (OPTION_LINE.containsMatchIn(value)) {
                        canonicalOption(value)
                    } else {
                        ('A' + index).toString() + ". " + value
                    }
                )
            }
        }
        val blob = cell(mapping.combinedOptionColumn)
        if (blob.isNotBlank()) options.addAll(splitOptionBlob(blob))

        var answer = normalizeAnswer(cell(mapping.answerColumn))
        var analysis = cell(mapping.analysisColumn)
        var typeHint = cell(mapping.typeColumn)

        // Word 段落 / TXT 块会把选项、答案、解析全糊在题干单元格里，这里再切一次
        val split = splitCellText(stemRaw)
        val stemCore = split.stem.ifBlank { stemRaw }
        if (split.options.isNotEmpty()) options = ArrayList(split.options)
        if (answer.isBlank()) answer = split.answer
        if (analysis.isBlank()) analysis = split.analysis
        if (typeHint.isBlank()) typeHint = split.typeHint

        if (stemCore.isBlank() && options.isEmpty()) return null

        val difficulty = cell(mapping.difficultyColumn)
            .filter { it.isDigit() }
            .take(1)
            .toIntOrNull()
            ?.coerceIn(0, 5)
            ?: 0

        return QuestionDraft(
            stem = stemCore.trim(),
            options = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            answer = answer,
            analysis = analysis,
            type = resolveType(typeHint, stemCore, options, answer),
            tags = cell(mapping.tagColumn),
            difficulty = difficulty,
        )
    }

    // ------------------------------------------------------------------ 文本切分

    /**
     * 把一块「题干 + 选项」拆成题干和选项，**不动题干里的任何一个字**。
     *
     * 和 [splitCellText] 的分工：那个是给导入用的，会顺手剥掉开头的题号
     * （「30.在应激的…」→「在应激的…」），因为它面对的是整份试卷的表格。
     * 这一个给另外两处用：
     *
     *  1. 收录时用户框下「题干 + 选项」一整块，要当场分成两栏；
     *  2. 「整理题库」把历史上被塞进题干的选项搬回选项栏（见 QuestionRepository.tidyStoredText）。
     *
     * 两处都不该悄悄改题干内容 —— 用户正对着屏幕核对，题号一律留着。
     *
     * 判据保守：选项必须从**某个整行**开始，且那一行的选项字母是 A，最后能切出至少两个选项。
     * 认不出来就原样返回（options 为空），调用方按「这本来就是纯题干」处理。
     * 之所以要求整行起头：题干里出现「分为 A、B、C 三类」这种写法时，
     * 按行内位置切会把一句正常的话切成三个「选项」。宁可漏拆，不可拆错。
     *
     * @param allowInline true 时额外处理「题面和选项挤在同一行」的情况（收录当场用得上，
     *   用户在框选预览里看得见结果、能改）；整理老题库时传 false，那条路上没人复核。
     */
    fun splitTrailingOptions(raw: String, allowInline: Boolean = true): Pair<String, List<String>> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')

        val startLine = lines.indexOfFirst { isOptionStartLine(it) }
        if (startLine > 0) {
            val stem = lines.subList(0, startLine).joinToString("\n").trim()
            val options = optionBlockOf(lines.subList(startLine, lines.size).joinToString("\n"))
            if (stem.isNotBlank() && options != null) return stem to options
        }

        if (!allowInline) return raw to emptyList()

        // 题面和选项挤在一行：找到第一个「A.」标记，从那里切开
        val flat = lines.joinToString(" ").trim()
        val mark = OPTION_SPLIT.findAll(flat)
            .firstOrNull { it.groupValues[1].equals("A", ignoreCase = true) }
            ?: return raw to emptyList()
        if (mark.range.first <= 0) return raw to emptyList()
        val stem = flat.substring(0, mark.range.first).trim()
        val options = optionBlockOf(flat.substring(mark.range.first))
        if (stem.isBlank() || options == null) return raw to emptyList()
        return stem to options
    }

    /**
     * 选项行：字母在**行首**，后面的分隔符是**可选的**。
     *
     * 分隔符为什么可选：OCR 经常把「A.」的那个点整个吃掉。真机 2026-10-09 的日志里
     * 五个选项就长这样 —— 「A粘液性水肿」「B.心动过缓」「C.粒细胞缺乏症」……
     * 只有一个选项丢了点，用 [OPTION_PATTERN]（要求有分隔符）判的话整块都拆不出来，
     * 选项于是全被留在题干里，这正是用户报的那个问题。
     *
     * 放宽的代价由 [optionBlockOf] 的「字母必须 A、B、C… 一个个来」兜住：
     * 题干里冒出一行「B超检查示…」不会被当成选项块。
     */
    private val LOOSE_OPTION = Regex("""^\s*[（(]?\s*([A-Ha-h])\s*[).、．:：,，]?\s*(\S.*)$""")

    /** 这一行是不是「选项从这里开始」——必须由 A 开头，避免被题干里的 B、C 之类带偏。 */
    private fun isOptionStartLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        val match = LOOSE_OPTION.matchEntire(trimmed) ?: return false
        return match.groupValues[1].equals("A", ignoreCase = true)
    }

    /**
     * 把一块文本切成一列选项；不像选项块就返回 null。
     *
     * 「不像」的判据是**字母必须从 A 开始、一个一个往上排**。这条很关键：
     * 题干里出现「分为 A、B、C 三类」这种枚举时切出来的字母虽然也是 A、B、C，
     * 但正文只有「B」「C 三类」这么几个字。所以除了字母顺序，还要求每一条
     * 都至少有 [MIN_OPTION_BODY] 个字 —— 枚举里的单字母不达标。
     */
    private fun optionBlockOf(block: String): List<String>? {
        val out = ArrayList<String>()
        for (rawLine in block.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 进度角标（「67/150」）会插进选项中间。它单独占一行，不可能是任何选项的一部分。
            if (PROGRESS_ONLY.matches(line)) continue

            // 只认「期望的那个字母」：上一条是 C，这一条就只能认 D。
            // 这条约束是整个解析的骨架 —— 缺了它，「B超检查」「CT 示……」这种正文会被当成选项。
            val expected = ('A' + out.size)
            val marker = optionMarker(line, expected)
            when {
                marker != null -> out.add(expected + ". " + marker)
                // 折行 / 混进来的界面文字：接到上一个选项后面
                out.isNotEmpty() -> out[out.size - 1] = out.last() + " " + line
                else -> return null
            }
        }
        if (out.size < 2) return null
        if (out.any { it.drop(2).trim().length < MIN_OPTION_BODY }) return null
        return out
    }

    /**
     * 这一行是不是「下一个选项」；是的话给出正文（不含字母和分隔符）。
     *
     * 三层判据，从最正规矩到最宽容，都是被真机日志逼出来的：
     *
     *  1. [OcrText.splitOptionLine] —— 全工程唯一的「这一行是不是选项行」实现，
     *     管得住圆圈被认成 O / C / (O 那一类；
     *  2. **字母被 OCR 吃掉点号**：真机上「D.静息期」认成了「D静息期」、
     *     「A.术前戒烟4周」认成了「A术前戒烟4周」—— 一整屏五个选项里能有三四个这样。
     *     所以只要**期望的那个字母出现在前三个字符里**就认。
     *     之所以敢这么松，是因为 [expected] 卡死了位置：等 A 的时候绝不会把「B超检查」认成 A；
     *  3. 第 2 条顺带管住了另外两种实测形态：圆圈被认成别的字母粘在前面（「DB.核黄素」），
     *     以及逗号、句号之类的杂字粘在前面（「。E15天」）。
     */
    private fun optionMarker(line: String, expected: Char): String? {
        OcrText.splitOptionLine(line)?.let { (letter, body) ->
            if (letter == expected && body.isNotEmpty()) return body
        }
        val at = line.indexOfFirst { it.uppercaseChar() == expected }
        if (at !in 0..2) return null
        val body = line.substring(at + 1).trimStart { !it.isLetterOrDigit() }.trim()
        return body.ifEmpty { null }
    }

    /**
     * 把一段自由文本切成题干 / 选项 / 答案 / 解析。
     * 规整的 Excel 单元格进来会原样返回（没有标记词也没有选项行），所以这条路径是安全的。
     */
    fun splitCellText(raw: String): SplitCell {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val stemLines = ArrayList<String>()
        val options = ArrayList<String>()
        val answers = ArrayList<String>()
        val analyses = ArrayList<String>()
        var typeHint = ""
        var mode = 0

        for (rawLine in normalized.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (mode == 0 && stemLines.isNotEmpty()) stemLines.add("")
                continue
            }

            val marker = findMarker(line)
            if (marker != null) {
                val rest = stripMarker(line, marker.text)
                when (marker.kind) {
                    Marker.ANSWER -> {
                        if (rest.isNotEmpty()) answers.add(rest)
                        mode = 2
                    }
                    Marker.ANALYSIS -> {
                        if (rest.isNotEmpty()) analyses.add(rest)
                        mode = 3
                    }
                    Marker.TYPE -> {
                        typeHint = rest
                        mode = 0
                    }
                }
                continue
            }

            // 解析正文里出现的「A.」多半是引用，不再当选项
            if (mode != 3 && OPTION_PATTERN.containsMatchIn(line)) {
                val pieces = splitOptionBlob(line)
                if (pieces.isNotEmpty()) {
                    options.addAll(pieces)
                    mode = 1
                    continue
                }
            }

            when (mode) {
                0 -> stemLines.add(if (stemLines.isEmpty()) stripLeadingNumber(line) else line)
                1 -> if (options.isNotEmpty()) {
                    options[options.size - 1] = options.last() + " " + line
                } else {
                    stemLines.add(line)
                }
                2 -> answers.add(line)
                else -> analyses.add(line)
            }
        }

        return SplitCell(
            stem = stemLines.joinToString("\n").trim(),
            options = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            answer = normalizeAnswer(answers.joinToString(" ").trim()),
            analysis = analyses.joinToString("\n").trim(),
            typeHint = typeHint.trim(),
        )
    }

    private fun findMarker(line: String): MarkerSpec? {
        val trimmed = trimLeadingDecorations(line)
        for (spec in MARKERS) {
            if (!trimmed.startsWith(spec.text, ignoreCase = true)) continue
            val next = trimmed.getOrNull(spec.text.length)
            if (next == null || next.isWhitespace() || next in STRIP_CHARS) return spec
        }
        return null
    }

    private fun stripMarker(line: String, markerText: String): String {
        val trimmed = trimLeadingDecorations(line)
        if (trimmed.length <= markerText.length) return ""
        return trimmed.substring(markerText.length).trimStart(*STRIP_CHARS).trim()
    }

    private fun trimLeadingDecorations(line: String): String =
        line.trimStart('【', '[', '（', '(', ' ', '\t', '*', '-', '·', '#', '►', '▶')

    private fun stripLeadingNumber(line: String): String =
        line.replaceFirst(LEADING_NUMBER, "").trim()

    /**
     * 切出同一行里挤着的多个选项，并统一成「A. 正文」的形式。
     * 出口统一格式，UI 和检索都只需要处理一种写法。
     */
    fun splitOptionBlob(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val matches = OPTION_SPLIT.findAll(line).toList()

            if (matches.size <= 1) {
                if (OPTION_PATTERN.containsMatchIn(line)) {
                    result.add(canonicalOption(line))
                } else if (result.isNotEmpty()) {
                    result[result.size - 1] = result.last() + " " + line
                } else {
                    result.add(canonicalOption(line))
                }
                continue
            }

            matches.forEachIndexed { index, match ->
                val contentStart = match.range.last + 1
                val contentEnd = if (index + 1 < matches.size) matches[index + 1].range.first else line.length
                val letter = match.groupValues[1].uppercase()
                val body = line.substring(contentStart, contentEnd).trim().trim('.', '。', '、', ',', '，')
                result.add(letter + ". " + body)
            }
        }
        return result.filter { it.isNotBlank() }
    }

    private fun canonicalOption(raw: String): String {
        val line = raw.trim()
        val match = OPTION_LINE.matchEntire(line)
        return if (match != null) {
            match.groupValues[1].uppercase() + ". " + match.groupValues[2].trim()
        } else {
            line
        }
    }

    // ------------------------------------------------------------------ 归一化

    fun normalizeAnswer(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""

        val compact = value.replace(ANSWER_LETTERS, "")
        if (compact.isNotEmpty() && compact.all { it.uppercaseChar() in 'A'..'H' }) {
            // 排序去重：多选答案写成 "B,A,A" 也能归一成 "AB"
            return compact.uppercase().toSortedSet().joinToString("")
        }

        val upper = value.uppercase().trim('.', '。', '、', ',', '，', ' ')
        if (TRUTHY.contains(upper)) return "正确"
        if (FALSY.contains(upper)) return "错误"

        return value
    }

    // ------------------------------------------------------------------ 收录用的两个入口

    /**
     * 推断题型。
     *
     * 文件导入那条路有「题型」列可以照抄，收录没有 —— 只有用户敲的一个答案字符串。
     * 所以这里从「选项长什么样 + 答案是几个字母」自己推：两个选项正好是「对 / 错」就是判断题，
     * 答案是两个以上字母就是多选题。
     *
     * 以前收录是 `saveCollected` 里按「有几个选项」硬猜的
     * （`if (options.size >= 2) SINGLE else UNKNOWN`），后果是**多选题和判断题全被标成单选题**，
     * 用户录入「AB」也还是单选题 —— 题库列表上的标签、按题型筛全都跟着错。
     */
    fun inferType(stem: String, options: List<String>, answer: String): String =
        resolveType("", stem, options, answer)

    private fun resolveType(
        hint: String,
        stem: String,
        optionTexts: List<String>,
        answer: String,
    ): String {
        if (hint.isNotBlank()) {
            val normalized = QuestionType.normalize(hint)
            if (normalized in QuestionType.ALL) return normalized
        }
        val letters = answer.count { it in 'A'..'H' }
        return when {
            // 判断题在表格里几乎总是写成「选项A=对 / 选项B=错」，
            // 这一条必须排在 optionCount >= 2 前面 —— 否则判断题会被判成单选题，
            // 题库里 500 道判断题全都顶着「单选题」的标签。
            isTruthPair(optionTexts) -> QuestionType.JUDGE
            optionTexts.size >= 2 -> if (letters >= 2) QuestionType.MULTI else QuestionType.SINGLE
            answer == "正确" || answer == "错误" -> QuestionType.JUDGE
            stem.contains("___") || stem.contains("（ ）") || stem.contains("( )") -> QuestionType.FILL
            stem.isNotEmpty() -> QuestionType.ESSAY
            else -> QuestionType.UNKNOWN
        }
    }

    /** 两个选项正好是一对「对 / 错」——那就不是选择题，是判断题。 */
    private fun isTruthPair(options: List<String>): Boolean {
        if (options.size != 2) return false
        // 选项在这里已经带上了「A.」「B.」前缀（预览页要显示给用户看），先剥掉再比对
        val marks = options.map { raw ->
            val trimmed = raw.trim()
            val body = OPTION_LINE.matchEntire(trimmed)?.groupValues?.get(2)?.trim() ?: trimmed
            body.trim('.', '。', '、', ')', '）', ' ')
        }
        if (marks.any { it.isEmpty() }) return false
        val truthy = marks.count { it.uppercase() in TRUTHY }
        val falsy = marks.count { it.uppercase() in FALSY }
        return truthy == 1 && falsy == 1
    }
}