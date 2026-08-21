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
    })
}
