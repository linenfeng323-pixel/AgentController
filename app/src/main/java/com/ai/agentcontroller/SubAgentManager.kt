package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 子智能体与局域网设备管理：
 *  - 主脑指挥多个专职子智能体（各管各的技能）
 *  - 发现同一 Wi-Fi 下其它开启 AgentController 的设备
 *  - 群聊房间：多个智能体同处一室，@名字点谁谁答
 */
object SubAgentManager {

    private val ctx: Context get() = App.instance

    data class SubAgent(
        val name: String,
        val role: String,           // 视觉/推理/翻译/转写/通用
        val modelUrl: String,       // 独立模型 URL
        val skills: List<String>,   // 技能列表
        val enabled: Boolean = true
    ) {
        fun toJson() = JSONObject().apply {
            put("name", name); put("role", role); put("modelUrl", modelUrl)
            put("skills", JSONArray(skills)); put("enabled", enabled)
        }
        companion object {
            fun fromJson(o: JSONObject) = SubAgent(
                o.optString("name"), o.optString("role"), o.optString("modelUrl"),
                (o.optJSONArray("skills") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.optString(it) } },
                o.optBoolean("enabled", true)
            )
        }
    }

    data class Room(
        val name: String,
        val members: List<String>,   // 子智能体名
        val history: List<RoomMessage>
    ) {
        fun toJson() = JSONObject().apply {
            put("name", name)
            put("members", JSONArray(members))
            put("history", JSONArray(history.map { it.toJson() }))
        }
        companion object {
            fun fromJson(o: JSONObject) = Room(
                o.optString("name"),
                (o.optJSONArray("members") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.optString(it) } },
                (o.optJSONArray("history") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { RoomMessage.fromJson(arr.getJSONObject(it)) }
                }
            )
        }
    }

    data class RoomMessage(val sender: String, val content: String, val timestamp: Long) {
        fun toJson() = JSONObject().apply {
            put("sender", sender); put("content", content); put("timestamp", timestamp)
        }
        companion object {
            fun fromJson(o: JSONObject) = RoomMessage(o.optString("sender"), o.optString("content"), o.optLong("timestamp"))
        }
    }

    // ===== 子智能体 CRUD =====

    private val agentsFile: File get() = File(ctx.filesDir, "sub_agents.json")
    private val agents = ConcurrentHashMap<String, SubAgent>()

    fun list(): List<SubAgent> { load(); return agents.values.toList() }
    fun get(name: String): SubAgent? { load(); return agents[name] }
    fun upsert(a: SubAgent) { agents[a.name] = a; save() }
    fun delete(name: String) { agents.remove(name); save() }

    /** 预设子智能体（视觉/翻译/转写/推理） */
    fun ensureDefaults() {
        val defaults = listOf(
            SubAgent("视觉师", "vision", "", listOf("截图分析、OCR"), true),
            SubAgent("翻译官", "translator", "", listOf("中英互译、多语种翻译"), true),
            SubAgent("速记员", "transcriber", "", listOf("语音转文字"), true),
            SubAgent("推理家", "reasoner", "", listOf("深度思考、计划生成"), true),
            SubAgent("执行者", "executor", "", listOf("root 指令执行"), true)
        )
        defaults.forEach { if (!agents.containsKey(it.name)) agents[it.name] = it }
        save()
    }

    private fun load() {
        if (agents.isNotEmpty()) return
        ensureDefaults()
        if (!agentsFile.exists()) return
        runCatching {
            val arr = JSONArray(agentsFile.readText())
            for (i in 0 until arr.length()) {
                val a = SubAgent.fromJson(arr.getJSONObject(i))
                agents[a.name] = a
            }
        }
    }

    private fun save() {
        val arr = JSONArray(agents.values.map { it.toJson() })
        agentsFile.writeText(arr.toString(2))
    }

    // ===== 群聊房间 =====

    private val roomsFile: File get() = File(ctx.filesDir, "rooms.json")
    private val rooms = ConcurrentHashMap<String, Room>()

    fun listRooms(): List<Room> { loadRooms(); return rooms.values.toList() }
    fun createRoom(name: String, members: List<String>) {
        rooms[name] = Room(name, members, emptyList()); saveRooms()
    }
    fun deleteRoom(name: String) { rooms.remove(name); saveRooms() }
    fun sendToRoom(room: String, sender: String, content: String) {
        val r = rooms[room] ?: return
        rooms[room] = r.copy(history = r.history + RoomMessage(sender, content, System.currentTimeMillis()))
        saveRooms()
    }

    private fun loadRooms() {
        if (rooms.isNotEmpty()) return
        if (!roomsFile.exists()) return
        runCatching {
            val arr = JSONArray(roomsFile.readText())
            for (i in 0 until arr.length()) {
                val r = Room.fromJson(arr.getJSONObject(i))
                rooms[r.name] = r
            }
        }
    }
    private fun saveRooms() {
        val arr = JSONArray(rooms.values.map { it.toJson() })
        roomsFile.writeText(arr.toString(2))
    }

    // ===== 局域网设备发现 =====

    data class RemoteDevice(val name: String, val ip: String, val port: Int, val lastSeen: Long)

    private val discovered = ConcurrentHashMap<String, RemoteDevice>()

    /** 启动 UDP 广播发现（约定端口 18898）。 */
    fun startDiscovery(): List<RemoteDevice> {
        val results = mutableListOf<RemoteDevice>()
        Thread {
            runCatching {
                val socket = DatagramSocket().apply { broadcast = true; soTimeout = 2000 }
                val msg = "ACTL:AgentController".toByteArray()
                val broadcast = DatagramPacket(msg, msg.size, java.net.InetAddress.getByName("255.255.255.255"), 18898)
                socket.send(broadcast)
                val buf = ByteArray(1024)
                repeat(5) {
                    val p = DatagramPacket(buf, buf.size)
                    runCatching { socket.receive(p) }
                    val ip = p.address.hostAddress ?: return@repeat
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith("ACTL")) {
                        val name = text.removePrefix("ACTL:")
                        discovered[ip] = RemoteDevice(name, ip, 18899, System.currentTimeMillis())
                    }
                }
                socket.close()
            }
            results.addAll(discovered.values)
        }.start()
        return results
    }

    fun listDiscovered(): List<RemoteDevice> = discovered.values.toList()
}
