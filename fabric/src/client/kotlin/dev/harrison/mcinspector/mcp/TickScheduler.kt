package dev.harrison.mcinspector.mcp

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Client-tick counter plus "run this N ticks from now" scheduling. */
object TickScheduler {
    @Volatile var tick: Long = 0
        private set

    private class Job(val at: Long, val seq: Long, val action: () -> Unit) : Comparable<Job> {
        override fun compareTo(other: Job) = compareValuesBy(this, other, { it.at }, { it.seq })
    }

    private val lock = Any()
    private val queue = PriorityQueue<Job>()
    private var seq = 0L

    fun install() {
        ClientTickEvents.END_CLIENT_TICK.register { onTick() }
    }

    /** Visible for tests. */
    internal fun onTick() {
        tick++
        val due = ArrayList<Job>()
        synchronized(lock) {
            while (queue.isNotEmpty() && queue.peek().at <= tick) due += queue.poll()
        }
        due.forEach { runCatching { it.action() } }
    }

    /** Schedule [action] to run on the client thread after [ticks] ticks (>= 1). */
    fun after(ticks: Int, action: () -> Unit) {
        synchronized(lock) { queue += Job(tick + maxOf(1, ticks), seq++, action) }
    }

    /** Block the CALLING thread (never the render thread) until [ticks] ticks have elapsed. */
    fun awaitTicks(ticks: Int, timeoutMs: Long = 60_000) {
        val latch = CountDownLatch(1)
        after(ticks) { latch.countDown() }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("timed out waiting $ticks ticks (client not ticking?)")
        }
    }
}
