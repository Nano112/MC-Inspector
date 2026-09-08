package dev.harrison.mcinspector.packets

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Query parameters shared by the MCP tool and the panel. */
data class PacketQuery(
    val direction: Direction? = null,
    val side: Side? = null,
    val phase: Phase? = null,
    val type: Regex? = null,
    /** Regex also applied to the decoded fields (lazy decode; slower). */
    val deep: Regex? = null,
    val sinceId: Long = 0,
    val limit: Int = 200,
    val newestFirst: Boolean = true,
)

/**
 * Thread-safe rolling buffer of captured packets plus counters, pause flag, exclude patterns and
 * per-second history for charts. Recording is cheap (no decoding); consumers snapshot via [query].
 *
 * Memory is bounded: only the newest [retain] records keep their live packet object; older ones are
 * materialised (decoded to JSON) on a background thread and the reference is dropped.
 */
class PacketStore(capacity: Int = 5000, retain: Int = 1000) {
    private val lock = Any()
    private val ring = ArrayDeque<PacketRecord>(capacity)
    private val ids = AtomicLong()
    private val counts = HashMap<String, Long>()

    @Volatile var capacity: Int = capacity
        private set
    @Volatile var retain: Int = retain
    @Volatile var paused: Boolean = false
    /** Also record the integrated server's connections (singleplayer). Off by default. */
    @Volatile var captureServerSide: Boolean = false
    @Volatile var excludes: List<Regex> = emptyList()
    @Volatile var totalSeen: Long = 0; private set
    @Volatile var dropped: Long = 0; private set

    // ---- background materialisation of records leaving the "hot" zone
    private val toMaterialise = LinkedBlockingQueue<PacketRecord>()
    init {
        Thread({
            while (true) {
                val r = toMaterialise.take()
                runCatching { r.materialise() }
            }
        }, "mcinspector-packet-materialiser").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    data class ExportSnapshot(
        val records: List<PacketRecord>, val buffered: Int, val totalSeen: Long,
        val evicted: Long, val omittedByLimit: Int, val paused: Boolean,
        val serverSide: Boolean, val exclusions: List<String>, val sinceId: Long,
    )

    /** Freeze buffer membership and counters together; file IO/decoding happens after releasing the lock. */
    fun snapshotForExport(sinceId: Long = 0, limit: Int = 2000): ExportSnapshot {
        require(sinceId >= 0 && limit in 1..10_000)
        return synchronized(lock) {
            val selected = ring.filter { it.id > sinceId }.sortedBy { it.id }
            ExportSnapshot(selected.takeLast(limit), ring.size, totalSeen, dropped,
                (selected.size - limit).coerceAtLeast(0), paused, captureServerSide,
                excludes.map { it.pattern }, sinceId)
        }
    }

    fun nextId(): Long = ids.incrementAndGet()

    /** Change the rolling window size; trims immediately if smaller. */
    fun resize(newCapacity: Int) {
        val c = newCapacity.coerceIn(100, 200_000)
        synchronized(lock) {
            capacity = c
            while (ring.size > c) { ring.removeFirst(); dropped++ }
        }
    }

    // ---- per-second history for graphs (120 s ring, indexed by epoch second)
    class Bucket(@Volatile var sec: Long) {
        @Volatile var inCount = 0; @Volatile var outCount = 0
        val byType = HashMap<String, Int>()
    }
    private val history = Array(120) { Bucket(0) }
    private fun bucket(sec: Long): Bucket {
        val b = history[(sec % history.size).toInt()]
        if (b.sec != sec) { b.sec = sec; b.inCount = 0; b.outCount = 0; b.byType.clear() }
        return b
    }
    /** (inCounts, outCounts) for the last [seconds] seconds, oldest first. */
    fun history(seconds: Int = 60): Pair<FloatArray, FloatArray> {
        val now = System.currentTimeMillis() / 1000
        val ins = FloatArray(seconds); val outs = FloatArray(seconds)
        synchronized(history) {
            for (i in 0 until seconds) {
                val sec = now - (seconds - 1 - i)
                val b = history[(sec % history.size).toInt()]
                if (b.sec == sec) { ins[i] = b.inCount.toFloat(); outs[i] = b.outCount.toFloat() }
            }
        }
        return ins to outs
    }
    /** Per-second counts per type for the last [seconds] seconds (oldest first), restricted to [types]. */
    fun historyByType(seconds: Int, types: List<String>): Map<String, IntArray> {
        val now = System.currentTimeMillis() / 1000
        val out = types.associateWith { IntArray(seconds) }
        synchronized(history) {
            for (i in 0 until seconds) {
                val sec = now - (seconds - 1 - i)
                val b = history[(sec % history.size).toInt()]
                if (b.sec != sec) continue
                for (t in types) out[t]!![i] = b.byType[t] ?: 0
            }
        }
        return out
    }

    /** Returns false when the packet was excluded/paused (callers can skip building the record). */
    fun accepts(type: String): Boolean {
        if (paused) return false
        val ex = excludes
        return ex.isEmpty() || ex.none { it.containsMatchIn(type) }
    }

    fun add(rec: PacketRecord) {
        var cooled: PacketRecord? = null
        synchronized(lock) {
            if (ring.size >= capacity) { ring.removeFirst(); dropped++ }
            ring.addLast(rec)
            totalSeen++
            counts.merge(rec.type, 1L, Long::plus)
            if (!rec.isMarker) synchronized(history) {
                val b = bucket(rec.epochMs / 1000)
                if (rec.dir == Direction.IN) b.inCount++ else b.outCount++
                b.byType.merge(rec.type, 1, Int::plus)
            }
            if (ring.size > retain) cooled = ring.elementAt(ring.size - retain - 1)
        }
        cooled?.let { if (!it.materialised) toMaterialise.offer(it) }
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
            .filter { q.deep == null || it.isMarker || q.deep.containsMatchIn(it.type) || q.deep.containsMatchIn(it.summary) || q.deep.containsMatchIn(it.fields.toString()) }
        val list = seq.toList()
        return if (q.newestFirst) list.takeLast(q.limit).asReversed() else list.take(q.limit)
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

    /** Size distribution of buffered network packets: bucket upper bounds in bytes → count. */
    fun sizeHistogram(): List<Pair<Int, Int>> {
        val bounds = intArrayOf(16, 32, 64, 128, 256, 512, 1024, 4096, 16384, 65536, Int.MAX_VALUE)
        val counts = IntArray(bounds.size)
        val snapshot = synchronized(lock) { ring.toList() }
        for (r in snapshot) { val s = r.size ?: continue; counts[bounds.indexOfFirst { s <= it }]++ }
        return bounds.zip(counts.toList())
    }

    fun clear() = synchronized(lock) { ring.clear(); counts.clear(); totalSeen = 0; dropped = 0 }
}
