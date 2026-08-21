package dev.harrison.mcinspector.packets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacketDecoderTest {
    data class Pos(val x: Int, val y: Int, val z: Int)
    @JvmRecord data class Move(val entityId: Int, val pos: Pos, val onGround: Boolean, val tags: List<String>)
    class Legacy { private val secret = "s"; val n = 3; val child = Pos(1, 2, 3) }
    class Cyclic { var self: Cyclic? = null }
    enum class Mode { A, B }

    @Test fun recordsDecodeByComponent() {
        val j = PacketDecoder.decode(Move(7, Pos(1, 2, 3), true, listOf("a", "b"))).asJsonObject
        assertEquals(7, j["entityId"].asInt)
        assertEquals(2, j["pos"].asJsonObject["y"].asInt)
        assertEquals(true, j["onGround"].asBoolean)
        assertEquals(2, j["tags"].asJsonArray.size())
    }

    @Test fun classesDecodeByField() {
        val j = PacketDecoder.decode(Legacy()).asJsonObject
        assertEquals("s", j["secret"].asString); assertEquals(3, j["n"].asInt); assertEquals(3, j["child"].asJsonObject["z"].asInt)
    }

    @Test fun enumsStringsNullsAndTruncation() {
        assertEquals("B", PacketDecoder.decode(Mode.B).asString)
        assertTrue(PacketDecoder.decode(null).isJsonNull)
        val long = "x".repeat(500)
        assertTrue(PacketDecoder.decode(long).asString.length < 230)
        val big = PacketDecoder.decode(IntArray(100) { it }).asJsonArray
        assertEquals(PacketDecoder.MAX_ELEMENTS + 1, big.size())
    }

    @Test fun cyclesDoNotRecurseForever() {
        val c = Cyclic(); c.self = c
        val j = PacketDecoder.decode(c)
        assertTrue(j.toString().contains("cycle"))
    }

    @Test fun summary() {
        val j = PacketDecoder.decode(Move(7, Pos(1, 2, 3), true, listOf("a")))
        val s = PacketDecoder.summarize(j)
        assertTrue(s.startsWith("entityId=7, pos={…}, onGround=true, tags=[1]"), s)
    }

    @Test fun phaseFromClassName() {
        assertEquals(Phase.GAME, Phase.of("net.minecraft.network.protocol.game.ClientboundSetTimePacket"))
        assertEquals(Phase.CONFIGURATION, Phase.of("net.minecraft.network.protocol.configuration.X"))
        assertEquals(Phase.UNKNOWN, Phase.of("some.mod.Packet"))
    }
}
