import com.daily.quizbank.data.import.XlsReader
import java.io.File

fun main(args: Array<String>) {
    for (path in args) {
        val f = File(path)
        if (!f.exists()) { println("找不到: " + path); continue }
        val bytes = f.readBytes()
        println("")
        println("=== " + f.name + "  " + (bytes.size / 1024) + " KB ===")
        println("  是 OLE2 复合文档: " + XlsReader.isXls(bytes))
        val sheets = try { XlsReader.readXls(bytes) } catch (e: Exception) {
            println("  解析失败: " + e.message); continue
        }
        println("  读到 " + sheets.size + " 张表:")
        var total = 0
        for (s in sheets) {
            total += s.dataRowCount
            println("    [" + s.name + "] " + s.dataRowCount + " 行 x " + s.columnCount + " 列")
            s.rows.take(2).forEachIndexed { i, row ->
                println("        R" + (i + 1) + ": " + row.joinToString(" | ") { it.take(18) })
            }
        }
        println("  合计数据行: " + total)
    }
}
