package dev.harrison.mcinspector.capture

import com.mojang.blaze3d.platform.NativeImage
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Captures the window's default framebuffer (FBO 0) immediately before the buffer swap — the exact
 * pixels the user sees, INCLUDING overlays other mods draw at present time (panel-lib's ImGui),
 * which `Screenshot.takeScreenshot(mainRenderTarget)` does not contain.
 *
 * Driven from PresentCaptureMixin (just before glfwSwapBuffers); requests are queued from any
 * thread and fulfilled on the render thread.
 */
object FrameCapture {
    class Request(val downscale: Int, val out: Path, val future: CompletableFuture<Result>)
    data class Result(val width: Int, val height: Int, val downscale: Int,
        val epochMs: Long = System.currentTimeMillis(), val tick: Long = dev.harrison.mcinspector.mcp.TickScheduler.tick)

    private var pending: Request? = null

    fun request(downscale: Int, out: Path): CompletableFuture<Result> {
        val f = CompletableFuture<Result>()
        val r = Request(downscale.coerceAtLeast(1), out, f)
        synchronized(this) {
            if (pending != null) {
                f.completeExceptionally(IllegalStateException("a frame capture is already pending"))
                return f
            }
            pending = r
        }
        f.whenComplete { _, _ ->
            if (f.isCancelled) synchronized(this) {
                if (pending?.future === f) pending = null
            }
        }
        return f
    }

    /** Called on the render thread right before glfwSwapBuffers. */
    @JvmStatic
    fun onBeforeSwap() {
        val r = synchronized(this) { pending.also { pending = null } } ?: return
        if (r.future.isCancelled) return
        try {
            // Cancellation may race a GL read already in progress. Do not leave a late PNG behind.
            if (!r.future.complete(readFbo0(r.downscale, r.out))) Files.deleteIfExists(r.out)
        } catch (t: Throwable) {
            r.future.completeExceptionally(t)
            runCatching { Files.deleteIfExists(r.out) }
        }
    }

    private fun readFbo0(downscale: Int, out: Path): Result {
        // Real framebuffer size from GLFW: Minecraft's Window may report a smaller, spoofed size while
        // panel-lib embeds the game inside its layout, but FBO 0 is always the full window.
        val fbw = IntArray(1); val fbh = IntArray(1)
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(dev.harrison.mcinspector.input.InputInjector.window(), fbw, fbh)
        val w = fbw[0]
        val h = fbh[0]
        val d = if (w % downscale == 0 && h % downscale == 0) downscale else 1
        val prevFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val prevPack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
        val buf = MemoryUtil.memAlloc(w * h * 4)
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
            GL11.glReadBuffer(GL11.GL_BACK)
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
            val ow = w / d
            val oh = h / d
            val n = d * d
            NativeImage(ow, oh, false).use { img ->
                for (y in 0 until oh) for (x in 0 until ow) {
                    var rs = 0; var gs = 0; var bs = 0
                    for (sy in 0 until d) for (sx in 0 until d) {
                        val srcY = h - 1 - (y * d + sy) // GL rows are bottom-up
                        val i = (srcY * w + (x * d + sx)) * 4
                        rs += buf.get(i).toInt() and 0xFF
                        gs += buf.get(i + 1).toInt() and 0xFF
                        bs += buf.get(i + 2).toInt() and 0xFF
                    }
                    img.setPixelABGR(x, y, (0xFF shl 24) or ((bs / n) shl 16) or ((gs / n) shl 8) or (rs / n))
                }
                img.writeToFile(out)
            }
            return Result(ow, oh, d)
        } finally {
            MemoryUtil.memFree(buf)
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPack)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevFbo)
        }
    }
}
