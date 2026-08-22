package dev.harrison.mcinspector.packets

import com.google.gson.JsonElement

enum class Direction { IN, OUT }

/** Which end of the connection recorded the packet (both exist in-process in singleplayer). */
enum class Side { CLIENT, SERVER }

enum class Phase {
    HANDSHAKE, STATUS, LOGIN, CONFIGURATION, GAME, COMMON, COOKIE, PING, UNKNOWN, MARKER;

    companion object {
        /** Infer the protocol phase from the packet class's package (`net.minecraft.network.protocol.<phase>.…`). */
        fun of(className: String): Phase {
            val marker = ".protocol."
            val i = className.indexOf(marker)
            if (i < 0) return UNKNOWN
            val seg = className.substring(i + marker.length).substringBefore('.')
            return entries.firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * One captured packet. [packet] is kept for lazy decoding; [fields] is decoded on first request and
 * cached so the netty thread never pays for reflection.
 */
class PacketRecord(
    val id: Long,
    val epochMs: Long,
    val tick: Long,
    val dir: Direction,
    val side: Side,
    val type: String,
    val cls: String,
    val phase: Phase,
    packet: Any?,
    private val decoder: (Any?) -> JsonElement,
) {
    /** Live packet object; dropped once the record is materialised (bounded memory). */
    @Volatile private var packet: Any? = packet
    @Volatile var materialised: Boolean = false
        private set

    /** Decode now and release the packet reference (background thread). */
    fun materialise() {
        fields; summary
        packet = null
        materialised = true
    }

    /** The live packet, if still retained (rules/held packets keep their own reference). */
    val livePacket: Any? get() = packet
    /** Wire size in bytes (payload after the packet id); null for in-memory connections or until the encoder ran. */
    @Volatile var size: Int? = null
    /** First bytes of the wire payload (network connections only, capped by PacketSizes.RAW_CAP). */
    @Volatile var raw: ByteArray? = null
    /** Rules that matched this packet and what they did to it. */
    val ruleHits: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    @Volatile var status: RecordStatus = RecordStatus.NORMAL
    /** Markers are synthetic records inserted with `packets_mark` to delimit an action in the stream. */
    val isMarker: Boolean get() = phase == Phase.MARKER
    @Volatile private var decoded: JsonElement? = null
    @Volatile private var summaryCache: String? = null

    val fields: JsonElement
        get() = decoded ?: synchronized(this) { decoded ?: decoder(packet).also { decoded = it } }

    /** One-line summary: first few decoded fields, `k=v` joined. */
    val summary: String
        get() = summaryCache ?: synchronized(this) {
            summaryCache ?: PacketDecoder.summarize(fields).also { summaryCache = it }
        }
}
