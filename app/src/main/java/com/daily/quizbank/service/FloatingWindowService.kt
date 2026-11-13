package com.daily.quizbank.service

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.daily.quizbank.MainActivity
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.R
import com.daily.quizbank.data.ai.ChatMessage
import com.daily.quizbank.data.ai.Messages
import com.daily.quizbank.data.ai.Pricing
import com.daily.quizbank.data.db.QuestionMatch
import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.prefs.AppSettings
import com.daily.quizbank.data.prefs.Prompts
import com.daily.quizbank.data.prefs.Recognizer
import com.daily.quizbank.data.repo.BankMatchOutcome
import com.daily.quizbank.ui.capture.CameraCaptureActivity
import com.daily.quizbank.ui.capture.ProjectionRequestActivity
import com.daily.quizbank.ui.collect.CollectActivity
import com.daily.quizbank.util.AnswerText
import com.daily.quizbank.util.ImageUtils
import com.daily.quizbank.util.MultiPartSplitter
import com.daily.quizbank.util.OcrText
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮球 + 拍题流水线。
 *
 * 整条链路：
 *   点悬浮球 → 藏起悬浮窗（否则会被截进去）→ 截屏 → 全屏框选 → 本地 OCR → 题库匹配
 *   → DeepSeek 流式作答 → 结果卡片
 *
 * 识别策略在 [Recognizer] 里，核心取舍是「省 token」和「认得更准」之间的平衡：
 * 本地 ML Kit OCR 免费且离线，够清楚时就用它的文字去提问；
 * 一旦识别质量不行，才把图片交给视觉模型。两种情况下图片最多只发一次。
 */
class FloatingWindowService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app: QuizBankApp get() = application as QuizBankApp

    private lateinit var windowManager: WindowManager
    private val resultPanel = lazy { ResultPanel(this, windowManager) }

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var actions: View? = null
    private var actionParams: WindowManager.LayoutParams? = null

    private var pipelineJob: Job? = null
    private var restoreJob: Job? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var bubbleSize = 0
    private var bubbleAlpha = 0.85f

/** 按钮条（继续收录 / 搜题 / 拍题 / 拍照 / 关闭）的不透明度，和悬浮球各调各的。 */
private var actionsAlpha = 1f

/** 结果卡片（「题库里没有这道题」那张）+ 搜题条的不透明度，又是另一个值。 */
private var panelAlpha = 1f

/**
 * 拿结果面板，顺便把当前的不透明度套上去。
 *
 * 面板是 lazy 建出来的，而**设置流在服务启动时就发过一次**（那时面板还没建），
 * 所以不能只靠在设置回调里赋值 —— 那样面板一建出来就是默认的 100%，滑块拖了没反应。
 */
