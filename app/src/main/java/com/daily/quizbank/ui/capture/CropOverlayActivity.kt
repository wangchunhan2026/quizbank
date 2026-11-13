package com.daily.quizbank.ui.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.service.CaptureBus
import com.daily.quizbank.service.CropOutcome
import com.daily.quizbank.service.FloatingWindowService
import com.daily.quizbank.service.OverlayUi
import com.daily.quizbank.util.CropBox
import com.daily.quizbank.util.CropFocus
import com.daily.quizbank.util.CropSuggest
import com.daily.quizbank.util.ImageUtils
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截屏之后的全屏框选界面。
 *
 * 用透明主题盖在原应用之上，所以用户看到的是「刚才那一屏」而不是切走的应用。
 * 这里只做框选，不做识别 —— 识别交给悬浮窗服务，这样用户选完立刻能看到结果面板。
 *
 * 打开时会**自动把框套到题干和选项上**（[CropSuggest]），用户直接点「确定」就行，
 * 不用每次都自己拖一个框；框不准再手动调，或者点「全屏」退回整屏识别。
 */
private const val TAG = "CropOverlayActivity"

private const val HINT_SCANNING = "正在自动找题干和选项…"
private const val HINT_AUTO = "已自动框住题干和选项，可以直接点「确定」；不准就拖一下"
private const val HINT_MANUAL = "没能自动认出题目，拖一个框把题干和选项圈起来"

/**
 * 收录那条路上的两句提示：这一轮要填哪一栏，框就只盖哪一块 —— 说清楚，用户才知道
 * 「框小了」是故意的（他点的是「只识别选项」），而不是自动框选又猜歪了。
 */
private const val HINT_SCANNING_STEM = "正在自动找题干…"
private const val HINT_AUTO_STEM = "已自动框住题干（只识别这一块），不准就拖一下"
private const val HINT_SCANNING_OPTIONS = "正在自动找选项…"
private const val HINT_AUTO_OPTIONS = "已自动框住选项（只识别这一块），不准就拖一下"
private const val HINT_SCANNING_PART = "正在自动找这一问…"
private const val HINT_AUTO_PART = "已自动框住这一问（只识别这一块），不准就拖一下"

/**
 * 「接着上次识别」那颗开关的两副面孔。
 *
 * 关着时也要说清「这次会覆盖」：用户点按钮的时候未必记得上一次框了什么，
 * 而覆盖是不可逆的 —— 说在前面，比事后让他重拍一次强。
 */
private const val TOGGLE_OFF = "接着上次识别：关（这次会覆盖那一栏）"
private const val TOGGLE_ON = "接着上次识别：开（接在后面，不覆盖）"

class CropOverlayActivity : ComponentActivity() {

    private var source: Bitmap? = null
    private var cropView: CropView? = null

    /** 是否已经发布过结局事件。用来判断界面是不是「不告而别」地消失了。 */
    private var outcomePublished = false

    /** 顶部那句提示。自动框选有结果之后要改词，所以得留个引用。 */
    private var hint: TextView? = null

    /** 顶部那一块（提示 + 开关）。开关在不在，取决于这一轮框选是干什么用的。 */
    private var topArea: LinearLayout? = null

    /** 「接着上次识别」那颗开关。只有收录那条路上才摆。 */
    private var continueToggle: TextView? = null

    private val toggleBackgroundOff = 0xCC3A3A3A.toInt()
    private val toggleBackgroundOn = 0xFF1F6FEB.toInt()

    /**
     * 每次换底图就 +1。
     *
     * 自动框选要跑一次 OCR（几百毫秒），期间用户可能已经点「重拍」换了下一张。
     * 拿着旧结果去改新截图上的框，比不自动框还糟 —— 所以结果回来先对一下号。
     */
    private var analyzeToken = 0

    private fun publish(outcome: CropOutcome) {
        outcomePublished = true
        Log.i(TAG, "发布结局: " + outcome)
        CaptureBus.publishOutcome(outcome)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须铺满整个窗口（含状态栏/导航栏区域）：
        // 否则视图会比截图矮，底图只能缩放到九成左右再双线性插值，文字就发虚了。
        enableEdgeToEdge()

        val bitmap = CaptureBus.consumeScreenshot()
        Log.i(TAG, "onCreate，截图=" + (bitmap?.let { it.width.toString() + "x" + it.height } ?: "null"))
        if (bitmap == null || bitmap.isRecycled) {
            Log.w(TAG, "没有拿到截图数据，直接结束")
            publish(CropOutcome.Cancelled)
            finish()
            return
        }
        source = bitmap

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E6000000"))
        }

