package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 上下文哨兵 + 自动 GitHub 构建与修复代理（在应用内可触发）。
 *
 * 功能：
 *  - 上下文压缩检测：AI 回复里丢了 requestId 或回复为纯自然语言时，自动重发系统提示
 *  - GitHub 上传代码 / 触发工作流 / 轮询进度 / 下载构建日志 / 自动修 bug / 再次推送
 *  - 进度实时写到 CommandLogManager，供 UI 日志窗口观察
 */
object ContextSentinel {

    private val ctx: Context get() = App.instance

    private val sentinelState = ConcurrentHashMap<String, Int>()  // requestId -> 连续无 id 计数

    /** 检测 AI 回复是否因上下文被压缩而缺失结构。 */
    fun check(aiText: String, requestId: String = "default"): SentinelResult {
        val hasId = aiText.contains(requestId) || aiText.contains("commands") || aiText.contains("\"action\"")
        val looksLikeNatural = Regex("""[\u4e00-\u9fa5]{30,}""").containsMatchIn(aiText)
                && !aiText.contains("```")
                && !aiText.contains('{')

        val counter = sentinelState.getOrDefault(requestId, 0)
        if (!hasId && looksLikeNatural) {
            sentinelState[requestId] = counter + 1
            if (counter >= 2) {
                McpServerExt.emit("context_compressed", JSONObject()
                    .put("requestId", requestId)
                    .put("count", counter + 1)
                    .put("hint", "上下文可能被压缩，请重发系统提示词"))
                CommandLogManager.warn("上下文哨兵：检测到回复异常（第 ${counter + 1} 次）")
                return SentinelResult(true, true)
            }
            return SentinelResult(true, false)
        }
        sentinelState[requestId] = 0
        return SentinelResult(false, false)
    }

    data class SentinelResult(val compressed: Boolean, val needResendPrompt: Boolean)

    // ============== GitHub 自动构建与修复代理（应用内版） ==============

    /**
     * 一键：提交 → 推送 → 触发 CI → 监控 → 失败自动修复 → 循环直至成功。
     * @param token GitHub PAT（classic 或 fine-grained，需 repo 权限）
     * @param repo 仓库 owner/name
     * @param branch 目标分支
     * @param commitMsg 提交信息
     */
    fun buildAndFixLoop(
        token: String,
        repo: String,
        branch: String = "main",
        commitMsg: String = "auto-fix: from device sentinel",
        onProgress: (String) -> Unit = {}
    ): Boolean {
        val projectRoot = ctx.filesDir.parentFile ?: File("/data/local/tmp")
        // 项目路径：应用文件目录/AgentController 或工作区里的目录
        var repoDir = File("/storage/emulated/0/XINCODE/AgentController")
        if (!repoDir.exists()) repoDir = File(projectRoot, "AgentController")
        if (!repoDir.exists()) {
            onProgress("未找到项目目录，跳过推送"); return false
        }

        var attempts = 0
        var lastLog = ""
        while (attempts < 6) {
            attempts++
            onProgress("第 $attempts/6 次：提交并推送")
            val r1 = sh("cd ${escape(repoDir.absolutePath)} && git add -A && " +
                    "git -c user.email=agent@trae.cn -c user.name=TraeAgent commit -m ${shellQuote(commitMsg)} --allow-empty && " +
                    "git push ${githubRemote(repo, token)} $branch 2>&1", repoDir)
            onProgress(r1.out.take(200))

            // 触发 workflow（push 事件会自动触发，此处轮询最新 run）
            onProgress("等待 Actions 开始…")
            Thread.sleep(8000)
            val runId = latestRunId(token, repo, branch)
            if (runId == null) { onProgress("未捕获到新 run，跳过"); continue }
            onProgress("Run ID = $runId，等待完成…")
            val status = pollRun(token, repo, runId, 20 * 60)
            onProgress("Run 状态: $status")
            if (status == "success") {
                onProgress("✅ 构建成功！Run id: $runId")
                // 下载 artifact APK 到下载目录
                downloadApk(token, repo, runId, File("/sdcard/Download/AgentController-debug.apk"))
                return true
            }
            // 失败：抓取日志
            val log = fetchBuildLog(token, repo, runId)
            lastLog = log
            onProgress("日志已下载 ${log.length} 字，开始自动修复…")
            val patches = analyzeAndPatch(repoDir, log)
            onProgress("生成 ${patches.size} 个修复")
            if (patches.isEmpty()) {
                onProgress("无法自动修复，停止循环"); break
            }
        }
        return false
    }

