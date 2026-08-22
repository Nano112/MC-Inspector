package dev.harrison.mcinspector.ui

import com.google.gson.JsonElement
import dev.harrison.mcinspector.packets.Direction
import dev.harrison.mcinspector.packets.PacketCapture
import dev.harrison.mcinspector.packets.PacketQuery
import dev.harrison.mcinspector.packets.PacketRecord
import dev.harrison.panellib.theme.Icons
import dev.harrison.panellib.theme.Theme
import dev.harrison.panellib.widgets.Widgets
import imgui.ImGui
import imgui.flag.ImGuiSelectableFlags
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The human view of the packet capture: filter bar, live table, decoded detail of the selected packet. */
object PacketsPanel {
    private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private const val MAX_ROWS = 400

    private val filter = ImString(128)
    private var showIn = true
    private var showOut = true
    private var autoscroll = true
    private var selectedId: Long = -1
    private var compiled: Regex? = null
    private var compiledSrc = ""
    private var filterError = false

    fun render() {
        val store = PacketCapture.store
        val t = Theme.current

        // ---- toolbar
        if (Widgets.toggleButton(if (store.paused) "${Icons.PLAY}  Resume" else "${Icons.PAUSE}  Pause", store.paused)) store.paused = !store.paused
        ImGui.sameLine()
        if (Widgets.secondaryButton("${Icons.TRASH}  Clear")) { store.clear(); selectedId = -1 }
        ImGui.sameLine()
        if (Widgets.toggleButton("${Icons.ARROW_DOWN} IN", showIn)) showIn = !showIn
        ImGui.sameLine()
        if (Widgets.toggleButton("${Icons.ARROW_UP} OUT", showOut)) showOut = !showOut
        ImGui.sameLine()
        if (Widgets.toggleButton(Icons.ARROW_DOWN + " auto", autoscroll)) autoscroll = !autoscroll
        Widgets.textField("##filter", filter, "filter type (regex), e.g. chat|move_player", width = -1f)
        updateFilter()
        ImGui.spacing()
        Widgets.faint("${store.size()} buffered · ${store.totalSeen} seen · ${store.dropped} dropped" + if (store.excludes.isNotEmpty()) " · ${store.excludes.size} excludes" else "")
        if (filterError) Widgets.statusText("invalid regex", Widgets.Tone.DANGER)

        // ---- query
        val dir = when { showIn && showOut -> null; showIn -> Direction.IN; showOut -> Direction.OUT; else -> null }
        // Newest MAX_ROWS, displayed oldest→newest so autoscroll follows the tail.
        var rows = store.query(PacketQuery(direction = dir, type = compiled, limit = MAX_ROWS, newestFirst = true)).asReversed()
        if (!showIn && !showOut) rows = emptyList()

        // ---- table (top ~60 %) + detail (rest)
        val avail = ImGui.getContentRegionAvailY()
        val tableH = if (selectedId >= 0) avail * 0.55f else avail
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.ScrollY or ImGuiTableFlags.Resizable
        if (ImGui.beginTable("##packets", 6, flags, 0f, tableH)) {
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableSetupColumn("#", ImGuiTableColumnFlags.WidthFixed, 46f)
            ImGui.tableSetupColumn("time", ImGuiTableColumnFlags.WidthFixed, 82f)
            ImGui.tableSetupColumn("dir", ImGuiTableColumnFlags.WidthFixed, 34f)
            ImGui.tableSetupColumn("bytes", ImGuiTableColumnFlags.WidthFixed, 48f)
            ImGui.tableSetupColumn("type", ImGuiTableColumnFlags.WidthStretch, 1.3f)
            ImGui.tableSetupColumn("summary", ImGuiTableColumnFlags.WidthStretch, 2f)
            ImGui.tableHeadersRow()
            for (r in rows) {
                ImGui.tableNextRow()
                ImGui.tableNextColumn()
                val sel = r.id == selectedId
                if (ImGui.selectable("${r.id}##row${r.id}", sel, ImGuiSelectableFlags.SpanAllColumns)) selectedId = if (sel) -1 else r.id
                ImGui.tableNextColumn(); Widgets.muted(TS.format(Instant.ofEpochMilli(r.epochMs)))
                ImGui.tableNextColumn()
                val c = if (r.dir == Direction.IN) t.info else t.success
                Widgets.colored(if (r.dir == Direction.IN) "IN" else "OUT", c)
                ImGui.tableNextColumn(); Widgets.muted(r.size?.toString() ?: "–")
                ImGui.tableNextColumn(); ImGui.text(r.type.removePrefix("minecraft:"))
                ImGui.tableNextColumn(); Widgets.muted(r.summary)
            }
            if (autoscroll && !store.paused && ImGui.getScrollY() >= ImGui.getScrollMaxY() - 40f) ImGui.setScrollHereY(1f)
            ImGui.endTable()
        }

        // ---- detail
        if (selectedId >= 0) {
            val r = store.get(selectedId)
            ImGui.spacing()
            if (r == null) { Widgets.faint("packet $selectedId was evicted"); return }
            Widgets.semibold("${r.type}  "); ImGui.sameLine(); Widgets.badge(r.dir.name, if (r.dir == Direction.IN) Widgets.Tone.INFO else Widgets.Tone.SUCCESS)
            ImGui.sameLine(); Widgets.faint("${r.cls} · ${r.phase.name.lowercase()} · tick ${r.tick} · #${r.id}" + (r.size?.let { " · $it bytes" } ?: ""))
            if (ImGui.beginChild("##detail", 0f, 0f, true)) {
                renderTree("fields", r.fields, 0)
            }
            ImGui.endChild()
        }
    }

    private fun updateFilter() {
        val src = filter.get()
        if (src == compiledSrc) return
        compiledSrc = src
        compiled = if (src.isBlank()) null else runCatching { Regex(src, RegexOption.IGNORE_CASE) }.getOrNull()
        filterError = src.isNotBlank() && compiled == null
    }

    private fun renderTree(name: String, el: JsonElement, depth: Int) {
        when {
            el.isJsonObject -> {
                val open = ImGui.treeNodeEx("$name##$depth$name", if (depth == 0) ImGuiTreeNodeFlags.DefaultOpen else 0)
                ImGui.sameLine(); Widgets.faint("{${el.asJsonObject.size()}}")
                if (open) { el.asJsonObject.entrySet().forEach { (k, v) -> renderTree(k, v, depth + 1) }; ImGui.treePop() }
            }
            el.isJsonArray -> {
                val open = ImGui.treeNodeEx("$name##$depth$name", 0)
                ImGui.sameLine(); Widgets.faint("[${el.asJsonArray.size()}]")
                if (open) { el.asJsonArray.forEachIndexed { i, v -> renderTree("[$i]", v, depth + 1) }; ImGui.treePop() }
            }
            else -> {
                Widgets.faint(name); ImGui.sameLine(0f, 8f)
                val s = if (el.isJsonNull) "null" else if (el.asJsonPrimitive.isString) el.asString else el.toString()
                ImGui.textWrapped(s)
            }
        }
    }
}
