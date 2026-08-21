package dev.harrison.mcinspector.docs

import dev.harrison.mcinspector.config.InspectorConfig
import dev.harrison.mcinspector.log.LogRingBuffer
import dev.harrison.mcinspector.mcp.ToolRegistry
import dev.harrison.mcinspector.tools.Tools
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/mcp-tools.md must list exactly the registered tools (regenerate it from tools/list when they change). */
class ToolDocsParityTest {
    @Test fun docsMatchRegistry() {
        val reg = ToolRegistry().also { Tools.registerAll(it, InspectorConfig(), LogRingBuffer(10)) }
        val docs = Path.of(System.getProperty("mcinspector.docsDir")).resolve("mcp-tools.md")
        val documented = Files.readAllLines(docs).filter { it.startsWith("### ") }.map { it.removePrefix("### ").trim() }.toSet()
        assertEquals(reg.all().map { it.name }.toSet(), documented, "docs/mcp-tools.md headings vs registered tools")
    }
}
