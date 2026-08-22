package dev.harrison.mcinspector.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

data class InspectorConfig(
    val port: Int = DEFAULT_PORT,
    val authToken: String? = null,
    val screenshotScale: Double = 0.5,
    val enabled: Boolean = true,
    /** "always" (vanilla), "never" (cursor stays free; agent-only machine), "auto" (vanilla unless the agent injected input recently). */
    val cursorLock: String = "auto",
    /** Start recording packets at launch (rules still run either way). Off by default: long sessions would fill the buffer. */
    val packetCapture: Boolean = false,
    /** Rolling packet buffer size (records). */
    val packetBuffer: Int = 5000,
    /** How many newest records keep the live packet object; older ones are materialised to JSON in the background (bounded memory). */
    val packetRetain: Int = 1000,
) {
    companion object {
        const val DEFAULT_PORT = 38271
        private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        /** Loads (creating a default file if missing) then applies -Dmcinspector.* overrides. */
        fun load(path: Path): InspectorConfig {
            val fromFile = if (Files.exists(path)) {
                parse(Files.readString(path))
            } else {
                InspectorConfig().also {
                    Files.createDirectories(path.parent)
                    Files.writeString(path, it.toJsonString())
                }
            }
            return fromFile.withSystemOverrides()
        }

        fun parse(json: String): InspectorConfig {
            val o = JsonParser.parseString(json).asJsonObject
            val d = InspectorConfig()
            return InspectorConfig(
                port = o.get("port")?.takeUnless { it.isJsonNull }?.asInt ?: d.port,
                authToken = o.get("auth_token")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() },
                screenshotScale = o.get("screenshot_scale")?.takeUnless { it.isJsonNull }?.asDouble ?: d.screenshotScale,
                enabled = o.get("enabled")?.takeUnless { it.isJsonNull }?.asBoolean ?: d.enabled,
                cursorLock = o.get("cursor_lock")?.takeUnless { it.isJsonNull }?.asString
                    ?: o.get("lock_cursor")?.takeUnless { it.isJsonNull }?.asBoolean?.let { if (it) "always" else "never" } ?: d.cursorLock,
                packetCapture = o.get("packet_capture")?.takeUnless { it.isJsonNull }?.asBoolean ?: d.packetCapture,
                packetBuffer = o.get("packet_buffer")?.takeUnless { it.isJsonNull }?.asInt ?: d.packetBuffer,
                packetRetain = o.get("packet_retain")?.takeUnless { it.isJsonNull }?.asInt ?: d.packetRetain,
            )
        }
    }

    fun withSystemOverrides(): InspectorConfig = copy(
        port = System.getProperty("mcinspector.port")?.toIntOrNull() ?: port,
        enabled = System.getProperty("mcinspector.enabled")?.toBooleanStrictOrNull() ?: enabled,
        authToken = System.getProperty("mcinspector.token")?.takeIf { it.isNotBlank() } ?: authToken,
    )

    fun toJsonString(): String = gson.toJson(JsonObject().apply {
        addProperty("port", port)
        addProperty("auth_token", authToken)
        addProperty("screenshot_scale", screenshotScale)
        addProperty("enabled", enabled)
        addProperty("cursor_lock", cursorLock)
        addProperty("packet_capture", packetCapture)
        addProperty("packet_buffer", packetBuffer)
        addProperty("packet_retain", packetRetain)
    })
}
