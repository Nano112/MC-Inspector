package dev.harrison.mcinspector.capture

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.harrison.mcinspector.packets.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

class CaptureBundleTest {
    @TempDir lateinit var directory: Path

    private fun packet(store: PacketStore, name: String) {
        store.add(PacketRecord(store.nextId(), 1000, 20, Direction.IN, Side.CLIENT,
            name, "ExamplePacket", Phase.GAME, null) { JsonObject().apply { addProperty("entityId", 42) } })
    }

    @Test fun bundleContainsChronologicalPacketsMarkersContextAndScreenshot() {
        val store = PacketStore(10)
        packet(store, "minecraft:add_entity")
        store.mark("effect appears", 21)
        packet(store, "minecraft:set_entity_data")
        val snapshot = store.snapshotForExport()
        store.clear() // An export must not change if the live buffer is cleared.
        val context = JsonObject().apply { addProperty("minecraftVersion", "1.21.11") }
        val result = CaptureBundle.write(directory, "test effect", snapshot, context) { file ->
            Files.write(file, byteArrayOf(1, 2, 3))
            JsonObject().apply { addProperty("file", "screenshot.png"); addProperty("tick", 22) }
        }
        ZipFile(result.path.toFile()).use { zip ->
            fun read(name: String) = zip.getInputStream(zip.getEntry(name)).reader().readText()
            val manifest = JsonParser.parseString(read("manifest.json")).asJsonObject
            assertEquals(1, manifest["schemaVersion"].asInt)
            assertEquals(3, manifest["records"].asInt)
            assertEquals(1, manifest["markers"].asInt)
            assertEquals(22, manifest["screenshot"].asJsonObject["tick"].asInt)
            val packets = read("packets.jsonl").lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }.toList()
            assertEquals(listOf(1L, 2L, 3L), packets.map { it["id"].asLong })
            assertEquals("effect appears", packets[1]["marker"].asString)
            assertEquals(42, packets[2]["fields"].asJsonObject["entityId"].asInt)
            assertContains(read("context.json"), "1.21.11")
            assertNotNull(zip.getEntry("screenshot.png"))
        }
        assertEquals(3, result.records)
        assertTrue(result.bytes > 0)
        Files.list(directory).use { assertEquals(1, it.count()) }
    }

    @Test fun snapshotReportsEvictionsLimitAndCaptureConfiguration() {
        val store = PacketStore(3)
        repeat(5) { packet(store, "packet-$it") }
        store.paused = true
        store.excludes = listOf(Regex("keep_alive"))
        val snapshot = store.snapshotForExport(sinceId = 2, limit = 2)
        assertEquals(listOf(4L, 5L), snapshot.records.map { it.id })
        assertEquals(2, snapshot.evicted)
        assertEquals(1, snapshot.omittedByLimit)
        assertTrue(snapshot.paused)
        assertEquals(listOf("keep_alive"), snapshot.exclusions)
        assertFailsWith<IllegalArgumentException> { store.snapshotForExport(limit = 0) }
    }

    @Test fun fileNamesCannotEscapeDirectoryOrOverwritePreviousCapture() {
        val store = PacketStore(3)
        val snapshot = store.snapshotForExport()
        val a = CaptureBundle.write(directory, "../../world/escape", snapshot, JsonObject())
        val b = CaptureBundle.write(directory, "../../world/escape", snapshot, JsonObject())
        assertEquals(directory, a.path.parent)
        assertNotEquals(a.path, b.path)
        ZipFile(a.path.toFile()).use { assertNull(it.getEntry("screenshot.png")) }
    }

    @Test fun failedScreenshotDoesNotPublishPartialBundle() {
        assertFailsWith<IllegalStateException> {
            CaptureBundle.write(directory, "broken", PacketStore(3).snapshotForExport(), JsonObject()) {
                Files.writeString(it, "partial")
                error("no frame")
            }
        }
        Files.list(directory).use { assertEquals(0, it.count()) }
    }

    @Test fun cancellingPendingFrameAllowsANewCapture() {
        val first = FrameCapture.request(1, directory.resolve("first.png"))
        val blocked = FrameCapture.request(1, directory.resolve("blocked.png"))
        assertTrue(blocked.isCompletedExceptionally)
        first.cancel(false)
        val next = FrameCapture.request(1, directory.resolve("next.png"))
        try { assertFalse(next.isDone) } finally { next.cancel(false) }
    }
}
