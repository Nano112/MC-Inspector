package dev.harrison.mcinspector.packets

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketStoreTest {
    private fun rec(store: PacketStore, type: String, dir: Direction = Direction.IN) =
        PacketRecord(store.nextId(), 0, 0, dir, Side.CLIENT, type, "X", Phase.GAME, null) { JsonPrimitive("v") }

    @Test fun ringEvictsOldest() {
        val s = PacketStore(3)
        repeat(5) { s.add(rec(s, "t$it")) }
        assertEquals(3, s.size()); assertEquals(2, s.dropped); assertEquals(5, s.totalSeen)
        assertNull(s.get(1)); assertEquals("t4", s.get(5)?.type)
    }

    @Test fun queryFiltersAndOrders() {
        val s = PacketStore(100)
        s.add(rec(s, "minecraft:set_time", Direction.IN)); s.add(rec(s, "minecraft:move_player", Direction.OUT)); s.add(rec(s, "minecraft:set_entity_motion", Direction.IN))
        assertEquals(listOf(3L, 1L), s.query(PacketQuery(direction = Direction.IN)).map { it.id })
        assertEquals(listOf(1L, 3L), s.query(PacketQuery(direction = Direction.IN, newestFirst = false)).map { it.id })
        assertEquals(listOf(3L), s.query(PacketQuery(type = Regex("entity"))).map { it.id })
        assertEquals(listOf(3L), s.query(PacketQuery(sinceId = 2)).map { it.id })
        assertEquals(1, s.query(PacketQuery(limit = 1)).size)
        assertEquals(3L, s.query(PacketQuery(limit = 1)).first().id)
    }

    @Test fun markersSearchRatesPrevious() {
        val s = PacketStore(10)
        val a = PacketRecord(s.nextId(), System.currentTimeMillis(), 0, Direction.IN, Side.CLIENT, "minecraft:set_health", "X", Phase.GAME, null) { JsonParser.parseString("""{"health":20}""") }
        s.add(a)
        val m = s.mark("before hit", 5)
        val b = PacketRecord(s.nextId(), System.currentTimeMillis(), 0, Direction.IN, Side.CLIENT, "minecraft:set_health", "X", Phase.GAME, null) { JsonParser.parseString("""{"health":14}""") }
        s.add(b)
        assertTrue(m.isMarker); assertEquals(listOf(b.id), s.query(PacketQuery(sinceId = m.id)).map { it.id })
        assertEquals(a, s.previousOfSameType(b))
        assertEquals(listOf(b.id), s.search(Regex("\"health\":14"), 10).map { it.id })
        assertEquals(1, JsonDiff.diff(a.fields, b.fields).size)
        assertTrue((s.rates()["minecraft:set_health"]?.first ?: 0.0) > 0.0)
    }

    @Test fun statsPauseExcludes() {
        val s = PacketStore(10)
        s.add(rec(s, "a")); s.add(rec(s, "a")); s.add(rec(s, "b"))
        assertEquals(mapOf("a" to 2L, "b" to 1L), s.stats())
        s.paused = true; assertFalse(s.accepts("a")); s.paused = false; assertTrue(s.accepts("a"))
        s.excludes = listOf(Regex("keep_alive|set_time")); assertFalse(s.accepts("minecraft:set_time")); assertTrue(s.accepts("minecraft:chat"))
        s.clear(); assertEquals(0, s.size()); assertTrue(s.stats().isEmpty())
    }
}
