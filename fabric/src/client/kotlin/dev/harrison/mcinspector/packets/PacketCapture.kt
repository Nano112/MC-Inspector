package dev.harrison.mcinspector.packets

import dev.harrison.mcinspector.mcp.TickScheduler
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

/** Static entry point for the Connection mixin. Hot path: must stay allocation-light and never throw. */
object PacketCapture {
    @JvmField val store = PacketStore()

    @JvmStatic
    fun onInbound(conn: Connection, packet: Packet<*>) = record(conn, packet, Direction.IN)

    @JvmStatic
    fun onOutbound(conn: Connection, packet: Packet<*>) = record(conn, packet, Direction.OUT)

    private fun record(conn: Connection, packet: Packet<*>, dir: Direction) {
        try {
            // In singleplayer the integrated server's connections live in this JVM too; a connection
            // that RECEIVES clientbound packets is the client's side.
            val side = if (conn.receiving == PacketFlow.CLIENTBOUND) Side.CLIENT else Side.SERVER
            if (side == Side.SERVER && !store.captureServerSide) return
            val cls = packet.javaClass
            val type = typeId(packet, cls)
            if (!store.accepts(type)) return
            val rec = PacketRecord(
                id = store.nextId(), epochMs = System.currentTimeMillis(), tick = TickScheduler.tick,
                dir = dir, side = side, type = type, cls = cls.simpleName, phase = Phase.of(cls.name),
                packet = packet, decoder = PacketDecoder::decode,
            )
            if (dir == Direction.IN) PacketSizes.takeInbound(packet)?.let { (n, raw) -> rec.size = n; rec.raw = raw } else PacketSizes.expectOutbound(packet, rec)
            store.add(rec)
        } catch (_: Throwable) {
            // never disturb networking
        }
    }

    private val typeCache = java.util.concurrent.ConcurrentHashMap<Class<*>, String>()

    private fun typeId(packet: Packet<*>, cls: Class<*>): String = typeCache.getOrPut(cls) {
        runCatching { packet.type().id().toString() }.getOrElse { cls.simpleName }
    }
}
