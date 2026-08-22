package dev.harrison.mcinspector.packets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.harrison.mcinspector.McInspectorClient
import dev.harrison.mcinspector.mcp.obj
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RuleAction { LOG, DROP, PAUSE, SCRIPT }

/** What the capture hook should do with a packet after the rules ran. */
sealed class Decision {
    object Pass : Decision()
    object Drop : Decision()
    object Hold : Decision()
    class Replace(val packet: Any) : Decision()
}

enum class RecordStatus { NORMAL, DROPPED, HELD, REPLACED, RELEASED, LOGGED }

class PacketRule(
    val id: Int,
    @Volatile var name: String,
    @Volatile var enabled: Boolean = true,
    @Volatile var direction: Direction? = null,
    @Volatile var typeRegex: Regex? = null,
    @Volatile var action: RuleAction = RuleAction.LOG,
    /** JS: for SCRIPT it is the handler; for other actions an optional condition expression (truthy → match). */
    @Volatile var script: String? = null,
) {
    val hits = AtomicLong()
    @Volatile var lastError: String? = null

    fun matches(dir: Direction, type: String, cls: String): Boolean {
        if (!enabled) return false
        if (direction != null && direction != dir) return false
        val re = typeRegex ?: return true
        return re.containsMatchIn(type) || re.containsMatchIn(cls)
    }

    fun toJson(): JsonObject = obj {
        "id" to id; "name" to name; "enabled" to enabled; "direction" to direction?.name; "type" to typeRegex?.pattern
        "action" to action.name; "script" to script; "hits" to hits.get(); "lastError" to lastError
    }
}

/** A packet stopped by a PAUSE rule, waiting for resume/drop. */
class HeldPacket(val record: PacketRecord, val packet: Any, val dir: Direction, val connection: net.minecraft.network.Connection, val rule: PacketRule)

/**
 * Rules engine: evaluated on the netty/render thread for every packet BEFORE it is delivered or sent.
 * LOG tags the record; DROP cancels; PAUSE holds the packet until released; SCRIPT runs JS that may
 * inspect, rewrite (`set(packet, field, value)`), `drop()` or `hold()`.
 */
object PacketRules {
    private val ids = AtomicInteger()
    val rules = CopyOnWriteArrayList<PacketRule>()
    private val held = java.util.Collections.synchronizedMap(LinkedHashMap<Long, HeldPacket>())
    /**
     * Packet instances we re-dispatch ourselves (released/replaced). The capture hook lets them through
     * untouched. Identity-based because outbound sends hop to the netty thread (a ThreadLocal would not follow).
     */
    private val bypassSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    fun markBypass(packet: Any) = synchronized(bypassSet) { bypassSet.add(packet) }
    /** True (and consumed) if [packet] was marked for bypass. */
    fun consumeBypass(packet: Any): Boolean = synchronized(bypassSet) { bypassSet.remove(packet) }

    fun add(name: String, direction: Direction?, typeRegex: String?, action: RuleAction, script: String?): PacketRule {
        val r = PacketRule(ids.incrementAndGet(), name, true, direction, typeRegex?.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) }, action, script?.takeIf { it.isNotBlank() })
        if (r.script != null) RuleScripts.compile(r)   // fail fast on syntax errors
        rules += r
        return r
    }

    fun remove(id: Int): Boolean = rules.removeIf { it.id == id }
    fun get(id: Int): PacketRule? = rules.firstOrNull { it.id == id }
    fun clear() = rules.clear()

    /** Evaluate all rules; first non-PASS decision wins. [rec] is tagged with hits/status. */
    fun evaluate(conn: net.minecraft.network.Connection, packet: Any, dir: Direction, type: String, cls: String, rec: PacketRecord): Decision {
        if (rules.isEmpty()) return Decision.Pass
        var current = packet
        for (rule in rules) {
            if (!rule.matches(dir, type, cls)) continue
            val decision: Decision = try {
                when (rule.action) {
                    RuleAction.SCRIPT -> RuleScripts.run(rule, current, dir, type, rec)
                    else -> {
                        val cond = rule.script
                        if (cond != null && !RuleScripts.condition(rule, current, dir, type, rec)) continue
                        when (rule.action) {
                            RuleAction.LOG -> Decision.Pass
                            RuleAction.DROP -> Decision.Drop
                            RuleAction.PAUSE -> Decision.Hold
                            else -> Decision.Pass
                        }
                    }
                }
            } catch (t: Throwable) {
                rule.lastError = "${t::class.simpleName}: ${t.message}"
                McInspectorClient.LOGGER.warn("[MCInspector] packet rule '{}' failed: {}", rule.name, rule.lastError)
                continue
            }
            rule.hits.incrementAndGet()
            rec.ruleHits += rule.name
            when (decision) {
                is Decision.Pass -> { if (rule.action == RuleAction.LOG) rec.status = RecordStatus.LOGGED }
                is Decision.Drop -> { rec.status = RecordStatus.DROPPED; return decision }
                is Decision.Hold -> { rec.status = RecordStatus.HELD; held[rec.id] = HeldPacket(rec, current, dir, conn, rule); return decision }
                is Decision.Replace -> { rec.status = RecordStatus.REPLACED; current = decision.packet }
            }
        }
        return if (current !== packet) Decision.Replace(current) else Decision.Pass
    }

    // ---- held queue

    fun heldList(): List<HeldPacket> = synchronized(held) { held.values.toList() }
    fun heldCount(): Int = held.size

    /** Resume (re-dispatch) or drop one held packet. */
    fun release(id: Long, drop: Boolean): Boolean {
        val h = synchronized(held) { held.remove(id) } ?: return false
        if (drop) { h.record.status = RecordStatus.DROPPED; return true }
        redispatch(h.connection, h.packet, h.dir)
        h.record.status = RecordStatus.RELEASED
        return true
    }

    fun releaseAll(drop: Boolean): Int {
        val all = synchronized(held) { held.keys.toList() }
        return all.count { release(it, drop) }
    }

    /** Send/deliver [packet] as if it had just arrived/been sent, without re-running capture or rules. */
    fun redispatch(conn: net.minecraft.network.Connection, packet: Any, dir: Direction) {
        markBypass(packet)
        @Suppress("UNCHECKED_CAST")
        val p = packet as net.minecraft.network.protocol.Packet<*>
        if (dir == Direction.OUT) conn.send(p)
        else (conn as dev.harrison.mcinspector.mixin.ConnectionInvoker).`mcinspector$channelRead0`(null, p)
    }

    fun toJson(): JsonArray = JsonArray().apply { rules.forEach { add(it.toJson()) } }
}
