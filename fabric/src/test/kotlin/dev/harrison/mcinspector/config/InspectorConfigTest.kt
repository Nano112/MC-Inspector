package dev.harrison.mcinspector.config

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InspectorConfigTest {
    @AfterTest fun clear() { System.clearProperty("mcinspector.port"); System.clearProperty("mcinspector.enabled") }

    @Test fun createsDefaultFileWhenMissing() {
        val dir = Files.createTempDirectory("mcinspector")
        val path = dir.resolve("sub/mcinspector.json")
        val cfg = InspectorConfig.load(path)
        assertEquals(InspectorConfig.DEFAULT_PORT, cfg.port)
        assertNull(cfg.authToken)
        assertTrue(Files.exists(path))
        assertTrue(Files.readString(path).contains("\"port\": 38271"))
    }

    @Test fun parsesFile() {
        val cfg = InspectorConfig.parse("""{"port":1234,"auth_token":"abc","screenshot_scale":0.25,"enabled":false}""")
        assertEquals(1234, cfg.port); assertEquals("abc", cfg.authToken); assertEquals(0.25, cfg.screenshotScale); assertEquals(false, cfg.enabled)
    }

    @Test fun blankTokenIsNull() {
        assertNull(InspectorConfig.parse("""{"auth_token":""}""").authToken)
    }

    @Test fun systemPropertyOverrides() {
        System.setProperty("mcinspector.port", "99")
        System.setProperty("mcinspector.enabled", "false")
        val cfg = InspectorConfig(port = 5).withSystemOverrides()
        assertEquals(99, cfg.port); assertEquals(false, cfg.enabled)
    }
}
