package com.daily.quizbank.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型服务商预设表。
 *
 * 这张表决定客户「换个模型 API」时少踩多少坑，所以钉两件事：
 * ① 表本身是干净的（id 不重复、地址和模型名不漏）；
 * ② 反推「当前是哪一家」要容忍手写的地址形态 —— 带不带协议、结尾斜杠、补了 /v1 或
 *    /chat/completions，都不该让界面上的芯片指错。
 */
class ModelProvidersTest {

    @Test
    fun 预设表的每一项都填全了() {
        val ids = ModelProviders.ALL.map { it.id }
        assertEquals("id 不许重复", ids.size, ids.distinct().size)
        for (p in ModelProviders.ALL) {
            assertTrue("名称不能为空: " + p.id, p.name.isNotBlank())
            assertTrue("说明不能为空: " + p.id, p.note.isNotBlank())
            if (p.id == ModelProviders.CUSTOM_ID) continue
            assertTrue("地址不能为空: " + p.id, p.baseUrl.isNotBlank())
            assertTrue("文本模型不能为空: " + p.id, p.chatModel.isNotBlank())
            assertTrue("视觉模型不能为空: " + p.id, p.visionModel.isNotBlank())
            assertTrue("地址要带协议头: " + p.id, p.baseUrl.startsWith("http"))
        }
    }

    @Test
    fun 官方那一档就是默认值() {
        val p = ModelProviders.DEEPSEEK
        assertEquals("deepseek", p.id)
        assertEquals("https://api.deepseek.com", p.baseUrl)
        assertEquals("deepseek-flash", p.chatModel)
        // 默认设置原样就能认出是它（AppSettings() 的默认值）
        assertEquals(p.id, ModelProviders.match("https://api.deepseek.com", "deepseek-flash"))
    }

    @Test
    fun 地址写法不同也认得出是哪一家() {
        val 各种写法 = listOf(
            "https://api.deepseek.com",
            "https://api.deepseek.com/",
            "api.deepseek.com",
            "https://api.deepseek.com/v1",
            "https://api.deepseek.com/chat/completions",
            "  https://api.deepseek.com/v1/  ",
        )
        for (url in 各种写法) {
            assertEquals("写法「" + url + "」应认得", "deepseek", ModelProviders.match(url, "deepseek-flash"))
        }
        assertEquals("zhipu", ModelProviders.match("https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash"))
        assertEquals("ollama", ModelProviders.match("http://192.168.1.100:11434/v1", "qwen2.5:7b"))
    }

    @Test
    fun 自己填的地址算自定义() {
        assertEquals(ModelProviders.CUSTOM_ID, ModelProviders.match("", "whatever"))
        assertEquals(ModelProviders.CUSTOM_ID, ModelProviders.match("https://my.gateway.example.com/v1", "my-model"))
        // 公司网关常见形态：带端口、带路径
        assertEquals(ModelProviders.CUSTOM_ID, ModelProviders.match("http://10.0.0.8:8000/openai/v1", "gpt-4o-mini"))
    }

    @Test
    fun 地址对上了但模型名明显是别家的也算自定义() {
        // 用户把 DeepSeek 的地址留着、模型名改成 qwen：这时候界面不能还高亮「DeepSeek 官方」
        assertEquals(
            ModelProviders.CUSTOM_ID,
            ModelProviders.match("https://api.deepseek.com", "qwen-plus"),
        )
        // 同族名的变体要认得（deepseek-flash → deepseek-v4-pro 仍算这一家）
        assertEquals(
            "deepseek",
            ModelProviders.match("https://api.deepseek.com", "deepseek-v4-pro"),
        )
    }

    @Test
    fun 地址归一化容忍各种写法() {
        assertEquals("api.deepseek.com", ModelProviders.normalizeUrl("https://api.deepseek.com/"))
        assertEquals("api.deepseek.com", ModelProviders.normalizeUrl("http://api.deepseek.com/v1/"))
        assertEquals("api.deepseek.com", ModelProviders.normalizeUrl("api.deepseek.com/chat/completions"))
        assertEquals("", ModelProviders.normalizeUrl("   "))
    }
}
