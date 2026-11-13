package com.daily.quizbank.data.ai

/**
 * 一个内置的「模型服务商」预设。
 *
 * 为什么要这张表：本应用的 AI 调用本来就是 OpenAI 兼容协议（见 [DeepSeekClient]），
 * 换个地址和模型名就能接别家 —— 但让客户自己去查「哪家的 base_url 是哪个形式、视觉模型叫什么」
 * 是实实在在的门槛。选一个预设，地址和模型名就填好了，剩下只要粘自己的 Key。
 *
 * [id] 会跟着用户存进设置，**别改已有的值**（改了等于把老用户的选择丢掉）。
 */
data class ModelProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** 文本模型。 */
    val chatModel: String,
    /** 视觉模型。接口不支持看图时和 [chatModel] 一样，靠「识别路线 = 纯文字」兜住。 */
    val visionModel: String,
    /** 选中时摆在界面上的一句话：怎么填、有什么坑。 */
    val note: String,
)

/**
 * 常见服务商的预设表。
 *
 * 选的都是**当下仍在提供的 OpenAI 兼容入口**；地址和模型名会随各家调整，所以界面上
 * 那几个输入框始终可改 —— 预设只是省去查文档，不是锁死。
 *
 * 本地部署（Ollama / llama.cpp / vLLM）也在这张表里：它们的 base_url 是
 * `http://<电脑的局域网 IP>:端口/v1`，手机上**不能用 127.0.0.1**（那是手机自己）。
 */
object ModelProviders {

    /** 用户自己填地址时算哪一档。 */
    const val CUSTOM_ID = "custom"

    val DEEPSEEK = ModelProvider(
        id = "deepseek",
        name = "DeepSeek 官方",
        baseUrl = "https://api.deepseek.com",
        chatModel = "deepseek-flash",
        visionModel = "deepseek-flash",
        note = "官方口径：deepseek-flash 一个模型同时管文本和看图；deepseek-v4-pro 不吃图片。",
    )

    val ALL: List<ModelProvider> = listOf(
        DEEPSEEK,
        ModelProvider(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            chatModel = "glm-4-flash",
            visionModel = "glm-4v-flash",
            note = "glm-4-flash 有免费档；看图要换成 glm-4v-flash（能不能用看账号权限）。",
        ),
        ModelProvider(
            id = "dashscope",
            name = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            chatModel = "qwen-plus",
            visionModel = "qwen-vl-plus",
            note = "阿里云百炼的 OpenAI 兼容入口，Key 在控制台「API-KEY 管理」里建。",
        ),
        ModelProvider(
            id = "moonshot",
            name = "Kimi（月之暗面）",
            baseUrl = "https://api.moonshot.cn/v1",
            chatModel = "moonshot-v1-32k",
            visionModel = "moonshot-v1-32k-vision-preview",
            note = "长题干友好（32k 上下文）；看图要用带 vision 的那一档。",
        ),
        ModelProvider(
            id = "siliconflow",
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            chatModel = "Qwen/Qwen2.5-7B-Instruct",
            visionModel = "Qwen/Qwen2-VL-7B-Instruct",
            note = "一个 Key 能调很多开源模型，模型名带斜杠（Qwen/…、deepseek-ai/…）。",
        ),
        ModelProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            chatModel = "gpt-4o-mini",
            visionModel = "gpt-4o-mini",
            note = "国内直连通常要走代理；gpt-4o-mini 本身就能看图。",
        ),
        ModelProvider(
            id = "ollama",
            name = "本地 Ollama",
            baseUrl = "http://192.168.1.100:11434/v1",
            chatModel = "qwen2.5:7b",
            visionModel = "qwen2.5vl:7b",
            note = "把 192.168.1.100 换成你电脑的局域网 IP（手机上的 127.0.0.1 指的是手机自己）；Ollama 的 Key 随便填一个非空值。",
        ),
        ModelProvider(
            id = CUSTOM_ID,
            name = "自定义",
            baseUrl = "",
            chatModel = "",
            visionModel = "",
            note = "任何 OpenAI 兼容接口：填地址（到 /v1 或公司网关）、Key、模型名；只吃文字的服务商请把「拍题识别 → 识别路线」设成「纯文字」。",
        ),
    )

    fun byId(id: String): ModelProvider? = ALL.firstOrNull { it.id == id }

    /**
     * 按「地址 + 文本模型」反推是哪一家；都不像就返回 [CUSTOM_ID]。
     *
     * 反推而不是存 id：地址和模型名本来就允许改，用户手改过之后存的 id 就不作数了 ——
     * 每次都按当下的值算，界面上那颗芯片才不会指错。
     */
    fun match(baseUrl: String, chatModel: String): String {
        val url = normalizeUrl(baseUrl)
        if (url.isEmpty()) return CUSTOM_ID
        val hit = ALL.firstOrNull { it.id != CUSTOM_ID && normalizeUrl(it.baseUrl) == url }
        if (hit == null) return CUSTOM_ID
        val model = chatModel.trim().lowercase()
        if (model.isEmpty()) return hit.id
        // 地址对上了、模型名却明显是别家的（DeepSeek 的地址填 qwen 的模型）也算自定义：
        // 这时候「选中的那家」是错的，别骗用户
        val sameFamily = model == hit.chatModel.lowercase() ||
            model == hit.visionModel.lowercase() ||
            model.startsWith(hit.chatModel.lowercase().substringBefore("-"))
        return if (sameFamily) hit.id else CUSTOM_ID
    }

    /** 比较地址时容忍：没有协议头、结尾多斜杠、带了 /v1 或 /chat/completions。 */
    fun normalizeUrl(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.trimEnd('/')
        for (suffix in listOf("/chat/completions", "/v1")) {
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trimEnd('/')
        }
        return s
    }
}
