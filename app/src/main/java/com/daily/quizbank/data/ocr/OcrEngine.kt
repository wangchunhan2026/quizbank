package com.daily.quizbank.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.daily.quizbank.util.OcrText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 一行识别结果：文字 + 它在图里的像素位置。
 *
 * 位置有两个用处：框选界面靠它猜题目在哪一块（CropSuggest），
 * 以及「这一行认废了」时把它抠出来放大重认一遍（[OcrEngine.recognizeRobust]）。
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

interface OcrEngine {

    suspend fun recognize(bitmap: Bitmap): String

    /**
     * 识别出每一行文字**和它的位置**。
     *
     * 框选界面要用位置来猜题目在哪一块；「重认认废了的那几行」也要用位置。
     */
    suspend fun detectLines(bitmap: Bitmap): List<OcrLine>

    /**
     * 把图上某一小块**单独放大**再认一遍。
     *
     * 给「这一行被认成乱码」用的：真机上 ML Kit 偶尔会在纯中文的一屏里凭空编出拉丁字母 ——
     * 2026-10-09 的日志里，「E.梨状肌」认成了「E.NAL」、「C.沙门菌」认成了「C.JÜ」，
     * 而那一屏上一个拉丁字母都没有。把那一行抠出来放大重认一次，换一个尺度往往就正常了。
     *
     * @param box 在原图里的位置
     * @param scale 放大倍数
     */
    suspend fun recognizeRegion(bitmap: Bitmap, box: Rect, scale: Int): String

    /**
     * 识别整块图，并**把看着像认废了的那几行单独放大重认一遍**。
     *
     * 平时结果和 [recognize] 一样（同一批行，按阅读顺序拼起来）；只有在整块里中文够多、
     * 而某一行偏偏是「选项字母 + 一串没中文的正文」时才会多跑几次识别。
     * 那几行的典型样子见 [OcrText.looksBrokenOptionLine]。
     *
     * 重认的结果**只在中文变多时才采纳** —— 宁可留着原来那个错的，
     * 也不要把本来就没问题的拉丁文选项（「C.ATP」）改坏。
     */
    suspend fun recognizeRobust(bitmap: Bitmap): String {
        val lines = detectLines(bitmap)
        if (lines.isEmpty()) return ""
        val cjkTotal = lines.sumOf { line -> line.text.count { isCjkChar(it) } }
        // 整块都没几个中文：那多半是英文题或者数字表格，别拿中文的判据去折腾它
        if (cjkTotal < ROBUST_MIN_CJK) return lines.joinToString(NEWLINE) { it.text }

        var retried = 0
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            var text = line.text
            if (retried < MAX_REGION_RETRY && OcrText.looksBrokenOptionLine(text)) {
                retried++
                val box = Rect(line.left, line.top, line.right, line.bottom)
                val better = runCatching { recognizeRegion(bitmap, box, REGION_SCALE) }.getOrNull()
                if (better != null && better.count { isCjkChar(it) } > text.count { isCjkChar(it) }) {
                    text = better.trim()
                }
            }
            out.add(text)
        }
        return out.joinToString(NEWLINE)
    }

    private fun isCjkChar(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    private companion object {
        const val NEWLINE = "\n"

        /** 整块中文少于这个数就不做重认 —— 英文题、数字表不该被这套判据打扰。 */
        const val ROBUST_MIN_CJK = 8

        /** 一次识别最多重认几行，兜住最坏情况的开销。 */
        const val MAX_REGION_RETRY = 4

        /** 重认时的放大倍数。 */
        const val REGION_SCALE = 3
    }
}

/**
 * 本地中文 OCR。
 *
 * 用 bundled 版模型（而非 play-services 动态下载版），
 * 好处是装上就能离线用；代价是 APK 里多出十几 MB。
 * 对「拍完立刻要结果」的场景，离线可用比体积更重要。
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }

    override suspend fun detectLines(bitmap: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (!cont.isActive) return@addOnSuccessListener
                    // ML Kit 已经把文字按「块 → 行」分好了，直接摊平就行，
                    // 不用自己按 y 坐标去拼行。
                    val lines = ArrayList<OcrLine>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box: Rect = line.boundingBox ?: continue
                            if (line.text.isBlank()) continue
                            lines.add(
                                OcrLine(
                                    text = line.text,
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom,
                                ),
                            )
                        }
                    }
                    cont.resume(lines)
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }

    /**
     * 抠出 [box] 那一块、放大 [scale] 倍再认一遍。
     *
     * 两个已知的坑都绕开了：
     *
     *  1. `Bitmap.createBitmap` 在**区域等于整图**时会直接返回源对象本身，
     *     后面回收它就把原图一起回收了（ImageUtils 里踩过同一个坑）；
     *  2. `Bitmap.createScaledBitmap` 在尺寸没变时同样可能返回入参本身。
     *
     * 所以回收之前一律比一遍引用，只回收自己新建出来的那张。
     */
    override suspend fun recognizeRegion(bitmap: Bitmap, box: Rect, scale: Int): String {
        val pad = (box.height() / 2).coerceAtLeast(4)
        val left = (box.left - pad).coerceAtLeast(0)
        val top = (box.top - pad).coerceAtLeast(0)
        val right = (box.right + pad).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + pad).coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return ""
        val width = (right - left) * scale
        val height = (bottom - top) * scale
        // 放大三倍之后边长可能顶到几千像素，先拦一下，别把内存打爆
        if (width <= 0 || height <= 0 || width > MAX_REGION_SIDE || height > MAX_REGION_SIDE) {
            return ""
        }

        var small: Bitmap? = null
        var big: Bitmap? = null
        return try {
            small = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            big = Bitmap.createScaledBitmap(small, width, height, true)
            recognize(big)
        } catch (error: Exception) {
            ""
        } finally {
            if (big != null && big !== small && !big.isRecycled) big.recycle()
            if (small != null && small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    private companion object {
        /** 重认区域的边长上限。 */
        const val MAX_REGION_SIDE = 2400
    }
}