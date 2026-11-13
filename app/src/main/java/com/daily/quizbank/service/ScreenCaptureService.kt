package com.daily.quizbank.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.daily.quizbank.MainActivity
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.R
import com.daily.quizbank.ui.capture.CropOverlayActivity

/**
 * 持有 MediaProjection 会话并按需抓一帧屏幕。
 *
 * 这个类的结构完全被 Android 的两条硬性约束决定：
 *
 *  1. 必须先 startForeground(mediaProjection) 再 getMediaProjection()，顺序反了直接抛 SecurityException。
 *  2. **同一个 MediaProjection 实例只能调用一次 createVirtualDisplay()**。
 *     Android 14 起会抛：
 *       "Don't take multiple captures by invoking MediaProjection#createVirtualDisplay
 *        multiple times on the same instance."
 *     Android 13 及以前「每次抓帧新建一个 VirtualDisplay」是常见写法，从 14 开始被禁止。
 *
 * 所以这里采取的是：授权后一次性建立 VirtualDisplay + ImageReader 并长期持有，
 * 之后每次「拍题」只是从常驻的 ImageReader 里取最新一帧。
 * 好处是抓帧更快（不用每次重建虚拟屏幕），代价是会一直保持镜像状态。
 */
class ScreenCaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** 是否正在等待新一帧（只在会话刚建立、还没有任何帧时才会用到）。 */
    private var awaitingFrame = false
    private var captureTimeout: Runnable? = null

    /**
     * 空闲期间一直保留着「最新一帧」。
     *
     * 这是这个类最容易写错的地方：隐藏悬浮球之后屏幕往往就静止了，
     * 不会再产生新帧。如果抓帧前把积压帧全丢掉再去等新帧，只会等到超时。
     * 正确做法是全程保留最新一帧，抓帧时直接用它 —— 隐藏悬浮球本身就是一次屏幕变化，
     * 那一刻产生的帧已经被这里存下来了。
     */
    private var latestImage: Image? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 会话被系统结束，释放虚拟屏幕")
            releaseSession()
        }
    }

    /**
     * 常驻的取帧回调。
     *
     * 必须一直挂着监听：ImageReader 的缓冲区只有几格，
     * 不消费的话生产端（SurfaceFlinger）很快就会被拖住，之后再也拿不到新帧。
     * 空闲时不是把帧丢掉，而是「留最新一帧、丢上一帧」。
     */
    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            ?: return@OnImageAvailableListener

        if (awaitingFrame) {
            awaitingFrame = false
            captureTimeout?.let { handler.removeCallbacks(it) }
            captureTimeout = null

            val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
            runCatching { image.close() }
            finishCapture(bitmap)
        } else {
            runCatching { latestImage?.close() }
            latestImage = image
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                promoteToForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    if (attachProjection(resultCode, resultData)) {
                        // 授权刚返回时画面还没稳定，稍等一下再抓
                        handler.postDelayed({ capture() }, FIRST_CAPTURE_DELAY_MS)
                    }
                } else {
                    CaptureBus.publishStatus("没有获得截屏授权")
                    CaptureBus.publishOutcome(CropOutcome.Cancelled)
                    stopSelf()
                }
            }

            ACTION_CAPTURE -> {
                promoteToForeground()
                capture()
            }

            ACTION_STOP -> {
                releaseSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 会话

    private fun attachProjection(resultCode: Int, resultData: Intent): Boolean {
        releaseSession()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, resultData) }
            .onFailure { Log.e(TAG, "获取 MediaProjection 失败", it) }
            .getOrNull()

        if (mediaProjection == null) {
            CaptureBus.publishStatus("无法建立截屏会话")
            CaptureBus.publishOutcome(CropOutcome.Cancelled)
            stopSelf()
            return false
        }

        mediaProjection.registerCallback(projectionCallback, handler)
        projection = mediaProjection

        return try {
            val size = screenSize()
            val density = resources.displayMetrics.densityDpi
            // 缓冲区给到 3：我们自己长期占用一格存「最新帧」，还要留足给生产端
            val reader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 3)
            val display = mediaProjection.createVirtualDisplay(
                "quizbank-capture",
                size.x,
                size.y,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
            reader.setOnImageAvailableListener(imageListener, handler)
            imageReader = reader
            virtualDisplay = display
            Log.i(TAG, "截屏会话已建立: " + size.x + "x" + size.y + " @" + density + "dpi")
            true
        } catch (error: Exception) {
            Log.e(TAG, "创建虚拟屏幕失败", error)
            releaseSession()
            CaptureBus.publishStatus("建立截屏会话失败，请重新拍题")
            CaptureBus.publishOutcome(CropOutcome.Cancelled)
            stopSelf()
            false
        }
    }

    private fun releaseSession() {
        awaitingFrame = false
        captureTimeout?.let { handler.removeCallbacks(it) }
        captureTimeout = null

        runCatching { latestImage?.close() }
        latestImage = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null

        val current = projection
        projection = null
        if (current != null) {
            runCatching { current.unregisterCallback(projectionCallback) }
            runCatching { current.stop() }
        }
    }

    // ------------------------------------------------------------------ 抓帧

    private fun capture() {
        val reader = imageReader
        if (projection == null || reader == null) {
            Log.w(TAG, "没有可用的截屏会话")
            CaptureBus.publishStatus("截屏授权已失效，请重新拍题以重新授权")
            CaptureBus.publishOutcome(CropOutcome.Cancelled)
            return
        }
        if (awaitingFrame) {
            Log.i(TAG, "上一帧还没取到，忽略这次重复请求")
            return
        }

        // 直接用手上最新的一帧。它必然是「悬浮球隐藏之后」的画面：
        // 隐藏悬浮球会让屏幕内容变化并产生新帧，被上面的空闲分支保留了下来。
        val pending = latestImage
        if (pending != null) {
            latestImage = null
            val bitmap = runCatching { imageToBitmap(pending) }.getOrNull()
            runCatching { pending.close() }
            if (bitmap != null) {
                Log.i(TAG, "使用缓存帧完成抓帧")
                finishCapture(bitmap)
                return
            }
        }

        // 会话刚建立、一帧都还没到时才走这里
        Log.i(TAG, "暂时没有可用帧，等待下一帧")
        awaitingFrame = true
        captureTimeout = Runnable {
            Log.w(TAG, "等待新帧超时")
            awaitingFrame = false
            finishCapture(null)
        }.also { handler.postDelayed(it, CAPTURE_TIMEOUT_MS) }
    }

    private fun finishCapture(bitmap: Bitmap?) {
        awaitingFrame = false

        if (bitmap == null) {
            Log.w(TAG, "没有拿到有效帧")
            CaptureBus.publishStatus("截屏失败，请重试")
            CaptureBus.publishOutcome(CropOutcome.Cancelled)
            return
        }

        CaptureBus.pendingScreenshot = bitmap

        // 抓帧已经结束，可以（也必须）把悬浮球放回来了。
        // 这里的 startActivity 同样受后台启动限制约束，没有可见悬浮窗就会被静默丢弃。
        FloatingWindowService.setOverlayVisibleNow(true)

        val intent = Intent(this, CropOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Log.e(TAG, "打不开框选界面", it)
                CaptureBus.publishStatus("打不开框选界面，请检查悬浮窗权限")
                CaptureBus.publishOutcome(CropOutcome.Cancelled)
            }
    }

    /**
     * ImageReader 给的 buffer 每行可能有末尾填充（rowStride > width × pixelStride），
     * 直接 copyPixelsFromBuffer 会让画面斜掉。先按 stride 建图再裁掉右侧填充。
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)

        if (rowPadding == 0) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun screenSize(): Point {
        val manager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(point)
            point
        }
    }

    // ------------------------------------------------------------------ 前台通知

    private fun promoteToForeground() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, QuizBankApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.fgs_capture_title))
            .setContentText(getString(R.string.fgs_capture_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        releaseSession()
        if (live === this) live = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 0x2002
        private const val FIRST_CAPTURE_DELAY_MS = 400L
        private const val CAPTURE_TIMEOUT_MS = 4000L

        const val ACTION_START = "com.daily.quizbank.action.START_CAPTURE"
        const val ACTION_CAPTURE = "com.daily.quizbank.action.CAPTURE"
        const val ACTION_STOP = "com.daily.quizbank.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 同进程单例引用：悬浮窗需要知道「现在还有没有可用的截屏会话」，避免每拍一次都弹授权框。 */
        @Volatile
        private var live: ScreenCaptureService? = null

        fun hasSession(): Boolean = live?.projection != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun capture(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
