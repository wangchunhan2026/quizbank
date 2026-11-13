package com.daily.quizbank.data.import

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用一份真实的 .xls 验证解析器。
 *
 * 样本文件放在 src/test/resources/sample.xls；没有样本时直接跳过，
 * 这样别人 clone 下来跑测试也不会因为缺少二进制样本而失败。
 */
class XlsReaderTest {

    @Test
    fun parsesRealWorkbook() {
        val sample = File("src/test/resources/sample.xls")
        if (!sample.exists()) {
            println("没有 sample.xls，跳过（把任意 .xls 放到 src/test/resources/ 即可启用）")
            return
        }

        val bytes = sample.readBytes()
        println("文件大小: " + bytes.size + " 字节")
        println("是 OLE2 复合文档: " + XlsReader.isXls(bytes))

        val sheets = XlsReader.readXls(bytes)
        println("工作表数量: " + sheets.size)
        for (sheet in sheets) {
            println("  [" + sheet.name + "] " + sheet.dataRowCount + " 行 x " + sheet.columnCount + " 列")
        }

        val first = sheets.first()
        println("")
        println("第一个工作表前 4 行:")
        first.rows.take(4).forEachIndexed { i, row ->
            println("  R" + (i + 1) + ": " + row.joinToString(" | ") { it.take(28) })
        }

        println("")
        println("解析出的题目草稿:")
        val parsed = QuestionParser.parse(first.rows)
        println("  表头识别: " + parsed.mapping.describe())
        println("  题目数量: " + parsed.drafts.size)
        parsed.drafts.take(3).forEachIndexed { i, d ->
            println("  #" + (i + 1) + " 题型=" + d.type + " 答案=" + d.answer + " 选项数=" + d.options.size)
            println("      题干: " + d.stem.take(50))
            d.options.forEach { println("      " + it.take(40)) }
        }

        assertTrue("应该至少解析出一个工作表", sheets.isNotEmpty())
        assertTrue("应该有数据行", sheets.first().dataRowCount > 0)
        assertTrue("应该解析出题目", parsed.drafts.isNotEmpty())
    }
}
