package com.ai.agentcontroller

import org.json.JSONObject

/**
 * 自然语言 → AgentCommand 映射器（AI 聊天框直接输入自然语言时用）。
 *
 * 规则库覆盖 agent-toolbox 暴露的全部能力，输入可中文自然语言，
 * 也可使用 "动作名 参数" 形式；未命中时再走 AI 回复流程。
 */
object NaturalLanguageResolver {

    data class Resolved(
        val commands: List<AgentCommand> = emptyList(),
        val planText: String? = null,
        val mcpTool: String? = null,
        val mcpArgs: JSONObject? = null,
        val githubOp: String? = null   // trigger_build / watch / auto_fix
    )

    fun resolve(text: String): Resolved {
        val t = text.trim()

        // ===== GitHub 操作 =====
        when {
            t.matches(Regex("^(触发|开始|启动)\\s*(编译|构建|CI)")) -> return Resolved(githubOp = "trigger_build")
            t.matches(Regex("^(查看|监控|检查)\\s*(编译|构建|工作流|action)")) -> return Resolved(githubOp = "watch")
            t.matches(Regex("^(自动|智能|一键)\\s*(修复|编译)")) -> return Resolved(githubOp = "auto_fix")
            t.matches(Regex("^上传(代码|到.*github)")) -> return Resolved(githubOp = "push")
        }

        // ===== 媒体 & 音量 =====
        when {
            t == "播放" || t == "暂停" || t == "播放/暂停" ->
                return Resolved(listOf(AgentCommand("media_play")))
            t == "下一首" || t == "下一曲" ->
                return Resolved(listOf(AgentCommand("media_next")))
            t == "上一首" || t == "上一曲" ->
                return Resolved(listOf(AgentCommand("media_prev")))
            t == "停止播放" ->
                return Resolved(listOf(AgentCommand("media_stop")))
            t == "音量加" || t == "音量+" || t == "加音量" ->
                return Resolved(listOf(AgentCommand("volume_up")))
            t == "音量减" || t == "音量-" || t == "减音量" ->
                return Resolved(listOf(AgentCommand("volume_down")))
        }
        Regex("^(音量|调音量到)\\s*(\\d+)\\s*$").matchEntire(t)?.let { m ->
            return Resolved(listOf(AgentCommand("volume_set", amount = m.groupValues[2].toInt())))
        }

        // ===== 系统开关 =====
        when {
            t.contains("WiFi") || t.contains("wifi") || t.contains("无线网") ->
                return Resolved(listOf(AgentCommand("set_wifi", target = (!(t.contains("关") || t.contains("关") || t.contains("禁用"))).toString())))
            t.contains("蓝牙") || t.contains("蓝芽") ->
                return Resolved(listOf(AgentCommand("set_bt", target = (!(t.contains("关") || t.contains("禁用"))).toString())))
            t.contains("定位") || t.contains("GPS") ->
                return Resolved(listOf(AgentCommand("set_location", target = (!(t.contains("关") || t.contains("禁用"))).toString())))
            t.contains("飞行模式") ->
                return Resolved(listOf(AgentCommand("set_airplane", target = ((t.contains("开") || t.contains("打开") || t.contains("启用"))).toString())))
            t.contains("自动旋转") || t.contains("屏幕旋转") ->
                return Resolved(listOf(AgentCommand("set_rotate", target = (!(t.contains("关") || t.contains("锁定"))).toString())))
            t.contains("深色模式") || t.contains("夜间模式") ->
                return Resolved(listOf(AgentCommand("set_dark", target = ((t.contains("开") || t.contains("打开") || t.contains("启用"))).toString())))
            t.contains("勿扰") || t.contains("免打扰") ->
                return Resolved(listOf(AgentCommand("set_dnd", target = ((t.contains("开") || t.contains("打开") || t.contains("启用"))).toString())))
        }

        // ===== 闹钟 / 计时器 =====
        Regex("(设置|新建|定|创建)\\s*闹钟\\s*(\\d{1,2})[:：](\\d{1,2})(\\s+.*)?").find(t)?.let { m ->
            val h = m.groupValues[2].toInt(); val mm = m.groupValues[3].toInt()
            val label = m.groupValues[4].trim()
            val cmds = mutableListOf(AgentCommand("create_alarm", target = String.format("%02d:%02d", h, mm), text = label))
            return Resolved(cmds)
        }
        Regex("(倒计时|计时器|定时)\\s*(\\d+)\\s*(秒|分钟|分)?").find(t)?.let { m ->
            var s = m.groupValues[2].toInt()
            val unit = m.groupValues[3]
            if (unit == "分钟" || unit == "分") s *= 60
            return Resolved(listOf(AgentCommand("create_timer", target = s.toString(), text = t)))
        }

        // ===== GM 内存修改 =====
        if (t == "root 状态" || t == "root 权限")
            return Resolved(listOf(AgentCommand("shell", target = "id")))
        if (t.contains("进程列表"))
            return Resolved(mcpTool = "gm_process_list", mcpArgs = JSONObject())
        Regex("(读取内存|memory read)\\s+(0x[0-9a-fA-F]+|\\d+)\\s*(pid=)?(\\d+)?").find(t)?.let { m ->
            return Resolved(mcpTool = "gm_memory_read", mcpArgs = JSONObject()
                .put("address", m.groupValues[1])
                .put("pid", m.groupValues[3].ifBlank { "0" }))
        }
        Regex("(写入内存|memory write)\\s+(0x[0-9a-fA-F]+|\\d+)\\s*=\\s*(\\S+)").find(t)?.let { m ->
            return Resolved(mcpTool = "gm_memory_write", mcpArgs = JSONObject()
                .put("address", m.groupValues[1]).put("value", m.groupValues[2]))
        }
        Regex("(内存搜索|memory search)\\s*(.+?)(\\s+pid=(\\d+))?$").find(t)?.let { m ->
            return Resolved(mcpTool = "gm_memory_search", mcpArgs = JSONObject()
                .put("value", m.groupValues[2].trim())
                .put("pid", (m.groupValues.getOrNull(4) ?: "0")))
        }

        // ===== 脚本 / Python / Lua / 计算 =====
        Regex("(python|py)\\s*`([^`]+)`", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            return Resolved(mcpTool = "python", mcpArgs = JSONObject().put("code", m.groupValues[2]))
        }
        Regex("^python\\s*", RegexOption.IGNORE_CASE).find(t)?.let {
            return Resolved(mcpTool = "python", mcpArgs = JSONObject().put("code", t.replaceFirst(Regex("^python\\s*", RegexOption.IGNORE_CASE), "")))
        }
        Regex("^lua\\s*", RegexOption.IGNORE_CASE).find(t)?.let {
            return Resolved(mcpTool = "lua", mcpArgs = JSONObject().put("script", t.replaceFirst(Regex("^lua\\s*", RegexOption.IGNORE_CASE), "")))
        }
        Regex("^计算\\s*(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "math_calculator", mcpArgs = JSONObject().put("expression", m.groupValues[1]))
        }
        Regex("^=(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "math_calculator", mcpArgs = JSONObject().put("expression", m.groupValues[1]))
        }

        // ===== 文件 / shell =====
        Regex("^(sh|shell)\\s+(.*)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            return Resolved(listOf(AgentCommand("shell", target = m.groupValues[2])))
        }
        Regex("^ls\\s*(.*)$").find(t)?.let { m ->
            return Resolved(listOf(AgentCommand("shell", target = "ls -la ${m.groupValues[1].ifBlank { "~" }}")))
        }
        Regex("^cat\\s+(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "file_read", mcpArgs = JSONObject().put("path", m.groupValues[1]))
        }
        Regex("^读文件\\s*(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "file_read", mcpArgs = JSONObject().put("path", m.groupValues[1].trim()))
        }
        Regex("^(写文件|写入文件)\\s*(\\S+)\\s*[=:]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(t)?.let { m ->
            return Resolved(mcpTool = "file_write", mcpArgs = JSONObject()
                .put("path", m.groupValues[2])
                .put("content", m.groupValues[3]))
        }
        Regex("^列目录\\s*(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "file_list", mcpArgs = JSONObject().put("path", m.groupValues[1].ifBlank { TerminalManager.DEFAULT_WORKSPACE }))
        }
        Regex("^搜文件\\s+(\\S+)\\s+(.+)?$").find(t)?.let { m ->
            return Resolved(mcpTool = "file_search", mcpArgs = JSONObject()
                .put("path", m.groupValues[2].ifBlank { TerminalManager.DEFAULT_WORKSPACE })
                .put("pattern", m.groupValues[1]))
        }

        // ===== HTTP / 问用户 / 技能 =====
        Regex("^请求(\\w+)\\s+(https?://\\S+)(\\s*body=(.+)?)?$", RegexOption.DOT_MATCHES_ALL).find(t)?.let { m ->
            return Resolved(mcpTool = "http_request", mcpArgs = JSONObject()
                .put("method", m.groupValues[1])
                .put("url", m.groupValues[2])
                .put("body", m.groupValues.getOrNull(4) ?: ""))
        }
        Regex("^GET\\s+(https?://\\S+)").find(t)?.let { m ->
            return Resolved(mcpTool = "http_request", mcpArgs = JSONObject().put("url", m.groupValues[1]).put("method", "GET"))
        }
        Regex("^问用户(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "ask", mcpArgs = JSONObject().put("question", m.groupValues[1]))
        }
        Regex("^技能列表|^skills/list").find(t)?.let {
            return Resolved(mcpTool = "skills/list", mcpArgs = JSONObject())
        }
        Regex("^读技能\\s*(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "skill_read", mcpArgs = JSONObject().put("name", m.groupValues[1]))
        }
        Regex("^reload skills|^重新加载技能|^刷新技能").find(t)?.let {
            return Resolved(mcpTool = "skills/reload", mcpArgs = JSONObject())
        }

        // ===== MT 管理器 APK 工具 =====
        Regex("^mt?apk分析\\s*(.+)$").find(t)?.let { m ->
            return Resolved(mcpTool = "mt_apk_analyze", mcpArgs = JSONObject().put("path", m.groupValues[1]))
        }

        // ===== 否则：看是否有计划步骤（"- xxx" 或数字列表），交给 PlanEngine 解析 =====
        val hasPlan = t.contains("- ") || Regex("""^\d+[\.、]""", RegexOption.MULTILINE).containsMatchIn(t)
        if (hasPlan) return Resolved(planText = t)

        return Resolved()  // 未命中，继续走 AI
    }
}
