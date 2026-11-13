package com.daily.quizbank.data.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把用户选中的文件变成 [ImportedSheet]。
 *
 * 格式判定以扩展名为主、MIME 与内容嗅探为辅：
 * 国产网盘 / 微信 这类来源经常给出无扩展名或错误 MIME 的 Uri，只认扩展名会翻车。
 */
class ImportService(private val context: Context) {

    data class LoadedFile(
        val displayName: String,
        val sheets: List<ImportedSheet>,
    )

    suspend fun load(uri: Uri): LoadedFile = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("读不到这个文件，换个位置（比如先下载到本地）再试")
        if (bytes.isEmpty()) throw IOException("文件是空的")
        if (bytes.size > MAX_BYTES) {
            throw IOException("文件超过 " + (MAX_BYTES / 1024 / 1024) + " MB，请拆分后再导入")
        }

        val sheets = parseByFormat(name, mime, bytes)
        if (sheets.isEmpty() || sheets.all { it.rows.isEmpty() }) {
            throw IOException("没有从这个文件里读到任何内容")
        }
        LoadedFile(name, sheets.filter { it.rows.isNotEmpty() })
    }

    fun parseSheet(sheet: ImportedSheet): ParseResult =
        QuestionParser.parse(sheet.rows)

    /**
     * 格式判定以「文件真实内容」为准，扩展名和 MIME 只作辅助。
     *
     * 之所以反过来做：网盘、微信、邮件客户端给出的扩展名和 MIME 经常是错的
     * （最常见的是把 .csv 报成 application/vnd.ms-excel），
     * 而这几种格式的文件头特征非常明确，看内容几乎不会判断错。
     */
    private fun parseByFormat(name: String, mime: String, bytes: ByteArray): List<ImportedSheet> {
        val lower = name.lowercase()

        // 1) OLE2 复合文档 —— 要么是老版 .xls，要么是老版 .doc
        if (Cfb.isCfb(bytes)) {
            val streams = Cfb.listStreamNames(bytes)
            if (streams.any { it.equals("WordDocument", ignoreCase = true) }) {
                throw IOException("这是旧版 .doc 格式。请用 Word 打开后「另存为 .docx」再导入")
            }
            return XlsReader.readXls(bytes)
        }

        // 2) ZIP 包 —— .xlsx / .docx（也包括扩展名被改成 .xls 的新格式文件）
        if (isZip(bytes)) {
            val entries = peekEntryNames(bytes)
            return when {
                entries.any { it == "xl/workbook.xml" } -> OoxmlReader.readXlsx(bytes)
                entries.any { it == "word/document.xml" } -> OoxmlReader.readDocx(bytes)
                else -> throw IOException("这是个压缩包，但里面没有找到 Excel 或 Word 的内容")
            }
        }

        // 3) 剩下的都当文本。CSV 和 TXT 的分块逻辑不同，只有这里才需要看扩展名和 MIME
        val delimited = lower.endsWith(".csv") || lower.endsWith(".tsv") ||
            mime == "text/csv" || mime == "text/tab-separated-values" ||
            (mime == "application/vnd.ms-excel" && looksTabular(bytes))
        return if (delimited) DelimitedReader.readDelimited(bytes) else DelimitedReader.readPlainText(bytes)
    }

    /**
     * 猜一个纯文本文件是不是表格。
     * 专门用来处理「MIME 报的是 Excel，内容其实是 CSV」这种最常见的错报。
     */
    private fun looksTabular(bytes: ByteArray): Boolean {
        val lines = DelimitedReader.decode(bytes)
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(20)
            .toList()
        if (lines.size < 2) return false
        val hits = lines.count { line ->
            line.count { it == ',' || it == '\t' || it == ';' } >= 2
        }
        return hits.toDouble() / lines.size >= 0.6
    }

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index).orEmpty()
                }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun peekEntryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (names.size < 500) {
                    val entry = zip.nextEntry ?: break
                    names.add(entry.name)
                }
            }
        }
        return names
    }

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
