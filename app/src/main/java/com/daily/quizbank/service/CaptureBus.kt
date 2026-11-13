package com.daily.quizbank.service

import android.graphics.Bitmap
import com.daily.quizbank.util.CropFocus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 截屏链路上各组件之间的进程内中转站。
 *
 * 为什么不用 Intent 传 Bitmap：一张全屏 ARGB_8888 截图轻松超过 10 MB，
 * 走 Binder 会直接触发 TransactionTooLargeException。
 * 这些组件同进程，用单例传引用既零拷贝又没有大小限制。
 */
object CaptureBus {

    /** 截屏服务抓到的原图，等裁剪 Activity 来取。 */
    @Volatile
    var pendingScreenshot: Bitmap? = null

    /**
     * 这一轮框选到的文字，是要**接在**收录面板那一栏的后面，还是**覆盖**那一栏。
     *
     * 题干长的题一屏拍不完（选项多、解析长时也一样）。用户把屏幕往下拨一段再拍，
     * 原来那段就被新的整段盖掉了 —— 拍几次只剩下最后一段。框选界面上那颗
     * 「接着上次识别」开关就是为这件事设的，开着的时候这一栏只往后接。
     *
     * 状态留在这个单例里（而不是塞进 Intent）：收录面板关掉再拉起的这一圈里，
     * 起草稿的是悬浮窗服务、按开关的是框选界面，两边只能靠这个中转站对上话。
     */
    @Volatile
    var continuation: Boolean = false

    /**
     * 这一轮框选的结果会不会填进收录面板的某一栏。
     *
     * 框选界面据此决定要不要摆那颗「接着上次识别」开关 —— 拍题 / 拍照那条路上
     * 框出来的东西是拿去搜题的，接不接没有任何意义。
     */
    @Volatile
    var fieldCapture: Boolean = false

    /**
     * 这一轮框选的默认框该盖哪一块（见 [com.daily.quizbank.util.CropFocus]）。
     *
     * 和 [fieldCapture] 一样是「这一轮」的状态，由悬浮窗服务在开始截屏前定好：
     * 拍题 / 搜题 / 拍照一律 AUTO（整道题，和以前一模一样），
     * 只有收录面板上按了「识别题干」「只识别选项」才换成对应的那一块。
     */
    @Volatile
    var cropFocus: CropFocus = CropFocus.AUTO

    private val _outcome = MutableSharedFlow<CropOutcome>(extraBufferCapacity = 4)
    val outcome: SharedFlow<CropOutcome> = _outcome

    /** 给悬浮窗用的状态文案（「正在识别…」这类）。 */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status


    fun publishOutcome(value: CropOutcome) {
        _outcome.tryEmit(value)
    }

    fun publishStatus(value: String?) {
        _status.value = value
    }

    /** 截图原图用掉之后一定要释放，否则连续拍题会很快把显存吃光。 */
    fun consumeScreenshot(): Bitmap? = pendingScreenshot.also { pendingScreenshot = null }
}

sealed interface CropOutcome {
    /**
     * 用户框选完成。
     *
     * @param continuation 这次框到的文字要不要接在收录面板那一栏的后面（见 [CaptureBus.continuation]）。
     */
    data class Done(val bitmap: Bitmap, val continuation: Boolean = false) : CropOutcome

    /** 用户放弃了这次框选。 */
    data object Cancelled : CropOutcome

    /** 用户想重新截一张。 */
    data object Retry : CropOutcome
}
