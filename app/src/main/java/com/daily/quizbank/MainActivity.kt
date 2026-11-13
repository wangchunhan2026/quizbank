package com.daily.quizbank

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daily.quizbank.service.FloatingWindowService
import com.daily.quizbank.ui.AppNav
import com.daily.quizbank.ui.Routes
import com.daily.quizbank.ui.theme.QuizBankTheme

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var floatingRunning by mutableStateOf(false)
    private var startFloatingAfterGrant = false

    /** 从「用其他应用打开 / 分享」进来的文件（微信、QQ、文件管理器都走这条路）。 */
    private var incomingFile by mutableStateOf<Uri?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 前台服务通知在 13+ 需要运行时权限，没有它悬浮球会随服务一起被系统干掉
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        overlayGranted = Settings.canDrawOverlays(this)
        floatingRunning = FloatingWindowService.running

        incomingFile = extractIncomingFile(intent)

        setContent {
            QuizBankTheme {
                AppNav(
                    overlayGranted = overlayGranted,
                    floatingRunning = floatingRunning,
                    onToggleFloating = { wanted -> setFloating(wanted) },
                    initialRoute = Routes.BANKS,
                    incomingFileUri = incomingFile,
                    onIncomingFileHandled = { incomingFile = null },
                )
            }
        }
    }

    /** launchMode 是 singleTask，所以应用已在运行时再次打开文件会走这里，而不是 onCreate。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingFile = extractIncomingFile(intent)
    }

    private fun extractIncomingFile(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        overlayGranted = granted
        floatingRunning = FloatingWindowService.running

        // 用户从系统授权页返回时，如果刚才就是因为要开悬浮球才去授权的，这里自动补上
        if (granted && startFloatingAfterGrant) {
            startFloatingAfterGrant = false
            FloatingWindowService.start(this)
            floatingRunning = true
        }
    }

    private fun setFloating(wanted: Boolean) {
        if (!wanted) {
            FloatingWindowService.stop(this)
            floatingRunning = false
            return
        }
        if (Settings.canDrawOverlays(this)) {
            FloatingWindowService.start(this)
            floatingRunning = true
        } else {
            startFloatingAfterGrant = true
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName),
                ),
            )
        }
    }
}
