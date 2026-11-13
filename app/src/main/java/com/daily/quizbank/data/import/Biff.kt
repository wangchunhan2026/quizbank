package com.daily.quizbank.data.import

/**
 * BIFF8（Excel 97-2003 的 .xls）记录解析器。
 *
 * .xls 内部是一串「记录」：2 字节类型 + 2 字节长度 + 数据。
 * 真正麻烦的是共享字符串表 SST —— 它几乎总会被拆到多个 CONTINUE 记录里，
 * 而且字符串字符数据跨记录时，续接记录的第一个字节是「新的编码标志」而不是字符本身。
 * 漏了这条规则，中文题干的尾部就会变成乱码。
 *
 * 同样不依赖任何 Android API，可以在 JVM 单元测试里直接跑。
 */
internal object Biff {

    private const val REC_FILEPASS = 0x002F
    private const val REC_FORMULA = 0x0006
    private const val REC_EOF = 0x000A
    private const val REC_CONTINUE = 0x003C
    private const val REC_BOUNDSHEET = 0x0085
    private const val REC_MULRK = 0x00BD
    private const val REC_SST = 0x00FC
    private const val REC_LABELSST = 0x00FD
    private const val REC_NUMBER = 0x0203
    private const val REC_LABEL = 0x0204
    private const val REC_BOOLERR = 0x0205
    private const val REC_STRING = 0x0207
    private const val REC_RK = 0x027E

    private const val MAX_ROWS = 200_000
    private const val MAX_COLS = 1_024

    private class SheetRef(val name: String, val position: Int)

    fun readSheets(workbook: ByteArray): List<ImportedSheet> {
        val bounds = ArrayList<SheetRef>()
        val sstBlocks = ArrayList<ByteArray>()

        // ---- 第一遍：全局子流。BOUNDSHEET 说明每个工作表在流里的位置，SST 是所有文本池 ----
        var pos = 0
        while (pos + 4 <= workbook.size) {
            val type = u16(workbook, pos)
            val length = u16(workbook, pos + 2)
            val body = pos + 4
            val end = minOf(body + length, workbook.size)

            when (type) {
                REC_EOF -> {
                    pos = end
                    break
                }
                REC_BOUNDSHEET -> bounds.add(parseBoundsheet(workbook, body))
                REC_FILEPASS -> throw IllegalStateException("这个 .xls 有密码保护，请先在 Excel 里去掉密码再导入")
                REC_SST -> {
                    sstBlocks.clear()
                    sstBlocks.add(workbook.copyOfRange(body, end))
                    // SST 后面紧跟的 CONTINUE 都是它的续接块
                    var cursor = end
                    while (cursor + 4 <= workbook.size && u16(workbook, cursor) == REC_CONTINUE) {
                        val partLength = u16(workbook, cursor + 2)
                        val partBody = cursor + 4
                        val partEnd = minOf(partBody + partLength, workbook.size)
                        sstBlocks.add(workbook.copyOfRange(partBody, partEnd))
                        cursor = partEnd
                    }
                    pos = cursor
                    continue
                }
            }
            pos = end
        }

        val shared = parseSst(sstBlocks)

        // ---- 第二遍：逐个工作表读单元格 ----
        val sheets = ArrayList<ImportedSheet>()
        for (ref in bounds) {
            if (ref.position <= 0 || ref.position >= workbook.size) continue
            val rows = parseSheet(workbook, ref.position, shared)
            sheets.add(ImportedSheet(ref.name, normalize(rows)))
        }
        return sheets
    }

    // ------------------------------------------------------------------ 全局

    private fun parseBoundsheet(data: ByteArray, body: Int): SheetRef {
        val position = i32(data, body)
        val cch = data[body + 6].toInt() and 0xFF
        val grbit = data[body + 7].toInt() and 0xFF
        return SheetRef(inlineString(data, body + 8, cch, grbit), position)
    }

    private fun parseSst(blocks: List<ByteArray>): List<String> {
        if (blocks.isEmpty()) return emptyList()
        val stream = BlockStream(blocks)
        val result = ArrayList<String>()
        if (stream.remaining() < 8) return result

        stream.u32() // 总条数，用不上
        val unique = stream.u32()
        if (unique <= 0 || unique > 2_000_000) return result

        for (i in 0 until unique) {
            if (!stream.hasMore()) break
            val cch = stream.u16()
            val grbit = stream.u8()
            var richRuns = 0
            var extSize = 0
            if (grbit and 0x08 != 0) richRuns = stream.u16()
            if (grbit and 0x04 != 0) extSize = stream.i32()

            result.add(stream.readChars(cch, grbit))

            // 富文本的格式索引和扩展数据紧跟在字符后面，必须跳过才能对上下一条
            if (richRuns > 0) stream.skip(richRuns * 4)
            if (extSize > 0) stream.skip(extSize)
        }
        return result
    }

    // ------------------------------------------------------------------ 工作表

