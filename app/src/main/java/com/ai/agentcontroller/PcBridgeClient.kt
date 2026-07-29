package com.ai.agentcontroller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.SSLContext

/**
 * PC / 云端协同执行模块。
 *
 * 借鉴白给 v19 讨论过的“电脑/云端解密”方案：
 *  - 手机作为执行端，PC/云作为算力端
 *  - WebSocket 长连接（支持 ws/wss），自动重连 + 心跳
 *  - 手机上报：屏幕文本 / 截图哈希 / 任务请求
 *  - PC/云下发：[CommandBatch]，手机本地执行
 *  - 会话粘连：单连接绑定单会话，避免状态错乱
 *
 * 纯 JDK 实现，避免引入 OkHttp 增大体积。
 * 服务端协议见仓库 scripts/pc_bridge_server.py。
 */
class PcBridgeClient(
    private val url: String,
    private val onCommand: (CommandBatch) -> Unit
) {
    @Volatile var isConnected = false
        private set

    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    private var sessionId: String = ""

    fun start() {
        if (thread?.isAlive == true) return
        stopFlag = false
        sessionId = newSessionId()
        thread = Thread({ runLoop() }, "PcBridge").apply { isDaemon = true; start() }
        CommandLogManager.info("PC 桥接启动: $url session=$sessionId")
    }

    fun stop() {
        stopFlag = true
        thread?.interrupt()
        isConnected = false
        CommandLogManager.info("PC 桥接停止")
    }

    private fun runLoop() {
        var backoff = 1000L
        while (!stopFlag) {
            try {
                connectOnce()
                backoff = 1000L
            } catch (e: Throwable) {
                CommandLogManager.warn("桥接断开: ${e.message}，${backoff}ms 后重连")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = (backoff * 2).coerceAtMost(15000L)
            }
        }
    }

    private fun connectOnce() {
        val uri = URI(url)
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 80
        val rawHost = uri.host ?: throw IllegalArgumentException("无效 host")
        val socket = if (uri.scheme == "wss") {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            ctx.socketFactory.createSocket(rawHost, port) as javax.net.ssl.SSLSocket
        } else {
            java.net.Socket(rawHost, port)
        }
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        val out = socket.getOutputStream()
        val ins = socket.getInputStreamStreamCompat()

        // WebSocket 握手
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val req = "GET ${uri.path.ifBlank { "/" }}${if (uri.rawQuery != null) "?${uri.query}" else ""} HTTP/1.1\r\n" +
            "Host: $rawHost:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "X-Session: $sessionId\r\n" +
            "X-Device: ${android.os.Build.MODEL}\r\n" +
            "\r\n"
        out.write(req.toByteArray())
        out.flush()

        // 读握手响应
        readHttpHeader(ins)

        isConnected = true
        CommandLogManager.ok("桥接已连接: $rawHost:$port")

        // 心跳线程
        val heart = Thread({
            while (!stopFlag && !socket.isClosed) {
                try { Thread.sleep(15000); sendText(out, "ping") } catch (_: Throwable) { break }
            }
        }, "PcBridge-Heart").apply { isDaemon = true; start() }

        // 主循环：读服务端消息
        try {
            while (!stopFlag && !socket.isClosed) {
                val msg = readFrame(ins) ?: break
                if (msg == "ping") { sendText(out, "pong"); continue }
                if (msg == "pong") continue
                handleMessage(msg, out)
            }
        } finally {
            heart.interrupt()
            runCatching { socket.close() }
            isConnected = false
        }
    }

    private fun handleMessage(msg: String, out: java.io.OutputStream) {
        try {
            val obj = JSONObject(msg)
            val type = obj.optString("type")
            when (type) {
                "command" -> {
                    val batch = CommandParser.parse(obj.optString("payload"))
                    onCommand(batch)
                    sendText(out, JSONObject().put("type", "ack").put("explain", batch.explain).toString())
                }
                "ping" -> sendText(out, "pong")
                "screenshot_request" -> {
                    val f = File(App.instance.cacheDir, "bridge_shot.png")
                    if (RootShellExecutor.screenshot(f)) {
                        sendText(out, JSONObject().put("type", "screenshot").put("sha256", sha256(f)).toString())
                    }
                }
                else -> CommandLogManager.log("BRIDGE", "未知消息: $type")
            }
        } catch (e: Throwable) {
            CommandLogManager.warn("桥接消息解析失败: ${e.message}")
        }
    }

    // ===== WebSocket 帧编解码（RFC 6455 最小实现） =====

    private fun sendText(out: java.io.OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val header = mutableListOf<Byte>()
        header.add(0x81.toByte()) // FIN + text
        when {
            payload.size <= 125 -> header.add(payload.size.toByte())
            payload.size <= 0xFFFF -> {
                header.add(126.toByte())
                header.add(((payload.size shr 8) and 0xFF).toByte())
                header.add((payload.size and 0xFF).toByte())
            }
            else -> {
                header.add(127.toByte())
                for (i in 7 downTo 0) header.add(((payload.size shr (8 * i)) and 0xFF).toByte())
            }
        }
        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun readFrame(ins: java.io.InputStream): String? {
        val b1 = ins.read(); if (b1 < 0) return null
        val b2 = ins.read(); if (b2 < 0) return null
        var len = (b2 and 0x7F).toLong()
        if (len == 126L) {
            val hi = ins.read(); val lo = ins.read()
            if (hi < 0 || lo < 0) return null
            len = ((hi and 0xFF) shl 8 or (lo and 0xFF)).toLong()
        } else if (len == 127L) {
            len = 0
            for (i in 0 until 8) { val v = ins.read(); if (v < 0) return null; len = (len shl 8) or (v and 0xFF).toLong() }
        }
        val mask = (b2 and 0x80) != 0
        val maskKey = if (mask) ByteArray(4).also { ins.readNBytesCompat(it, 4) } else null
        val data = ByteArray(len.toInt()).also { ins.readNBytesCompat(it, it.size) }
        if (mask && maskKey != null) for (i in data.indices) data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        // 不处理分片，假定单帧文本
        return String(data, Charsets.UTF_8)
    }

    private fun readHttpHeader(ins: java.io.InputStream) {
        val buf = ByteArrayOutputStreamCompat()
        var last4 = 0
        while (true) {
            val b = ins.read()
            if (b < 0) break
            buf.write(b)
            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) break // \r\n\r\n
        }
        val header = String(buf.toByteArray(), Charsets.UTF_8)
        if (!header.contains("101", ignoreCase = true)) throw java.io.IOException("握手失败: ${header.take(200)}")
    }

    private fun newSessionId(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update((System.currentTimeMillis().toString() + android.os.Build.MODEL).toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(8192); while (true) { val n = i.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// ===== 兼容低版本 InputStream 的辅助扩展 =====

private fun java.io.InputStream.readNBytesCompat(buf: ByteArray, n: Int): Int {
    var read = 0
    while (read < n) {
        val r = this.read(buf, read, n - read)
        if (r < 0) break
        read += r
    }
    return read
}

private fun java.net.Socket.getInputStreamStreamCompat(): java.io.InputStream = this.getInputStream()

private class ByteArrayOutputStreamCompat {
    private val buf = java.io.ByteArrayOutputStream()
    fun write(b: Int) = buf.write(b)
    fun toByteArray(): ByteArray = buf.toByteArray()
}
