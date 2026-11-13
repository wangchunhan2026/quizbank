package com.daily.quizbank.data.import

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 纯文本与 CSV 的读取。
 *
 * 编码探测是有意为之的：Windows 版 Excel「另存为 CSV」默认输出 GBK，
 * 如果直接按 UTF-8 解，中文会全变成乱码，而用户根本意识不到是编码问题。
 */
object DelimitedReader {

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            runCatching { String(bytes, charset("GBK")) }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
        }
    }

    fun readDelimited(bytes: ByteArray): List<ImportedSheet> {
        val text = decode(bytes)
        val delimiter = sniffDelimiter(text)
        val rows = parseDelimited(text, delimiter)
        val name = when (delimiter) {
            '\t' -> "TSV"
            ';' -> "CSV(;)"
            else -> "CSV"
        }
        return listOf(ImportedSheet(name, rows))
    }

    fun readPlainText(bytes: ByteArray): List<ImportedSheet> {
        val text = decode(bytes)
        return listOf(ImportedSheet("文本", splitIntoBlocks(text)))
    }

    /** 只在前 20 行里数分隔符，避免题干正文里的逗号干扰判断。 */
    private fun sniffDelimiter(text: String): Char {
        val sample = text.lineSequence().take(20).joinToString("\n")
        val candidates = listOf(',', '\t', ';', '|')
        return candidates.maxByOrNull { c -> sample.count { it == c } } ?: ','
    }

    private fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        // CSV 里两个连续引号表示一个字面引号
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                ch == '\r' -> Unit
                ch == '\n' -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }

    /**
     * 把 TXT 切成题目块。
     *
     * 优先按空行切；如果整篇没有空行（很常见），就退化成「以题号开头的行」作为新题起点。
     * 两条规则都用上，是因为这两种排版在国内题库 txt 里各占一半。
     */
    private fun splitIntoBlocks(text: String): List<List<String>> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val numbered = Regex("""^\s*[（(]?\s*\d{1,4}\s*[).、．:：]\s*\S""")
        val hasBlankSeparator = lines.any { it.isBlank() }

        val blocks = ArrayList<MutableList<String>>()
        var current = ArrayList<String>()

        for (line in lines) {
            val startsNew = if (hasBlankSeparator) {
                line.isBlank()
            } else {
                numbered.containsMatchIn(line) && current.isNotEmpty()
            }
            if (startsNew) {
                if (current.any { it.isNotBlank() }) blocks.add(current)
                current = ArrayList()
                continue
            }
            current.add(line)
        }
        if (current.any { it.isNotBlank() }) blocks.add(current)

        return blocks
            .map { block -> listOf(block.joinToString("\n").trim()) }
            .filter { it[0].isNotBlank() }
    }
}
