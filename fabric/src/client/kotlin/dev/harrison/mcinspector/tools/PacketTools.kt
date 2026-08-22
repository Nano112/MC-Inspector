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
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str
import dev.harrison.mcinspector.mcp.strList
import dev.harrison.mcinspector.packets.Direction
import dev.harrison.mcinspector.packets.PacketCapture
import dev.harrison.mcinspector.packets.PacketQuery
import dev.harrison.mcinspector.packets.PacketRecord
import dev.harrison.mcinspector.packets.PacketRules
import dev.harrison.mcinspector.packets.RuleAction
import dev.harrison.mcinspector.packets.Phase
import dev.harrison.mcinspector.packets.Side
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun row(r: PacketRecord): JsonObject = obj {
    "id" to r.id; "time" to TS.format(Instant.ofEpochMilli(r.epochMs)); "tick" to r.tick
    "dir" to r.dir.name; "side" to r.side.name; "phase" to r.phase.name; "type" to r.type; "class" to r.cls; "bytes" to r.size; "summary" to r.summary
    if (r.status != dev.harrison.mcinspector.packets.RecordStatus.NORMAL) "status" to r.status.name
    if (r.ruleHits.isNotEmpty()) "rules" to JsonArray().apply { r.ruleHits.forEach { add(JsonPrimitive(it)) } }
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
        "deep" to prop("string", "Regex also matched against summary + decoded fields (slower)."),
        "since" to prop("integer", "Only packets with id > since."),
        "newestFirst" to prop("boolean", "Default true."),
    )
    override fun call(args: JsonObject): ToolResult {
        val q = PacketQuery(
            direction = args.str("direction")?.let { Direction.valueOf(it.uppercase()) },
            side = args.str("side")?.let { Side.valueOf(it.uppercase()) },
            phase = args.str("phase")?.let { Phase.valueOf(it.uppercase()) },
            type = args.str("type")?.let { Regex(it, RegexOption.IGNORE_CASE) },
            deep = args.str("deep")?.let { Regex(it, RegexOption.IGNORE_CASE) },
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
    override val inputSchema = schema("id" to prop("integer", "Packet id."), "hex" to prop("boolean", "Include a hex dump of the first bytes (network connections only)."), required = listOf("id"))
    override fun call(args: JsonObject): ToolResult {
        val id = args.int("id")?.toLong() ?: throw IllegalArgumentException("id required")
        val r = PacketCapture.store.get(id) ?: throw IllegalArgumentException("no packet with id $id (buffer holds ${PacketCapture.store.size()}; it may have been evicted)")
        return ToolResult.json(row(r).apply {
            add("fields", r.fields)
            if (args.bool("hex") == true) add("hex", JsonArray().apply { r.raw?.let { dev.harrison.mcinspector.packets.Hex.dump(it).forEach { l -> add(JsonPrimitive(l)) } } })
        })
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
            "perSecond" to JsonObject().apply { store.rates().entries.sortedByDescending { it.value.first }.forEach { (k, v) -> add(k, obj { "packets" to "%.1f".format(v.first).toDouble(); "bytes" to "%.0f".format(v.second).toDouble() }) } }
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

class PacketsMarkTool : Tool {
    override val name = "packets_mark"
    override val description = "Insert a labelled marker into the packet stream (e.g. before/after an action) and return its id; then `packets_list {since: id}` shows exactly what happened after it."
    override val inputSchema = schema("label" to prop("string", "Marker label."), required = listOf("label"))
    override fun call(args: JsonObject): ToolResult {
        val m = PacketCapture.store.mark(args.require("label"), dev.harrison.mcinspector.mcp.TickScheduler.tick)
        return ToolResult.json(obj { "id" to m.id; "label" to args.require("label"); "tick" to m.tick })
    }
}

class PacketsSearchTool : Tool {
    override val name = "packets_search"
    override val description = "Regex search over DECODED packet fields (JSON text) and type ids, newest first — e.g. find every packet mentioning entity id 231: \"\\\"id\\\":231\\b\" or a block position \"x=-3, z=-8\". Scans up to `maxScan` buffered packets."
    override val inputSchema = schema(
        "query" to prop("string", "Regex (case-insensitive)."),
        "limit" to prop("integer", "Max results (default 20, max 200)."),
        "maxScan" to prop("integer", "Max packets to decode/scan (default 2000)."),
        required = listOf("query"),
    )
    override fun call(args: JsonObject): ToolResult {
        val rows = PacketCapture.store.search(Regex(args.require("query"), RegexOption.IGNORE_CASE), (args.int("limit") ?: 20).coerceIn(1, 200), (args.int("maxScan") ?: 2000).coerceIn(1, 5000))
        return ToolResult.json(obj { "returned" to rows.size; "packets" to JsonArray().apply { rows.forEach { add(row(it)) } } })
    }
}

class PacketsWatchTool : Tool {
    override val name = "packets_watch"
    override val description = "Block until `count` NEW packets matching `type` (regex) / `direction` arrive, or `timeoutTicks` elapse. Returns the matches with decoded fields. Use it to wait for a specific event instead of polling (e.g. type \"block_update\" after placing a block)."
    override val inputSchema = schema(
        "type" to prop("string", "Regex on type id or class name."),
        "direction" to prop("string", "IN or OUT", enum = listOf("IN", "OUT")),
        "count" to prop("integer", "How many matches to wait for (default 1, max 50)."),
        "timeoutTicks" to prop("integer", "Give up after N ticks (default 200 = 10 s, max 6000)."),
        "fields" to prop("boolean", "Include decoded fields (default true)."),
        required = listOf("type"),
    )
    override fun call(args: JsonObject): ToolResult {
        val store = PacketCapture.store
        val q = PacketQuery(direction = args.str("direction")?.let { Direction.valueOf(it.uppercase()) }, type = Regex(args.require("type"), RegexOption.IGNORE_CASE), sinceId = store.latestId(), limit = 500, newestFirst = false)
        val want = (args.int("count") ?: 1).coerceIn(1, 50)
        val timeout = (args.int("timeoutTicks") ?: 200).coerceIn(1, 6000)
        val withFields = args.bool("fields") ?: true
        val start = dev.harrison.mcinspector.mcp.TickScheduler.tick
        var found: List<PacketRecord> = emptyList()
        while (dev.harrison.mcinspector.mcp.TickScheduler.tick - start < timeout) {
            found = store.query(q)
            if (found.size >= want) break
            dev.harrison.mcinspector.mcp.TickScheduler.awaitTicks(2)
        }
        val ok = found.size >= want
        return ToolResult.json(obj {
            "satisfied" to ok; "matched" to found.size; "waitedTicks" to (dev.harrison.mcinspector.mcp.TickScheduler.tick - start)
            "packets" to JsonArray().apply { found.take(want).forEach { r -> add(row(r).apply { if (withFields) add("fields", r.fields) }) } }
        })
    }
}

class PacketsDiffTool : Tool {
    override val name = "packets_diff"
    override val description = "Changed fields between a packet and the previous packet of the same type+direction (or an explicit `against` id). Great for 'what changed in set_entity_data / update_attributes'."
    override val inputSchema = schema("id" to prop("integer", "Packet id."), "against" to prop("integer", "Compare with this id instead of the previous same-type packet."), required = listOf("id"))
    override fun call(args: JsonObject): ToolResult {
        val store = PacketCapture.store
        val b = store.get(args.int("id")!!.toLong()) ?: throw IllegalArgumentException("no packet ${args.int("id")}")
        val a = args.int("against")?.let { store.get(it.toLong()) ?: throw IllegalArgumentException("no packet $it") } ?: store.previousOfSameType(b)
            ?: return ToolResult.json(obj { "id" to b.id; "type" to b.type; "against" to null; "changes" to JsonArray(); "note" to "no earlier packet of this type in the buffer" })
        val changes = dev.harrison.mcinspector.packets.JsonDiff.diff(a.fields, b.fields)
        return ToolResult.json(obj { "id" to b.id; "against" to a.id; "type" to b.type; "changed" to changes.size; "changes" to dev.harrison.mcinspector.packets.JsonDiff.toJson(changes) })
    }
}

class PacketsRulesTool : Tool {
    override val name = "packets_rules"
    override val description = """Breakpoints and rewriting. Rules run for every packet BEFORE it is delivered/sent.
Actions: LOG (tag the record), DROP (swallow), PAUSE (hold until packets_release — a breakpoint), SCRIPT (JS handler).
`script` is a JS expression/program with globals: packet, type, dir ("IN"/"OUT"), id, get(packet,"field"), set(packet,"field",value) → rewritten packet
(records are copied), drop(), hold(), log(...), Packages.* / importClass. For LOG/DROP/PAUSE the script is an optional CONDITION (truthy = match);
for SCRIPT, returning a packet object replaces the original. Examples:
  {op:"add", name:"no-time", direction:"IN", type:"set_time", action:"DROP"}
  {op:"add", name:"freeze-health", direction:"IN", type:"set_health", action:"SCRIPT", script:"set(packet, 'health', 20.0)"}
  {op:"add", name:"bp-chat", direction:"OUT", type:"^minecraft:chat$", action:"PAUSE", script:"get(packet,'message').indexOf('!') == 0"}
  {op:"add", name:"teleport-log", type:"player_position", action:"SCRIPT", script:"log(get(packet,'change')); undefined"}""".trimIndent()
    override val inputSchema = schema(
        "op" to prop("string", "list | add | remove | enable | disable | clear", enum = listOf("list", "add", "remove", "enable", "disable", "clear")),
        "id" to prop("integer", "Rule id for remove/enable/disable."),
        "name" to prop("string", "Rule name (add)."),
        "direction" to prop("string", "IN or OUT (add; omit = both)", enum = listOf("IN", "OUT")),
        "type" to prop("string", "Regex on type id / class (add; omit = all)."),
        "action" to prop("string", "LOG | DROP | PAUSE | SCRIPT (add)", enum = RuleAction.entries.map { it.name }),
        "script" to prop("string", "JS handler or condition (add)."),
        required = listOf("op"),
    )
    override fun call(args: JsonObject): ToolResult {
        when (args.require("op")) {
            "list" -> {}
            "add" -> {
                val r = PacketRules.add(args.str("name") ?: "rule", args.str("direction")?.let { Direction.valueOf(it.uppercase()) }, args.str("type"),
                    RuleAction.valueOf((args.str("action") ?: "LOG").uppercase()), args.str("script"))
                return ToolResult.json(obj { "added" to r.toJson(); "rules" to PacketRules.toJson() })
            }
            "remove" -> if (!PacketRules.remove(args.int("id") ?: -1)) throw IllegalArgumentException("no rule ${args.int("id")}")
            "enable", "disable" -> (PacketRules.get(args.int("id") ?: -1) ?: throw IllegalArgumentException("no rule ${args.int("id")}")).enabled = args.require("op") == "enable"
            "clear" -> PacketRules.clear()
        }
        return ToolResult.json(obj { "rules" to PacketRules.toJson(); "held" to PacketRules.heldCount() })
    }
}

class PacketsHeldTool : Tool {
    override val name = "packets_held"
    override val description = "Packets currently stopped at a PAUSE breakpoint (with decoded fields), oldest first."
    override val inputSchema = schema("fields" to prop("boolean", "Include decoded fields (default true)."))
    override fun call(args: JsonObject): ToolResult {
        val withFields = args.bool("fields") ?: true
        return ToolResult.json(obj {
            "count" to PacketRules.heldCount()
            "held" to JsonArray().apply { PacketRules.heldList().forEach { h -> add(row(h.record).apply { addProperty("rule", h.rule.name); if (withFields) add("fields", h.record.fields) }) } }
        })
    }
}

class PacketsReleaseTool : Tool {
    override val name = "packets_release"
    override val description = "Resume (deliver/send) or drop held packets: one by id, or all. Resumed inbound packets are handled on the game thread as usual."
    override val inputSchema = schema("id" to prop("integer", "Held packet id (omit with all=true)."), "all" to prop("boolean", "Release every held packet."), "drop" to prop("boolean", "Drop instead of resuming (default false)."))
    override fun call(args: JsonObject): ToolResult {
        val drop = args.bool("drop") ?: false
        val n = if (args.bool("all") == true) PacketRules.releaseAll(drop) else {
            val id = args.int("id")?.toLong() ?: throw IllegalArgumentException("id or all required")
            if (PacketRules.release(id, drop)) 1 else throw IllegalArgumentException("no held packet $id")
        }
        return ToolResult.json(obj { (if (drop) "dropped" else "resumed") to n; "stillHeld" to PacketRules.heldCount() })
    }
}