    private fun parseSheet(data: ByteArray, start: Int, shared: List<String>): List<List<String>> {
        val cells = HashMap<Long, String>()
        var maxRow = -1
        var maxCol = -1
        var pendingStringRow = -1
        var pendingStringCol = -1

        var pos = start
        var guard = 0
        while (pos + 4 <= data.size && guard++ < 4_000_000) {
            val type = u16(data, pos)
            val length = u16(data, pos + 2)
            val body = pos + 4
            val end = body + length
            if (end > data.size) break

            when (type) {
                REC_EOF -> break

                REC_LABELSST -> {
                    val row = u16(data, body)
                    val col = u16(data, body + 2)
                    val index = i32(data, body + 6)
                    val text = if (index in shared.indices) shared[index] else ""
                    if (put(cells, row, col, text)) {
                        if (row > maxRow) maxRow = row
                        if (col > maxCol) maxCol = col
                    }
                }

                REC_RK -> {
                    val row = u16(data, body)
                    val col = u16(data, body + 2)
                    val value = formatNumber(decodeRk(i32(data, body + 6)))
                    if (put(cells, row, col, value)) {
                        if (row > maxRow) maxRow = row
                        if (col > maxCol) maxCol = col
                    }
                }

                REC_MULRK -> {
                    val row = u16(data, body)
                    var col = u16(data, body + 2)
                    var offset = body + 4
                    // 末尾 2 字节是 colLast，不参与数据
                    while (offset + 6 <= end - 2) {
                        val value = formatNumber(decodeRk(i32(data, offset + 2)))
                        if (put(cells, row, col, value)) {
                            if (row > maxRow) maxRow = row
                            if (col > maxCol) maxCol = col
                        }
                        col++
                        offset += 6
                    }
                }

                REC_NUMBER -> {
                    val row = u16(data, body)
                    val col = u16(data, body + 2)
                    val value = formatNumber(Double.fromBits(i64(data, body + 6)))
                    if (put(cells, row, col, value)) {
                        if (row > maxRow) maxRow = row
                        if (col > maxCol) maxCol = col
                    }
                }

                REC_LABEL -> {
                    val row = u16(data, body)
                    val col = u16(data, body + 2)
                    val cch = u16(data, body + 6)
                    val grbit = data[body + 8].toInt() and 0xFF
                    if (put(cells, row, col, inlineString(data, body + 9, cch, grbit))) {
                        if (row > maxRow) maxRow = row
                        if (col > maxCol) maxCol = col
                    }
                }

                REC_FORMULA -> {
                    val row = u16(data, body)
                    val col = u16(data, body + 2)
                    val b0 = data[body + 6].toInt() and 0xFF
                    val b1 = data[body + 7].toInt() and 0xFF
                    if (b0 == 0xFF && b1 == 0xFF) {
                        // 特殊值：真正的结果看 body+8 的类型标志
                        when (data[body + 8].toInt() and 0xFF) {
                            0 -> {
                                // 字符串结果放在紧随其后的 STRING 记录里
                                pendingStringRow = row
                                pendingStringCol = col
                            }
                            1 -> put(cells, row, col, if (data[body + 10].toInt() != 0) "TRUE" else "FALSE")
                            else -> put(cells, row, col, "")
                        }
                    } else {
                        put(cells, row, col, formatNumber(Double.fromBits(i64(data, body + 6))))
                    }
                    if (row > maxRow) maxRow = row
                    if (col > maxCol) maxCol = col
                }

                REC_STRING -> {
                    if (pendingStringRow >= 0) {
                        val cch = u16(data, body)
                        val grbit = data[body + 2].toInt() and 0xFF
                        put(
                            cells,
                            pendingStringRow,
                            pendingStringCol,
                            inlineString(data, body + 3, cch, grbit),
                        )
                        pendingStringRow = -1
                    }
                }

                REC_BOOLERR -> {
                    val row = u16(data, body)
                    val col = u16(data, body + 2)
                    val value = data[body + 6].toInt() and 0xFF
                    val isError = (data[body + 7].toInt() and 0xFF) != 0
                    if (!isError) put(cells, row, col, if (value != 0) "TRUE" else "FALSE")
                    if (row > maxRow) maxRow = row
                    if (col > maxCol) maxCol = col
                }
            }
            pos = end
        }

        if (cells.isEmpty() || maxRow < 0) return emptyList()
        if (maxRow + 1 > MAX_ROWS) maxRow = MAX_ROWS - 1
        if (maxCol + 1 > MAX_COLS) maxCol = MAX_COLS - 1

        val rows = ArrayList<List<String>>(maxRow + 1)
        for (r in 0..maxRow) {
            val row = ArrayList<String>(maxCol + 1)
            for (c in 0..maxCol) row.add(cells[key(r, c)] ?: "")
            rows.add(row)
        }
        return rows
    }

