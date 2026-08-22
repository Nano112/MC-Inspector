package dev.harrison.mcinspector.packets

/**
 * Correlates wire sizes with captured records. Outbound: the record is created at `doSendPacket`
 * and the encoder runs later (netty thread) → remember record by packet identity until the size
 * arrives. Inbound: the decoder runs before `channelRead0` → remember the size until the record is
 * created. In-memory (singleplayer) connections never encode, so those records keep `size == null`.
 */
object PacketSizes {
    private const val MAX = 8192

    private class IdKey(val o: Any) {
        override fun equals(other: Any?) = other is IdKey && other.o === o
        override fun hashCode() = System.identityHashCode(o)
    }

    private class Bounded<V> : LinkedHashMap<IdKey, V>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdKey, V>?) = size > MAX
    }

    private val awaitingSize = Bounded<PacketRecord>()   // outbound records waiting for the encoder
    private val pendingInbound = Bounded<Int>()          // decoded sizes waiting for channelRead0
    private val decodeStart = ThreadLocal<Int>()

    fun expectOutbound(packet: Any, rec: PacketRecord) = synchronized(awaitingSize) { awaitingSize[IdKey(packet)] = rec }

    /** PacketEncoder.encode RETURN. */
    @JvmStatic
    fun onEncoded(packet: Any, bytes: Int) {
        val rec = synchronized(awaitingSize) { awaitingSize.remove(IdKey(packet)) } ?: return
        rec.size = bytes
    }

    /** PacketDecoder.decode HEAD. */
    @JvmStatic
    fun onDecodeStart(readable: Int) = decodeStart.set(readable)

    /** PacketDecoder.decode RETURN with the decoded packet. */
    @JvmStatic
    fun onDecoded(packet: Any?) {
        val n = decodeStart.get() ?: return
        decodeStart.remove()
        if (packet == null) return
        synchronized(pendingInbound) { pendingInbound[IdKey(packet)] = n }
    }

    /** Called when an inbound record is created; returns the size seen by the decoder, if any. */
    fun takeInbound(packet: Any): Int? = synchronized(pendingInbound) { pendingInbound.remove(IdKey(packet)) }
}