        val view = CropView(this, bitmap)
        cropView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val top = buildTopArea()
        root.addView(top, topParams())

        val actionBar = buildActionBar()
        // edge-to-edge 之后要自己给底部按钮条让出导航栏的高度
        ViewCompat.setOnApplyWindowInsetsListener(actionBar) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                OverlayUi.dp(this, 16f),
                OverlayUi.dp(this, 14f),
                OverlayUi.dp(this, 16f),
                OverlayUi.dp(this, 28f) + bottom,
            )
            insets
        }
        root.addView(actionBar, bottomParams())

        setContentView(root)

        // 框选界面已经铺满全屏，这时候藏掉悬浮球才不会挡着用户操作。
        // （更早藏不行：跳转过来之前必须保持有可见悬浮窗，否则会被后台启动限制拦截）
        FloatingWindowService.setOverlayVisibleNow(false)

        // 先按整屏显示，同时后台去猜题目在哪一块；猜出来再收框，用户看到的是
        // 「框自己跳到题目上」，等这几百毫秒完全值得。
        suggestCrop(bitmap)

        // 开关的可见性要等 CaptureBus 那边把「这一轮是收录」记下来之后才定，
        // 所以放在最后一步 —— 这个 Activity 是 singleInstance，从拍照那条路复用进来时，
        // 上一次没有开关，这一次得补上。
        syncContinueToggle()

        // 用 onBackPressedDispatcher 而不是已废弃的 onBackPressed()：
        // 后者在开启预测性返回（Android 13+）的设备上拿不到回调
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    publish(CropOutcome.Cancelled)
                    finish()
                }
            },
        )
    }

    /**
     * 跑一次 OCR，用文字行的位置猜出题目在哪一块，然后把选框套上去。
     *
     * 失败、或者猜得离谱（[CropSuggest] 自己会判），就保持整屏不动 ——
     * 自动框选只是省事，绝不能变成「框错了还得先把它拖回来」。
     */
    private fun suggestCrop(bitmap: Bitmap) {
        val token = ++analyzeToken
        val app = application as? QuizBankApp ?: return
        // 这一轮要填收录面板的哪一栏，框就只盖哪一块（拍题这条路上恒为 AUTO）。
        // 在开始识别时取一次：几百毫秒之后结果回来时，CaptureBus 上可能已经是下一轮了。
        val focus = CaptureBus.cropFocus
        hint?.text = when (focus) {
            CropFocus.STEM -> HINT_SCANNING_STEM
            CropFocus.OPTIONS -> HINT_SCANNING_OPTIONS
            CropFocus.PART -> HINT_SCANNING_PART
            CropFocus.AUTO -> HINT_SCANNING
        }
        lifecycleScope.launch {
            val box = withContext(Dispatchers.Default) {
                runCatching {
                    if (bitmap.isRecycled) return@runCatching null
                    CropSuggest.suggest(
                        app.ocr.detectLines(bitmap),
                        bitmap.width,
                        bitmap.height,
                        focus,
                    )
                }.getOrNull()
            }
            // 界面已经关了、或者底图已经换成下一张了，结果就作废
            if (token != analyzeToken || isFinishing) return@launch
            if (box == null) {
                Log.i(TAG, "自动框选：没认出题目，保持整屏")
                hint?.text = HINT_MANUAL
                return@launch
            }
            Log.i(
                TAG,
                "自动框选" + focusLabel(focus) + ": " + (box.left * 100).toInt() + "%," +
                    (box.top * 100).toInt() + "% - " + (box.right * 100).toInt() + "%," +
                    (box.bottom * 100).toInt() + "%",
            )
            cropView?.applyNormalized(box)
            hint?.text = when (focus) {
                CropFocus.STEM -> HINT_AUTO_STEM
                CropFocus.OPTIONS -> HINT_AUTO_OPTIONS
                CropFocus.PART -> HINT_AUTO_PART
                CropFocus.AUTO -> HINT_AUTO
            }
        }
    }

    /** 日志里那个方括号：一眼看出这一轮框的是整道题、题干还是选项。 */
    private fun focusLabel(focus: CropFocus): String = when (focus) {
        CropFocus.STEM -> "[题干]"
        CropFocus.OPTIONS -> "[选项]"
        CropFocus.PART -> "[这一问]"
        CropFocus.AUTO -> ""
    }

    /** 顶部：提示一句，收录那条路上再加一颗「接着上次识别」开关。 */
    private fun buildTopArea(): LinearLayout {
        val column = OverlayUi.vertical(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val hintView = buildHint()
        hint = hintView
        column.addView(hintView)
        topArea = column
        return column
    }

    /**
     * 摆好那颗「接着上次识别」开关。
     *
     * 只在「这一轮框选的结果要填进收录面板」时才出现：拍题 / 拍照框出来的东西是拿去搜题的，
     * 接不接没有任何意义，多一颗开关只会让人以为点错了。
     */
    private fun syncContinueToggle() {
        val column = topArea ?: return
        if (!CaptureBus.fieldCapture) {
            continueToggle?.let { column.removeView(it) }
            continueToggle = null
            return
        }
        val view = continueToggle
            ?: buildContinueToggle().also {
                continueToggle = it
                column.addView(it, toggleParams())
            }
        refreshContinueToggle(view)
    }

    private fun buildContinueToggle(): TextView =
        OverlayUi.button(this, TOGGLE_OFF, toggleBackgroundOff).apply {
            setOnClickListener {
                CaptureBus.continuation = !CaptureBus.continuation
                Log.i(TAG, "接着上次识别 -> " + CaptureBus.continuation)
                syncContinueToggle()
            }
        }

    private fun refreshContinueToggle(view: TextView) {
        val on = CaptureBus.continuation
        view.text = if (on) TOGGLE_ON else TOGGLE_OFF
        view.background = OverlayUi.rounded(
            if (on) toggleBackgroundOn else toggleBackgroundOff,
            OverlayUi.dp(this, 10f).toFloat(),
        )
    }

    private fun toggleParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = OverlayUi.dp(this@CropOverlayActivity, 8f)
        }

    private fun buildHint(): TextView = OverlayUi.text(this, HINT_SCANNING, 14f, Color.WHITE).apply {
        val padH = OverlayUi.dp(this@CropOverlayActivity, 14f)
        val padV = OverlayUi.dp(this@CropOverlayActivity, 8f)
        setPadding(padH, padV, padH, padV)
        setBackground(OverlayUi.rounded(0xB3000000.toInt(), OverlayUi.dp(this@CropOverlayActivity, 18f).toFloat()))
    }

    private fun topParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = OverlayUi.dp(this@CropOverlayActivity, 56f)
        }

    private fun buildActionBar(): View {
        val context = this
        val bar = OverlayUi.horizontal(context).apply {
            setPadding(
                OverlayUi.dp(context, 16f),
                OverlayUi.dp(context, 14f),
                OverlayUi.dp(context, 16f),
                OverlayUi.dp(context, 28f),
            )
            setBackgroundColor(0xCC000000.toInt())
        }

        val retry = OverlayUi.button(context, "重拍", 0xFF3A3A3A.toInt())
        retry.setOnClickListener {
            publish(CropOutcome.Retry)
            finish()
        }

        val full = OverlayUi.button(context, "全屏", 0xFF3A3A3A.toInt())
        full.setOnClickListener { cropView?.selectAll() }

        val cancel = OverlayUi.button(context, "取消", 0xFF3A3A3A.toInt())
        cancel.setOnClickListener {
            // 和返回键同一条出路：发一个 Cancelled 把悬浮球放回来，然后关掉自己。
            // 没有这个键的时候，用户想放弃只能按返回或者硬点「确定」，
            // 而「确定」会真的跑一遍 OCR + 匹配 —— 放弃一件事不该绕这么大一圈。
            publish(CropOutcome.Cancelled)
            finish()
        }

        val confirm = OverlayUi.button(context, "确定", 0xFF1F6FEB.toInt())
        confirm.setOnClickListener { confirmSelection() }

        bar.addView(retry, buttonParams(context, endMargin = 10))
        bar.addView(full, buttonParams(context, endMargin = 10))
        bar.addView(OverlayUi.spacer(context))
        bar.addView(cancel, buttonParams(context, endMargin = 10))
        bar.addView(confirm, buttonParams(context))
        return bar
    }

    private fun buttonParams(context: Context, endMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            rightMargin = OverlayUi.dp(context, endMargin.toFloat())
        }

    private fun bottomParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

    private fun confirmSelection() {
        val bitmap = source ?: return
        val view = cropView ?: return
        val rect = view.normalizedSelection()
        if (rect.width() <= 0.01f || rect.height() <= 0.01f) {
            CaptureBus.publishStatus("选框太小了，重新拖一下")
            return
        }
        val cropped = runCatching { ImageUtils.cropNormalized(bitmap, rect) }.getOrNull()
        if (cropped == null) {
            publish(CropOutcome.Cancelled)
        } else {
            // 开关的当前状态跟着这次结果一起走：服务那边只认这一份，
            // 免得「用户点了确定」和「读开关」之间隔着几百毫秒，状态对不上。
            publish(CropOutcome.Done(cropped, CaptureBus.continuation))
        }
        finish()
    }

    /**
     * 这个 Activity 是 singleInstance，服务再次拉起它时走的是 onNewIntent 而不是 onCreate。
     * 不处理的话会停在上一张截图上，用户会以为「拍题没反应」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val bitmap = CaptureBus.consumeScreenshot() ?: return
        source?.takeIf { !it.isRecycled && it !== bitmap }?.recycle()
        source = bitmap
        cropView?.updateSource(bitmap)
        FloatingWindowService.setOverlayVisibleNow(false)
        suggestCrop(bitmap)
        syncContinueToggle()
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy，结局已发布=" + outcomePublished)

        // 兜底：这个界面被系统回收、却没有走过任何一个正常出口时（比如被 MIUI 直接清掉），
        // 必须补一个取消事件。否则悬浮球会永远停在「为截屏而隐藏」的状态，用户再也点不到它。
        if (!outcomePublished && !isFinishing) {
            Log.w(TAG, "界面不告而别，补发取消事件把悬浮球救回来")
            publish(CropOutcome.Cancelled)
        }

        source?.takeIf { !it.isRecycled }?.recycle()
        source = null
        super.onDestroy()
    }
}

/**
 * 画截图 + 可拖拽的选框。
 *
 * 选框状态在「视图坐标」里维护，只有交出去的时候才归一化成 0..1，
 * 这样旋转或不同密度的屏幕上都不会算错裁剪位置。
 */
