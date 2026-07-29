package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 工作区代码索引：
 *  - 扫描 /storage/emulated/0/XINCODE 下的代码结构
 *  - 抽取符号（类/函数/变量）到本地索引
 *  - 支持 AI 直接按符号名查定义与引用
 *  - 增量更新
 */
object CodeIndexer {

    private val ctx: Context get() = App.instance

    data class Symbol(
        val name: String,
        val kind: String,   // class / function / variable / import
        val file: String,
        val line: Int,
        val summary: String = ""
    )

    private data class Index(val workspace: String, val symbols: List<Symbol>) {
        fun toJson() = JSONObject().apply {
            put("workspace", workspace)
            put("symbols", JSONArray(symbols.map { it.toJson() }))
        }
    }
    private fun Symbol.toJson() = JSONObject().apply {
        put("name", name); put("kind", kind); put("file", file); put("line", line); put("summary", summary)
    }
    private fun jsonToSymbol(o: JSONObject) = Symbol(o.optString("name"), o.optString("kind"), o.optString("file"), o.optInt("line"), o.optString("summary"))

    private val indexFile: File get() = File(ctx.filesDir, "code_index.json")

    fun buildIndex(workspace: String = TerminalManager.DEFAULT_WORKSPACE, progress: (String) -> Unit = {}): Int {
        val dir = File(workspace)
        if (!dir.exists()) return 0
        val symbols = mutableListOf<Symbol>()
        val extensions = setOf(".kt", ".kts", ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h", ".xml", ".gradle", ".tsx", ".jsx")
        val files = mutableListOf<File>()
        dir.walkTopDown().forEach { if (it.isFile && it.extension in extensions) files.add(it) }
        progress("扫描 ${files.size} 个文件…")
        files.forEachIndexed { i, f ->
            if (i % 20 == 0) progress("索引中 ${i + 1}/${files.size}")
            parseFile(f, symbols)
        }
        indexFile.writeText(Index(workspace, symbols).toJson().toString(2))
        CommandLogManager.ok("索引完成：${symbols.size} 个符号")
        return symbols.size
    }

    private fun parseFile(f: File, out: MutableList<Symbol>) {
        val lang = when (f.extension) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "jsx", "ts", "tsx" -> "js"
            "go" -> "go"
            "rs" -> "rust"
            "c", "cpp", "h" -> "c"
            else -> "other"
        }
        runCatching {
            val lines = f.readLines()
            for ((idx, line) in lines.withIndex()) {
                val trimmed = line.trim()
                when (lang) {
                    "kotlin" -> {
                        Regex("""^(class|object|interface|fun|val|var|enum class|sealed class|data class)\s+([A-Za-z_][\w]*)""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[2], it.groupValues[1], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                    "java" -> {
                        Regex("""^(public|private|protected)?\s*(class|interface|enum|void|\w+)\s+(\w+)\s*[(<]""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[3], it.groupValues[2], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                    "python" -> {
                        Regex("""^(def|class)\s+([A-Za-z_][\w]*)""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[2], it.groupValues[1], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                    "js" -> {
                        Regex("""^(function|const|let|var|class)\s+([A-Za-z_][\w$]*)""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[2], it.groupValues[1], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                    "go" -> {
                        Regex("""^(func|type|var|const)\s+([A-Za-z_][\w]*)""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[2], it.groupValues[1], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                    "rust" -> {
                        Regex("""^(fn|struct|enum|trait|impl|pub)\s+([A-Za-z_][\w]*)""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[2], it.groupValues[1], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                    "c" -> {
                        Regex("""^(typedef|struct|enum|static|inline|void|int|char|float|double|unsigned)\s+([A-Za-z_][\w]*)""").find(trimmed)?.let {
                            out.add(Symbol(it.groupValues[2], it.groupValues[1], f.path, idx + 1, trimmed.take(120)))
                        }
                    }
                }
            }
        }
    }

    /** 按名字查找符号定义 */
    fun findSymbol(name: String, kind: String? = null): List<Symbol> {
        val idx = loadIndex() ?: return emptyList()
        return idx.symbols.filter { it.name == name && (kind == null || it.kind == kind) }
    }

    /** 全文搜索符号（模糊） */
    fun searchSymbols(keyword: String, limit: Int = 50): List<Symbol> {
        val idx = loadIndex() ?: return emptyList()
        val kw = keyword.lowercase()
        return idx.symbols.filter { it.name.lowercase().contains(kw) || it.summary.lowercase().contains(kw) }.take(limit)
    }

    fun stats(): JSONObject {
        val idx = loadIndex() ?: return JSONObject().put("total", 0)
        val byKind = idx.symbols.groupBy { it.kind }.mapValues { it.value.size }
        return JSONObject().apply {
            put("total", idx.symbols.size)
            put("files", idx.symbols.map { it.file }.distinct().size)
            put("byKind", JSONObject(byKind))
            put("workspace", idx.workspace)
        }
    }

    private fun loadIndex(): Index? {
        if (!indexFile.exists()) return null
        return runCatching {
            val o = JSONObject(indexFile.readText())
            val arr = o.optJSONArray("symbols") ?: JSONArray()
            Index(o.optString("workspace"), (0 until arr.length()).map { jsonToSymbol(arr.getJSONObject(it)) })
        }.getOrNull()
    }
}
