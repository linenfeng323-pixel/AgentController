package com.ai.agentcontroller

/**
 * 把搜索栏里输入的关键词 / 网址解析成可访问的 AI 站点。
 *
 * 支持中文别名、英文别名、域名、完整 URL；找不到匹配时按“补全成 https://”处理。
 */
object AiSiteResolver {

    data class Site(val name: String, val emoji: String, val url: String, val keywords: List<String>)

    private val SITES = listOf(
        Site("豆包", "🤖", "https://www.doubao.com/chat", listOf("豆包", "doubao", "doubao.com")),
        Site("DeepSeek", "🐳", "https://chat.deepseek.com", listOf("deepseek", "深度求索")),
        Site("Kimi", "🌙", "https://kimi.moonshot.cn", listOf("kimi", "月之暗面", "moonshot")),
        Site("通义千问", "🧠", "https://tongyi.aliyun.com", listOf("通义", "通义千问", "qwen", "tongyi")),
        Site("文心一言", "🐦", "https://yiyan.baidu.com", listOf("文心", "文心一言", "yiyan", "ernie")),
        Site("智谱清言", "✨", "https://chatglm.cn", listOf("智谱", "清言", "chatglm", "glm")),
        Site("腾讯元宝", "🪙", "https://yuanbao.tencent.com/chat", listOf("元宝", "腾讯元宝", "yuanbao")),
        Site("ChatGPT", "💬", "https://chat.openai.com", listOf("chatgpt", "openai", "gpt")),
        Site("Claude", "🎭", "https://claude.ai", listOf("claude", "克劳德")),
        Site("Gemini", "♊", "https://gemini.google.com", listOf("gemini", "巴德", "bard")),
        Site("字节跳动 Coze", "🧩", "https://www.coze.cn", listOf("coze", "扣子")),
        Site("讯飞星火", "🔥", "https://xinghuo.xfyun.cn/desk", listOf("星火", "讯飞", "xfyun"))
    )

    val hotSites: List<Site> get() = SITES

    /**
     * 解析用户输入，返回 (站点名, 最终 URL)。
     */
    fun resolve(input: String): Pair<String, String> {
        val raw = input.trim()
        if (raw.isEmpty()) return Pair("", "")
        val lower = raw.lowercase()

        // 完整 URL
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return Pair(guessName(raw), raw)
        }

        // 域名形式 xxx.com / xxx.cn
        if (lower.contains(".com") || lower.contains(".cn") || lower.contains(".net") || lower.contains(".org") || lower.contains(".io")) {
            val withScheme = if (lower.startsWith("www.")) "https://$raw" else "https://$raw"
            return Pair(guessName(raw), withScheme)
        }

        // 关键词匹配
        for (site in SITES) {
            if (site.keywords.any { lower.contains(it) || raw.contains(it) }) {
                return Pair(site.name, site.url)
            }
        }

        // 兜底：当成搜索词拼接（这里直接作为域名）
        return Pair(raw, "https://$raw")
    }

    private fun guessName(url: String): String {
        val s = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val host = s.substringBefore('/')
        return host.substringBefore('.')
    }
}
