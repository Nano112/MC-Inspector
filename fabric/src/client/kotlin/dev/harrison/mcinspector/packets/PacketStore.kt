package dev.harrison.mcinspector.packets

import java.util.concurrent.atomic.AtomicLong

/** Query parameters shared by the MCP tool and the panel. */
data class PacketQuery(
    val direction: Direction? = null,
    val side: Side? = null,
    val phase: Phase? = null,
    val type: Regex? = null,
    val sinceId: Long = 0,
    val limit: Int = 200,
    val newestFirst: Boolean = true,
)

/**
 * Thread-safe ring buffer of captured packets plus per-type counters, pause flag and exclude patterns.
 * Recording is cheap (no decoding); consumers snapshot via [query].
 */
class PacketStore(val capacity: Int = 5000) {
    private val lock = Any()
    private val ring = ArrayDeque<PacketRecord>(capacity)
    private val ids = AtomicLong()
    private val counts = HashMap<String, Long>()
    @Volatile var paused: Boolean = false
    /** Also record the integrated server's connections (singleplayer). Off by default. */
    @Volatile var captureServerSide: Boolean = false
    @Volatile var excludes: List<Regex> = emptyList()
    @Volatile var totalSeen: Long = 0; private set
    @Volatile var dropped: Long = 0; private set

    fun nextId(): Long = ids.incrementAndGet()

    /** Returns false when the packet was excluded/paused (callers can skip building the record). */
    fun accepts(type: String): Boolean {
        if (paused) return false
        val ex = excludes
        return ex.isEmpty() || ex.none { it.containsMatchIn(type) }
    }

    fun add(rec: PacketRecord) = synchronized(lock) {
        if (ring.size >= capacity) { ring.removeFirst(); dropped++ }
        ring.addLast(rec)
        totalSeen++
        counts.merge(rec.type, 1L, Long::plus)
    }

    fun get(id: Long): PacketRecord? = synchronized(lock) { ring.firstOrNull { it.id == id } }

    /** Insert a marker row (shows in the panel and in packets_list; ids share the packet sequence). */
    fun mark(label: String, tick: Long): PacketRecord {
        val rec = PacketRecord(nextId(), System.currentTimeMillis(), tick, Direction.OUT, Side.CLIENT, "marker:$label", "Marker", Phase.MARKER, null) { com.google.gson.JsonPrimitive(label) }
        add(rec)
        return rec
    }

    /** Previous record of the same type (and direction) before [rec], if still buffered. */
    fun previousOfSameType(rec: PacketRecord): PacketRecord? = synchronized(lock) {
        ring.lastOrNull { it.id < rec.id && it.type == rec.type && it.dir == rec.dir }
    }

    /** Regex over the DECODED fields (JSON text) and type, newest first; decodes lazily so scanning is capped. */
    fun search(regex: Regex, limit: Int, maxScan: Int = 2000): List<PacketRecord> {
        val snapshot = synchronized(lock) { ring.toList() }
        val out = ArrayList<PacketRecord>()
        for (r in snapshot.asReversed().take(maxScan)) {
            if (r.isMarker) continue
            if (regex.containsMatchIn(r.type) || regex.containsMatchIn(r.fields.toString())) { out += r; if (out.size >= limit) break }
        }
        return out
    }

    /** Packets and bytes per second over the trailing [windowMs], per type. */
    fun rates(windowMs: Long = 5000): Map<String, Pair<Double, Double>> {
        val snapshot = synchronized(lock) { ring.toList() }
        val now = System.currentTimeMillis()
        val counts = HashMap<String, Long>(); val bytes = HashMap<String, Long>()
        for (r in snapshot.asReversed()) {
            if (now - r.epochMs > windowMs) break
            if (r.isMarker) continue
            counts.merge(r.type, 1L, Long::plus); r.size?.let { bytes.merge(r.type, it.toLong(), Long::plus) }
        }
        val sec = windowMs / 1000.0
        return counts.keys.associateWith { (counts[it]!! / sec) to ((bytes[it] ?: 0L) / sec) }
    }

    fun query(q: PacketQuery): List<PacketRecord> {
        val snapshot = synchronized(lock) { ring.toList() }
        val seq = snapshot.asSequence()
            .filter { it.id > q.sinceId }
            .filter { q.direction == null || it.dir == q.direction }
            .filter { q.side == null || it.side == q.side }
            .filter { q.phase == null || it.phase == q.phase }
            .filter { q.type == null || q.type.containsMatchIn(it.type) || q.type.containsMatchIn(it.cls) }
        val list = seq.toList()
        val limited = if (q.newestFirst) list.takeLast(q.limit).asReversed() else list.take(q.limit)
        return limited
    }

    fun size(): Int = synchronized(lock) { ring.size }
    fun latestId(): Long = synchronized(lock) { ring.lastOrNull()?.id ?: 0 }
    fun stats(): Map<String, Long> = synchronized(lock) { counts.toSortedMap() }

    /** Bytes per type over the records currently buffered (sizes arrive asynchronously, so computed on demand). */
    fun bytesStats(): Map<String, Long> {
        val snapshot = synchronized(lock) { ring.toList() }
        val m = HashMap<String, Long>()
        for (r in snapshot) r.size?.let { m.merge(r.type, it.toLong(), Long::plus) }
        return m
    }

    fun clear() = synchronized(lock) { ring.clear(); counts.clear(); totalSeen = 0; dropped = 0 }
}
