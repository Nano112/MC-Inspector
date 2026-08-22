package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.bool
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str
import dev.harrison.mcinspector.mcp.strList
import dev.harrison.mcinspector.packets.Direction
import dev.harrison.mcinspector.packets.PacketCapture
import dev.harrison.mcinspector.packets.PacketQuery
import dev.harrison.mcinspector.packets.PacketRecord
import dev.harrison.mcinspector.packets.Phase
import dev.harrison.mcinspector.packets.Side
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun row(r: PacketRecord): JsonObject = obj {
    "id" to r.id; "time" to TS.format(Instant.ofEpochMilli(r.epochMs)); "tick" to r.tick
    "dir" to r.dir.name; "side" to r.side.name; "phase" to r.phase.name; "type" to r.type; "class" to r.cls; "bytes" to r.size; "summary" to r.summary
}

class PacketsListTool : Tool {
    override val name = "packets_list"
    override val description = "List captured client<->server packets (newest first by default) with a one-line summary each. " +
        "Use `since` (last seen id) to poll incrementally, `type` regex to focus (e.g. \"entity|chunk\"), `direction` IN (server->client) / OUT (client->server)."
    override val inputSchema = schema(
        "limit" to prop("integer", "Max rows (1-500, default 50)."),
        "direction" to prop("string", "IN or OUT", enum = listOf("IN", "OUT")),
        "side" to prop("string", "CLIENT (default: only client-side connections are captured) or SERVER (integrated server, needs packets_capture {serverSide:true}).", enum = listOf("CLIENT", "SERVER")),
        "phase" to prop("string", "Protocol phase", enum = Phase.entries.map { it.name }),
        "type" to prop("string", "Regex matched against the packet type id (e.g. minecraft:move_player_pos) or class name."),
        "since" to prop("integer", "Only packets with id > since."),
        "newestFirst" to prop("boolean", "Default true."),
    )
    override fun call(args: JsonObject): ToolResult {
        val q = PacketQuery(
            direction = args.str("direction")?.let { Direction.valueOf(it.uppercase()) },
            side = args.str("side")?.let { Side.valueOf(it.uppercase()) },
            phase = args.str("phase")?.let { Phase.valueOf(it.uppercase()) },
            type = args.str("type")?.let { Regex(it, RegexOption.IGNORE_CASE) },
            sinceId = args.int("since")?.toLong() ?: 0,
            limit = (args.int("limit") ?: 50).coerceIn(1, 500),
            newestFirst = args.bool("newestFirst") ?: true,
        )
        val store = PacketCapture.store
        val rows = store.query(q)
        return ToolResult.json(obj {
            "buffered" to store.size(); "latestId" to store.latestId(); "paused" to store.paused; "returned" to rows.size
            "packets" to JsonArray().apply { rows.forEach { add(row(it)) } }
        })
    }
}

class PacketsGetTool : Tool {
    override val name = "packets_get"
    override val description = "Full decoded fields of one captured packet by id (from packets_list)."
    override val inputSchema = schema("id" to prop("integer", "Packet id."), required = listOf("id"))
    override fun call(args: JsonObject): ToolResult {
        val id = args.int("id")?.toLong() ?: throw IllegalArgumentException("id required")
        val r = PacketCapture.store.get(id) ?: throw IllegalArgumentException("no packet with id $id (buffer holds ${PacketCapture.store.size()}; it may have been evicted)")
        return ToolResult.json(row(r).apply { add("fields", r.fields) })
    }
}

class PacketsStatsTool : Tool {
    override val name = "packets_stats"
    override val description = "Per-type packet counts and total bytes since the last clear, plus buffer/pause state. Byte counts are null/0 for in-memory singleplayer connections (never encoded)."
    override val inputSchema = schema("top" to prop("integer", "Only the N most frequent types (default all)."))
    override fun call(args: JsonObject): ToolResult {
        val store = PacketCapture.store
        var entries = store.stats().entries.sortedByDescending { it.value }
        args.int("top")?.let { entries = entries.take(it.coerceAtLeast(1)) }
        return ToolResult.json(obj {
            "totalSeen" to store.totalSeen; "buffered" to store.size(); "dropped" to store.dropped; "capacity" to store.capacity
            "paused" to store.paused; "serverSide" to store.captureServerSide
            "excludes" to JsonArray().apply { store.excludes.forEach { add(JsonPrimitive(it.pattern)) } }
            "byType" to JsonObject().apply { entries.forEach { addProperty(it.key, it.value) } }
            "bytesByType" to JsonObject().apply { store.bytesStats().entries.sortedByDescending { it.value }.forEach { addProperty(it.key, it.value) } }
        })
    }
}

class PacketsClearTool : Tool {
    override val name = "packets_clear"
    override val description = "Clear the packet buffer and counters."
    override val inputSchema = schema()
    override fun call(args: JsonObject): ToolResult { PacketCapture.store.clear(); return ToolResult.json(obj { "cleared" to true }) }
}

class PacketsCaptureTool : Tool {
    override val name = "packets_capture"
    override val description = "Control capture: pause/resume and set exclude patterns (regexes on type id) to drop noisy packets such as \"set_time|keep_alive|move_player\"."
    override val inputSchema = schema(
        "paused" to prop("boolean", "Pause (true) or resume (false) capture."),
        "exclude" to prop("array", "Replace the exclude list with these regexes (empty array clears).").apply { add("items", prop("string", "regex")) },
        "serverSide" to prop("boolean", "Also capture the integrated server's connections in singleplayer (default false)."),
    )
    override fun call(args: JsonObject): ToolResult {
        val store = PacketCapture.store
        args.bool("paused")?.let { store.paused = it }
        args.bool("serverSide")?.let { store.captureServerSide = it }
        if (args.has("exclude")) store.excludes = args.strList("exclude").map { Regex(it, RegexOption.IGNORE_CASE) }
        return ToolResult.json(obj { "paused" to store.paused; "serverSide" to store.captureServerSide; "excludes" to JsonArray().apply { store.excludes.forEach { add(JsonPrimitive(it.pattern)) } } })
    }
}
