package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.schema
import net.fabricmc.loader.api.FabricLoader

class ModsTool : Tool {
    override val name = "list_mods"
    override val description = "List loaded Fabric mods (id, version, name)."
    override val inputSchema = schema()

    override fun call(args: JsonObject): ToolResult = ToolResult.json(JsonArray().apply {
        FabricLoader.getInstance().allMods.sortedBy { it.metadata.id }.forEach { m ->
            add(obj { "id" to m.metadata.id; "version" to m.metadata.version.friendlyString; "name" to m.metadata.name })
        }
    })
}
