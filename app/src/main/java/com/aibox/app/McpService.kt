package com.aibox.app

import android.content.Context
import io.modelcontextprotocol.spec.McpSchema
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * @MCPServer：声明式标注一个类为 MCP 服务器，其工具通过标准 MCP API 暴露给智能体。
 *
 * 说明：MCP Java SDK 2.0.0 已移除 0.x 时代的官方注解
 * （io.modelcontextprotocol.sdk.mcp.server.annotation.MCPServer），此处保留同名注解做声明标记，
 * 实际工具注册基于 mcp-core 2.0.0 的标准数据模型 McpSchema.Tool（tools/list 与 tools/call 语义一致）。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class MCPServer(val name: String = "aibox", val version: String = "2.5.40")

/**
 * App 内置 MCP 服务器：把 App 现有工具（执行命令、读写文件、联网搜索、HTTP 请求、下载）以标准 MCP API 暴露。
 * 智能体工具调用时优先查这里的工具列表，命中即通过 MCP 调用；未命中再回退到硬编码逻辑。
 */
@MCPServer(name = "aibox", version = "2.5.40")
object McpService {

    private const val OUT_LIMIT = 4000
    private const val TOOL_TIMEOUT_SEC = 120L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 标准 MCP 工具定义表（tools/list 语义） */
    private val toolDefs: MutableList<McpSchema.Tool> = mutableListOf()

    @Synchronized
    fun init() {
        if (toolDefs.isNotEmpty()) return
        fun schema(properties: Map<String, String>, required: List<String>): Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to properties.mapValues { mapOf("type" to it.value) },
            "required" to required
        )
        fun tool(name: String, desc: String, input: Map<String, Any>): McpSchema.Tool =
            McpSchema.Tool.builder(name, input).description(desc).build()

