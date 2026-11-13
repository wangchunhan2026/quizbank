package com.daily.quizbank.data.prefs

/** 截屏后的识别路线。 */
enum class Recognizer {
    /** 有图就走视觉模型，失败时回落到本地 OCR。 */
    AUTO,

    /** 只走 DeepSeek 视觉模型。 */
    VISION,

    /** 只走本地 ML Kit OCR，完全离线、不消耗 token。 */
    OCR;

    companion object {
        fun fromName(name: String?): Recognizer =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val chatModel: String = DEFAULT_MODEL,
    val visionModel: String = DEFAULT_MODEL,
    val recognizer: Recognizer = Recognizer.AUTO,
    val temperature: Double = 0.3,
    val maxTokens: Int = 2048,
    val systemPrompt: String = Prompts.DEFAULT_SYSTEM,
    val answerPrompt: String = Prompts.DEFAULT_ANSWER,
    val bubbleSizeDp: Int = 52,
    val bubbleAlphaPercent: Int = DEFAULT_BUBBLE_ALPHA_PERCENT,

    /**
     * 按钮条（继续收录 / 搜题 / 拍题 / 拍照 / 关闭 那一条）的不透明度（%）。
     *
     * 默认 100 = 和以前一模一样。用户 2026-10-13 要的是「能调」而不是「改默认」：
     * 那条横着占 250~340dp，压在题目或解析上时挡得比悬浮球厉害得多，
     * 但他要先能拖、能看清，自己决定调到多淡。
     */
    val actionsAlphaPercent: Int = DEFAULT_ACTIONS_ALPHA_PERCENT,

    /**
     * 结果卡片（「题库里没有这道题」那张）和搜题条的不透明度（%）。
     *
     * 用户 2026-10-13 追加要的：那张卡片和搜题时顶上的输入框同样压在题目/解析上，
     * 而且它比按钮条大得多 —— 挡字的时候最碍事的就是它。
     *
     * 两者**是同一个窗口**（搜题模式下卡片顶上多出来的那条输入框就是它的一部分，
     * 收起时整张卡片只剩那一条），所以一个值就够，不用再拆一个设置。
     * 默认 100 = 和以前一模一样。
     */
    val panelAlphaPercent: Int = DEFAULT_PANEL_ALPHA_PERCENT,
    val autoCapture: Boolean = true,
    /** 视觉模型传图时的 detail 档位。low 会把图缩到 512×512，更快更省 token。 */
    val imageDetail: String = "high",
    /**
     * 拍照拍题默认用前置还是后置。
     *
     * 默认后置：拍纸、拍远处的屏幕都靠它；前置一进去就是自己的脸，很别扭。
     * 用户点「切换镜头」之后会把选择记在这里，下次进来还用上次那个。
     */
    val cameraFacingFront: Boolean = false,

    /**
     * 「免费通道」——一条独立的 OpenAI 兼容接口。
     *
     * 存在的理由：只想要个解释的题、或者本来就简单的题，用不着 DeepSeek 那个档次的模型。
     * 国内几家的 flash / lite 档都有免费额度，协议同样是 OpenAI 那套，
     * 所以这里只多存一组「地址 + Key + 模型名」，请求代码一行都不用改。
     *
     * 默认指向智谱 GLM 的免费档；换别家（硅基流动、百炼……）改这两项即可。
     * 免费额度政策各家变动频繁，这里只负责「填得进去」，不保证某家一直免费。
     */
    val freeBaseUrl: String = DEFAULT_FREE_BASE_URL,
    val freeApiKey: String = "",
    val freeModel: String = DEFAULT_FREE_MODEL,
) {
    /** DeepSeek（付费）通道能用。 */
    val configured: Boolean get() = apiKey.isNotBlank()

    /** 免费通道能用。没配 Key 时结果面板上不出现「免费解析」按钮。 */
    val freeConfigured: Boolean get() = freeApiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /**
         * 官方当前唯一支持图像理解的模型是 deepseek-flash。
         * 旧的 deepseek-v4-flash-vision-exp 已下线，请求会被 Flash 承接，所以这里直接用新名字。
         */
        const val DEFAULT_MODEL = "deepseek-flash"

        /** 免费通道的默认端点：智谱 GLM 的 OpenAI 兼容入口。 */
        const val DEFAULT_FREE_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

        /** 免费通道的默认模型。glm-4-flash 是它的免费档。 */
        const val DEFAULT_FREE_MODEL = "glm-4-flash"

        /**
         * 悬浮球的不透明度（%）。
         *
         * 从 85 降到 45：用户报「悬浮球别那么明显、最好半透明」（2026-10-09）。
         * 85% 那颗球在刷题界面上就是个实心圆点，一直杵在那里提醒他开着拍题；
         * 45% 还看得清、也点得着，但不再抢注意力。嫌太淡的人可以在设置里拖回去。
         */
        const val DEFAULT_BUBBLE_ALPHA_PERCENT = 45

        /**
         * 按钮条的不透明度（%）默认值：100 = 原样。
         *
         * 和悬浮球那个不一样，这里**不动默认**：悬浮球是「一直杵在那儿」，用户明确要求别那么明显；
         * 按钮条是点开才出现的，他要的是「自己能调」。想更淡的人在设置里拖一下就行。
         */
        const val DEFAULT_ACTIONS_ALPHA_PERCENT = 100

        /** 结果卡片 / 搜题条的不透明度（%）默认值：100 = 原样，理由同按钮条。 */
        const val DEFAULT_PANEL_ALPHA_PERCENT = 100

        /**
         * 旧版本的默认不透明度。
         *
         * 和旧默认提示词一个道理：用户只要动过任何一项设置，这个值就被**当成他选的**写进了
         * DataStore，光改上面那个常量对他一点用都没有。所以存着的 85 按「没设置过」处理
         * **一次**（靠 SettingsStore 里的迁移标记保证只走一次）。
         */
        const val LEGACY_BUBBLE_ALPHA_PERCENT = 85
    }
}
