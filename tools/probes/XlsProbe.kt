import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.import.XlsReader
import java.io.File

/**
 * 把一个 .xls / .xlsx 题库文件按**App 自己的解析链路**跑一遍，看它到底解析成了什么。
 *
 * 用途：用户报「导入了某个题库文件，某道题拍题/搜题都说没有」时，第一步先确认
 * **那道题到底有没有进库**、进库时长什么样（题干/选项/答案是不是被拆坏了）。
 *
 * 用法：XlsProbeKt <题库文件> [题干里的关键词]
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: return
    val keyword = args.getOrNull(1)
    val bytes = File(path).readBytes()
    println("文件: " + File(path).name + "  " + bytes.size + " 字节")
    println("前 4 字节: " + bytes.take(4).joinToString(" ") { "%02X".format(it) })

    val sheets = if (bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
        XlsReader.readXls(bytes)
    } else {
        println("不是 OLE2 (.xls)，本探针只处理 .xls")
        return
    }
    println("工作表数: " + sheets.size)
    sheets.forEachIndexed { i, s ->
        println("  [" + i + "] 「" + s.name + "」 " + s.rows.size + " 行 × " + s.columnCount + " 列，数据行 " + s.dataRowCount)
    }

    var total = 0
    for ((i, sheet) in sheets.withIndex()) {
        if (sheet.rows.isEmpty()) continue
        println("")
        println("=== sheet[" + i + "] 「" + sheet.name + "」前 3 行原始内容 ===")
        sheet.rows.take(3).forEach { row ->
            println("  " + row.mapIndexed { k, c -> k.toString() + "=" + c.replace("\n", "⏎").take(38) }.joinToString("  ‖  "))
        }
        val result = QuestionParser.parse(sheet.rows)
        total += result.drafts.size
        println("--- 解析结果: " + result.drafts.size + " 道 ---")
        println("  表头识别: " + result.mapping.describe())
        if (result.warnings.isNotEmpty()) println("  警告: " + result.warnings.joinToString(" | "))
        result.drafts.take(3).forEachIndexed { k, d ->
            println("  例" + (k + 1) + ". 题干(" + d.stem.length + " 字) " + d.stem.replace("\n", " ⏎ ").take(90))
            println("        选项 " + d.options.size + " 个: " + d.options.joinToString(" ／ ").take(140))
            println("        答案=" + d.answer + "  题型=" + d.type)
        }
        if (keyword != null) {
            val hits = result.drafts.filter { it.stem.contains(keyword) || it.options.any { o -> o.contains(keyword) } }
            println("  ★ 含「" + keyword + "」的条目: " + hits.size + " 条")
            hits.take(6).forEach { d ->
                println("     · 题干(" + d.stem.length + ") " + d.stem.replace("\n", " ⏎ "))
                println("       选项 " + d.options.size + " 个: " + d.options.joinToString(" ／ "))
                println("       答案=" + d.answer + " 题型=" + d.type)
            }
        }
    }
    println("")
    println("合计解析出 " + total + " 道")
}
