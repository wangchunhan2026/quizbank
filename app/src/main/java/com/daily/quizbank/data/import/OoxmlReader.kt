package com.daily.quizbank.data.import

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * 极简 OOXML 解析器（xlsx / docx）。
 *
 * 为什么不用 Apache POI：POI 依赖 java.awt / javax.xml 的一堆桌面端实现，
 * 在 Android 上要么跑不起来，要么得靠 desugaring 打补丁，而且光是引入就让 APK 多出十几 MB。
 * 而 xlsx 和 docx 说到底就是「ZIP 里装 XML」，我们用 Android 自带的 XmlPullParser
 * 抽需要的那几个节点即可，几百行、零依赖、体积为零。
 *
 * 已知不支持：加密文档、旧版二进制 .xls / .doc（这两种格式需要完全不同的解析器）。
 */
object OoxmlReader {

    /** 解压后总大小的上限，防止 zip bomb 把内存打爆。 */
    private const val MAX_UNCOMPRESSED = 64L * 1024 * 1024

    // ------------------------------------------------------------------ xlsx

    fun readXlsx(bytes: ByteArray): List<ImportedSheet> {
        val zip = readZip(bytes)
        val sharedStrings = zip["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val rels = zip["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val declared = zip["xl/workbook.xml"]?.let { parseWorkbookSheets(it) } ?: emptyList()

        // 按 workbook.xml 声明的顺序取 sheet；声明缺失时退回按文件名排序，保证顺序稳定。
        val ordered = ArrayList<Pair<String, String>>()
        for ((name, rId) in declared) {
            val target = rels[rId] ?: continue
            val path = normalizeSheetPath(target)
            if (zip.containsKey(path)) ordered.add(name to path)
        }
        if (ordered.isEmpty()) {
            zip.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
                .sorted()
                .forEachIndexed { index, path -> ordered.add("Sheet" + (index + 1) to path) }
        }

        val sheets = ArrayList<ImportedSheet>()
        for ((name, path) in ordered) {
            val rows = parseWorksheet(zip[path] ?: continue, sharedStrings)
            sheets.add(ImportedSheet(name, normalizeRows(rows)))
        }
        return sheets
    }

    private fun normalizeSheetPath(target: String): String {
        val t = target.removePrefix("/")
        return if (t.startsWith("xl/")) t else "xl/" + t
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        val parser = parserFor(bytes)
        var buffer: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> buffer = StringBuilder()
                    "t" -> Unit // 富文本会有多个 t，全部拼接
                }
                XmlPullParser.TEXT -> buffer?.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    result.add(buffer?.toString().orEmpty())
                    buffer = null
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseWorkbookSheets(bytes: ByteArray): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val parser = parserFor(bytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name").orEmpty()
                val rId = attributeEndingWith(parser, "id")
                if (name.isNotEmpty() && rId != null) result.add(name to rId)
            }
            event = parser.next()
        }
        return result
    }

    /** 命名空间处理关闭时属性名保留原始前缀，r:id / rel:id 都可能出现，所以按后缀匹配。 */
    private fun attributeEndingWith(parser: XmlPullParser, suffix: String): String? {
        for (i in 0 until parser.attributeCount) {
            val raw = parser.getAttributeName(i)
            val local = raw.substringAfterLast(':')
            if (local == suffix) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val map = HashMap<String, String>()
        val parser = parserFor(bytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) map[id] = target
            }
            event = parser.next()
        }
        return map
    }

    private fun parseWorksheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val parser = parserFor(bytes)
        var cells: ArrayList<String>? = null
        var columnIndex = 0
        var cellType = ""
        var valueBuffer: StringBuilder? = null
        var inValue = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        cells = ArrayList()
                        columnIndex = 0
                    }
                    "c" -> {
                        // 有 r="C5" 就按它定位列，没有就顺序推进 —— 省略空单元格的表格全靠这个对齐
                        val ref = parser.getAttributeValue(null, "r")
                        if (ref != null) columnIndex = columnIndexFromRef(ref)
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        valueBuffer = StringBuilder()
                        while (cells != null && cells.size < columnIndex) cells.add("")
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) valueBuffer?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = valueBuffer?.toString().orEmpty()
                        val text = when (cellType) {
                            "s" -> {
                                val idx = raw.trim().toIntOrNull()
                                if (idx != null) shared.getOrNull(idx).orEmpty() else ""
                            }
                            "inlineStr", "str" -> raw
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        val target = cells
                        if (target != null) {
                            while (target.size <= columnIndex) target.add("")
                            target[columnIndex] = text.trim()
                        }
                        columnIndex++
                        valueBuffer = null
                    }
                    "row" -> {
                        cells?.let { rows.add(it) }
                        cells = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun columnIndexFromRef(ref: String): Int {
        var index = 0
        var seenLetter = false
        for (ch in ref) {
            if (ch.isLetter()) {
                index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
                seenLetter = true
            } else if (seenLetter) {
                break
            }
        }
        return (index - 1).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ docx

    /**
     * docx 同时产出「表格行」和「普通段落」两类内容，统一成同样的行/列结构：
     * 表格的每个 tr 是一行，普通段落是一行一列。
     * 这样下游的题目解析器完全不用关心文件到底是 Excel 还是 Word。
     */
    fun readDocx(bytes: ByteArray): List<ImportedSheet> {
        val zip = readZip(bytes)
        val document = zip["word/document.xml"]
            ?: throw IOException("这个 .docx 里没有 word/document.xml，可能不是有效的 Word 文档")

        val rows = ArrayList<List<String>>()
        val parser = parserFor(document)

        val paragraph = StringBuilder()
        val cellBuffer = StringBuilder()
        var cellDepth = 0
        var rowCells: ArrayList<String>? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:p" -> paragraph.setLength(0)
                    "w:tr" -> rowCells = ArrayList()
                    "w:tc" -> {
                        if (cellDepth == 0) cellBuffer.setLength(0)
                        cellDepth++
                    }
                    "w:t" -> {
                        // 到这儿正文才真正出现
                    }
                    "w:br" -> paragraph.append('\n')
                    "w:tab" -> paragraph.append('\t')
                }
                XmlPullParser.TEXT -> if (parser.name == null || parser.name == "w:t") {
                    paragraph.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:p" -> {
                        val text = paragraph.toString().trim()
                        if (cellDepth > 0) {
                            if (cellBuffer.isNotEmpty()) cellBuffer.append('\n')
                            cellBuffer.append(text)
                        } else if (text.isNotEmpty()) {
                            rows.add(listOf(text))
                        }
                        paragraph.setLength(0)
                    }
                    "w:tc" -> {
                        cellDepth = (cellDepth - 1).coerceAtLeast(0)
                        if (cellDepth == 0) {
                            rowCells?.add(cellBuffer.toString().trim())
                            cellBuffer.setLength(0)
                        }
                    }
                    "w:tr" -> {
                        rowCells?.let { if (it.any { c -> c.isNotBlank() }) rows.add(it) }
                        rowCells = null
                    }
                }
            }
            event = parser.next()
        }
        return listOf(ImportedSheet("文档", normalizeRows(rows)))
    }

    // ------------------------------------------------------------------ 公共

    private fun parserFor(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return parser
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_UNCOMPRESSED) {
                    throw IOException("文档解压后超过 " + (MAX_UNCOMPRESSED / 1024 / 1024) + " MB，已中止导入")
                }
                entries[entry.name] = data
            }
        }
        return entries
    }

    /** 补齐成矩形、去掉全空行、裁掉右侧全空列。 */
    private fun normalizeRows(rows: List<List<String>>): List<List<String>> {
        val kept = rows.filter { row -> row.any { it.isNotBlank() } }
        if (kept.isEmpty()) return emptyList()

        var lastUsed = -1
        for (row in kept) {
            for (i in row.indices) if (row[i].isNotBlank()) lastUsed = maxOf(lastUsed, i)
        }
        if (lastUsed < 0) return emptyList()

        return kept.map { row ->
            val size = maxOf(row.size, lastUsed + 1)
            List(size) { i -> row.getOrElse(i) { "" }.trim() }
        }
    }
}
