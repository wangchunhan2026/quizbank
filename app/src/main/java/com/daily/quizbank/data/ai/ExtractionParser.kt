package com.daily.quizbank.data.ai

import com.daily.quizbank.data.db.QuestionType
import com.daily.quizbank.data.import.QuestionParser

/**
 * 解析视觉模型按 [com.daily.quizbank.data.prefs.Prompts.EXTRACT_QUESTION] 返回的带标签文本。
 *
 * 模型偶尔会不守格式（多写解释、加粗、换成中英文冒号），所以解析必须宽容：
 * 认不出标签的行一律当作题干正文，绝不丢内容。
 */
object ExtractionParser {

    data class Extracted(
        val stem: String,
        val options: List<String>,
        val type: String,
    ) {
        val plainText: String
            get() = buildString {
                append(stem)
                for (option in options) {
                    append('\n')
                    append(option)
                }
            }.trim()
    }

    private val LABELS = listOf("题干", "题目", "选项", "题型", "类型")

    fun parse(raw: String): Extracted {
        val stemLines = ArrayList<String>()
        val optionLines = ArrayList<String>()
        var type = ""

        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim().trimStart('*', '#', '-', ' ', '\t')
            if (line.isEmpty()) continue

            val label = LABELS.firstOrNull { line.startsWith(it) }
            if (label == null) {
                // 没带标签：已经在选项区就继续当选项，否则并入题干
                if (optionLines.isNotEmpty()) optionLines.add(line) else stemLines.add(line)
                continue
            }

            val rest = stripLabel(line, label)
            when (label) {
                "题干", "题目" -> if (rest.isNotEmpty()) stemLines.add(rest)
                "选项" -> {
                    if (rest.isNotEmpty() && rest != "无" && rest != "（无）" && rest != "(无)") {
                        optionLines.add(rest)
                    }
                }
                else -> type = rest
            }
        }

        val options = QuestionParser.splitOptionBlob(optionLines.joinToString("\n"))
            .filter { it.isNotBlank() }

        val stem = stemLines.joinToString("\n").trim()

        return Extracted(
            stem = stem,
            options = options,
            type = if (type.isBlank()) {
                if (options.size >= 2) QuestionType.SINGLE else QuestionType.UNKNOWN
            } else {
                QuestionType.normalize(type)
            },
        )
    }

    private fun stripLabel(line: String, label: String): String =
        line.removePrefix(label)
            .trimStart(':', '：', ' ', '\t', '*', '】', ']')
            .trim()
            .trim('*')
            .trim()
}
