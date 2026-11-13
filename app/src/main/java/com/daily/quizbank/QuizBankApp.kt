package com.daily.quizbank

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.daily.quizbank.data.ai.DeepSeekClient
import com.daily.quizbank.data.db.AppDatabase
import com.daily.quizbank.data.import.ImportService
import com.daily.quizbank.data.ocr.MlKitOcrEngine
import com.daily.quizbank.data.prefs.SettingsStore
import com.daily.quizbank.data.repo.QuestionRepository

class QuizBankApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: QuestionRepository by lazy { QuestionRepository(database) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val deepSeek: DeepSeekClient by lazy { DeepSeekClient() }
    val ocr: MlKitOcrEngine by lazy { MlKitOcrEngine() }
    val importService: ImportService by lazy { ImportService(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // 悬浮球常驻用 IMPORTANCE_MIN：不响不震不弹，只在通知栏留一条，尽量不打扰
        val floating = NotificationChannel(
            CHANNEL_FLOATING,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.fgs_channel_desc)
            setShowBadge(false)
        }
        val capture = NotificationChannel(
            CHANNEL_CAPTURE,
            getString(R.string.fgs_capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.fgs_capture_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(floating, capture))
    }

    companion object {
        const val CHANNEL_FLOATING = "floating_assistant"
        const val CHANNEL_CAPTURE = "screen_capture"

        lateinit var instance: QuizBankApp
            private set
    }
}
