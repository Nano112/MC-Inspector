package dev.harrison.mcinspector.packets

import com.google.gson.JsonElement

enum class Direction { IN, OUT }

/** Which end of the connection recorded the packet (both exist in-process in singleplayer). */
enum class Side { CLIENT, SERVER }

enum class Phase {
    HANDSHAKE, STATUS, LOGIN, CONFIGURATION, GAME, COMMON, COOKIE, PING, UNKNOWN;

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
    private val packet: Any?,
    private val decoder: (Any?) -> JsonElement,
) {
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
