package com.daily.quizbank.data.import

/**
 * OLE2 复合文档（Compound File Binary）读取器。
 *
 * .xls / .doc 这类"老版 Office"文件本质上是「把一个小型 FAT 文件系统装进单个文件」。
 * 要读到里面的 Workbook 流，就得先按它的规则解析扇区分配表。
 *
 * 只实现读取，够用即可。故意不依赖任何 Android API，这样可以放在 JVM 单元测试里跑。
 */
internal object Cfb {

    private val SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(),
    )

    private const val TYPE_STREAM = 2
    private const val TYPE_ROOT = 5
    private const val END_OF_CHAIN = 0xFFFFFFFE.toInt()
    private const val FREE_SECTOR = 0xFFFFFFFF.toInt()
    private const val MINI_STREAM_CUTOFF = 4096
    private const val CHAIN_GUARD = 1 shl 20

    fun isCfb(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return false
        return true
    }

    /** 依次尝试 [names] 里的每个流名，返回第一个找到的流内容。 */
    fun readStream(bytes: ByteArray, names: List<String>): ByteArray? {
        if (!isCfb(bytes)) return null
        return runCatching { Reader(bytes).readStream(names) }.getOrNull()
    }

    /** 列出文档里的所有流名，用来判断一个 OLE2 文件到底是 .xls 还是 .doc。 */
    fun listStreamNames(bytes: ByteArray): List<String> {
        if (!isCfb(bytes)) return emptyList()
        return runCatching { Reader(bytes).streamNames() }.getOrDefault(emptyList())
    }

    private class Reader(private val bytes: ByteArray) {

        private val sectorSize: Int
        private val miniSectorSize: Int
        private val fat: IntArray
        private val miniFat: IntArray
        private val miniStream: ByteArray
        private val entries: List<Entry>

        init {
            val sectorShift = u16(30)
            val miniSectorShift = u16(32)
            if (sectorShift !in 7..20 || miniSectorShift !in 4..12) {
                throw IllegalArgumentException("不是有效的复合文档：扇区大小异常")
            }
            sectorSize = 1 shl sectorShift
            miniSectorSize = 1 shl miniSectorShift

            fat = buildFat()
            entries = buildDirectory()

            val root = entries.firstOrNull { it.type == TYPE_ROOT }
            miniFat = buildMiniFat(u32(60), root)
            miniStream = if (root != null) readFatChain(root.startSector, root.size.toInt()) else ByteArray(0)
        }

        fun streamNames(): List<String> =
            entries.filter { it.type == TYPE_STREAM }.map { it.name }

        fun readStream(names: List<String>): ByteArray? {
            for (name in names) {
                val entry = entries.firstOrNull {
                    it.type == TYPE_STREAM && it.name.equals(name, ignoreCase = true)
                } ?: continue
                val data = if (entry.size < MINI_STREAM_CUTOFF) {
                    readMiniChain(entry.startSector, entry.size.toInt())
                } else {
                    readFatChain(entry.startSector, entry.size.toInt())
                }
                if (data.isNotEmpty()) return data
            }
            return null
        }

        // -------------------------------------------------------------- FAT

        private fun buildFat(): IntArray {
            val difat = ArrayList<Int>(128)
            for (i in 0 until 109) {
                val s = u32(76 + i * 4)
                if (s != FREE_SECTOR && s != END_OF_CHAIN) difat.add(s)
            }

            var difatSector = u32(68)
            var guard = 0
            val perSector = sectorSize / 4 - 1
            while (difatSector != END_OF_CHAIN && difatSector != FREE_SECTOR && guard++ < CHAIN_GUARD) {
                val base = offsetOf(difatSector)
                if (base < 0) break
                for (i in 0 until perSector) {
                    val s = u32(base + i * 4)
                    if (s != FREE_SECTOR && s != END_OF_CHAIN) difat.add(s)
                }
                difatSector = u32(base + perSector * 4)
            }

            val perFat = sectorSize / 4
            val table = IntArray(difat.size * perFat)
            var index = 0
            for (sector in difat) {
                val base = offsetOf(sector)
                if (base < 0 || base + sectorSize > bytes.size) continue
                for (i in 0 until perFat) table[index++] = u32(base + i * 4)
            }
            return table
        }

        private fun buildMiniFat(firstMiniFatSector: Int, root: Entry?): IntArray {
            if (root == null || firstMiniFatSector == END_OF_CHAIN || firstMiniFatSector == FREE_SECTOR) {
                return IntArray(0)
            }
            val count = u32(64)
            val data = readFatChain(firstMiniFatSector, count * sectorSize)
            val table = IntArray(data.size / 4)
            for (i in table.indices) table[i] = u32(data, i * 4)
            return table
        }

        private fun chain(table: IntArray, start: Int): List<Int> {
            val out = ArrayList<Int>()
            var sector = start
            var guard = 0
            while (sector in 0 until table.size &&
                sector != END_OF_CHAIN && sector != FREE_SECTOR && guard++ < CHAIN_GUARD
            ) {
                out.add(sector)
                sector = table[sector]
            }
            return out
        }

        private fun readFatChain(start: Int, size: Int): ByteArray {
            if (size <= 0) return ByteArray(0)
            val out = ByteArray(size)
            var written = 0
            for (sector in chain(fat, start)) {
                if (written >= size) break
                val base = offsetOf(sector)
                if (base < 0) break
                val n = minOf(sectorSize, size - written, bytes.size - base)
                if (n <= 0) break
                System.arraycopy(bytes, base, out, written, n)
                written += n
            }
            return if (written == size) out else out.copyOf(written)
        }

        private fun readMiniChain(start: Int, size: Int): ByteArray {
            if (size <= 0) return ByteArray(0)
            val out = ByteArray(size)
            var written = 0
            for (miniSector in chain(miniFat, start)) {
                if (written >= size) break
                val base = miniSector * miniSectorSize
                if (base < 0 || base >= miniStream.size) break
                val n = minOf(miniSectorSize, size - written, miniStream.size - base)
                if (n <= 0) break
                System.arraycopy(miniStream, base, out, written, n)
                written += n
            }
            return if (written == size) out else out.copyOf(written)
        }

        // -------------------------------------------------------------- 目录

        private fun buildDirectory(): List<Entry> {
            val result = ArrayList<Entry>()
            val sectors = chain(fat, u32(48))
            for (sector in sectors) {
                val base = offsetOf(sector)
                if (base < 0) continue
                var offset = base
                while (offset + 128 <= base + sectorSize && offset + 128 <= bytes.size) {
                    val nameLen = u16(offset + 64)
                    val type = bytes[offset + 66].toInt() and 0xFF
                    if (type != 0 || nameLen > 0) {
                        // 名字是 UTF-16LE，长度含结尾的 0 字符
                        val name = if (nameLen in 2..64) {
                            String(bytes, offset, nameLen - 2, Charsets.UTF_16LE)
                        } else {
                            ""
                        }
                        result.add(
                            Entry(
                                name = name,
                                type = type,
                                startSector = u32(offset + 116),
                                size = i64(offset + 120),
                            )
                        )
                    }
                    offset += 128
                }
            }
            return result
        }

        // -------------------------------------------------------------- 基础读取

        private fun offsetOf(sector: Int): Int {
            val offset = (sector + 1).toLong() * sectorSize
            return if (offset < 0 || offset + sectorSize > bytes.size) -1 else offset.toInt()
        }

        private fun u16(offset: Int): Int {
            if (offset + 2 > bytes.size) return 0
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(offset: Int): Int {
            if (offset + 4 > bytes.size) return 0
            return u16(offset) or (u16(offset + 2) shl 16)
        }

        private fun i64(offset: Int): Long {
            if (offset + 8 > bytes.size) return 0
            var value = 0L
            for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            return value
        }

        private fun u32(data: ByteArray, offset: Int): Int {
            if (offset + 4 > data.size) return 0
            return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
        }
    }

    private class Entry(val name: String, val type: Int, val startSector: Int, val size: Long)
}
