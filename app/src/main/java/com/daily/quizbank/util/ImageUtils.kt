package com.daily.quizbank.util

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    /**
     * 等比缩放到最长边不超过 [maxSide]。
     *
     * 之所以要主动缩：官方会把超过约 1300×1300 的图缩到这个量级，
     * 也就是说客户端上传 4000px 的图并不会让模型看得更清楚，
     * 只会白白多吃带宽和上传耗时。单图 token 上限 1024，缩到 1300 正好打满信息量。
     */
    fun scaleDown(src: Bitmap, maxSide: Int = VISION_MAX_SIDE): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toDouble() / longest
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled != src) src.recycle()
        return scaled
    }

    /** 按归一化坐标裁剪。传入的 rect 必须是 0..1 的相对比例。 */
    fun cropNormalized(src: Bitmap, rect: RectF): Bitmap {
        val left = (rect.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val top = (rect.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val right = (rect.right * src.width).roundToInt().coerceIn(left + 1, src.width)
        val bottom = (rect.bottom * src.height).roundToInt().coerceIn(top + 1, src.height)

        val width = right - left
        val height = bottom - top

        // 官方文档明确说 createBitmap 的返回值「可能就是 source 本身」。
        // 用户点「全屏」时正好命中这条捷径，而调用方随后会回收源图，
        // 结果就是识别阶段拿到一张已经 recycle 的位图。这里强制复制一份切断这个别名。
        if (left == 0 && top == 0 && width == src.width && height == src.height) {
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
                ?: Bitmap.createBitmap(src, 0, 0, src.width, src.height)
        }

        return Bitmap.createBitmap(src, left, top, width, height)
    }

    fun toJpegBytes(bitmap: Bitmap, quality: Int = 88): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** 生成 data URL，直接塞进 image_url.url 字段。 */
    fun toDataUrl(bitmap: Bitmap, maxSide: Int = VISION_MAX_SIDE, quality: Int = 88): String {
        val scaled = scaleDown(bitmap, maxSide)
        val encoded = Base64.encodeToString(toJpegBytes(scaled, quality), Base64.NO_WRAP)
        return "data:image/jpeg;base64," + encoded
    }

    const val VISION_MAX_SIDE = 1300
}
