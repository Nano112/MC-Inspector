package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.compat.Compat
import com.google.gson.JsonObject
import dev.harrison.mcinspector.config.InspectorConfig
import dev.harrison.mcinspector.input.InputInjector
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.dbl
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.schema
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max
import kotlin.math.roundToInt

class ScreenshotTool(private val cfg: InspectorConfig) : Tool {
    override val name = "screenshot"
    override val description = "Capture the game window as a PNG image. `scale` (0.1-1, default ${cfg.screenshotScale}) " +
        "downsamples by an integer factor to keep the image small. The text block reports `mouseScale`: multiply image " +
        "pixel coordinates by it to get the coordinates that mouse_move/mouse_click expect."
    override val inputSchema = schema(
        "scale" to prop("number", "Approximate scale factor 0.1-1.0 (rounded to 1/n). Default ${cfg.screenshotScale}."),
    )

    override fun call(args: JsonObject): ToolResult {
        val scale = (args.dbl("scale") ?: cfg.screenshotScale).coerceIn(0.05, 1.0)
        val downscale = max(1, (1.0 / scale).roundToInt())
        val tmp = Files.createTempFile("mcinspector-shot", ".png")
        // The GPU readback completes on a LATER frame (copyTextureToBuffer callback),
        // so kick it off on the render thread and await the future from this thread.
        val done = CompletableFuture<JsonObject>()
        RenderThread.run {
            val mc = Minecraft.getInstance()
            val target = Compat.mainRenderTarget()
            // takeScreenshot requires width/height divisible by the factor; fall back to 1 if not.
            val d = if (target.width % downscale == 0 && target.height % downscale == 0) downscale else 1
            val win = mc.window
            val meta = obj {
                "downscale" to d
                "mouseScale" to d / InputInjector.pixelRatio()
                "mouseWidth" to win.screenWidth; "mouseHeight" to win.screenHeight
                "framebufferWidth" to win.width; "framebufferHeight" to win.height; "guiScale" to win.guiScale
                "screen" to Compat.screen()?.javaClass?.simpleName
            }
            try {
                Screenshot.takeScreenshot(target, d) { img ->
                    try {
                        meta.addProperty("width", img.width); meta.addProperty("height", img.height)
                        img.writeToFile(tmp)
                        done.complete(meta)
                    } catch (t: Throwable) { done.completeExceptionally(t) } finally { img.close() }
                }
            } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        val meta = try {
            done.get(15, TimeUnit.SECONDS)
        } catch (e: ExecutionException) { throw e.cause ?: e }
        catch (e: TimeoutException) { throw IllegalStateException("screenshot readback never completed (is the game rendering?)") }
        val bytes = Files.readAllBytes(tmp)
        Files.deleteIfExists(tmp)
        return ToolResult.image(Base64.getEncoder().encodeToString(bytes), meta)
    }
}
