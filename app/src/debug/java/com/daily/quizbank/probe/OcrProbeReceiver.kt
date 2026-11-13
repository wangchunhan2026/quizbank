package com.daily.quizbank.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.icu.text.Transliterator
import android.util.Log
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.util.OcrText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 只存在于 debug 包里的「OCR 探针」。
 *
 * 为什么要它：排查「识别出来多了个 C」「选项前有个 O」这类问题时，
 * 光看结果界面是不够的 —— 需要知道 ML Kit **原文**到底是什么样、
 * 每个字落在哪一行。而真机上跑一遍完整拍题链路要手点好几下，
 * 电脑这边还看不到中间的日志。
 *
 * 用法（只在 debug 包里有，正式包里这个 receiver 根本不存在）：
 *
 *   adb push 图片 /sdcard/Android/data/com.daily.quizbank.debug/files/probe.png
 *   adb shell am broadcast -a com.daily.quizbank.probe.OCR \
 *       -n com.daily.quizbank.debug/com.daily.quizbank.probe.OcrProbeReceiver \
 *       --es path /sdcard/Android/data/com.daily.quizbank.debug/files/probe.png
 *   adb logcat -d -s OcrProbe
 *
 * 日志里三种前缀：
 *   RAW|左,上,右,下|文本   —— 每一行原文和它的位置（CropSuggest 吃的就是它）
 *   TXT|文本               —— result.text 按行拆开的原文
 *   CLN|文本               —— 过完 OcrText.clean 之后的样子
 */
class OcrProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 必须先让「应用自己」把外部目录建出来：如果那个目录是 adb shell 建的，
        // 它的属主不是本应用，应用(sandbox 之外的 FUSE 层)读不进去，
        // BitmapFactory.decodeFile 只会安静地返回 null。
        // 所以不带 path 发一次广播 = 建目录 + 报路径；然后再推图、再发一次。
        val dir = context.getExternalFilesDir(null)
        Log.i(
            TAG,
            "外部目录=" + (dir?.absolutePath ?: "null") +
                " 存在=" + (dir?.exists() == true) + " 可读=" + (dir?.canRead() == true),
        )
        // 只给一段文字：直接把「繁→简」的结果打出来。
        // 用来确认真实语料里常见的字（显著、患者、干涉、干部……）
        // 不会被 ICU 的转换规则误伤 —— 这是这个方案唯一的风险点。
        intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { sample ->
            Log.i(TAG, "T2S|原=" + sample)
            Log.i(TAG, "T2S|后=" + OcrText.toSimplified(sample))
            // 逐字对照：两个方向都试一遍，看哪个判据能分开「真繁体」和「简体里也有的字」。
            //   c        ：原字
            //   t2s(c)   ：ICU 认为的简体   —— 想转换的目标
            //   s2t(c)   ：ICU 认为的繁体   —— 如果 s2t(c) != c，说明 c 本身就是简体常用字
            //   s2t(t2s) ：往返对不对得上   —— 对得上说明这个转换是稳定的一对一
            val t2s = Transliterator.getInstance("Traditional-Simplified")
            val s2t = Transliterator.getInstance("Simplified-Traditional")
            for (c in sample) {
                if (c.isWhitespace()) continue
                val s = c.toString()
                val a = t2s.transliterate(s)
                val b = s2t.transliterate(s)
                val back = s2t.transliterate(a)
                Log.i(
                    TAG,
                    "CH|" + s + "|t2s=" + a + "|s2t=" + b +
                        "|往返=" + back + "|往返一致=" + (back == s),
                )
            }
            return
        }

        // 跑一遍「整理题库」，用来在电脑这边验证批处理的效果（不用手点设置页那个按钮）。
        if (intent.getBooleanExtra(EXTRA_TIDY, false)) {
            val tidyApp = context.applicationContext as? QuizBankApp ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val report = tidyApp.repository.tidyStoredText()
                    Log.i(TAG, "TIDY|扫描=" + report.scanned + " 修正=" + report.fixed)
                    for (sample in report.samples) Log.i(TAG, "TIDY|" + sample)
                } catch (error: Exception) {
                    Log.e(TAG, "整理题库失败", error)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            Log.i(TAG, "没给 path：目录已备好，把图推过去再发一次广播")
            return
        }
        Log.i(TAG, "目标文件 存在=" + java.io.File(path).exists() + " 可读=" + java.io.File(path).canRead())
        val app = context.applicationContext as? QuizBankApp ?: return
        // goAsync：onReceive 一返回，进程就可能被回收，异步的 OCR 得先申请延期
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                    Log.w(TAG, "解不开这张图: " + path)
                    return@launch
                }
                Log.i(TAG, "===== " + bitmap.width + "x" + bitmap.height + " =====")

                val lines = app.ocr.detectLines(bitmap)
                Log.i(TAG, "----- RAW 共 " + lines.size + " 行 -----")
                for (line in lines) {
                    Log.i(
                        TAG,
                        "RAW|" + line.left + "," + line.top + "," + line.right + "," + line.bottom +
                            "|" + line.text,
                    )
                }

                val text = app.ocr.recognize(bitmap)
                Log.i(TAG, "----- TXT -----")
                for (line in text.split("\n")) Log.i(TAG, "TXT|" + line)

                Log.i(TAG, "----- CLN -----")
                for (line in OcrText.clean(text).split("\n")) Log.i(TAG, "CLN|" + line)

                bitmap.recycle()
            } catch (error: Exception) {
                Log.e(TAG, "探针挂了", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "OcrProbe"
        const val EXTRA_PATH = "path"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIDY = "tidy"
    }
}
