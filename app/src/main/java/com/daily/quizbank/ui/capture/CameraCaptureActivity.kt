package com.daily.quizbank.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.service.CaptureBus
import com.daily.quizbank.util.CropFocus
import com.daily.quizbank.service.CropOutcome
import com.daily.quizbank.service.OverlayUi
import com.daily.quizbank.util.ImageUtils
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * 用摄像头拍题。
 *
 * 和截屏拍题的区别只在「图从哪来」：拿到照片之后同样塞进 [CaptureBus]，
 * 再走同一个框选界面和同一条 OCR / 题库匹配 / 出答案流水线 —— 后面几步一行都没改。
 *
 * 默认后置（拍纸、拍远处的屏幕都靠它），点右上角可以切到前置并**记住选择**：
 * 下次进来还是你上次用的那个镜头，不用每次都被拍一次脸。
 */
class CameraCaptureActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: View
    private lateinit var flipButton: TextView
    private lateinit var torchButton: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    /** false = 后置（默认）。真正的取值在 onCreate 里从设置里读出来。 */
    private var useFrontCamera = false
    private var capturing = false
    private var switching = false
    private var outcomePublished = false
    private var cameraReady = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "没有相机权限，无法拍照拍题", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // 读上次用的是哪个镜头。读设置是挂起调用，所以放在协程里；
        // 读完再开相机，避免「先开前置、半秒后又跳回后置」的闪一下。
        lifecycleScope.launch {
            val settings = runCatching { (application as QuizBankApp).settings.snapshot() }.getOrNull()
            if (savedInstanceState == null) {
                useFrontCamera = settings?.cameraFacingFront ?: false
            }
            updateFlipLabel()
            startCamera()
        }
    }

    // ------------------------------------------------------------------ 界面

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        // 点哪儿对哪儿：手机拍纸的时候，自动对焦经常对着背景对上了，必须能手点
        previewView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                focusAt(view, event.x, event.y)
            }
            true
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // 触控目标一律给足 48dp，并且**离屏幕最上方远一点**。
        //
        // 之前「切换镜头」放在右上角，用户点了没反应 —— 原因不是按钮小，
        // 而是屏幕顶部那条带子是系统下拉通知栏的手势区，点在那一带会被系统先吃掉。
        // 现在功能键全挪到快门两侧（相机 App 的常规布局），关闭按钮也往下让开一截。
        val top = OverlayUi.horizontal(this).apply {
            setPadding(dp(16f), dp(22f), dp(16f), dp(10f))
        }
        val close = roundButton("✕", 18f) { finish() }
        top.addView(close)
        root.addView(
            top,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP },
        )

        val bottom = OverlayUi.vertical(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(30f))
        }
        bottom.addView(
            OverlayUi.text(this, "点一下画面可以对焦 · 点快门拍下题目", 13f, 0xFFE5E7EB.toInt()).apply {
                setBackground(OverlayUi.rounded(0x66000000, dp(14f).toFloat()))
                setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            },
        )
        bottom.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(1, dp(20f))
        })

        val shutterSize = dp(74f)
        shutter = View(this).apply {
            background = OverlayUi.rounded(Color.WHITE, shutterSize / 2f, 0x66000000, dp(4f))
            isClickable = true
            setOnClickListener { takePicture() }
        }
        flipButton = roundButton("后置", 13f) { switchLens() }
        // 没有闪光灯的机型用 INVISIBLE 而不是 GONE：占位还在，快门才会一直居中
        torchButton = roundButton("闪光灯", 12f) { toggleTorch() }.apply { visibility = View.INVISIBLE }

        val controls = OverlayUi.horizontal(this).apply { gravity = Gravity.CENTER }
        controls.addView(torchButton)
        controls.addView(
            shutter,
            android.widget.LinearLayout.LayoutParams(shutterSize, shutterSize).apply {
                marginStart = dp(26f)
                marginEnd = dp(26f)
            },
        )
        controls.addView(flipButton)
        bottom.addView(controls)

        root.addView(
            bottom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )

        setContentView(root)
    }

    /** 半透明圆形按钮，触控区至少 48dp。 */
    private fun roundButton(label: String, textSize: Float, onClick: () -> Unit): TextView {
        val size = dp(48f)
        return OverlayUi.text(this, label, textSize, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(6f), 0)
            minWidth = size
            minimumWidth = size
            minHeight = size
            minimumHeight = size
            background = OverlayUi.rounded(0x8C000000.toInt(), size / 2f, 0x33FFFFFF, dp(1f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------------ 相机

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull()
            if (provider == null) {
                Toast.makeText(this, "相机初始化失败", Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                // 拍题不需要连拍速度，但要清晰度
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()

            val selector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, selector, preview, capture) }
                .onSuccess { bound ->
                    camera = bound
                    imageCapture = capture
                    cameraReady = true
                    switching = false
                    updateFlipLabel()
                    flipButton.isEnabled = true
                    // 有闪光灯才显示；没有就留个隐形占位，快门位置不动
                    torchButton.visibility = if (bound.cameraInfo.hasFlashUnit()) View.VISIBLE else View.INVISIBLE
                }
                .onFailure { error ->
                    Log.e(TAG, "相机启动失败（前置=$useFrontCamera）", error)
                    switching = false
                    flipButton.isEnabled = true
                    if (useFrontCamera) {
                        // 这台设备没有前置就自动退到后置，别让用户对着黑屏发呆
                        Toast.makeText(this, "这台设备没有可用的前置摄像头", Toast.LENGTH_SHORT).show()
                        useFrontCamera = false
                        startCamera()
                    } else {
                        Toast.makeText(this, "后置摄像头不可用", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchLens() {
        if (switching) return
        switching = true
        flipButton.isEnabled = false
        flipButton.text = "切换中"
        useFrontCamera = !useFrontCamera
        // 记住选择：下次进来还是这个镜头
        lifecycleScope.launch {
            runCatching {
                (application as QuizBankApp).settings.edit { it.copy(cameraFacingFront = useFrontCamera) }
            }
        }
        startCamera()
    }

    private fun updateFlipLabel() {
        flipButton.text = if (useFrontCamera) "前置" else "后置"
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        val on = cam.cameraInfo.torchState.value == TorchState.ON
        runCatching { cam.cameraControl.enableTorch(!on) }
    }

    /** 点按对焦 + 自动测光，对完在手指位置画个圈。 */
    private fun focusAt(view: View, x: Float, y: Float) {
        val cam = camera ?: return
        runCatching {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build(),
            )
        }.onFailure { Log.w(TAG, "对焦失败", it) }

        showFocusRing(view as ViewGroup, x, y)
    }

    private fun showFocusRing(root: ViewGroup, x: Float, y: Float) {
        val size = dp(72f)
        val ring = View(this).apply {
            background = OverlayUi.rounded(Color.TRANSPARENT, size / 2f, Color.WHITE, dp(2f))
        }
        val params = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (x - size / 2f).toInt()
            topMargin = (y - size / 2f).toInt()
        }
        root.addView(ring, params)
        ring.animate()
            .alpha(0f)
            .setStartDelay(450)
            .setDuration(350)
            .withEndAction { runCatching { root.removeView(ring) } }
            .start()
    }

    // ------------------------------------------------------------------ 拍照

    private fun takePicture() {
        val capture = imageCapture ?: return
        if (capturing || !cameraReady) return
        capturing = true
        shutter.isEnabled = false

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = runCatching {
                        val raw = image.toBitmap()
                        // 竖屏下 rotationDegrees 通常是 90/270，不转的话 OCR 会读出一堆乱码
                        val rotated = rotate(raw, image.imageInfo.rotationDegrees)
                        // 四千多万像素的原图没必要，OCR 也慢；长边压到 2000 足够看清题目
                        ImageUtils.scaleDown(rotated, OCR_MAX_SIDE)
                    }.getOrNull()
                    runCatching { image.close() }

                    if (bitmap == null) {
                        capturing = false
                        shutter.isEnabled = true
                        Toast.makeText(this@CameraCaptureActivity, "照片处理失败，再试一次", Toast.LENGTH_SHORT).show()
                        return
                    }
                    deliver(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    capturing = false
                    shutter.isEnabled = true
                    Toast.makeText(this@CameraCaptureActivity, "拍照失败，再试一次", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) source.recycle()
        return rotated
    }

    /** 拍到的照片交给和截屏完全一样的那条链路。 */
    private fun deliver(bitmap: Bitmap) {
        outcomePublished = true
        CaptureBus.pendingScreenshot = bitmap
        // 拍照这条路框出来的东西拿去搜题，不填收录面板 —— 框选界面那颗
        // 「接着上次识别」开关因此不该出现。上一轮收录留下的状态要在这里清掉，
        // 不然这个 Activity（singleInstance）复用过来时会带着上一次的开关。
        CaptureBus.fieldCapture = false
        // 同理，上一轮收录留下的「只框题干 / 只框选项」也不能带过来
        CaptureBus.cropFocus = CropFocus.AUTO
        runCatching {
            startActivity(
                Intent(this, CropOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure {
            Log.e(TAG, "打不开框选界面", it)
            Toast.makeText(this, "打不开框选界面", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        imageCapture = null

        // 用户直接退出拍照界面时（没拍、没进框选），补一个取消事件，
        // 否则悬浮球会一直停在「为跳转而隐藏」的状态，用户再也点不到它。
        if (!outcomePublished) CaptureBus.publishOutcome(CropOutcome.Cancelled)

        super.onDestroy()
    }

    private fun dp(value: Float): Int = OverlayUi.dp(this, value)

    private companion object {
        const val TAG = "CameraCapture"

        /** OCR 用的长边上限。再大只是白白拖慢识别。 */
        const val OCR_MAX_SIDE = 2000
    }
}
