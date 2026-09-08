package dev.harrison.mcinspector.capture

import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.packets.PacketCapture
import dev.harrison.mcinspector.tools.StateTool
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Shared asynchronous export for the in-game panel and MCP. Never blocks the render thread. */
object CaptureBundles {
    private val busy = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mcinspector-capture-export").apply { isDaemon = true } }
    val isBusy get() = busy.get()
    @Volatile var lastMessage: String? = null
        private set

    fun request(label: String = "effect", since: Long = 0, limit: Int = 2000,
                screenshot: Boolean = true, scale: Double = 0.5): CompletableFuture<CaptureBundle.Result> {
        require(label.length <= 120 && since >= 0 && limit in 1..10_000 && scale.isFinite() && scale in 0.1..1.0)
        if (!busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(IllegalStateException("A capture export is already running"))
        val result = CompletableFuture<CaptureBundle.Result>()
        lastMessage = "Saving capture bundle…"
        try {
            val snapshot = PacketCapture.store.snapshotForExport(since, limit)
            worker.execute {
                try {
                    val loader = FabricLoader.getInstance()
                    val context = StateTool().snapshot().apply {
                        addProperty("minecraftVersion", loader.getModContainer("minecraft").get().metadata.version.friendlyString)
                        addProperty("inspectorVersion", loader.getModContainer("mcinspector").get().metadata.version.friendlyString)
                    }
                    val takeScreenshot: ((Path) -> com.google.gson.JsonObject)? = if (!screenshot) null else { path ->
                        val future = FrameCapture.request((1.0 / scale).roundToInt().coerceAtLeast(1), path)
                        try {
                            val frame = future.get(15, TimeUnit.SECONDS)
                            obj { "file" to "screenshot.png"; "width" to frame.width; "height" to frame.height
                                "downscale" to frame.downscale; "epochMs" to frame.epochMs; "tick" to frame.tick }
                        } finally { future.cancel(false) }
                    }
                    val saved = CaptureBundle.write(loader.gameDir.resolve("capture-exports"), label, snapshot, context, takeScreenshot)
                    lastMessage = "Saved ${saved.records} records: ${saved.path.fileName}"
                    result.complete(saved)
                } catch (t: Throwable) {
                    val failure = if (t is ExecutionException) t.cause ?: t else t
                    lastMessage = "Capture failed: ${failure.message}"
                    result.completeExceptionally(failure)
                } finally { busy.set(false) }
            }
        } catch (t: Throwable) {
            busy.set(false)
            result.completeExceptionally(t)
        }
        return result
    }

    fun shutdown() { worker.shutdownNow() }
}
