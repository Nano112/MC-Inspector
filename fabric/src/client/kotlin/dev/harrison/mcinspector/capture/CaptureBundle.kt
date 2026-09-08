package dev.harrison.mcinspector.capture

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.harrison.mcinspector.packets.PacketExport
import dev.harrison.mcinspector.packets.PacketStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Portable, versioned capture archive. No Minecraft/render-thread dependencies. */
object CaptureBundle {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    data class Result(val path: Path, val records: Int, val bytes: Long)

    fun write(
        directory: Path, label: String, snapshot: PacketStore.ExportSnapshot, context: JsonObject,
        screenshot: ((Path) -> JsonObject)? = null,
    ): Result {
        require(label.length <= 120) { "label must be at most 120 characters" }
        Files.createDirectories(directory)
        val stem = label.replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-').take(60).ifEmpty { "capture" }
        val target = directory.resolve("$stem-${UUID.randomUUID()}.zip")
        val staging = Files.createTempDirectory(directory, ".capture-")
        try {
            val packetFile = staging.resolve("packets.jsonl")
            PacketExport.export(snapshot.records, PacketExport.Format.JSONL, packetFile)
            val shot = screenshot?.invoke(staging.resolve("screenshot.png"))
            val manifest = JsonObject().apply {
                addProperty("schemaVersion", 1)
                addProperty("label", label)
                addProperty("createdAt", Instant.now().toString())
                addProperty("records", snapshot.records.size)
                addProperty("markers", snapshot.records.count { it.isMarker })
                addProperty("firstId", snapshot.records.firstOrNull()?.id)
                addProperty("lastId", snapshot.records.lastOrNull()?.id)
                addProperty("sinceIdExclusive", snapshot.sinceId)
                addProperty("bufferedAtSnapshot", snapshot.buffered)
                addProperty("totalSeen", snapshot.totalSeen)
                addProperty("evictedBeforeSnapshot", snapshot.evicted)
                addProperty("omittedByLimit", snapshot.omittedByLimit)
                addProperty("capturePaused", snapshot.paused)
                addProperty("captureServerSide", snapshot.serverSide)
                add("excludedTypes", gson.toJsonTree(snapshot.exclusions))
                add("screenshot", shot)
                addProperty("timing", "Packets are snapshotted first; screenshot is captured on a subsequent rendered frame.")
            }
            Files.writeString(staging.resolve("manifest.json"), gson.toJson(manifest))
            Files.writeString(staging.resolve("context.json"), gson.toJson(context))
            Files.writeString(staging.resolve("README.txt"), """
                MC-Inspector capture bundle, schema 1
                packets.jsonl: chronological decoded packets, markers and available raw hex.
                context.json: Minecraft/mod versions and client state at export request.
                manifest.json: selection, capture exclusions, dropped/omitted counts and screenshot timing.
                screenshot.png: optional rendered frame, including overlays.
                This is a snapshot of the rolling buffer, not a complete session recording.
                Packets excluded, paused, or evicted before export cannot be recovered.
            """.trimIndent() + "\n")
            val archive = staging.resolve("bundle.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                for (name in listOf("README.txt", "manifest.json", "context.json", "packets.jsonl", "screenshot.png")) {
                    val file = staging.resolve(name)
                    if (!Files.exists(file)) continue
                    zip.putNextEntry(ZipEntry(name))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
            // Publish only complete archives; target names cannot collide or escape the export directory.
            Files.move(archive, target, StandardCopyOption.ATOMIC_MOVE)
            return Result(target, snapshot.records.size, Files.size(target))
        } finally {
            Files.list(staging).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(staging)
        }
    }
}