        toolDefs.add(tool("exec_command",
            "在手机环境中执行一条 bash 命令并返回输出。注意：Python/wget 等工具 DNS 可能受限，需要联网获取内容时优先使用 http_get / download_file。",
            schema(mapOf("cmd" to "string"), listOf("cmd"))))
        toolDefs.add(tool("read_file",
            "读取文本文件内容。支持绝对路径（如 /sdcard/...）或相对工作目录的路径。",
            schema(mapOf("path" to "string"), listOf("path"))))
        toolDefs.add(tool("write_file",
            "写入/覆盖一个文本文件。支持绝对路径（如 /sdcard/...）或相对工作目录的路径。",
            schema(mapOf("path" to "string", "content" to "string"), listOf("path", "content"))))
        toolDefs.add(tool("web_search",
            "联网搜索互联网，返回相关网页的标题、链接和摘要。适合查询最新资讯、事实、文档等。",
            schema(mapOf("query" to "string"), listOf("query"))))
        toolDefs.add(tool("http_get",
            "由 App 直接请求一个 URL 并返回响应文本（绕开沙盒 DNS 限制）。适合获取 API 数据、HTML 页面、JSON 等。",
            schema(mapOf("url" to "string"), listOf("url"))))
        toolDefs.add(tool("download_file",
            "由 App 直接联网下载文件到本地路径（绕开沙盒 DNS 限制）。可下载 pip wheel、静态编译工具、源码包、模型文件等任意 URL。",
            schema(mapOf("url" to "string", "path" to "string"), listOf("url", "path"))))
    }

    /** MCP tools/list：返回标准工具定义列表 */
    fun listTools(): List<McpSchema.Tool> {
        init()
        return toolDefs
    }

    /** MCP 工具名集合，供智能体查找匹配 */
    fun toolNames(): Set<String> {
        init()
        return toolDefs.map { it.name() }.toSet()
    }

    /** MCP tools/call：调用工具并返回文本结果 */
    fun callTool(ctx: Context, name: String, args: JSONObject): String {
        init()
        return try {
            when (name) {
                "exec_command" -> runShell(ctx, args.optString("cmd"))
                "read_file" -> readFile(ctx, args.optString("path"))
                "write_file" -> writeFile(ctx, args.optString("path"), args.optString("content"))
                "web_search" -> webSearch(args.optString("query"))
                "http_get" -> httpGet(args.optString("url"))
                "download_file" -> download(ctx, args.optString("url"), args.optString("path"))
                else -> "错误：MCP 服务器没有这个工具：$name"
            }
        } catch (e: Exception) {
            "错误：MCP 工具执行失败：${e.message}"
        }
    }

    // ---------- 工具实现 ----------

    private fun workDir(ctx: Context): File =
        File(ctx.filesDir, "codex/work").apply { mkdirs() }

    private fun resolvePath(ctx: Context, raw: String): File? {
        if (raw.isBlank()) return null
        val work = workDir(ctx).absoluteFile
        val f = if (raw.startsWith("/")) File(raw) else File(work, raw.trimStart('/'))
        return f.absoluteFile
    }

    private fun runShell(ctx: Context, cmd: String): String {
        if (cmd.isBlank()) return "错误：cmd 不能为空"
        val p = CodexEngine.paths(ctx)
        val bash = File(p.bin, "bash")
        if (!bash.exists()) return "错误：运行环境未安装（bash 不存在），请先初始化引擎"
        val pb = ProcessBuilder(bash.absolutePath, "-c", cmd)
        pb.directory(workDir(ctx))
        pb.redirectErrorStream(true)
        return try {
            val proc = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                proc.inputStream.bufferedReader().use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        if (out.length < OUT_LIMIT * 2) out.append(line).append('\n')
                    }
                }
            }
            reader.start()
            val finished = proc.waitFor(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS)
            reader.join(2000)
            if (!finished) {
                proc.destroyForcibly()
                "命令超时（${TOOL_TIMEOUT_SEC}s），已终止：$cmd"
            } else if (out.isBlank()) "(无输出，退出码 ${proc.exitValue()})"
            else out.toString().trim().take(OUT_LIMIT)
        } catch (e: Exception) {
            "错误：执行失败：${e.message}"
        }
    }

    private fun readFile(ctx: Context, path: String): String {
        val f = resolvePath(ctx, path)
        if (f == null) return "错误：路径为空"
        return if (!f.exists()) "错误：文件不存在 ${f.absolutePath}"
        else if (f.isDirectory) "错误：${f.absolutePath} 是目录"
        else f.readText().take(OUT_LIMIT)
    }

    private fun writeFile(ctx: Context, path: String, content: String): String {
        val f = resolvePath(ctx, path)
        if (f == null) return "错误：路径为空"
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "已写入 ${f.absolutePath}（${f.length()} 字节）"
    }

    private fun httpGet(url: String): String {
        if (url.isBlank()) return "错误：url 不能为空"
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) "请求失败（HTTP ${resp.code}）"
                else resp.body?.string().orEmpty().take(OUT_LIMIT)
            }
        } catch (e: Exception) {
            "错误：请求失败：${e.message}"
        }
    }

    private fun download(ctx: Context, url: String, path: String): String {
        if (url.isBlank()) return "错误：url 不能为空"
        val f = resolvePath(ctx, path)
        if (f == null) return "错误：路径为空"
        return try {
            f.parentFile?.mkdirs()
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) "下载失败（HTTP ${resp.code}）：${resp.message}"
                else {
                    val len = resp.body?.byteStream()?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    } ?: -1L
                    "已下载到 ${f.absolutePath}（$len 字节）"
                }
            }
        } catch (e: Exception) {
            "错误：下载失败：${e.message}"
        }
    }

    private fun webSearch(query: String): String {
        if (query.isBlank()) return "错误：搜索关键词为空"
        return try {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                if (resp.code !in 200..299) "搜索失败（HTTP ${resp.code}）"
                else {
                    val sb = StringBuilder()
                    // 简单解析 DuckDuckGo 结果：标题 + 链接 + 摘要
                    Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
                        .findAll(html).take(5).forEach { m ->
                            val title = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                            sb.append("· ").append(title).append('\n').append("  ").append(m.groupValues[1]).append('\n')
                        }
                    if (sb.isEmpty()) "未搜索到结果"
                    else sb.toString().trim().take(OUT_LIMIT)
                }
            }
        } catch (e: Exception) {
            "错误：搜索失败：${e.message}"
        }
    }
}
