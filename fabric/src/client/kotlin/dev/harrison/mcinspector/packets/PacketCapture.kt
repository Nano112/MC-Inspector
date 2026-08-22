package dev.harrison.mcinspector.packets

import dev.harrison.mcinspector.mcp.TickScheduler
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

/** Static entry point for the Connection mixin. Hot path: must stay allocation-light and never throw. */
object PacketCapture {
    @JvmField val store = PacketStore()

    /** @return true when the original delivery must be cancelled (dropped, held, or replaced). */
    @JvmStatic
    fun onInbound(conn: Connection, packet: Packet<*>): Boolean = handle(conn, packet, Direction.IN, null, false)

    @JvmStatic
    fun onOutbound(conn: Connection, packet: Packet<*>, listener: io.netty.channel.ChannelFutureListener?, flush: Boolean): Boolean =
        handle(conn, packet, Direction.OUT, listener, flush)

    private fun handle(conn: Connection, packet: Packet<*>, dir: Direction, listener: io.netty.channel.ChannelFutureListener?, flush: Boolean): Boolean {
        try {
            if (PacketRules.consumeBypass(packet)) return false
            // In singleplayer the integrated server's connections live in this JVM too; a connection
            // that RECEIVES clientbound packets is the client's side.
            val side = if (conn.receiving == PacketFlow.CLIENTBOUND) Side.CLIENT else Side.SERVER
            if (side == Side.SERVER && !store.captureServerSide) return false
            val cls = packet.javaClass
            val type = typeId(packet, cls)
            val recorded = store.accepts(type)
            if (!recorded && PacketRules.rules.isEmpty()) return false
            val rec = PacketRecord(
                id = store.nextId(), epochMs = System.currentTimeMillis(), tick = TickScheduler.tick,
                dir = dir, side = side, type = type, cls = cls.simpleName, phase = Phase.of(cls.name),
                packet = packet, decoder = PacketDecoder::decode,
            )
            if (dir == Direction.IN) PacketSizes.takeInbound(packet)?.let { (n, raw) -> rec.size = n; rec.raw = raw } else PacketSizes.expectOutbound(packet, rec)
            val decision = PacketRules.evaluate(conn, packet, dir, type, cls.simpleName, rec)
            if (recorded || decision !is Decision.Pass) store.add(rec)
            return when (decision) {
                is Decision.Pass -> false
                is Decision.Drop, is Decision.Hold -> true
                is Decision.Replace -> {
                    PacketRules.markBypass(decision.packet)
                    @Suppress("UNCHECKED_CAST") val p = decision.packet as Packet<*>
                    if (dir == Direction.OUT) conn.send(p, listener, flush)
                    else (conn as dev.harrison.mcinspector.mixin.ConnectionInvoker).`mcinspector$channelRead0`(null, p)
                    true
                }
            }
        } catch (t: Throwable) {
            dev.harrison.mcinspector.McInspectorClient.LOGGER.warn("[MCInspector] packet hook failed", t)
            return false
        }
    }

    private val typeCache = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()

    private fun typeId(packet: Packet<*>, cls: Class<*>): String = typeCache.getOrPut(cls) {
        runCatching { packet.type().id().toString() }.getOrElse { cls.simpleName }
    }
}
