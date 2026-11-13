import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.import.XlsReader
import java.io.File

fun main(args: Array<String>) {
    val f = File(args[0])
    val sheets = XlsReader.readXls(f.readBytes())
    var total = 0
    for (s in sheets) {
        val r = try { QuestionParser.parse(s.rows) } catch (e: Exception) {
            println("[" + s.name + "] 解析抛异常: " + e.message); continue
        }
        total += r.drafts.size
        println("[" + s.name + "] 原始 " + s.dataRowCount + " 行 → 解析出 " + r.drafts.size + " 道题")
        println("    表头理解: " + r.mapping.describe())
        if (r.warnings.isNotEmpty()) println("    警告: " + r.warnings.take(3).joinToString(" / "))
        r.drafts.take(2).forEach { d ->
            println("    样例: [" + d.type + " 答案=" + d.answer + " 选项=" + d.options + "] " + d.stem.take(34))
        }
    }
    println("")
    println("全部工作表合计: " + total + " 道题")
}
