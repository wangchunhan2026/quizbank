package com.daily.quizbank.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "quizbank_settings")

/**
 * 旧版本写进 DataStore 的默认提示词。
 *
 * 这里有个坑：`p[Keys.systemPrompt] ?: Prompts.DEFAULT_SYSTEM` 看着像「没设过就用默认」，
 * 但用户只要动过任何一项设置，[SettingsStore.edit] 就会把**当时那份提示词连同其余字段一起落盘**。
 * 从那以后它就不再是「没设置过」，而是「设置成了旧默认值」—— 光改 Prompts 里的常量
 * 是改不动这些用户的，读到的永远是存下来的那一份。
 *
 * 所以把旧默认值显式列出来，撞上就当成没设置过，用新的。
 */
private val LEGACY_SYSTEM_PROMPTS = setOf(
    """你是一位耐心、严谨的助教。用户会把题目（可能是图片或文字）发给你。
请遵守：
1. 先给结论，再给推理过程；结论单独一行放在最前面。
2. 选择题请明确写出正确选项字母；判断题写「正确」或「错误」。
3. 涉及计算时把关键步骤写清楚，不要跳步。
4. 如果不确定或题目信息缺失，直接说明缺少什么，不要编造。
5. 使用简体中文，语气简洁，不要客套话。""",
)

private val LEGACY_ANSWER_PROMPTS = setOf("""请解答下面这道题。""")

class SettingsStore(private val context: Context) {

    private object Keys {
        val apiKey = stringPreferencesKey("api_key")
        val baseUrl = stringPreferencesKey("base_url")
        val chatModel = stringPreferencesKey("chat_model")
        val visionModel = stringPreferencesKey("vision_model")
        val recognizer = stringPreferencesKey("recognizer")
        val temperature = doublePreferencesKey("temperature")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val answerPrompt = stringPreferencesKey("answer_prompt")
        val bubbleSizeDp = intPreferencesKey("bubble_size_dp")
        val bubbleAlpha = intPreferencesKey("bubble_alpha")

        /** 按钮条（继续收录 / 搜题 / … 那一条）的不透明度，和悬浮球分开存。 */
        val actionsAlpha = intPreferencesKey("actions_alpha")

        /** 结果卡片 / 搜题条的不透明度（两张是同一个窗口，见 AppSettings.panelAlphaPercent）。 */
        val panelAlpha = intPreferencesKey("panel_alpha")

