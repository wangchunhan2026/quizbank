package com.daily.quizbank.data.ai

import java.util.Locale

/**
 * DeepSeek 计费估算。
 *
 * 官方按「元 / 百万 token」计价，并且分高峰与空闲时段（空闲时段单价减半，
 * 周末与法定节假日全天算空闲）。这里统一按**高峰价**估算，属于保守上限，
 * 真实账单通常比显示的低。
 *
 * 如果官方调价，只需要改这一个文件。
 */
object Pricing {

    const val INPUT_CACHE_HIT_PER_MILLION = 0.04
    const val INPUT_CACHE_MISS_PER_MILLION = 2.0
    const val OUTPUT_PER_MILLION = 8.0

    /**
     * 估算一次调用花了多少。返回值单位是「微元」（1e-6 元）——
     * 单次调用往往只有几百微元，用整数存可以避免浮点累加误差。
     */
    fun estimateMicros(promptTokens: Int, completionTokens: Int, cacheHitTokens: Int): Long {
        val hit = cacheHitTokens.coerceIn(0, promptTokens)
        val miss = (promptTokens - hit).coerceAtLeast(0)

        val yuan = miss * INPUT_CACHE_MISS_PER_MILLION / 1_000_000.0 +
            hit * INPUT_CACHE_HIT_PER_MILLION / 1_000_000.0 +
            completionTokens * OUTPUT_PER_MILLION / 1_000_000.0

        return (yuan * 1_000_000).toLong()
    }

    /**
     * 把微元格式化成「0.0031」这样的元。
     *
     * 固定 4 位小数：单次拍题问一次 AI 通常就是几厘到几分钱，
     * 用两位小数会全部显示成 0.00，等于没告诉用户任何信息。
     */
    fun formatYuan(micros: Long): String =
        String.format(Locale.US, "%.4f", micros / 1_000_000.0)
}