private fun panel(): ResultPanel = resultPanel.value.also { it.alpha = panelAlpha }

    /** 留着给「存入题库」用；下一次拍题或服务销毁时才释放。 */
    private var lastBitmap: Bitmap? = null

    /**
     * 当前这张结果是从哪来的。
     *
     * 只为一件事存在：结果卡片上那个「再来一题」按下去，得回到用户刚才用的那条路。
     * 拍照来的题在纸上或另一台设备上，把他丢回截屏流程只会让他对着屏幕框选一张
     * 根本没题的画面 —— 之前就是这个毛病。
     */
    private enum class CaptureSource { SCREEN, CAMERA }

    private var lastSource = CaptureSource.SCREEN

    /** 本次识别到的题干原文。收录时拿它当初值，不重新跑一遍 OCR。 */
    private var lastQuestionText = ""

    /** 命中题库时题库给的答案和解析，收录界面的预填值。 */
    private var lastBankAnswer = ""
    private var lastBankAnalysis = ""

    /** 打开收录界面之前抄下来的卡片内容，取消回来时用。 */
    private var pendingPanelSnapshot: ResultPanel.Snapshot? = null

    /**
     * 按钮条被拖走之后，相对「贴着悬浮球」那个默认位置的偏移。
     *
     * 存成偏移而不是绝对坐标：悬浮球一动，按钮条该跟着一起走（用户拖球是为了让开内容，
     * 按钮条留在原地反而挡住别处）。拖过之后这个偏移一直生效，直到服务重启。
     */
    private var actionsOffsetX = 0
    private var actionsOffsetY = 0

    /** 上一次算出来的默认位置，拖动时用它换算偏移。 */
    private var actionsBaseX = 0
    private var actionsBaseY = 0

    /** 一次拖动开始时的偏移，拖动过程中按「手指位移」在此基础上加。 */
    private var actionsDragStartX = 0
    private var actionsDragStartY = 0

    /** 用户正在「框选屏幕识别解析」：这一轮截屏的结果不进搜题流水线，而是 OCR 完送回收录界面。 */
    private var pendingFieldCapture: CollectBus.Outcome.FieldCapture? = null

    /**
     * 用户点「去翻原题」时那整份草稿（序列化成一串）。
     *
     * 收录面板是 Activity 窗口，整屏触摸都归它，用户翻不动底下的原题
     * （解析往往就在下面看不到）。所以「去翻原题」会把面板整个收掉、
     * 把不吃触摸的结果卡片还回去；草稿先寄存在这里，
     * 用户翻完再点一次「存入题库」就能接着填。
     */
    private var collectDraftJson: String? = null

    /** 本次结果列出来的候选，以及用户选了第几条。「解析」解释的就是选中那条的答案。 */
    private var currentMatches: List<QuestionMatch> = emptyList()
    private var selectedMatchIndex = 0

    /**
     * 问 AI 走哪条通道。
     *
     * FREE 用的是设置里另一组「地址 + Key + 模型」，之外没有任何分叉 ——
     * 国内几家的免费档同样说 OpenAI 协议，所以 [DeepSeekClient] 一行都不用改。
     */
    private enum class AiChannel { FREE, PAID }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        measureScreen()

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "缺少悬浮窗权限，服务不启动")
            stopSelf()
            return
        }

        running = true
        live = this
        promoteToForeground()
        showBubble()
        observeCaptureBus()
        observeSettings()

        // 预热数据库，避免用户第一次拍题时还要等 Room 建表
        scope.launch { runCatching { app.repository.totalQuestions() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE -> triggerCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        restoreJob?.cancel()
        resultPanel.valueOrNull()?.hide()
        removeBubble()
        hideActions()
        lastBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastBitmap = null
        ScreenCaptureService.stop(this)
        scope.cancel()
        if (live === this) live = null
        running = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 悬浮球

    private fun measureScreen() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        bubbleSize = OverlayUi.dp(this, 52f)
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = bubbleSize
        val view = buildBubble(size)
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - OverlayUi.dp(this@FloatingWindowService, 8f)
            y = (screenHeight * 0.55f).toInt()
        }
        bubbleParams = params
        bubble = view
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "悬浮球添加失败", it) }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble(size: Int): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_bubble)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val inset = size / 5
        setPadding(inset, inset, inset, inset)
        background = OverlayUi.rounded(
            0xFF1F6FEB.toInt(),
            size / 2f,
            0x66FFFFFF,
            OverlayUi.dp(this@FloatingWindowService, 1f),
        )
        alpha = bubbleAlpha
        isClickable = true
        setOnTouchListener(BubbleTouchListener())
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    view.alpha = 1f
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (startX + dx).coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                        positionActions()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = bubbleAlpha
                    if (dragging) snapToEdge() else toggleActions()
                    return true
                }
            }
            return false
        }
    }

    /** 松手后吸附到最近的屏幕边缘，避免悬浮球停在屏幕中间挡内容。 */
    private fun snapToEdge() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val target = if (params.x + bubbleSize / 2 < screenWidth / 2) 0 else screenWidth - bubbleSize
        if (params.x == target) return

        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180L
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
                positionActions()
            }
            start()
        }
    }

    // ------------------------------------------------------------------ 动作面板

    private fun toggleActions() {
        if (actions != null) hideActions() else showActions()
    }

    private fun showActions() {
        if (actions != null) return
        // 手上有一份没收完的草稿时，要多摆一个「继续收录」，面板得宽一点
        val resuming = collectDraftJson != null
        val width = OverlayUi.dp(this, if (resuming) 340f else 250f)
        val height = OverlayUi.dp(this, 50f)

        // 整条都能拖着走：它横着占 250~340dp，常常正好压住题目或解析，
        // 而「拖悬浮球让它跟着走」对用户来说不是个显然的操作（他 2026-10-09 报的就是这个）。
        val row = DraggableBar(
            this,
            onDragStart = {
                actionsDragStartX = actionsOffsetX
                actionsDragStartY = actionsOffsetY
            },
            onDrag = { dx, dy ->
                actionsOffsetX = (actionsDragStartX + dx).coerceIn(-screenWidth, screenWidth)
                actionsOffsetY = (actionsDragStartY + dy).coerceIn(-screenHeight, screenHeight)
                positionActions()
            },
        ).apply {
            setPadding(
                OverlayUi.dp(this@FloatingWindowService, 6f),
                OverlayUi.dp(this@FloatingWindowService, 6f),
                OverlayUi.dp(this@FloatingWindowService, 6f),
                OverlayUi.dp(this@FloatingWindowService, 6f),
            )
            background = OverlayUi.rounded(0xF210141B.toInt(), OverlayUi.dp(this@FloatingWindowService, 14f).toFloat(), 0x33FFFFFF, OverlayUi.dp(this@FloatingWindowService, 1f))
            // 整条一起淡（背景 + 按钮）：只淡背景的话按钮还是实心的，压在题目上照样挡字
            alpha = actionsAlpha
        }

        // 「继续收录」排在最前面：它是「去翻原题」之后唯一的回头路，
        // 藏在一堆按钮中间没人找得到。
        if (resuming) {
            row.addView(actionButton("继续收录") {
                hideActions()
                openCollect()
            })
        }
        row.addView(actionButton("搜题") { openSearch() })
        row.addView(actionButton("拍题") { triggerCapture() })
        row.addView(actionButton("拍照") { launchCamera() })
        row.addView(actionButton("关闭") { stopSelf() })

        val params = overlayParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        actionParams = params
        actions = row
        runCatching { windowManager.addView(row, params) }
        positionActions()
    }

    private fun actionButton(label: String, onClick: () -> Unit): View =
        OverlayUi.text(this, label, 13f, 0xFFE5E7EB.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = OverlayUi.rounded(0xFF232A36.toInt(), OverlayUi.dp(this@FloatingWindowService, 10f).toFloat())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = OverlayUi.dp(this@FloatingWindowService, 3f)
                marginEnd = OverlayUi.dp(this@FloatingWindowService, 3f)
            }
        }

    private fun hideActions() {
        actions?.let { runCatching { windowManager.removeView(it) } }
        actions = null
        actionParams = null
    }

    private fun positionActions() {
        val panel = actions ?: return
        val bubbleLayout = bubbleParams ?: return
        val panelLayout = actionParams ?: return

        var baseX = bubbleLayout.x - panelLayout.width - OverlayUi.dp(this, 8f)
        if (baseX < OverlayUi.dp(this, 4f)) {
            baseX = bubbleLayout.x + bubbleSize + OverlayUi.dp(this, 8f)
        }
        val baseY = bubbleLayout.y + (bubbleSize - panelLayout.height) / 2
        actionsBaseX = baseX
        actionsBaseY = baseY

        panelLayout.x = (baseX + actionsOffsetX)
            .coerceIn(0, (screenWidth - panelLayout.width).coerceAtLeast(0))
        panelLayout.y = (baseY + actionsOffsetY)
            .coerceIn(0, (screenHeight - panelLayout.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(panel, panelLayout) }
    }

    // ------------------------------------------------------------------ 搜题

    /**
     * 打开搜题。
     *
     * 一开始屏幕上只有一条搜索框 —— 结果面板此时是「收起」状态，卡片高度就一条输入框那么高。
     * 等用户打完字点「搜索」，同一张卡片才展开，答案长在输入框下面（见 [runSearch]）。
     *
     * 整个过程不需要截屏：悬浮球不用藏、界面也不会被切走，
     * 用户可以边看着原来的应用边把题干打进来。
     */
    private fun openSearch() {
        hideActions()
        setOverlayVisible(true)

        val panel = panel()
        panel.onSearch = { text -> runSearch(text) }
        panel.onCollapse = { collapseSearch() }
        panel.onClose = { closeSearch() }
        // 搜题没有图，「重拍」和「存入题库」在搜题模式下根本不出现，回调留空更保险
        panel.onRetry = null
        panel.onCollect = null
        panel.onAiFree = null
        panel.onAiPaid = null

        // 模式、收起状态、题干原文都必须在 show() 之前设好：
        // build() 要拿它们决定卡片布局、窗口高度和窗口标记。
        panel.searchMode = true
        panel.collapsed = true
        panel.query = ""
        panel.hide()
        panel.show()

        // 视图建好了才能往里写内容
        panel.setQuestion("")
        panel.setMatches(emptyList())
        panel.setAnswer("")
        panel.hideAiButtons()
    }

    /** 搜题面板折回一条输入框。答案那半张卡片会被拆掉，正在跑的问答也就没意义了。 */
    private fun collapseSearch() {
        pipelineJob?.cancel()
        resultPanel.valueOrNull()?.collapseSearch()
    }

    private fun closeSearch() {
        pipelineJob?.cancel()
        resultPanel.valueOrNull()?.hide()
        setOverlayVisible(true)
    }

    /**
     * 开新一轮拍题 / 拍照之前，把上一轮的结果卡片收掉。
     *
     * **必须收**：结果卡片是 TYPE_APPLICATION_OVERLAY 窗口，永远浮在所有 Activity 之上。
     * 不收的话，紧接着打开的框选界面 / 相机界面会被它整个盖住 ——
     * 用户看到的是「上一题的答案还在原地」，点哪都不对，因为底下那层根本点不到。
     * 之前的 dismissSearchPanel() 只在搜题模式下才收，拍题模式漏了这一步。
     */
    private fun dismissResultPanel() {
        pipelineJob?.cancel()
        resultPanel.valueOrNull()?.hide()
    }

    // ------------------------------------------------------------------ 截屏

    private fun triggerCapture(settleMs: Long = OVERLAY_HIDE_DELAY_MS) {
        Log.i(TAG, "触发拍题，当前有无截屏会话=" + ScreenCaptureService.hasSession())
        lastSource = CaptureSource.SCREEN
        // 框选界面据此决定摆不摆那颗「接着上次识别」开关。判断就放在这一处：
        // 「再截一题」重来时 pendingFieldCapture 还留着，开关也该跟着留着。
        CaptureBus.fieldCapture = pendingFieldCapture != null
        // 按的是收录面板上哪一栏的「识别」，默认框就套在哪一块上：
        // 「识别题干」只框题干（共用题干题里是材料那一段），「只识别选项」只框选项。
        // 拍题 / 搜题这条路上 pendingFieldCapture 是 null，于是照旧框整道题。
        CaptureBus.cropFocus = CollectDraft.cropFocusOf(pendingFieldCapture?.target)
        // 只收起按钮面板。悬浮球必须保持可见 —— 见下面 launchProjectionRequest 的说明，
        // 把它藏起来会导致后续的 startActivity 被系统静默丢弃。
        hideActions()
        dismissResultPanel()
        setOverlayVisible(true)

        if (ScreenCaptureService.hasSession()) {
            // 已有授权会话，直接抓帧。这是唯一需要隐藏悬浮球的时刻。
            setOverlayVisible(false)
            armRestoreTimeout()
            scope.launch {
                delay(settleMs)
                ScreenCaptureService.capture(this@FloatingWindowService)
            }
        } else {
            launchProjectionRequest()
        }
    }

    /**
     * 用摄像头拍题。
     *
     * 截屏拍题要求题目显示在**本机屏幕**上；题目在纸上、或者显示在另一台设备上时，
     * 截屏拿到的是一张无关画面，只能拍。拍到的照片会走和截屏完全一样的
     * 框选 → OCR → 题库匹配 → 出答案链路，后面几步没有任何分叉。
     */
    private fun launchCamera() {
        lastSource = CaptureSource.CAMERA
        hideActions()
        dismissResultPanel()
        // 和截屏那条链路同理：跳转前必须保持有一个「可见」悬浮窗，
        // 否则 Android 10 起会把这次 startActivity 静默丢掉。
        // 球只是变透明、不产生像素，不会被拍进照片里。
        setOverlayVisible(false)
        armRestoreTimeout()

        val launched = runCatching {
            startActivity(
                Intent(this, CameraCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(TAG, "打不开拍照界面", it) }.isSuccess

        if (!launched) {
            toast("打不开拍照界面")
            setOverlayVisible(true)
        }
    }

    /**
     * 拉起系统截屏授权页。
     *
     * Android 10 起，后台应用调用 startActivity 会被静默丢弃（不抛异常、不打日志），
     * 而本应用的豁免条件正是「拥有一个用户可见的悬浮窗」。之前这里先把悬浮球设为 GONE
     * 再去 startActivity，等于亲手把豁免条件弄没了，表现就是「点了拍题什么都没有」。
     *
     * 另外：国产 ROM 常常还有一层自己的「后台弹出界面」限制，所以这里会在一秒半后
     * 回头确认授权页到底有没有起来，没起来就给用户一句能照做的提示。
     */
    private fun launchProjectionRequest() {
        ProjectionRequestActivity.reset()
        armRestoreTimeout()

        val launched = runCatching {
            startActivity(
                Intent(this, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(TAG, "startActivity 抛出异常", it) }.isSuccess

        if (!launched) {
            toast("无法发起截屏授权")
            setOverlayVisible(true)
            return
        }

        scope.launch {
            delay(START_CHECK_DELAY_MS)
            if (!ProjectionRequestActivity.started) {
                Log.w(TAG, "授权页没有起来，判定为被系统拦截")
                toast("系统拦截了授权页：请到系统设置里允许「题库助手」的「后台弹出界面」权限")
                setOverlayVisible(true)
            }
        }
    }

    /** 兜底：用户可能在授权框上卡住或直接离开，超时后一定把悬浮球放回来。 */
    private fun armRestoreTimeout() {
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(RESTORE_TIMEOUT_MS)
            Log.w(TAG, "等待超时，兜底把悬浮球放回来")
            setOverlayVisible(true)
        }
    }

    /**
     * 改变悬浮球「是否出现在画面里」。
     *
     * 关键：隐藏时用的是 alpha = 0，而不是 visibility = GONE。
     *
     * 因为 Android 10 起禁止后台启动 Activity，而本应用唯一的豁免条件就是
     * 「拥有一个用户可见的窗口」。View 设成 GONE 会让窗口从 WindowManager 的
     * 可见窗口列表里消失，于是紧随其后的 startActivity（跳授权页 / 跳框选页）
     * 会被直接 Abort —— 日志里就是 "Abort background activity starts from <uid>"。
     *
     * alpha = 0 的窗口在合成阶段不产生任何像素（截图里干干净净看不到球），
     * 但它在 WindowManager 眼里依然是「可见窗口」，豁免条件始终成立。
     */
    private fun setOverlayVisible(participates: Boolean) {
        Log.i(TAG, "悬浮球参与画面 -> " + participates)
        val view = bubble ?: return
        view.alpha = if (participates) bubbleAlpha else 0f
        if (!participates) {
            // 按钮条是实打实要收起来的，它本来也不参与截屏
            actions?.visibility = View.GONE
        }
    }

    /**
     * 立即改变悬浮窗可见性（调用方必须已在主线程）。
     *
     * 为什么不用事件流：截屏服务在 startActivity 之前需要「此刻」就有一个可见窗口，
     * 否则后台启动限制会把跳转丢掉。事件流是异步投递的，等它跑到就已经晚了。
     */
    private fun applyOverlayVisibilityNow(participates: Boolean) {
        val view = bubble ?: return
        view.alpha = if (participates) bubbleAlpha else 0f
        if (!participates) actions?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 事件

    private fun observeCaptureBus() {
        scope.launch {
            CaptureBus.outcome.collect { outcome ->
                Log.i(TAG, "收到结局事件: " + outcome)
                when (outcome) {
                    is CropOutcome.Done -> {
                        restoreJob?.cancel()
                        setOverlayVisible(true)
                        val field = pendingFieldCapture
                        if (field != null) {
                            // 这一轮不是搜题，是「框选屏幕识别解析」——结果不进匹配流水线，
                            // OCR 完直接填回收录界面的解析栏。
                            pendingFieldCapture = null
                            scope.launch {
                                fillAnalysisFromCrop(field, outcome.bitmap, outcome.continuation)
                            }
                        } else {
                            runPipeline(outcome.bitmap)
                        }
                    }
                    CropOutcome.Cancelled -> {
                        restoreJob?.cancel()
                        setOverlayVisible(true)
                        // 框选被取消时，收录界面还在外面等着（它为了不挡屏已经关掉了），
                        // 得把它还回去，否则用户编辑到一半的东西就没了
                        val field = pendingFieldCapture
                        if (field != null) {
                            pendingFieldCapture = null
                            armCollectBus()
                            if (!launchCollect(field.draft, null, null)) {
                                restoreResultPanel()
                            }
                        }
                    }
                    CropOutcome.Retry -> {
                        // 重截时 pendingFieldCapture 保持不变，下一轮仍然是「填解析」
                        restoreJob?.cancel()
                        triggerCapture()
                    }
                }
            }
        }
        scope.launch {
            CaptureBus.status.collectLatest { message ->
                if (!message.isNullOrBlank()) toast(message)
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            app.settings.flow.collectLatest { settings -> applyOverlayAppearance(settings) }
        }
    }

    /**
     * 把外观设置套到两个悬浮窗上：悬浮球（大小 + 不透明度）和按钮条（不透明度）。
     *
     * 按钮条是**点开才建**的（[showActions]），所以这里要顺手给已经开着的那条也刷一遍，
     * 不然用户在设置里拖完滑块回到刷题界面，那条还是老的透明度。
     */
    private fun applyOverlayAppearance(settings: AppSettings) {
        actionsAlpha = (settings.actionsAlphaPercent.coerceIn(20, 100)) / 100f
        actions?.alpha = actionsAlpha
        panelAlpha = (settings.panelAlphaPercent.coerceIn(20, 100)) / 100f
        resultPanel.valueOrNull()?.alpha = panelAlpha
        bubbleAlpha = (settings.bubbleAlphaPercent.coerceIn(20, 100)) / 100f
        val desired = OverlayUi.dp(this, settings.bubbleSizeDp.coerceIn(40, 72))
        if (desired != bubbleSize) {
            // WindowManager 不支持改已添加窗口的尺寸，只能拆了重建
            bubbleSize = desired
            removeBubble()
            showBubble()
        } else {
            bubble?.alpha = bubbleAlpha
        }
    }

    // ------------------------------------------------------------------ 识别流水线

    private fun runPipeline(bitmap: Bitmap) {
        pipelineJob?.cancel()
        // 新的一题进来了，上一题没收完的草稿作废
        collectDraftJson = null

        lastBitmap?.takeIf { !it.isRecycled && it !== bitmap }?.recycle()
        lastBitmap = bitmap

        val panel = panel()
        // 按下来源走：拍照来的题就回拍照，截屏来的题就回截屏。
        // 以前一律 triggerCapture()，拍照搜完题点「重拍」会莫名其妙跳到截屏框选，
        // 用户还得先点确定、再从悬浮球里重新点一次「拍照」。
        panel.onRetry = {
            when (lastSource) {
                CaptureSource.CAMERA -> launchCamera()
                CaptureSource.SCREEN -> triggerCapture()
            }
        }
        panel.onClose = { setOverlayVisible(true) }
        panel.onCollect = { openCollect() }
        // 上一次可能停在搜题模式：那张卡片是铺满宽度、顶部带输入框、咬着屏幕下沿的。
        // 布局、窗口高度和窗口标记都是 build() 时一次定死的，换模式只能拆掉重建。
        panel.searchMode = false
        panel.collapsed = false
        panel.query = ""
        panel.hide()
        panel.show()
        // 题干不往面板上贴了：用户手上就有题目（在屏幕上、在纸上），
        // 再复述一遍只是把「匹配到了哪道题」挤下去。OCR 原文仍然打日志，排错时看得到。
        panel.setQuestion("")
        panel.setMatches(emptyList())
        panel.setAnswer("")
        panel.hideAiButtons()
        panel.setRetryLabel(
            if (lastSource == CaptureSource.CAMERA) "再拍一题" else "再截一题",
        )
        panel.setStatus("正在识别…", "先跑一次本地 OCR")

        pipelineJob = scope.launch {
            try {
                val settings = app.settings.snapshot()

                // 1) 本地 OCR。免费、离线、几百毫秒，既用于展示也用于题库匹配。
                //
                // 认出来的原文先过一遍 OcrText.clean：刷题软件选项前面那个圆形单选按钮
                // 会被认成字母 O，清掉之后匹配、问 AI、收录界面拿到的才是同一份干净文本。
                //
                // 走 recognizeRobust 而不是 recognize：它会顺手把「认成乱码的那几行」
                // 抠出来放大重认一遍（真机上「E.梨状肌」被认成过「E.NAL」）。
                val ocrText = OcrText.clean(
                    withContext(Dispatchers.Default) {
                        runCatching { app.ocr.recognizeRobust(bitmap) }.getOrDefault("")
                    },
                )
                // 诊断日志：OCR 出来到底是什么、题库里有多少题、匹配到了什么。
                // 匹配不到位时没有这些信息就只能靠猜。
                Log.i(TAG, "裁剪图 " + bitmap.width + "x" + bitmap.height +
                    "，OCR 得到 " + ocrText.length + " 字")
                Log.i(TAG, "OCR 内容: " + ocrText.replace("\n", " ⏎ ").take(300))
                Log.i(TAG, "题库总题数: " + app.repository.totalQuestions())
                if (ocrText.isBlank()) Log.w(TAG, "OCR 结果为空，跳过题库匹配")

                // 2)+3) 匹配题库、决定给题库答案还是交给 AI —— 和搜题共用的后半段
                matchAndAnswer(panel, settings, ocrText, bitmap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "拍题流水线失败", error)
                panel.setStatus("出错了", error.message ?: "未知错误")
            }
        }
    }

    /**
     * 搜题：拿用户粘贴进来的题干直接匹配题库，不经过截屏和 OCR。
     *
     * 这条链路值得单独存在：题干本来就已经在剪贴板里的时候，拍题要藏球、截屏、框选、
     * OCR 四步，每一步都可能出错（截到无关画面、框错范围、识别出乱码、手机屏幕根本不
     * 显示题目）。而文字本身就是准确的，直接跳到后半段，又快又不会错。
     *
     * 匹配、采信判据、问 AI 的逻辑和拍题完全共用 [matchAndAnswer]，
     * 唯一交给它的区别是 bitmap = null —— 搜题没有图，视觉模型那条路走不了。
     */
    private fun runSearch(stem: String) {
        // 新的一题进来了，上一题没收完的草稿作废
        collectDraftJson = null
        // 从刷题软件里复制出来的题干同样会带上选项前的圈，一起清掉
        val text = OcrText.clean(stem).trim()
        if (text.isBlank()) {
            toast("先输入或粘贴题干")
            return
        }

        pipelineJob?.cancel()

        val panel = panel()
        // 展开：同一张卡片从「只有一条输入框」长成「输入框 + 答案」，答案就在输入框下面
        panel.expandSearch(text)
        panel.setQuestion(text)
        panel.setMatches(emptyList())
        panel.setAnswer("")
        panel.hideAiButtons()
        panel.setStatus("正在匹配题库", "在本地题库里找相近题目")

        pipelineJob = scope.launch {
            try {
                val settings = app.settings.snapshot()
                Log.i(TAG, "搜题：题干 " + text.length + " 字，题库总题数 " + app.repository.totalQuestions())
                Log.i(TAG, "搜题内容: " + text.replace("\n", " ⏎ ").take(300))
                matchAndAnswer(panel, settings, text, null, fromSearch = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "搜题失败", error)
                panel.setStatus("出错了", error.message ?: "未知错误")
            }
        }
    }

    /**
     * 「题干已经拿到」之后的公共后半段。
     *
     * 拍题（图片 → OCR）和搜题（剪贴板 → 文字）只有题干来源不同，后面每一步都一样：
     * 匹配题库 → 命中且可信就给题库答案 → 只有候选就摆出来让用户决定 → 都没有才问 AI。
     * 两边各写一份的话，改采信判据时必然会漏掉一边，所以收在这里共用。
     *
     * @param bitmap 拍题时是裁剪图，搜题时是 null（没有图可发，也没有图可存）。
     * @param fromSearch 这次是搜题（剪贴板里的文字）还是拍题。只有搜题才让题库那条的
     *   **解析**参与候选排序 —— 用户搜题常常把「题干 + 解析」一整块粘进来。
     */
    private suspend fun matchAndAnswer(
        panel: ResultPanel,
        settings: AppSettings,
        questionText: String,
        bitmap: Bitmap?,
        fromSearch: Boolean = false,
    ) {
        // 2) 题库匹配。拍题和搜题从这里开始走完全一样的路。
        var outcome = BankMatchOutcome.EMPTY
        lastQuestionText = questionText
        lastBankAnswer = ""
        lastBankAnalysis = ""
        if (questionText.isNotBlank()) {
            // 题干里与这次原文对得上的片段要标红，所以把原文交给面板
            panel.highlightSource = questionText
            panel.setStatus("正在匹配题库", "在本地题库里找相近题目")
            outcome = matchQuestion(questionText, withAnalysis = fromSearch)
            Log.i(
                TAG,
                "题库匹配: 命中 " + outcome.matches.size + " 条，与最近的不同题相差 " +
                    (outcome.margin * 100).toInt() + "% | " +
                    outcome.matches.joinToString(" ; ") {
                        (it.score * 100).toInt().toString() + "% " + it.question.stem.take(18) +
                            if (it.analysisScore >= 0.5) {
                                "（解析 " + (it.analysisScore * 100).toInt() + "%）"
                            } else {
                                ""
                            }
                    } +
                    if (outcome.genericOnly > 0) {
                        " | 另有 " + outcome.genericOnly + " 条只是撞上一句通用问法，已挡掉"
                    } else {
                        ""
                    },
            )
            currentMatches = outcome.matches.take(3)
            selectedMatchIndex = 0
            panel.setMatches(currentMatches, selectedMatchIndex)
            // 同一道题干在题库里可能同时有单选题和判断题（题干一模一样、答案不同），
            // 这时列出来的候选不止一条，必须让用户先指定一条再问 AI —— 见 onMatchSelected 的说明。
            wireMatchSelection(panel, settings, questionText, bitmap, outcome)
        } else {
            currentMatches = emptyList()
            selectedMatchIndex = 0
            Log.w(TAG, "题干为空，跳过题库匹配")
        }

        // 3) 命中题库足够可信时，直接给题库里的标准答案，不再调用 AI。
        //
        // 这一步很关键：题库答案是官方/人工录入的，权威、零成本、零延迟；
        // 而大模型是从自己的知识里"生成"答案，既可能答错又要花钱。
        // 之前的实现无论命中与否都调 AI，属于设计缺陷。
        //
        // 「可信」要过三道关，全都在 StemMatcher 里算好，这里只看结论：
        //   1. 分数够高（≥ 0.80）；
        //   2. 和第二道题干不同的题拉开了距离（≥ 0.03）——
        //      题库里存在只差一两个字的近似题（「防堵、防冻、（）」与「防堵、防冻、解冻」），
        //      两边分数一样高，赌一个就是 50% 的错；
        //   3. 对上的那一段和题库题干是同一个问法（否定字个数一致）——
        //      判断题加一个「没有」就能把答案反过来，而那道题可能根本不在题库里。
        // 三道关任意一道不过就退回 AI：多花几分钱，好过照着错的答案答题。
        // 取值依据见 拍题匹配分析/README.md。
        val best = outcome.best
        if (outcome.confident && best != null) {
            val percent = (best.score * 100).toInt()
            // 收录界面要拿这两个当预填值：用户想记的是**题库给的**答案，不是我们猜的
            lastBankAnswer = AnswerText.resolve(best.question.answer, best.question.options)
            lastBankAnalysis = best.question.analysis
            panel.setAnswer(bankConclusion(best))
            panel.setAnswerLabel("结论", fromBank = true)
            panel.setStatus(
                "已命中你的题库 · 相似度 " + percent + "%",
                "答案取自题库，未经 AI 处理，也未产生任何 token 消耗",
            )
            // 题库已经把答案摆出来了，这里要的是「为什么」而不是「再做一遍」，
            // 所以把确定答案一起交给 AI —— 见 buildMessages 的 bankAnswer。
            wireAiButtons(
                panel,
                settings,
                questionText,
                bitmap,
                outcome,
                bankAnswer = AnswerText.resolve(best.question.answer, best.question.options),
            )
            return
        }

        if (outcome.ambiguous) {
            Log.w(
                TAG,
                "命中分数够高但不敢直接采信（" + outcome.hesitation + "，与下一名相差 " +
                    (outcome.margin * 100).toInt() + "%）: " +
                    outcome.best?.question?.stem?.take(24),
            )
        }

        // 题库里给出了候选（哪怕分数不够高）时，只把候选摆出来 + 一个「问 AI」按钮，
        // **不自动去调 AI**。
        //
        // 理由很直接：这些练习题本来就是从题库里出的，命中的候选往往已经够用户自己
        // 判断了；而自动问 AI 每次都要花 token、要等流式输出，用户还没得选。
        // 要不要进一步问，交给他点一下决定。
        if (best != null) {
            // 还没定论，不该拿一个不确定的答案去诱导 AI，所以 bankAnswer 传 null
            wireAiButtons(panel, settings, questionText, bitmap, outcome, bankAnswer = null)
            panel.setStatus(
                if (outcome.ambiguous) "题目对上了但不敢直接采信" else "题库里有相近的题",
                outcome.hesitation
                    ?: "上面几条候选对不对由你判断；想让 AI 再答一次就点上面的按钮",
            )
            return
        }

        // 一条候选都没有（相似度低到连展示都不够）时，AI 是唯一的答案来源。
        //
        // 这里**不自动调任何一条通道**，两个按钮都摆出来等用户点。
        // 之前免费通道配好时会自动走它，看着贴心，实际是替用户做了决定：
        // 他可能本来就想要 DeepSeek 那句更准的，也可能压根不想问这一题。
        // 要不要问、走哪条、花不花钱，全交给他自己点。
        wireAiButtons(panel, settings, questionText, bitmap, outcome, bankAnswer = null)
        panel.setStatus(
            "题库里没有这道题",
            when {
                settings.freeConfigured && settings.configured ->
                    "想让 AI 做一遍就点上面的按钮：免费通道不花钱，深度通道更准"
                settings.freeConfigured -> "想让 AI 做一遍就点上面的按钮（免费通道，不花钱）"
                settings.configured -> "想让 AI 做一遍就点上面的按钮（会消耗 token）"
                else -> "到「设置」里填一个 API Key 才能让 AI 作答；只填免费通道的 Key 不花钱"
            },
        )
    }

    /**
     * 题库匹配。
     *
     * 共用题干题必须**按「大题干 + 单个小问」去匹**，不能拿整屏去匹：题库里每一条存的
     * 是「大题干 + 某一小问」，而截屏拿到的往往是「大题干 + 五六个小问」。
     * 拿这么长一段去比，哪一条都对不上 —— 多出来的那几问把相似度稀释掉了。
     *
     * 所以先按小问拆开、各匹一次，取分数最高的那个结果。整屏的原文仍然照旧留给
     * [lastQuestionText]：收录界面那边也要靠它把题目拆成几问。
     */
    private suspend fun matchQuestion(
        text: String,
        withAnalysis: Boolean = false,
    ): BankMatchOutcome {
        val split = MultiPartSplitter.split(text)
        if (split == null) {
            return app.repository.match(text, withAnalysis = withAnalysis)
        }
        Log.i(
            TAG,
            "识别为共用题干题（当前第 " + split.parts.first().label + " 问，共 " +
                split.parts.size + " 问），按问分别匹配",
        )
        var best: BankMatchOutcome? = null
        for (part in split.parts) {
            // 单问拿去匹的时候要把大题干带上 —— 离开材料，第 2 问根本读不通
            // 不另给 limit：列表最多摆 3 条，条数以 repository.match 的默认为准
            val outcome = app.repository.match(
                split.shared + "\n" + part.body,
                withAnalysis = withAnalysis,
            )
            val score = outcome.best?.score ?: 0.0
            Log.i(
                TAG,
                "  第 " + part.label + " 问：命中 " + outcome.matches.size +
                    " 条，最佳 " + (score * 100).toInt() + "%",
            )
            if (best == null || score > (best.best?.score ?: 0.0)) best = outcome
        }
        return best ?: BankMatchOutcome.EMPTY
    }

    /**
     * 把题库里的一条答案拼成一句能读的结论。
     *
     * 「B」这种字母要先还原成「B. 中国国家」—— 拍完题题干已经不在屏幕上，
     * 只给一个字母用户没法核对；再用「本题答案为：」框成一句话，而不是甩个答案。
     */
    private fun bankConclusion(match: QuestionMatch): String = buildString {
        append("本题答案为：")
        append(AnswerText.resolve(match.question.answer, match.question.options))
        append("\n\n解析：")
        if (match.question.analysis.isNotBlank()) {
            append(match.question.analysis)
        } else {
            // 题库里没解析就明说。留一片空白会让人以为「面板坏了」或者
            // 「AI 还没答完」—— 写清「暂无」，下面的免费/深度解析按钮才有意义：
            // 要不要花这一次，由用户自己决定。
            append("暂无（题库里这道题没有解析；想听讲解就点下面的解析按钮）")
        }
    }

    /**
     * 让用户能在候选之间切换。
     *
     * 只有一条候选时纯属多余，所以面板自己会判断要不要显示选择提示；
     * 这里只管把回调接上 —— 换选一条之后，结论区和两个「解析」按钮
     * 都要跟着换成那条的答案，否则 AI 解释的还是原来那条。
     */
    private fun wireMatchSelection(
        panel: ResultPanel,
        settings: AppSettings,
        questionText: String,
        bitmap: Bitmap?,
        outcome: BankMatchOutcome,
    ) {
        panel.onMatchSelected = { index ->
            val picked = currentMatches.getOrNull(index)
            if (picked != null) {
                selectedMatchIndex = index
                panel.setSelectedMatch(index)
                panel.setAnswer(bankConclusion(picked))
                panel.setAnswerLabel("结论（你选的那条）", fromBank = true)
                Log.i(TAG, "用户改选了第 " + (index + 1) + " 条候选: " + picked.question.stem.take(24))
                wireAiButtons(
                    panel,
                    settings,
                    questionText,
                    bitmap,
                    outcome,
                    bankAnswer = AnswerText.resolve(picked.question.answer, picked.question.options),
                )
            }
        }
    }

    /**
     * 把「免费解析 / 深度解析」（或「免费解答 / 深度解答」）两个按钮挂上去。
     *
     * @param bankAnswer 题库已经给出的确定答案；非空表示这是「要解析」而不是「要解答」，
     *   两者用的提示词完全不同，见 [buildMessages]。
     */
    private fun wireAiButtons(
        panel: ResultPanel,
        settings: AppSettings,
        questionText: String,
        bitmap: Bitmap?,
        outcome: BankMatchOutcome,
        bankAnswer: String?,
    ) {
        panel.setupAiButtons(
            explaining = !bankAnswer.isNullOrBlank(),
            freeConfigured = settings.freeConfigured,
            paidConfigured = settings.configured,
        )
        fun launch(channel: AiChannel) {
            panel.hideAiButtons()
            scope.launch {
                startAiAnswer(panel, settings, questionText, bitmap, outcome, channel, bankAnswer)
            }
        }
        panel.onAiFree = { launch(AiChannel.FREE) }
        panel.onAiPaid = { launch(AiChannel.PAID) }
    }


    /** 调用 DeepSeek 生成答案（流式）。命中题库时由「问 AI」按钮按需触发。 */
    /**
     * 问 AI（流式）。
     *
     * @param channel 走哪条通道。免费通道只是把「地址 + Key + 模型」换成另一组，
     *   之后一行都不分叉 —— 国内几家的免费档同样说 OpenAI 协议。
     * @param bankAnswer 题库已经给出的答案。非空表示这是「要解析」（解释为什么是它），
     *   为空表示「要解答」（让模型自己做一遍）。
     */
    private suspend fun startAiAnswer(
        panel: ResultPanel,
        settings: AppSettings,
        questionText: String,
        bitmap: Bitmap?,
        outcome: BankMatchOutcome,
        channel: AiChannel,
        bankAnswer: String?,
    ) {
        try {
            panel.hideAiButtons()
            Log.i(
                TAG,
                "开始问 AI（通道=" + channel + "，要解析=" + (bankAnswer != null) +
                    "，题库候选 " + outcome.matches.size + " 条）",
            )

            // 免费通道一律按纯文本用：国内免费档基本都是小模型，多数不吃图，
            // 硬塞图片进去会直接 400。宁可少一种能力，也别让用户点完按钮收一个报错。
            val effective = if (channel == AiChannel.FREE) {
                settings.copy(
                    apiKey = settings.freeApiKey,
                    baseUrl = settings.freeBaseUrl,
                    chatModel = settings.freeModel,
                    visionModel = settings.freeModel,
                    recognizer = Recognizer.OCR,
                )
            } else {
                settings
            }

            // 决定这一问要不要带图：只有「本地文字不够可信」时才付图片 token
            val reliable = isTextReliable(questionText)
            val wantImage = when (effective.recognizer) {
                Recognizer.OCR -> false
                Recognizer.VISION -> true
                Recognizer.AUTO -> !reliable
            }
            // 搜题没有图：哪怕识别策略配的是「总是用视觉模型」，也只能按文字作答，
            // 否则会拿一份根本不存在的图片去请求。
            val source = bitmap
            val dataUrl = if (wantImage && source != null) {
                panel.setStatus("正在读取图片", "把题目交给视觉模型")
                withContext(Dispatchers.Default) {
                    // source 在这里已经被智能转换成非空
                    ImageUtils.toDataUrl(source, ImageUtils.VISION_MAX_SIDE)
                }
            } else {
                null
            }
            // 「有没有带上图」以最后是否真的拼出了 dataUrl 为准。
            // 单独用一个 useImage 变量会多出一个永远为真的判断，也容易和实际发出去的内容脱节。
            val useImage = dataUrl != null

            val messages = buildMessages(effective, questionText, dataUrl, bankAnswer)
            val model = if (useImage) effective.visionModel else effective.chatModel

            panel.setAnswerLabel(
                if (channel == AiChannel.FREE) "结论（免费通道）" else "结论（AI 生成）",
                fromBank = false,
            )
            panel.setStatus(
                when {
                    bankAnswer != null -> "AI 正在解释这道题"
                    outcome.ambiguous -> "题目对上了但不敢直接采信 · 交给 AI 判断"
                    outcome.matches.isEmpty() -> "未命中题库 · 交给 AI 作答"
                    else -> "题库相似度不足 · 交给 AI 作答"
                },
                when {
                    channel == AiChannel.FREE -> "免费通道 · " + model + " · 不产生费用"
                    outcome.ambiguous -> outcome.hesitation.orEmpty()
                    useImage -> "已附带题目图片（会消耗图片 token）"
                    else -> if (bitmap == null) "按你粘贴的题干作答" else "按本地识别文字作答"
                },
            )

            val answer = StringBuilder()
            var usage: com.daily.quizbank.data.ai.Usage? = null
            var thinkingChars = 0
            app.deepSeek.chatStream(effective, messages, model).collect { delta ->
                delta.reasoning?.let {
                    thinkingChars += it.length
                    panel.appendThinking(it)
                }
                delta.usage?.let { usage = it }
                if (delta.content.isNotEmpty()) {
                    answer.append(delta.content)
                    panel.setAnswer(answer.toString())
                }
            }

            // 记一笔用量。官方只在开启 stream_options.include_usage 后
            // 才会在最后一个分片里返回 usage，拿不到就不记，避免写进假数据。
            // 记一笔用量。官方只在开启 stream_options.include_usage 后
            // 才会在最后一个分片里返回 usage，拿不到就不记，避免写进假数据。
            //
            // 费用只有付费通道才估：[Pricing] 那张表是 DeepSeek 的价目，
            // 套到别家的免费模型上会把不花钱的调用记成花了钱，用量页的数字就假了。
            val billed = usage
            var costMicros: Long? = null
            if (billed != null) {
                costMicros = if (channel == AiChannel.FREE) {
                    0L
                } else {
                    Pricing.estimateMicros(billed.promptTokens, billed.completionTokens, billed.cacheHitTokens)
                }
                app.repository.recordUsage(
                    model = model,
                    promptTokens = billed.promptTokens,
                    completionTokens = billed.completionTokens,
                    cacheHitTokens = billed.cacheHitTokens,
                    usedImage = useImage,
                    costMicros = costMicros,
                )
            }

            if (answer.isEmpty()) {
                panel.setStatus("没有得到回答", "模型返回了空内容，可以再试一次")
            } else {
                // 花了多少钱要当场说清楚。问 AI 是要花钱的，用户点了按钮之后
                // 只能去「用量」页翻总账，等于把单次成本藏起来了。
                // 免费通道反过来：写清「本次免费」，免得他以为没花钱是出了错。
                val detail = buildString {
                    if (channel == AiChannel.FREE) {
                        append("免费通道 · ")
                    } else {
                        append(if (useImage) "视觉模型 · " else "文本模型 · ")
                    }
                    append(model)
                    if (billed != null) {
                        append(" · 输入 ")
                        append(billed.promptTokens)
                        append(" / 输出 ")
                        append(billed.completionTokens)
                        append(" tokens")
                    }
                    when {
                        channel == AiChannel.FREE -> append(" · 本次免费")
                        costMicros != null -> {
                            append(" · 本次约 ¥")
                            append(Pricing.formatYuan(costMicros))
                        }
                        // 官方在 stream_options.include_usage 打开时会回一个只带 usage 的分片；
                        // 真拿不到就直说，别让用户以为「问 AI 不要钱」
                        else -> append(" · 本次费用未返回")
                    }
                }
                Log.i(
                    TAG,
                    "AI 作答完成: 通道=" + channel + "，思维链 " + thinkingChars +
                        " 字，用量=" + billed + "，费用=" + costMicros + " 微元",
                )
                panel.setStatus("AI 作答完成（非题库答案）", detail)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "AI 作答失败", error)
            panel.setStatus("AI 作答失败", error.message ?: "未知错误")
        }
    }

    /**
     * 判断本地 OCR 结果是否够可信。
     * 判据是「有效字符数」和「中文占比」，因为数学题里的符号、图形 OCR 基本读不出来，
     * 这种时候哪怕字符数够多也不该相信它。
     */
    private fun isTextReliable(text: String): Boolean {
        if (text.isBlank()) return false
        val meaningful = text.count { it.isLetterOrDigit() }
        if (meaningful < MIN_MEANINGFUL_CHARS) return false
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        return cjk.toDouble() / meaningful >= MIN_CJK_RATIO
    }

    /**
     * @param bankAnswer 题库已经给出的确定答案。非空时改用「解释」提示词，并把答案一起发过去 ——
     *   「告诉我为什么是 B」比「把这题做出来」简单得多，免费档的小模型也答得动，
     *   而且和题库答案摆在一起，AI 一旦胡说用户能立刻看出来。
     */
    private fun buildMessages(
        settings: AppSettings,
        questionText: String,
        dataUrl: String?,
        bankAnswer: String? = null,
    ): List<ChatMessage> {
        val known = bankAnswer?.trim().orEmpty()
        val prompt = if (known.isNotEmpty()) {
            Prompts.DEFAULT_EXPLAIN
        } else {
            settings.answerPrompt.trim().ifEmpty { Prompts.DEFAULT_ANSWER }
        }
        val text = if (known.isEmpty()) {
            questionText.trim()
        } else {
            "题目：" + questionText.trim() + "\n\n这道题的答案：" + known
        }

        return buildList {
            add(Messages.system(settings.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM }))
            if (dataUrl != null) {
                // 图片只能出现在 user 消息里，塞进 system 会直接 400
                val hint = if (text.isEmpty()) {
                    prompt
                } else {
                    prompt + "\n\n以下是本地 OCR 的识别结果，可能有错字，" +
                        "如果和图片内容冲突请以图片为准：\n" + text
                }
                add(Messages.userWithImage(hint, dataUrl, settings.imageDetail))
            } else {
                val body = if (text.isEmpty()) prompt else prompt + "\n\n" + text
                add(Messages.user(body))
            }
        }
    }

    // ------------------------------------------------------------------ 收录

    /**
     * 打开收录界面。
     *
     * 走之前必须做两件事，顺序都不能反：
     *
     *  1. **把结果卡片的内容抄下来**（[ResultPanel.snapshot]）。卡片是 overlay 窗口，
     *     永远浮在 Activity 之上，不收掉的话收录界面会被它整个盖住 ——
     *     这和「再截一题」时框选界面被盖住是同一个坑。
     *  2. **再收掉它**。收掉意味着视图全销毁，所以得先抄。
     *
     * 用户点「取消」回来时，用抄下来的内容重建一张一模一样的卡片。
     */
    private fun openCollect() {
        val question = lastQuestionText.trim()
        if (question.isBlank()) {
            toast("这一张没识别出内容，没法收录")
            return
        }

        // 上一次可能是「去翻原题」让开的，草稿要原样还回去 ——
        // 用户翻完原题回来接着填，之前写的答案和解析不能凭空消失。
        val restored = collectDraftJson
        collectDraftJson = null
        val payload = restored ?: run {
            // 新的一道题，接续开关归零：上一道题是分几次拍完的，和这一道没有任何关系，
            // 留着开着的话，这一道随手重拍一次就会把内容接在后面。
            CaptureBus.continuation = false
            // 截屏 OCR 拿到的是一整块「题干 + A. xx + B. xx…」。进面板之前先拆一次，
            // 用户一眼能看出分对了没有、再决定改不改 —— 而不是存完才发现选项被算进了题干。
            val (stem, options) = QuestionParser.splitTrailingOptions(question)
            CollectDraft(
                mode = "",
                question = stem,
                options = options.joinToString("\n"),
                answer = lastBankAnswer,
                analysis = lastBankAnalysis,
            ).encode()
        }

        val panel = resultPanel.valueOrNull()
        pendingPanelSnapshot = panel?.snapshot()
        panel?.hide()

        armCollectBus()
        if (!launchCollect(payload, null, null)) {
            CollectBus.onFinished = null
            toast("打不开收录界面")
            restoreResultPanel()
        }
    }

    /**
     * 拉起收录界面。
     *
     * 单独抽出来是因为「框选识别」要在这上面来回走一圈：
     * 收起界面 → 截屏 → 再拉起同一个界面，字段靠 Intent 带过去。
     */
    private fun launchCollect(
        draftJson: String,
        captureTarget: String?,
        captureText: String?,
        captureAppend: Boolean = false,
    ): Boolean = runCatching {
        val intent = Intent(this, CollectActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CollectActivity.EXTRA_DRAFT, draftJson)
        if (captureTarget != null && captureText != null) {
            intent.putExtra(CollectActivity.EXTRA_CAPTURE_TARGET, captureTarget)
            intent.putExtra(CollectActivity.EXTRA_CAPTURE_TEXT, captureText)
            intent.putExtra(CollectActivity.EXTRA_CAPTURE_APPEND, captureAppend)
        }
        startActivity(intent)
    }.onFailure { Log.e(TAG, "打不开收录界面", it) }.isSuccess

    /**
     * 挂上收录界面的回话通道。
     *
     * 界面会被反复拉起（框选识别走一圈再回来），每拉起一次就要重挂一次。
     */
    private fun armCollectBus() {
        CollectBus.onFinished = { outcome -> handleCollectOutcome(outcome) }
    }

    private fun handleCollectOutcome(outcome: CollectBus.Outcome) {
        when (outcome) {
            // 用户要去截屏幕识别解析。收录界面已经自己关掉了，屏幕上是刷题软件的解析页。
            is CollectBus.Outcome.FieldCapture -> {
                // 收录界面是在 onStop 里回的话，那时它已经离开屏幕了；这里再留一段沉降时间，
                // 是为了让退场动画最后几帧也落地 —— 否则抓到的会是「一半收录界面、
                // 一半题目界面」的中间帧，框选界面拿它当底图，看起来就像屏幕卡住了。
                pendingFieldCapture = outcome
                triggerCapture(FIELD_CAPTURE_SETTLE_MS)
            }

            // 用户要先去屏幕上翻原题。面板整个收掉（不只是折叠 ——
            // 折叠只是把卡片画小，窗口照样吃掉全屏的触摸），
            // 结果卡片还回去；卡片是悬浮窗，不吃触摸，用户能自由翻页。
            is CollectBus.Outcome.StepAside -> {
                collectDraftJson = outcome.draft
                // 结果卡片**不还回去**。用户点「去翻原题」就是为了看屏幕上的原题，
                // 而拍题模式下这张卡片能占掉近三分之一屏 —— 留着它等于白让开。
                // 回来的路改成悬浮球旁边那条小按钮条上的「继续收录」，
                // 高度只有一行，不挡事。
                dismissResultPanel()
                setOverlayVisible(true)
                showActions()
            }

            is CollectBus.Outcome.Cancelled -> {
                val pending = pendingFieldCapture
                pendingFieldCapture = null
                if (pending != null) {
                    // 框选识别中途放弃：把收录界面连同整份草稿还回去，
                    // 别把用户编辑到一半的东西弄丢
                    armCollectBus()
                    if (!launchCollect(pending.draft, null, null)) {
                        restoreResultPanel()
                    }
                } else {
                    restoreResultPanel()
                }
            }
        }
    }

    /**
     * 框选识别回来了：把截到的那块 OCR 出来，送回收录面板对应的那一栏。
     *
     * 覆盖还是接续由 [append] 决定（就是用户在框选界面上那颗「接着上次识别」开关）：
     * 开着的时候填进那一栏的是「原来那段 + 新拍这段」，重合的行会去掉，
     * 题干长的题因此可以分几次拍完。默认仍然是覆盖，理由见 [CollectDraft.applyCapture]。
     */
    private suspend fun fillAnalysisFromCrop(
        pending: CollectBus.Outcome.FieldCapture,
        bitmap: Bitmap,
        append: Boolean,
    ) {
        // 同样走 recognizeRobust：框一栏时被认成乱码的概率和整屏是一样的
        val text = OcrText.clean(
            withContext(Dispatchers.Default) {
                runCatching { app.ocr.recognizeRobust(bitmap) }.getOrDefault("")
            },
        )
        bitmap.takeIf { !it.isRecycled }?.recycle()

        if (text.isBlank()) {
            toast("没识别出文字，可以框得再准一点")
        }

        // 填哪个字段由界面决定（题干 / 某一小问 / 某一问的答案 / 单题的解析）。
        // 归并规则也在草稿那边，这里只管把原文带回去。
        armCollectBus()
        if (!launchCollect(pending.draft, pending.target, text, append)) {
            restoreResultPanel()
        }
    }

    /**
     * 能整条拖着走的按钮条。
     *
     * 为什么不是直接给整条挂 OnTouchListener：条上是五个按钮，触摸事件先给按钮，
     * 按钮吃掉了就轮不到父容器 —— 那样只有按钮之间的缝能拖，等于没有。
     * 这里用 onInterceptTouchEvent 在「手指移动超过阈值」时才把事件从按钮手里抢过来：
     * 单纯点按钮照旧是点按钮（子视图会先收到 ACTION_CANCEL），按住一拖就是移动整条。
     */
    private class DraggableBar(
        context: Context,
        private val onDragStart: () -> Unit,
        private val onDrag: (dx: Int, dy: Int) -> Unit,
    ) : LinearLayout(context) {

        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (abs(ev.rawX - downRawX) > DRAG_SLOP_PX || abs(ev.rawY - downRawY) > DRAG_SLOP_PX)
                    ) {
                        dragging = true
                        onDragStart()
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    onDrag((event.rawX - downRawX).toInt(), (event.rawY - downRawY).toInt())
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return true
        }
    }

    /** 从收录界面取消回来，把卡片按原样摆回去。 */
    private fun restoreResultPanel() {
        val snapshot = pendingPanelSnapshot
        pendingPanelSnapshot = null
        pendingFieldCapture = null
        val panel = resultPanel.valueOrNull() ?: return
        if (snapshot == null) return
        panel.hide()
        panel.show()
        panel.restore(snapshot)
        setOverlayVisible(true)
    }

    // ------------------------------------------------------------------ 杂项

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )

    private fun promoteToForeground() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingWindowService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, QuizBankApp.CHANNEL_FLOATING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.fgs_float_title))
            .setContentText(getString(R.string.fgs_float_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent)
            .addAction(0, "关闭悬浮球", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun <T> Lazy<T>.valueOrNull(): T? = if (isInitialized()) value else null

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_ID = 0x2001
        // 隐藏悬浮球到抓帧之间的间隔：要够长让合成器产生新的一帧，又不能长到用户觉得卡
        private const val OVERLAY_HIDE_DELAY_MS = 260L

/**
 * 「框选屏幕识别解析」那条路上的沉降时间。
 *
 * 比平常多一截：那一步是「收录界面刚退场、马上要抓题目那一屏」，
 * 抓早了会抓在退场动画中间。多一点等待换一张干净的截图，值。
 */
private const val FIELD_CAPTURE_SETTLE_MS = 350L
        private const val START_CHECK_DELAY_MS = 1500L
        private const val RESTORE_TIMEOUT_MS = 30_000L
        private const val DRAG_SLOP_PX = 12
        private const val MIN_MEANINGFUL_CHARS = 8
        private const val MIN_CJK_RATIO = 0.35
    

        /** 同进程标志位，主界面用它显示悬浮球的开关状态。 */
        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var live: FloatingWindowService? = null

        /**
         * 供截屏链路同步调用。必须在主线程调用。
         *
         * Android 10 起禁止后台启动 Activity，本应用的豁免条件就是「有可见的悬浮窗」，
         * 所以「抓帧前藏球」和「跳转前放球」都必须精确、立即生效，不能走异步事件。
         */
        fun setOverlayVisibleNow(visible: Boolean) {
            live?.applyOverlayVisibilityNow(visible)
        }

        const val ACTION_STOP = "com.daily.quizbank.action.STOP_FLOATING"
        const val ACTION_CAPTURE = "com.daily.quizbank.action.CAPTURE_NOW"

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: android.content.Context) {
            runCatching { context.startService(Intent(context, FloatingWindowService::class.java).setAction(ACTION_STOP)) }
        }
    }
}