        /**
         * 「旧默认不透明度已经改过一次」的标记。
         *
         * 没有它就分不清存着的 85 是「从来没设置过（写盘时顺手带上的旧默认）」还是
         * 「用户自己拖到 85 的」—— 前者要改成新默认，后者必须尊重。有了它只改一次。
         */
        val bubbleAlphaMigrated = booleanPreferencesKey("bubble_alpha_migrated")
        val autoCapture = booleanPreferencesKey("auto_capture")
        val imageDetail = stringPreferencesKey("image_detail")
        val cameraFacingFront = booleanPreferencesKey("camera_facing_front")
        val freeBaseUrl = stringPreferencesKey("free_base_url")
        val freeApiKey = stringPreferencesKey("free_api_key")
        val freeModel = stringPreferencesKey("free_model")
    }

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            apiKey = p[Keys.apiKey].orEmpty(),
            baseUrl = p[Keys.baseUrl] ?: AppSettings.DEFAULT_BASE_URL,
            chatModel = p[Keys.chatModel] ?: AppSettings.DEFAULT_MODEL,
            visionModel = p[Keys.visionModel] ?: AppSettings.DEFAULT_MODEL,
            recognizer = Recognizer.fromName(p[Keys.recognizer]),
            temperature = p[Keys.temperature] ?: 0.3,
            maxTokens = p[Keys.maxTokens] ?: 2048,
            systemPrompt = p[Keys.systemPrompt]
                ?.takeIf { it.isNotBlank() && it !in LEGACY_SYSTEM_PROMPTS }
                ?: Prompts.DEFAULT_SYSTEM,
            answerPrompt = p[Keys.answerPrompt]
                ?.takeIf { it.isNotBlank() && it !in LEGACY_ANSWER_PROMPTS }
                ?: Prompts.DEFAULT_ANSWER,
            bubbleSizeDp = p[Keys.bubbleSizeDp] ?: 52,
            bubbleAlphaPercent = readBubbleAlpha(p),
            actionsAlphaPercent = p[Keys.actionsAlpha] ?: AppSettings.DEFAULT_ACTIONS_ALPHA_PERCENT,
            panelAlphaPercent = p[Keys.panelAlpha] ?: AppSettings.DEFAULT_PANEL_ALPHA_PERCENT,
            autoCapture = p[Keys.autoCapture] ?: true,
            imageDetail = p[Keys.imageDetail] ?: "high",
            cameraFacingFront = p[Keys.cameraFacingFront] ?: false,
            freeBaseUrl = p[Keys.freeBaseUrl] ?: AppSettings.DEFAULT_FREE_BASE_URL,
            freeApiKey = p[Keys.freeApiKey].orEmpty(),
            freeModel = p[Keys.freeModel] ?: AppSettings.DEFAULT_FREE_MODEL,
        )
    }

    /**
     * 悬浮球不透明度：把「旧默认值」当成没设置过。
     *
     * 用户报「悬浮球别那么明显」（2026-10-09），默认值从 85 降到 45。
     * 但他只要动过任何一项设置，85 就已经作为「他选的值」写进 DataStore 了，
     * 光改常量改不动他。所以：没迁移过 + 存的就是旧默认 → 用新默认；迁移过就照存着的走。
     */
    private fun readBubbleAlpha(p: Preferences): Int {
        val stored = p[Keys.bubbleAlpha]
            ?: return AppSettings.DEFAULT_BUBBLE_ALPHA_PERCENT
        val migrated = p[Keys.bubbleAlphaMigrated] == true
        if (!migrated && stored == AppSettings.LEGACY_BUBBLE_ALPHA_PERCENT) {
            return AppSettings.DEFAULT_BUBBLE_ALPHA_PERCENT
        }
        return stored
    }

    suspend fun snapshot(): AppSettings = flow.first()

    /** 读改写一次性落盘。字段少，全量写入比逐个 diff 更不容易漏字段。 */
    suspend fun edit(transform: (AppSettings) -> AppSettings) {
        val next = transform(snapshot())
        context.settingsDataStore.edit { p ->
            p[Keys.apiKey] = next.apiKey
            p[Keys.baseUrl] = next.baseUrl
            p[Keys.chatModel] = next.chatModel
            p[Keys.visionModel] = next.visionModel
            p[Keys.recognizer] = next.recognizer.name
            p[Keys.temperature] = next.temperature
            p[Keys.maxTokens] = next.maxTokens
            p[Keys.systemPrompt] = next.systemPrompt
            p[Keys.answerPrompt] = next.answerPrompt
            p[Keys.bubbleSizeDp] = next.bubbleSizeDp
            p[Keys.bubbleAlpha] = next.bubbleAlphaPercent
            p[Keys.actionsAlpha] = next.actionsAlphaPercent
            p[Keys.panelAlpha] = next.panelAlphaPercent
            // 只要落过一次盘，就说明用户动过设置、这个值是他自己的了，以后不再迁移
            p[Keys.bubbleAlphaMigrated] = true
            p[Keys.autoCapture] = next.autoCapture
            p[Keys.imageDetail] = next.imageDetail
            p[Keys.cameraFacingFront] = next.cameraFacingFront
            p[Keys.freeBaseUrl] = next.freeBaseUrl
            p[Keys.freeApiKey] = next.freeApiKey
            p[Keys.freeModel] = next.freeModel
        }
    }
}