    private fun sh(cmd: String, cwd: File): Result {
        val start = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", cmd).directory(cwd).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val finished = p.waitFor(60_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) { p.destroyForcibly(); Result(-1, out, "timeout") } else Result(p.exitValue(), out.trim(), err.trim())
        } catch (t: Throwable) {
            Result(-2, "", t.message ?: "error")
        }
    }

    data class Result(val exit: Int, val out: String, val err: String)
    private fun Result.ok() = exit == 0
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"
    private fun escape(s: String): String = s.replace(" ", "\\ ")
    private fun githubRemote(repo: String, token: String) =
        "https://x-access-token:$token@github.com/$repo.git"

    // ===== 基于 PAT 的 GitHub REST 调用 =====

    private fun ghApi(token: String, path: String, method: String = "GET", body: String? = null): JSONObject? {
        return runCatching {
            val url = java.net.URL("https://api.github.com$path")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                doOutput = body != null
            }
            if (body != null) c.outputStream.write(body.toByteArray())
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        }.getOrNull()
    }

    private fun ghApiArr(token: String, path: String): JSONArray? {
        return runCatching {
            val url = java.net.URL("https://api.github.com$path")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Authorization", "token $token")
                requestMethod = "GET"
                connectTimeout = 15_000; readTimeout = 30_000
            }
            val text = c.inputStream.bufferedReader().readText()
            JSONArray(text)
        }.getOrNull()
    }

    private fun latestRunId(token: String, repo: String, branch: String): Long? {
        val arr = ghApiArr(token, "/repos/$repo/actions/runs?branch=$branch&per_page=5") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("status") != "completed") return o.optLong("id")
        }
        return arr.optJSONObject(0)?.optLong("id")
    }

    private fun pollRun(token: String, repo: String, runId: Long, timeoutSeconds: Int): String {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val o = ghApi(token, "/repos/$repo/actions/runs/$runId")
            val status = o?.optString("status") ?: "unknown"
            val conclusion = o?.optString("conclusion") ?: ""
            if (status == "completed") return conclusion.ifBlank { "unknown" }
            Thread.sleep(10_000)
        }
        return "timeout"
    }

    private fun fetchBuildLog(token: String, repo: String, runId: Long): String {
        val jobs = ghApiArr(token, "/repos/$repo/actions/runs/$runId/jobs?per_page=20") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            val jid = job.optLong("id")
            val text = ghApiRaw(token, "/repos/$repo/actions/jobs/$jid/logs")
            sb.append("==== Job #$jid ${job.optString("name")} / ${job.optString("conclusion")} ====\n")
            sb.append(text.take(50_000)).append('\n')
        }
        return sb.toString()
    }

    private fun ghApiRaw(token: String, path: String): String {
        return runCatching {
            val url = java.net.URL("https://api.github.com$path")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Authorization", "token $token")
                instanceFollowRedirects = true; requestMethod = "GET"
                connectTimeout = 15_000; readTimeout = 60_000
            }
            c.inputStream.bufferedReader().readText()
        }.getOrDefault("")
    }

    private fun downloadApk(token: String, repo: String, runId: Long, target: File) {
        val arts = ghApiArr(token, "/repos/$repo/actions/runs/$runId/artifacts?per_page=10")
        if (arts == null) { CommandLogManager.warn("无 artifacts"); return }
        for (i in 0 until arts.length()) {
            val a = arts.getJSONObject(i)
            if (a.optString("name", "").lowercase().contains("apk")) {
                val archiveUrl = a.optJSONObject("archive_download_url")?.optString("href")
                        ?: "/repos/$repo/actions/artifacts/${a.optLong("id")}/zip"
                val zipBytes = ghApiBytes(token, archiveUrl)
                if (zipBytes != null && zipBytes.isNotEmpty()) {
                    target.parentFile?.mkdirs()
                    val zipFile = File(target.parent, "artifacts.zip")
                    zipFile.writeBytes(zipBytes)
                    CommandLogManager.ok("Artifact 已下载: ${zipFile.absolutePath}")
                }
                break
            }
        }
    }

    private fun ghApiBytes(token: String, path: String): ByteArray? {
        return runCatching {
            val url = java.net.URL(if (path.startsWith("http")) path else "https://api.github.com$path")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Authorization", "token $token")
                instanceFollowRedirects = true
                requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 120_000
            }
            c.inputStream.readBytes()
        }.getOrNull()
    }

    /**
     * 极简的构建日志 → Kotlin/Gradle 修复规则库（后续可接 AI）。
     * 返回应用了的规则数。
     */
    private fun analyzeAndPatch(repoDir: File, log: String): List<String> {
        val fixes = mutableListOf<String>()
        if (!log.contains("Unresolved reference") && !log.contains("error: ") && !log.contains("Execution failed")) return fixes

        // 1. 缺 import → 按类名补
        val importRegex = Regex("""Unresolved reference:\s+(\w+)""")
        val importCandidates = mapOf(
            "GestureDescription" to "import android.accessibilityservice.GestureDescription",
            "GridLayoutManager" to "import androidx.recyclerview.widget.GridLayoutManager",
            "PendingIntent" to "import android.app.PendingIntent",
            "AudioManager" to "import android.media.AudioManager"
        )
        for (m in importRegex.findAll(log)) {
            val cls = m.groupValues[1]
            val line = importCandidates[cls] ?: continue
            // 找第一个 Unresolved 出现的源文件
            val fileMatch = Regex(""".*?([\w/]+\.kt):\d+:\d+.*$cls""").find(log)
            val fpath = if (fileMatch != null) fileMatch.groupValues[1] else continue
            val file = File(repoDir, fpath)
            if (file.exists() && !file.readText().contains(line)) {
                file.writeText(file.readText().replaceFirst("package ", "$line\n\npackage "))
                fixes.add("import $cls")
            }
        }

        // 2. 资源缺失 → 新建 drawable xml
        val drawableRegex = Regex("""No resource found that matches the given name \(at '[^']+' with value '@drawable/(\w+)'""")
        for (m in drawableRegex.findAll(log)) {
            val name = m.groupValues[1]
            val dir = File(repoDir, "app/src/main/res/drawable").apply { mkdirs() }
            val f = File(dir, "$name.xml")
            if (!f.exists()) {
                f.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n  <solid android:color=\"#F3F4F6\"/>\n  <corners android:radius=\"12dp\"/>\n</shape>\n")
                fixes.add("drawable $name")
            }
        }

        // 3. Gradle 版本号/依赖报错提示 → 更新 build.gradle
        if (log.contains("Could not resolve com.android.tools.build:gradle")) {
            val build = File(repoDir, "build.gradle")
            if (build.exists()) {
                build.writeText(build.readText().replace("com.android.tools.build:gradle:\\d[\\d.]+".toRegex(), "com.android.tools.build:gradle:8.2.2"))
                fixes.add("gradle version")
            }
        }
        return fixes
    }
}
