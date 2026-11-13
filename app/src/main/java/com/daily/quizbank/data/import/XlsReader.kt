package com.daily.quizbank.data.import

import java.io.IOException

/**
 * .xls（Excel 97-2003）读取入口。
 *
 * 路径是：OLE2 复合文档 → 取出 Workbook 流 → 按 BIFF8 记录解析。
 * 这条链路和 xlsx 完全不同（xlsx 是 ZIP + XML），所以是两套独立实现。
 */
object XlsReader {

    /** 通过文件头判断，比看扩展名可靠。 */
    fun isXls(bytes: ByteArray): Boolean = Cfb.isCfb(bytes)

    fun readXls(bytes: ByteArray): List<ImportedSheet> {
        if (!isXls(bytes)) throw IOException("这不是一个有效的 .xls 文件")

        val workbook = Cfb.readStream(bytes, listOf("Workbook", "Book"))
            ?: throw IOException("这个文件里没有找到 Excel 数据流，可能已损坏或不是 .xls")

        val sheets = try {
            Biff.readSheets(workbook)
        } catch (error: IllegalStateException) {
            throw IOException(error.message ?: "解析 .xls 失败")
        }
        val usable = sheets.filter { it.rows.isNotEmpty() }
        if (usable.isEmpty()) {
            throw IOException("从这个 .xls 里没有读到任何内容，可能是受保护的文档或空文件")
        }
        return usable
    }
}