    private fun put(cells: HashMap<Long, String>, row: Int, col: Int, value: String): Boolean {
        if (row < 0 || col < 0 || row >= MAX_ROWS || col >= MAX_COLS) return false
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        cells[key(row, col)] = trimmed
        return true
    }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)

    // ------------------------------------------------------------------ 数值

    /**
     * RK 编码：用 4 字节表示一个数字，二进制的低 2 位是标志。
     * 位 1 置位表示这是个 30 位整数，否则高 30 位是一个 IEEE754 双精度数的高 30 位。
     */
    private fun decodeRk(rk: Int): Double {
        var value: Double
        if (rk and 0x02 != 0) {
            value = (rk shr 2).toDouble()
        } else {
            val bits = (rk.toLong() and 0xFFFFFFFCL) shl 32
            value = Double.fromBits(bits)
        }
        if (rk and 0x01 != 0) value /= 100.0
        return value
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ""
        return if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    // ------------------------------------------------------------------ 字符串

    /**
     * 读取单个记录内部的字符串。BIFF8 的「压缩」形式里，每个字节就是 UTF-16 的低字节，
     * 所以逐字节转 char 是对的（中文会走 16 位形式）。
     */
    private fun inlineString(data: ByteArray, offset: Int, cch: Int, grbit: Int): String {
        if (cch <= 0) return ""
        val high = (grbit and 0x01) != 0
        val sb = StringBuilder(cch)
        var o = offset
        for (i in 0 until cch) {
            if (high) {
                if (o + 2 > data.size) break
                sb.append((((data[o + 1].toInt() and 0xFF) shl 8) or (data[o].toInt() and 0xFF)).toChar())
                o += 2
            } else {
                if (o >= data.size) break
                sb.append((data[o].toInt() and 0xFF).toChar())
                o += 1
            }
        }
        return sb.toString()
    }

    /** 把 SST 的多个数据块拼成一条可跨块读取的字符流。 */
    private class BlockStream(private val blocks: List<ByteArray>) {

        private var blockIndex = 0
        private var offset = 0

        private fun align() {
            while (blockIndex < blocks.size && offset >= blocks[blockIndex].size) {
                blockIndex++
                offset = 0
            }
        }

        fun hasMore(): Boolean {
            align()
            return blockIndex < blocks.size
        }

        fun remaining(): Int {
            var total = 0
            for (i in blockIndex until blocks.size) {
                total += if (i == blockIndex) blocks[i].size - offset else blocks[i].size
            }
            return total
        }

        fun u8(): Int {
            align()
            if (blockIndex >= blocks.size) return 0
            return blocks[blockIndex][offset++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun i32(): Int = u16() or (u16() shl 16)

        fun u32(): Int = i32()

        fun skip(count: Int) {
            var left = count
            while (left > 0) {
                align()
                if (blockIndex >= blocks.size) return
                val available = blocks[blockIndex].size - offset
                if (available <= 0) {
                    blockIndex++
                    offset = 0
                    continue
                }
                val take = minOf(left, available)
                offset += take
                left -= take
            }
        }

        /**
         * 读 [cch] 个字符。核心在于：字符数据一旦跨过 CONTINUE 边界，
         * 续接块的首字节是后续字符的新编码标志，必须先读掉它再继续取字符。
         */
        fun readChars(cch: Int, grbit0: Int): String {
            if (cch <= 0) return ""
            val sb = StringBuilder(cch)
            var remaining = cch
            var high = (grbit0 and 0x01) != 0

            while (remaining > 0) {
                align()
                if (blockIndex >= blocks.size) break

                val charSize = if (high) 2 else 1
                val available = blocks[blockIndex].size - offset
                val canRead = minOf(remaining, available / charSize)

                if (canRead > 0) {
                    val block = blocks[blockIndex]
                    if (high) {
                        for (i in 0 until canRead) {
                            val lo = block[offset].toInt() and 0xFF
                            val hi = block[offset + 1].toInt() and 0xFF
                            sb.append(((hi shl 8) or lo).toChar())
                            offset += 2
                        }
                    } else {
                        for (i in 0 until canRead) {
                            sb.append((block[offset].toInt() and 0xFF).toChar())
                            offset += 1
                        }
                    }
                    remaining -= canRead
                }

                if (remaining > 0) {
                    blockIndex++
                    offset = 0
                    align()
                    if (blockIndex >= blocks.size) break
                    high = (blocks[blockIndex][offset].toInt() and 0x01) != 0
                    offset++
                }
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ 收尾

    /** 去掉全空行、裁掉右侧全空列，和 xlsx 路径保持一致。 */
    private fun normalize(rows: List<List<String>>): List<List<String>> {
        val kept = rows.filter { row -> row.any { it.isNotBlank() } }
        if (kept.isEmpty()) return emptyList()
        var lastUsed = -1
        for (row in kept) {
            for (i in row.indices) if (row[i].isNotBlank() && i > lastUsed) lastUsed = i
        }
        if (lastUsed < 0) return emptyList()
        return kept.map { row ->
            List(lastUsed + 1) { i -> row.getOrElse(i) { "" }.trim() }
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun i32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return u16(data, offset) or (u16(data, offset + 2) shl 16)
    }

    private fun i64(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }
}
