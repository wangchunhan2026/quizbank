package com.daily.quizbank.data.ai

import com.daily.quizbank.data.prefs.AppSettings
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek 开放平台客户端（OpenAI 兼容协议）。
 *
 * 关键事实（来自官方文档，改动前请先核对）：
 *  - 文本与图像理解用同一个模型 deepseek-flash，旧名 deepseek-v4-flash-vision-exp 已下线。
 *  - 图片只能出现在 user 消息里，system / assistant 带图会 400。
 *  - 图片走 content 块数组 + image_url，data URL 内联；单图 base64 上限 32 MiB，请求体 48 MiB。
 *  - 服务端会把每张图缩放到约 1300×1300，单图折算 token 上限 1024。
 */
class DeepSeekClient {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // 读超时给得很宽：思考模式下模型可能憋很久才开始吐字
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------ 模型列表

    suspend fun listModels(settings: AppSettings): List<ModelInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(settings.baseUrl, "/models"))
            .header("Authorization", "Bearer " + settings.apiKey.trim())
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(describeError(body, response.code), response.code)
            }
            json.decodeFromString<ModelList>(body).data
        }
    }

    // ------------------------------------------------------------------ 流式对话

    fun chatStream(
        settings: AppSettings,
        messages: List<ChatMessage>,
        model: String,
    ): Flow<ChatDelta> = flow {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                streamOptions = StreamOptions(includeUsage = true),
            ),
        )

        val request = Request.Builder()
            .url(endpoint(settings.baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer " + settings.apiKey.trim())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        val call = http.newCall(request)
        // 协程被取消时要主动掐断底层连接，否则 OkHttp 的阻塞读会一直挂着
        val cancellation = coroutineContext.job.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(describeError(response.body?.string().orEmpty(), response.code), response.code)
                }
                val source = response.body?.source() ?: throw ApiException("响应体为空")

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    val chunk = runCatching { json.decodeFromString<ChatResponse>(data) }.getOrNull() ?: continue

                    // 用量分片通常没有 choices，只有一个 usage
                    chunk.usage?.let { emit(ChatDelta("", null, it)) }

                    val delta = chunk.choices.firstOrNull()?.delta ?: continue
                    val text = delta.content.orEmpty()
                    val thinking = delta.reasoningContent.orEmpty()
                    if (text.isNotEmpty() || thinking.isNotEmpty()) {
                        emit(ChatDelta(text, thinking.ifEmpty { null }))
                    }
                }
            }
        } finally {
            cancellation.dispose()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ 一次性对话

    suspend fun complete(
        settings: AppSettings,
        messages: List<ChatMessage>,
        model: String,
    ): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages,
                stream = false,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
            ),
        )

        val request = Request.Builder()
            .url(endpoint(settings.baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer " + settings.apiKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(describeError(body, response.code), response.code)
            }
            val parsed = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
            parsed?.choices?.firstOrNull()?.message?.content.orEmpty()
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 规整 base_url，容忍用户把地址填成任意层级：
     * "api.deepseek.com"、"https://api.deepseek.com/"、"https://api.deepseek.com/v1" 都能用。
     */
    private fun endpoint(baseUrl: String, path: String): String {
        var base = baseUrl.trim().ifEmpty { AppSettings.DEFAULT_BASE_URL }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base
        }
        base = base.trimEnd('/')
        for (suffix in listOf("/chat/completions", "/v1/chat/completions")) {
            if (base.endsWith(suffix)) {
                base = base.removeSuffix(suffix)
                break
            }
        }
        return base + path
    }

    private fun describeError(body: String, code: Int): String {
        if (body.isBlank()) {
            return when (code) {
                401 -> "API Key 无效或已失效（401），请到设置里重新填写"
                402 -> "账户余额不足（402），请到 DeepSeek 平台充值"
                429 -> "请求过于频繁（429），稍后再试"
                else -> "请求失败（HTTP " + code + "）"
            }
        }
        val parsed = runCatching { json.decodeFromString<ApiErrorEnvelope>(body) }.getOrNull()
        val message = parsed?.error?.message
        return if (!message.isNullOrBlank()) message else body.take(400)
    }
}
