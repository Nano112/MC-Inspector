package dev.harrison.mcinspector.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.packets.Direction
import dev.harrison.mcinspector.packets.Hex
import dev.harrison.mcinspector.packets.JsonDiff
import dev.harrison.mcinspector.packets.PacketCapture
import dev.harrison.mcinspector.packets.PacketQuery
import dev.harrison.mcinspector.packets.PacketRecord
import dev.harrison.panellib.theme.Icons
import dev.harrison.panellib.theme.Theme
import dev.harrison.panellib.widgets.Widgets
import imgui.ImGui
import imgui.flag.ImGuiSelectableFlags
import imgui.flag.ImGuiTableBgTarget
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Human view of the packet capture: Live tab (filter bar, table with markers, detail with fields / diff / hex)
 * and Types tab (counts, bytes, rates, hide/solo per type).
 */
object PacketsPanel {
    private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private const val MAX_ROWS = 400

    private val filter = ImString(128)
    private val markerLabel = ImString(64)
    private var showIn = true
    private var showOut = true
    private var autoscroll = true
    private var selectedId: Long = -1
    private var compiled: Regex? = null
    private var compiledSrc = ""
    private var filterError = false
    private var detailMode = 0 // 0 fields, 1 diff, 2 hex
    private var markerCounter = 0

    fun render() {
        Widgets.tabBar("##packets-tabs", listOf("Live" to ::renderLive, "Types" to ::renderTypes))
    }

    // ------------------------------------------------------------------ Live

