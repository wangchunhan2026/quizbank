package com.daily.quizbank.ui.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.daily.quizbank.service.CaptureBus
import com.daily.quizbank.service.CropOutcome
import com.daily.quizbank.service.FloatingWindowService
import com.daily.quizbank.service.ScreenCaptureService

/**
 * 只为弹一次系统截屏授权框而存在的透明 Activity。
 *
 * MediaProjection 的授权只能由 Activity 发起（服务没有这个能力），
 * 所以悬浮球点「拍题」时先跳到这个看不见的 Activity，拿到授权数据后立刻交给截屏服务。
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 现在才是隐藏悬浮球的正确时机：授权已经拿到，下一步就是抓帧，
            // 球要是还在画面上就会被一起截进去。
            FloatingWindowService.setOverlayVisibleNow(false)
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
        } else {
            CaptureBus.publishStatus("没有获得截屏授权，无法拍题")
            CaptureBus.publishOutcome(CropOutcome.Cancelled)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让悬浮窗服务知道授权页确实起来了；没起来说明被系统的后台启动限制拦了
        started = true

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            CaptureBus.publishStatus("这台设备不支持屏幕截取")
            CaptureBus.publishOutcome(CropOutcome.Cancelled)
            finish()
            return
        }
        // Android 14 起系统默认可能只共享「一个应用」，而我们的做法是
        // 截全屏再让用户框选，所以必须显式要求整个默认显示器。
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }

        runCatching { launcher.launch(captureIntent) }
            .onFailure {
                CaptureBus.publishStatus("无法发起截屏授权")
                CaptureBus.publishOutcome(CropOutcome.Cancelled)
                finish()
            }
    }

    companion object {
        /** 悬浮窗服务用它确认授权页是否真的被拉起来了。 */
        @Volatile
        var started: Boolean = false
            private set

        fun reset() {
            started = false
        }
    }
}
