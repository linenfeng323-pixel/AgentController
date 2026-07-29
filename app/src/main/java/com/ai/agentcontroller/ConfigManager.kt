package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 配置管理器。
 *
 * 借鉴白给 v19 “保存配置 / 恢复原配置 + SHA-256 完整性校验”的做法：
 *  - 当前配置与“原配置”双份保存，支持一键恢复
 *  - 保存时计算 SHA-256 并写入校验文件，加载时校验，损坏自动回退
 *  - 配置为 JSON，便于人读与扩展
 */
object ConfigManager {

    private const val FILE_CURRENT = "agent_config.json"
    private const val FILE_ORIGINAL = "agent_config.original.json"
    private const val FILE_CHECKSUM = "agent_config.sha256"

    private val ctx: Context get() = App.instance

    data class Config(
        var lastSiteName: String = "",
        var lastSiteUrl: String = "",
        var autoExecute: Boolean = true,
        var useRoot: Boolean = true,
        var useAccessibility: Boolean = true,
        var maxSteps: Int = 30,
        var stepIntervalMs: Long = 300,
        var screenshotOnError: Boolean = true,
        var pcBridgeEnabled: Boolean = false,
        var pcBridgeUrl: String = "ws://192.168.1.100:9912",
        var recentGoals: MutableList<String> = mutableListOf(),
        var favorites: MutableList<String> = mutableListOf(),
        var deepThinking: Boolean = false,
        var webBrowsingEnabled: Boolean = true,
        var deviceDirectAccess: Boolean = true,
        var sensitiveRead: Boolean = false,
        var sensitiveOperate: Boolean = false,
        var terminalEnabled: Boolean = true,
        var linuxEnvEnabled: Boolean = true,
        var workspaceDir: String = "/storage/emulated/0/XINCODE",
        var currentProfile: String = "default",
        var mainModelUrl: String = "",
        var visionModelUrl: String = "",
        var translateModelUrl: String = "",
        var reasonModelUrl: String = "",
        var contextSummary: Boolean = true,
        var backgroundReview: Boolean = false,
        var mcpEnabled: Boolean = false,
        var githubToken: String = "",
        var githubRepo: String = "",
        var githubBranch: String = "main"
    )

    @Volatile private var current: Config = Config()
    @Volatile private var original: Config = Config()

    fun get(): Config = current

    /** 保存当前配置（同时刷新校验）。 */
    fun save(cfg: Config) {
        current = cfg
        writeJson(File(ctx.filesDir, FILE_CURRENT), cfg)
        writeChecksum(File(ctx.filesDir, FILE_CURRENT), File(ctx.filesDir, FILE_CHECKSUM))
        CommandLogManager.ok("配置已保存")
    }

    /** 把当前配置标记为“原配置”，用于之后恢复。 */
    fun saveAsOriginal(cfg: Config) {
        original = cfg
        writeJson(File(ctx.filesDir, FILE_ORIGINAL), cfg)
        CommandLogManager.ok("已保存为原配置")
    }

    /** 恢复到原配置。 */
    fun restoreOriginal(): Config {
        val f = File(ctx.filesDir, FILE_ORIGINAL)
        if (!f.exists()) {
            CommandLogManager.warn("无原配置可恢复，使用默认")
            current = Config()
        } else {
            original = readJson(f) ?: Config()
            current = original.copy()
        }
        writeJson(File(ctx.filesDir, FILE_CURRENT), current)
        writeChecksum(File(ctx.filesDir, FILE_CURRENT), File(ctx.filesDir, FILE_CHECKSUM))
        CommandLogManager.ok("已恢复原配置")
        return current
    }

    /** 加载配置（启动时调用）。损坏则回退。 */
    fun load(): Config {
        val f = File(ctx.filesDir, FILE_CURRENT)
        if (!f.exists()) {
            current = Config()
            return current
        }
        // 校验 SHA-256
        val checksumFile = File(ctx.filesDir, FILE_CHECKSUM)
        if (checksumFile.exists()) {
            val expected = checksumFile.readText().trim()
            val actual = sha256(f)
            if (!expected.equals(actual, ignoreCase = true)) {
                CommandLogManager.warn("配置校验失败，回退默认 | 期望=$expected 实际=$actual")
                current = Config()
                return current
            }
        }
        current = readJson(f) ?: Config()
        // 加载原配置
        val of = File(ctx.filesDir, FILE_ORIGINAL)
        if (of.exists()) original = readJson(of) ?: Config()
        return current
    }

