package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 终端/文件工具：
 *  - 允许 Agent 使用 user/root shell
 *  - 读取或写入手机文件
 *  - Linux 工具环境安装（Python/Git/jq/zip/node 等）
 *  - 通用工作区目录，默认 /storage/emulated/0/XINCODE
 */
object TerminalManager {

    const val DEFAULT_WORKSPACE = "/storage/emulated/0/XINCODE"

    private val ctx: Context get() = App.instance

    // ===== Shell =====

    data class ShellResult(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0
    }

    fun shell(cmd: String, useRoot: Boolean = true): ShellResult {
        val r = if (useRoot) RootShellExecutor.exec(cmd) else RootShellExecutor.exec(cmd)
        return ShellResult(r.exit, r.out, r.err)
    }

    /** 批量命令，一次 su 会话执行。 */
    fun shellBatch(cmds: List<String>, useRoot: Boolean = true): ShellResult {
        val r = if (useRoot) RootShellExecutor.execBatch(cmds) else RootShellExecutor.execBatch(cmds)
        return ShellResult(r.exit, r.out, r.err)
    }

    // ===== 文件操作（root 优先） =====

    fun readFile(path: String, maxBytes: Int = 1024 * 1024): String {
        val r = RootShellExecutor.exec("cat $path 2>/dev/null || cat $path 2>/dev/null")
        return r.out.take(maxBytes)
    }

    fun writeFile(path: String, content: String): Boolean {
        val escaped = content.replace("'", "'\\''")
        val r = RootShellExecutor.exec("cat > $path << 'EOF'\n$content\nEOF")
        return r.ok
    }

    fun deleteFile(path: String): Boolean = RootShellExecutor.exec("rm -rf $path").ok
    fun mkdirs(path: String): Boolean = RootShellExecutor.exec("mkdir -p $path").ok
    fun list(path: String): List<String> {
        val r = RootShellExecutor.exec("ls -la $path")
        if (!r.ok) return emptyList()
        return r.out.lineSequence().filter { it.isNotBlank() }.toList()
    }

    // ===== 工作区 =====

    fun ensureWorkspace(): File {
        val d = File(DEFAULT_WORKSPACE)
        if (!d.exists()) {
            d.mkdirs()
            RootShellExecutor.exec("mkdir -p $DEFAULT_WORKSPACE")
        }
        return d
    }

    // ===== Linux 工具环境 =====

    /** 基础工具是否可用 */
    data class ToolStatus(val name: String, val available: Boolean, val path: String = "") {
        override fun toString() = "$name: ${if (available) "✓ $path" else "✗ 未安装"}"
    }

    private val tools = listOf("python3", "python", "git", "jq", "zip", "unzip", "node", "npm", "curl", "wget", "ssh")

    fun checkTools(): List<ToolStatus> {
        return tools.map { t ->
            val r = RootShellExecutor.exec("command -v $t 2>/dev/null || which $t 2>/dev/null")
            if (r.ok) ToolStatus(t, true, r.out.trim()) else ToolStatus(t, false)
        }
    }

    /** 在 Termux / Debian 用户空间存在的情况下，自动安装基础工具。 */
    fun installTools(toolList: List<String>): JSONObject {
        val result = JSONObject()
        for (t in toolList) {
            val installCmd = when (t) {
                "python3", "python" -> "pkg install -y python 2>/dev/null || apt-get install -y python3 2>/dev/null || true"
                "git" -> "pkg install -y git 2>/dev/null || apt-get install -y git 2>/dev/null || true"
                "jq" -> "pkg install -y jq 2>/dev/null || apt-get install -y jq 2>/dev/null || true"
                "zip" -> "pkg install -y zip 2>/dev/null || apt-get install -y zip 2>/dev/null || true"
                "unzip" -> "pkg install -y unzip 2>/dev/null || apt-get install -y unzip 2>/dev/null || true"
                "node" -> "pkg install -y nodejs 2>/dev/null || apt-get install -y nodejs 2>/dev/null || true"
                "curl" -> "pkg install -y curl 2>/dev/null || apt-get install -y curl 2>/dev/null || true"
                "wget" -> "pkg install -y wget 2>/dev/null || apt-get install -y wget 2>/dev/null || true"
                "ssh" -> "pkg install -y openssh 2>/dev/null || apt-get install -y openssh-client 2>/dev/null || true"
                else -> "pkg install -y $t 2>/dev/null || apt-get install -y $t 2>/dev/null || true"
            }
            val r = RootShellExecutor.exec(installCmd, 60_000L)
            result.put(t, r.ok)
        }
        return result
    }

    // ===== 环境配置 =====

    data class EnvProfile(
        val name: String,
        val env: Map<String, String>,
        val pathExt: List<String>,
        val tools: List<String>
    ) {
        fun toJson() = JSONObject().apply {
            put("name", name)
            put("env", JSONObject(env))
            put("pathExt", JSONArray(pathExt))
            put("tools", JSONArray(tools))
        }
        companion object {
            fun fromJson(o: JSONObject) = EnvProfile(
                name = o.optString("name"),
                env = o.optJSONObject("env")?.let { jo ->
                    val m = mutableMapOf<String, String>()
                    val keys = jo.keys()
                    while (keys.hasNext()) { val k = keys.next(); m[k] = jo.optString(k) }
                    m.toMap()
                } ?: emptyMap(),
                pathExt = (o.optJSONArray("pathExt") ?: JSONArray()).let { list -> (0 until list.length()).map { list.optString(it) } },
                tools = (o.optJSONArray("tools") ?: JSONArray()).let { list -> (0 until list.length()).map { list.optString(it) } }
            )
        }
    }

    private val profilesDir: File get() = File(ctx.filesDir, "env_profiles").apply { mkdirs() }

    fun listProfiles(): List<EnvProfile> {
        return profilesDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { f ->
            runCatching { EnvProfile.fromJson(JSONObject(f.readText())) }.getOrNull()
        }?.toList() ?: emptyList()
    }

    fun saveProfile(p: EnvProfile) {
        File(profilesDir, "${p.name}.json").writeText(p.toJson().toString(2))
    }

    fun deleteProfile(name: String) {
        File(profilesDir, "$name.json").delete()
    }

    /** 克隆/导出 profile 为 JSON 字符串 */
    fun exportProfile(name: String): String? {
        val f = File(profilesDir, "$name.json")
        return if (f.exists()) f.readText() else null
    }

    /** 导入 profile JSON 字符串 */
    fun importProfile(json: String): Boolean {
        return runCatching {
            val o = JSONObject(json)
            val name = o.optString("name")
            if (name.isBlank()) return false
            File(profilesDir, "$name.json").writeText(json)
            true
        }.getOrDefault(false)
    }

    /** 在当前 profile 下执行命令（设置 env 和 PATH） */
    fun withProfile(name: String, cmd: String): ShellResult {
        val p = listProfiles().firstOrNull { it.name == name }
        val prefix = buildString {
            if (p != null) {
                p.env.forEach { (k, v) -> append("export $k='$v'; ") }
                if (p.pathExt.isNotEmpty()) {
                    append("export PATH='${p.pathExt.joinToString(":")}':\${PATH}; ")
                }
            }
        }
        return shell("$prefix $cmd")
    }
}
