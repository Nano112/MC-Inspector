package dev.harrison.mcinspector.mcp

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        require(tool.name !in tools) { "duplicate tool '${tool.name}'" }
        tools[tool.name] = tool
    }
    fun register(vararg more: Tool) = more.forEach { register(it) }
    fun get(name: String): Tool? = tools[name]
    fun all(): List<Tool> = tools.values.toList()
    val size: Int get() = tools.size
}
