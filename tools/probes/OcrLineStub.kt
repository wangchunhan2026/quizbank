package com.daily.quizbank.data.ocr

/**
 * 只为在电脑上跑 CropSuggest 的替身；内容与 OcrEngine.kt 里的真身一致。
 *
 * 真身那个文件会 import android.graphics.Bitmap 和 ML Kit，脱离 Android 编译不过，
 * 而框选那套逻辑是纯计算、本来就应该能在电脑上跑测试。所以这里照抄一份数据结构，
 * 配上 CropSuggestTest 一起编译即可（和 QuestionTypeStub.kt 是同一个套路）。
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)
