package dev.harrison.mcinspector.packets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PacketRewriteTest {
    @JvmRecord data class Health(val health: Float, val food: Int, val sat: Float)
    class Mutable { var message: String = "a"; var n: Int = 1 }
    enum class Mode { A, B }
    @JvmRecord data class Modes(val mode: Mode, val id: java.util.UUID?)

    @Test fun recordsAreCopied() {
        val p = Health(20f, 20, 5f)
        val q = PacketRewrite.set(p, "health", 3) as Health
        assertNotSame(p, q); assertEquals(3f, q.health); assertEquals(20, q.food)
        assertEquals(3f, PacketRewrite.get(q, "health"))
    }

    @Test fun classesAreMutatedInPlace() {
        val m = Mutable()
        val r = PacketRewrite.setAll(m, mapOf("message" to "hello", "n" to 7L))
        assertSame(m, r); assertEquals("hello", m.message); assertEquals(7, m.n)
    }

    @Test fun coercion() {
        val r = PacketRewrite.set(Modes(Mode.A, null), "mode", "b") as Modes
        assertEquals(Mode.B, r.mode)
        val u = PacketRewrite.set(r, "id", "123e4567-e89b-12d3-a456-426614174000") as Modes
        assertEquals("123e4567-e89b-12d3-a456-426614174000", u.id.toString())
        assertFailsWith<IllegalArgumentException> { PacketRewrite.set(Health(1f, 1, 1f), "nope", 1) }
    }
}
