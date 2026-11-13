package android.icu.text

/**
 * 只为在电脑上跑测试用的替身：这台机器上没有 Android 的 ICU。
 *
 * getInstance 一律返回 null，OcrText 里那两层 runCatching 就会拿到 null、
 * 于是繁→简原样返回 —— 和真机上「拿不到转换器」时的行为完全一致。
 * 也就是说：这个替身只让 OcrText 能编译、能测非繁简的那几条规则，
 * **繁体那部分只能在真机上验**（交接笔记里早就写着这一条）。
 */
class Transliterator private constructor() {

    fun transliterate(text: String): String = text

    companion object {
        @JvmStatic
        fun getInstance(id: String): Transliterator? = null
    }
}