    private fun writeJson(f: File, cfg: Config) {
        val arr = JSONArray()
        cfg.recentGoals.forEach { arr.put(it) }
        val fav = JSONArray()
        cfg.favorites.forEach { fav.put(it) }
        val o = JSONObject().apply {
            put("lastSiteName", cfg.lastSiteName)
            put("lastSiteUrl", cfg.lastSiteUrl)
            put("autoExecute", cfg.autoExecute)
            put("useRoot", cfg.useRoot)
            put("useAccessibility", cfg.useAccessibility)
            put("maxSteps", cfg.maxSteps)
            put("stepIntervalMs", cfg.stepIntervalMs)
            put("screenshotOnError", cfg.screenshotOnError)
            put("pcBridgeEnabled", cfg.pcBridgeEnabled)
            put("pcBridgeUrl", cfg.pcBridgeUrl)
            put("recentGoals", arr)
            put("favorites", fav)
            put("deepThinking", cfg.deepThinking)
            put("webBrowsingEnabled", cfg.webBrowsingEnabled)
            put("deviceDirectAccess", cfg.deviceDirectAccess)
            put("sensitiveRead", cfg.sensitiveRead)
            put("sensitiveOperate", cfg.sensitiveOperate)
            put("terminalEnabled", cfg.terminalEnabled)
            put("linuxEnvEnabled", cfg.linuxEnvEnabled)
            put("workspaceDir", cfg.workspaceDir)
            put("currentProfile", cfg.currentProfile)
            put("mainModelUrl", cfg.mainModelUrl)
            put("visionModelUrl", cfg.visionModelUrl)
            put("translateModelUrl", cfg.translateModelUrl)
            put("reasonModelUrl", cfg.reasonModelUrl)
            put("contextSummary", cfg.contextSummary)
            put("backgroundReview", cfg.backgroundReview)
            put("mcpEnabled", cfg.mcpEnabled)
            put("githubToken", cfg.githubToken)
            put("githubRepo", cfg.githubRepo)
            put("githubBranch", cfg.githubBranch)
        }
        f.writeText(o.toString(2))
    }

    private fun readJson(f: File): Config? = try {
        val o = JSONObject(f.readText())
        Config(
            lastSiteName = o.optString("lastSiteName"),
            lastSiteUrl = o.optString("lastSiteUrl"),
            autoExecute = o.optBoolean("autoExecute", true),
            useRoot = o.optBoolean("useRoot", true),
            useAccessibility = o.optBoolean("useAccessibility", true),
            maxSteps = o.optInt("maxSteps", 30),
            stepIntervalMs = o.optLong("stepIntervalMs", 300),
            screenshotOnError = o.optBoolean("screenshotOnError", true),
            pcBridgeEnabled = o.optBoolean("pcBridgeEnabled", false),
            pcBridgeUrl = o.optString("pcBridgeUrl", "ws://192.168.1.100:9912"),
            recentGoals = (o.optJSONArray("recentGoals") ?: JSONArray()).let { arr ->
                MutableList(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }.toMutableList()
            },
            favorites = (o.optJSONArray("favorites") ?: JSONArray()).let { arr ->
                MutableList(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }.toMutableList()
            },
            deepThinking = o.optBoolean("deepThinking", false),
            webBrowsingEnabled = o.optBoolean("webBrowsingEnabled", true),
            deviceDirectAccess = o.optBoolean("deviceDirectAccess", true),
            sensitiveRead = o.optBoolean("sensitiveRead", false),
            sensitiveOperate = o.optBoolean("sensitiveOperate", false),
            terminalEnabled = o.optBoolean("terminalEnabled", true),
            linuxEnvEnabled = o.optBoolean("linuxEnvEnabled", true),
            workspaceDir = o.optString("workspaceDir", "/storage/emulated/0/XINCODE"),
            currentProfile = o.optString("currentProfile", "default"),
            mainModelUrl = o.optString("mainModelUrl"),
            visionModelUrl = o.optString("visionModelUrl"),
            translateModelUrl = o.optString("translateModelUrl"),
            reasonModelUrl = o.optString("reasonModelUrl"),
            contextSummary = o.optBoolean("contextSummary", true),
            backgroundReview = o.optBoolean("backgroundReview", false),
            mcpEnabled = o.optBoolean("mcpEnabled", false),
            githubToken = o.optString("githubToken"),
            githubRepo = o.optString("githubRepo"),
            githubBranch = o.optString("githubBranch", "main")
        )
    } catch (e: Throwable) {
        CommandLogManager.err("读取配置失败: ${e.message}")
        null
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeChecksum(dataFile: File, checksumFile: File) {
        checksumFile.writeText(sha256(dataFile))
    }

    /** 记录最近目标，最多保留 20 条。 */
    fun addRecentGoal(goal: String) {
        if (goal.isBlank()) return
        current.recentGoals.removeAll { it == goal }
        current.recentGoals.add(0, goal)
        if (current.recentGoals.size > 20) current.recentGoals.subList(20, current.recentGoals.size).clear()
        save(current)
    }
}
