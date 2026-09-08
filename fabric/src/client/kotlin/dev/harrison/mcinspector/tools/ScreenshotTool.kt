package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.compat.Compat
import com.google.gson.JsonObject
import dev.harrison.mcinspector.capture.FrameCapture
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
import java.nio.file.Files
import java.util.Base64
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
        check(!Minecraft.getInstance().isSameThread) {
            "Call screenshot directly through MCP, or use exec_script with thread=background"
        }
        val scale = (args.dbl("scale") ?: cfg.screenshotScale).coerceIn(0.1, 1.0)
        val downscale = max(1, (1.0 / scale).roundToInt())
        val tmp = Files.createTempFile("mcinspector-shot", ".png")
        // Captured from FBO 0 right before the buffer swap (PresentCaptureMixin) so overlays drawn at
        // present time (panel-lib's ImGui) are included. Fulfilled on the render thread on the next frame.
        val future = FrameCapture.request(downscale, tmp)
        try {
            val r = try {
                future.get(15, TimeUnit.SECONDS)
            } catch (e: ExecutionException) { throw e.cause ?: e }
            catch (e: TimeoutException) { throw IllegalStateException("frame capture never completed (is the game rendering?)") }
            val meta = RenderThread.run {
                val mc = Minecraft.getInstance()
                val win = mc.window
                obj {
                    "epochMs" to r.epochMs; "tick" to r.tick
                    "width" to r.width; "height" to r.height; "downscale" to r.downscale
                    "mouseScale" to r.downscale / InputInjector.pixelRatio()
                    "mouseWidth" to win.screenWidth; "mouseHeight" to win.screenHeight
                    "framebufferWidth" to win.width; "framebufferHeight" to win.height; "guiScale" to win.guiScale
                    "screen" to Compat.screen()?.javaClass?.simpleName
                }
            }
            val bytes = Files.readAllBytes(tmp)
            return ToolResult.image(Base64.getEncoder().encodeToString(bytes), meta)
        } finally {
            future.cancel(false)
            Files.deleteIfExists(tmp)
        }
    }
}