@SuppressLint("ViewConstructor")
class CropView(context: Context, source: Bitmap) : View(context) {

    private var source: Bitmap = source

    /** 复用同一个实例时（singleInstance）用来替换底图。 */
    fun updateSource(bitmap: Bitmap) {
        source = bitmap
        resetSelection()
        invalidate()
    }

    private val selection = RectF()
    private val imageRect = RectF()
    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f

    private val density = resources.displayMetrics.density
    private val handleRadius = 26f * density
    private val minSize = 48f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF3B82F6.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66FFFFFF
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = min(w.toFloat() / source.width, h.toFloat() / source.height)
        val bw = source.width * scale
        val bh = source.height * scale
        val left = (w - bw) / 2f
        val top = (h - bh) / 2f
        imageRect.set(left, top, left + bw, top + bh)
        resetSelection()
    }

    /**
     * 默认整屏。
     *
     * 以前默认往里缩 8%，等于逼着用户每次都去拖边框 —— 但「把题目框准」这件事本来就该由识别端
     * 负责。现在匹配算法对截图里的状态栏、选项行、计时器、底部工具栏已经免疫
     * （全屏截图实测 86%~100%，三种框选范围分数完全一致），所以默认全选、直接点确定就行，
     * 想更准的再自己拖一个框。
     */
    fun resetSelection() {
        selection.set(imageRect)
        invalidate()
    }

    /**
     * 选框是否已经铺满整张图。
     *
     * 铺满时「拖动选框内部」没有意义（没有地方可移），应当理解成「我要重画一个」。
     * 以前这里一律当成移动，于是用户想框题目却只是在挪一个几乎全屏的大框，
     * 最后交出去的还是一整屏 —— 这也是当初日志里出现「裁剪图 1080x2400」的原因之一。
     */
    private fun selectionCoversAll(): Boolean {
        val w = imageRect.width().coerceAtLeast(1f)
        val h = imageRect.height().coerceAtLeast(1f)
        return selection.width() >= w * 0.98f && selection.height() >= h * 0.98f
    }

    fun selectAll() {
        selection.set(imageRect)
        invalidate()
    }

    /**
     * 按归一化坐标设置选框。自动框选算出来的就是这种坐标，
     * 走这条路等于替用户把四个角一次性拖到位。
     */
    fun applyNormalized(box: CropBox) {
        val w = imageRect.width()
        val h = imageRect.height()
        if (w <= 0f || h <= 0f) return
        selection.set(
            imageRect.left + box.left * w,
            imageRect.top + box.top * h,
            imageRect.left + box.right * w,
            imageRect.top + box.bottom * h,
        )
        normalizeEdges()
        invalidate()
    }

    /** 换算成 0..1 的相对坐标，交给 [ImageUtils.cropNormalized] 按原图尺寸裁剪。 */
    fun normalizedSelection(): RectF {
        val w = imageRect.width().coerceAtLeast(1f)
        val h = imageRect.height().coerceAtLeast(1f)
        return RectF(
            ((selection.left - imageRect.left) / w).coerceIn(0f, 1f),
            ((selection.top - imageRect.top) / h).coerceIn(0f, 1f),
            ((selection.right - imageRect.left) / w).coerceIn(0f, 1f),
            ((selection.bottom - imageRect.top) / h).coerceIn(0f, 1f),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(source, null, imageRect, bitmapPaint)

        // 选区外压暗。minSdk 26 起有 clipOutRect，不用再碰已废弃的 Region.Op。
        canvas.save()
        canvas.clipOutRect(selection)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()

        canvas.drawRect(selection, borderPaint)

        // 三分参考线，对齐题目行的时候有用
        for (i in 1..2) {
            val x = selection.left + selection.width() * i / 3f
            val y = selection.top + selection.height() * i / 3f
            canvas.drawLine(x, selection.top, x, selection.bottom, gridPaint)
            canvas.drawLine(selection.left, y, selection.right, y, gridPaint)
        }

        val r = 6f * density
        for (corner in corners()) {
            canvas.drawCircle(corner.first, corner.second, r + 1.5f * density, borderPaint)
            canvas.drawCircle(corner.first, corner.second, r, handlePaint)
        }
    }

    private fun corners(): List<Pair<Float, Float>> = listOf(
        selection.left to selection.top,
        selection.right to selection.top,
        selection.left to selection.bottom,
        selection.right to selection.bottom,
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitTest(x, y)
                if (mode == MODE_NEW) {
                    selection.set(x, y, x, y)
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                when (mode) {
                    MODE_MOVE -> {
                        selection.offset(dx, dy)
                        keepInsideImage()
                    }
                    MODE_LEFT_TOP -> {
                        selection.left += dx
                        selection.top += dy
                    }
                    MODE_RIGHT_TOP -> {
                        selection.right += dx
                        selection.top += dy
                    }
                    MODE_LEFT_BOTTOM -> {
                        selection.left += dx
                        selection.bottom += dy
                    }
                    MODE_RIGHT_BOTTOM -> {
                        selection.right += dx
                        selection.bottom += dy
                    }
                    MODE_NEW -> {
                        selection.right = x
                        selection.bottom = y
                    }
                }
                normalizeEdges()
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                normalizeEdges()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Int {
        val near = { cx: Float, cy: Float -> abs(x - cx) <= handleRadius && abs(y - cy) <= handleRadius }
        return when {
            near(selection.left, selection.top) -> MODE_LEFT_TOP
            near(selection.right, selection.top) -> MODE_RIGHT_TOP
            near(selection.left, selection.bottom) -> MODE_LEFT_BOTTOM
            near(selection.right, selection.bottom) -> MODE_RIGHT_BOTTOM
            selection.contains(x, y) && !selectionCoversAll() -> MODE_MOVE
            else -> MODE_NEW
        }
    }

    /** 拖出图外时把整个选框平移回来，而不是裁剪选框 —— 后者会让用户的手感突然断掉。 */
    private fun keepInsideImage() {
        if (selection.left < imageRect.left) selection.offset(imageRect.left - selection.left, 0f)
        if (selection.top < imageRect.top) selection.offset(0f, imageRect.top - selection.top)
        if (selection.right > imageRect.right) selection.offset(imageRect.right - selection.right, 0f)
        if (selection.bottom > imageRect.bottom) selection.offset(0f, imageRect.bottom - selection.bottom)
    }

    private fun normalizeEdges() {
        if (selection.left > selection.right) {
            val tmp = selection.left
            selection.left = selection.right
            selection.right = tmp
        }
        if (selection.top > selection.bottom) {
            val tmp = selection.top
            selection.top = selection.bottom
            selection.bottom = tmp
        }

        selection.left = selection.left.coerceIn(imageRect.left, imageRect.right)
        selection.right = selection.right.coerceIn(imageRect.left, imageRect.right)
        selection.top = selection.top.coerceIn(imageRect.top, imageRect.bottom)
        selection.bottom = selection.bottom.coerceIn(imageRect.top, imageRect.bottom)
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_MOVE = 1
        const val MODE_NEW = 2
        const val MODE_LEFT_TOP = 3
        const val MODE_RIGHT_TOP = 4
        const val MODE_LEFT_BOTTOM = 5
        const val MODE_RIGHT_BOTTOM = 6
    }
}
