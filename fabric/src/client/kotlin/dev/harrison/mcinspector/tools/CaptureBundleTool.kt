package dev.harrison.mcinspector.tools

import com.google.gson.JsonObject
import dev.harrison.mcinspector.capture.CaptureBundles
import dev.harrison.mcinspector.config.InspectorConfig
import dev.harrison.mcinspector.mcp.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class CaptureBundleTool(private val cfg: InspectorConfig) : Tool {
    override val name = "capture_bundle"
    override val description = "Save a ZIP in <gameDir>/capture-exports/ containing decoded packets, raw hex where available, " +
        "markers, client/version context, and an optional screenshot including overlays. Exports the newest matching buffered " +
        "records in chronological order; manifest reports evictions, exclusions and limit truncation. The screenshot follows " +
        "the packet snapshot on a later rendered frame. Does not change capture state or upload anything."
    override val inputSchema = schema(
        "label" to prop("string", "Descriptive label, up to 120 characters; default effect."),
        "since" to prop("integer", "Only records after this packet/marker ID (exclusive); default 0."),
        "limit" to prop("integer", "Newest matching records to export, 1–10000; default 2000."),
        "screenshot" to prop("boolean", "Include a rendered PNG; default true."),
        "scale" to prop("number", "Approximate screenshot scale 0.1–1.0; default configured screenshot scale."),
    )
    override fun call(args: JsonObject): ToolResult {
        check(!net.minecraft.client.Minecraft.getInstance().isSameThread) {
            "Call capture_bundle directly through MCP, or use exec_script with thread=background"
        }
        val saved = try {
            CaptureBundles.request(args.str("label") ?: "effect", args.long("since") ?: 0,
                args.int("limit") ?: 2000, args.bool("screenshot") ?: true,
                args.dbl("scale") ?: cfg.screenshotScale).get(120, TimeUnit.SECONDS)
        } catch (e: ExecutionException) { throw e.cause ?: e }
        return ToolResult.json(obj { "path" to saved.path.toString(); "records" to saved.records; "bytes" to saved.bytes })
    }
}
