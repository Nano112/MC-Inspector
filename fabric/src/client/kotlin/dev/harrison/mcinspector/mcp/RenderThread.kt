package dev.harrison.mcinspector.mcp

import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs [block] on the Minecraft render thread and waits for the result. */
object RenderThread {
    const val DEFAULT_TIMEOUT_MS = 10_000L

    fun <T> run(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> T): T {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) return block()
        val future = CompletableFuture<T>()
        mc.execute {
            try { future.complete(block()) } catch (t: Throwable) { future.completeExceptionally(t) }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IllegalStateException("render thread did not respond within ${timeoutMs} ms (game frozen or blocked?)")
        }
    }
}