    private fun renderLive() {
        val store = PacketCapture.store
        val t = Theme.current

        if (Widgets.toggleButton(if (store.paused) "${Icons.PLAY}  Resume" else "${Icons.PAUSE}  Pause", store.paused)) store.paused = !store.paused
        ImGui.sameLine()
        if (Widgets.secondaryButton("${Icons.TRASH}  Clear")) { store.clear(); selectedId = -1 }
        ImGui.sameLine()
        if (Widgets.toggleButton("${Icons.ARROW_DOWN} IN", showIn)) showIn = !showIn
        ImGui.sameLine()
        if (Widgets.toggleButton("${Icons.ARROW_UP} OUT", showOut)) showOut = !showOut
        ImGui.sameLine()
        if (Widgets.toggleButton("${Icons.ARROW_DOWN} auto", autoscroll)) autoscroll = !autoscroll
        ImGui.sameLine()
        if (Widgets.secondaryButton("${Icons.TAG}  Mark")) {
            val label = markerLabel.get().ifBlank { "mark ${++markerCounter}" }
            store.mark(label, TickScheduler.tick); markerLabel.set("")
        }
        ImGui.sameLine()
        Widgets.textField("##marker", markerLabel, "marker label", width = 120f)

        Widgets.textField("##filter", filter, "filter type (regex), e.g. chat|move_player", width = -1f)
        updateFilter()
        Widgets.faint("${store.size()} buffered · ${store.totalSeen} seen · ${store.dropped} dropped" + if (store.excludes.isNotEmpty()) " · ${store.excludes.size} excludes" else "")
        if (filterError) Widgets.statusText("invalid regex", Widgets.Tone.DANGER)

        val dir = when { showIn && showOut -> null; showIn -> Direction.IN; showOut -> Direction.OUT; else -> null }
        var rows = store.query(PacketQuery(direction = dir, type = compiled, limit = MAX_ROWS, newestFirst = true)).asReversed()
        if (!showIn && !showOut) rows = emptyList()

        val avail = ImGui.getContentRegionAvailY()
        val tableH = if (selectedId >= 0) avail * 0.5f else avail
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
                if (r.isMarker) {
                    ImGui.tableSetBgColor(ImGuiTableBgTarget.RowBg0, ImGui.getColorU32(t.accent.x, t.accent.y, t.accent.z, 0.18f))
                    Widgets.colored("${r.id}", t.accent)
                    ImGui.tableNextColumn(); Widgets.muted(TS.format(Instant.ofEpochMilli(r.epochMs)))
                    ImGui.tableNextColumn(); ImGui.tableNextColumn(); ImGui.tableNextColumn()
                    Widgets.colored("${Icons.TAG}  ${r.type.removePrefix("marker:")}", t.accent)
                    ImGui.tableNextColumn(); Widgets.faint("tick ${r.tick}")
                    continue
                }
                val sel = r.id == selectedId
                if (ImGui.selectable("${r.id}##row${r.id}", sel, ImGuiSelectableFlags.SpanAllColumns)) selectedId = if (sel) -1 else r.id
                rowContextMenu(r)
                ImGui.tableNextColumn(); Widgets.muted(TS.format(Instant.ofEpochMilli(r.epochMs)))
                ImGui.tableNextColumn()
                Widgets.colored(if (r.dir == Direction.IN) "IN" else "OUT", if (r.dir == Direction.IN) t.info else t.success)
                ImGui.tableNextColumn(); Widgets.muted(r.size?.toString() ?: "–")
                ImGui.tableNextColumn(); ImGui.text(r.type.removePrefix("minecraft:"))
                ImGui.tableNextColumn(); Widgets.muted(r.summary)
            }
            if (autoscroll && !store.paused && ImGui.getScrollY() >= ImGui.getScrollMaxY() - 40f) ImGui.setScrollHereY(1f)
            ImGui.endTable()
        }

        if (selectedId >= 0) renderDetail(store.get(selectedId))
    }

    private fun rowContextMenu(r: PacketRecord) {
        if (ImGui.beginPopupContextItem("ctx${r.id}")) {
            Widgets.semibold(r.type)
            ImGui.separator()
            if (ImGui.menuItem("${Icons.FILTER}  Only this type")) filter.set(Regex.escape(r.type))
            if (ImGui.menuItem("${Icons.EYE}  Hide this type")) PacketCapture.store.excludes = PacketCapture.store.excludes + Regex(Regex.escape(r.type))
            if (ImGui.menuItem("${Icons.COPY}  Copy JSON")) ImGui.setClipboardText(PRETTY.toJson(r.fields))
            if (ImGui.menuItem("${Icons.TAG}  Mark here")) PacketCapture.store.mark("after #${r.id}", TickScheduler.tick)
            ImGui.endPopup()
        }
    }

    private fun renderDetail(r: PacketRecord?) {
        val store = PacketCapture.store
        ImGui.spacing()
        if (r == null) { Widgets.faint("packet $selectedId was evicted"); return }
        Widgets.semibold(r.type); ImGui.sameLine()
        Widgets.badge(r.dir.name, if (r.dir == Direction.IN) Widgets.Tone.INFO else Widgets.Tone.SUCCESS)
        ImGui.sameLine(); Widgets.faint("${r.cls} · ${r.phase.name.lowercase()} · tick ${r.tick} · #${r.id}" + (r.size?.let { " · $it bytes" } ?: ""))
        if (Widgets.toggleButton("Fields", detailMode == 0)) detailMode = 0
        ImGui.sameLine(); if (Widgets.toggleButton("Diff vs previous", detailMode == 1)) detailMode = 1
        ImGui.sameLine(); if (Widgets.toggleButton("Hex", detailMode == 2)) detailMode = 2
        ImGui.sameLine(); if (Widgets.ghostButton("${Icons.COPY}  Copy")) ImGui.setClipboardText(PRETTY.toJson(r.fields))
        if (ImGui.beginChild("##detail", 0f, 0f, true)) {
            when (detailMode) {
                0 -> renderTree("fields", r.fields, 0)
                1 -> {
                    val prev = store.previousOfSameType(r)
                    if (prev == null) Widgets.faint("no earlier ${r.type} in the buffer")
                    else {
                        val changes = JsonDiff.diff(prev.fields, r.fields)
                        Widgets.faint("vs #${prev.id} (${TS.format(Instant.ofEpochMilli(prev.epochMs))}) · ${changes.size} change(s)")
                        for (c in changes) {
                            Widgets.semibold(c.path)
                            ImGui.sameLine(0f, 8f); Widgets.colored(c.before?.toString() ?: "∅", Theme.current.danger)
                            ImGui.sameLine(0f, 6f); Widgets.faint(Icons.ARROW_RIGHT)
                            ImGui.sameLine(0f, 6f); Widgets.colored(c.after?.toString() ?: "∅", Theme.current.success)
                        }
                    }
                }
                else -> {
                    val raw = r.raw
                    if (raw == null) Widgets.faint("no wire bytes (in-memory connection, or not encoded yet)")
                    else { Widgets.faint("first ${raw.size} of ${r.size ?: raw.size} bytes"); for (line in Hex.dump(raw)) ImGui.text(line) }
                }
            }
        }
        ImGui.endChild()
    }

    // ------------------------------------------------------------------ Types

    private fun renderTypes() {
        val store = PacketCapture.store
        val counts = store.stats(); val bytes = store.bytesStats(); val rates = store.rates()
        Widgets.faint("${counts.size} types · rates over the last 5 s")
        if (store.excludes.isNotEmpty()) { ImGui.sameLine(); if (Widgets.ghostButton("${Icons.XMARK}  clear ${store.excludes.size} hidden")) store.excludes = emptyList() }
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.ScrollY
        if (ImGui.beginTable("##types", 5, flags)) {
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableSetupColumn("type", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("count", ImGuiTableColumnFlags.WidthFixed, 64f)
            ImGui.tableSetupColumn("bytes", ImGuiTableColumnFlags.WidthFixed, 72f)
            ImGui.tableSetupColumn("/s", ImGuiTableColumnFlags.WidthFixed, 56f)
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, 92f)
            ImGui.tableHeadersRow()
            for ((type, n) in counts.entries.sortedByDescending { it.value }) {
                ImGui.tableNextRow()
                ImGui.tableNextColumn(); ImGui.text(type.removePrefix("minecraft:"))
                ImGui.tableNextColumn(); ImGui.text(n.toString())
                ImGui.tableNextColumn(); Widgets.muted(bytes[type]?.let { human(it) } ?: "–")
                ImGui.tableNextColumn(); Widgets.muted(rates[type]?.let { "%.1f".format(it.first) } ?: "0")
                ImGui.tableNextColumn()
                ImGui.pushID(type)
                if (Widgets.ghostButton(Icons.FILTER)) filter.set(Regex.escape(type))
                if (ImGui.isItemHovered()) ImGui.setTooltip("Only this type in Live")
                ImGui.sameLine()
                if (Widgets.ghostButton(Icons.EYE)) store.excludes = store.excludes + Regex(Regex.escape(type))
                if (ImGui.isItemHovered()) ImGui.setTooltip("Hide this type (stop capturing it)")
                ImGui.popID()
            }
            ImGui.endTable()
        }
    }

    private fun human(b: Long): String = when {
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
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
