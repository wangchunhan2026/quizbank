package com.daily.quizbank.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * content 既可能是字符串（纯文本），也可能是内容块数组（带图片）。
 * 用 JsonElement 承接，避免为一个字段写两套 DTO。
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement,
)

object Messages {

    fun system(text: String) = ChatMessage("system", JsonPrimitive(text))

    fun user(text: String) = ChatMessage("user", JsonPrimitive(text))

    fun assistant(text: String) = ChatMessage("assistant", JsonPrimitive(text))

    /**
     * 带图消息。官方限制：图片只能出现在 user 消息中，
     * system / assistant 携带图片会返回 400。
     */
    fun userWithImage(text: String, dataUrl: String, detail: String): ChatMessage {
        val blocks = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
                buildJsonObject {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JsonObject(
                            mapOf(
                                "url" to JsonPrimitive(dataUrl),
                                "detail" to JsonPrimitive(detail),
                            )
                        )
                    )
                },
            )
        )
        return ChatMessage("user", blocks)
    }
}

/**
 * 流式返回时默认不带用量统计，必须显式要求。
 * 官方用的是 OpenAI 兼容的 stream_options 字段。
 */
@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage? = null,
    val delta: ResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** 命中上下文缓存的输入 token，单价只有未命中的五十分之一。 */
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val cacheMissTokens: Int = 0,
)

@Serializable
data class ModelList(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(
    val id: String,
    val name: String? = null,
    @SerialName("object") val objectType: String? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
) {
    val supportsImage: Boolean
        get() = inputModalities?.any { it.equals("image", ignoreCase = true) } == true
}

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

/**
 * 流式返回的一小片增量。
 * reasoning 是思考模式下的思维链；usage 只在最后一个分片里出现一次（需主动开启）。
 */
data class ChatDelta(
    val content: String,
    val reasoning: String? = null,
    val usage: Usage? = null,
)

class ApiException(message: String, val httpCode: Int = 0) : Exception(message)
