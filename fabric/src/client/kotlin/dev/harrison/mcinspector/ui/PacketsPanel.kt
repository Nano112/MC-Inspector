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
import dev.harrison.mcinspector.packets.PacketRules
import dev.harrison.mcinspector.packets.RecordStatus
import dev.harrison.mcinspector.packets.RuleAction
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
    private var deep = false

    private val PRESETS = listOf(
        "chat" to "chat|system_chat|disguised",
        "entities" to "entity|add_mob|remove_entities|rotate_head",
        "chunks" to "chunk|light_update|section_blocks",
        "movement" to "move_player|player_position|set_entity_motion|move_entity|teleport",
        "inventory" to "container|slot|set_carried|held_item|creative",
        "world" to "block_update|block_event|level_event|sound|particle|explode",
    )
    private const val NOISE = "level_chunk|rotate_head|move_entity|set_entity_motion|client_tick_end|chunk_batch|light_update|set_entity_data|keep_alive|set_time"

    fun render() {
        val held = PacketRules.heldCount()
        Widgets.tabBar("##packets-tabs", listOf(
            "Live" to ::renderLive,
            "Types" to ::renderTypes,
            "Graph" to ::renderGraph,
            (if (held > 0) "Rules ($held held)###rules" else "Rules###rules") to ::renderRules,
        ))
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
        for ((i, preset) in PRESETS.withIndex()) {
            if (i > 0) ImGui.sameLine(0f, 4f)
            if (Widgets.toggleButton(preset.first, filter.get() == preset.second)) filter.set(if (filter.get() == preset.second) "" else preset.second)
        }
        ImGui.sameLine(0f, 4f)
        if (Widgets.toggleButton("deep", deep)) deep = !deep
        if (ImGui.isItemHovered()) ImGui.setTooltip("Also match the regex against decoded fields (slower)")
        ImGui.sameLine(0f, 4f)
        if (Widgets.ghostButton("${Icons.EYE}  hide noise")) store.excludes = store.excludes + Regex(NOISE, RegexOption.IGNORE_CASE)
        Widgets.faint("${store.size()} buffered · ${store.totalSeen} seen · ${store.dropped} dropped" + if (store.excludes.isNotEmpty()) " · ${store.excludes.size} excludes" else "")
        if (filterError) Widgets.statusText("invalid regex", Widgets.Tone.DANGER)

        val dir = when { showIn && showOut -> null; showIn -> Direction.IN; showOut -> Direction.OUT; else -> null }
        var rows = store.query(PacketQuery(direction = dir, type = if (deep) null else compiled, deep = if (deep) compiled else null, limit = MAX_ROWS, newestFirst = true)).asReversed()
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
                ImGui.tableNextColumn()
                when (r.status) {
                    RecordStatus.DROPPED -> Widgets.colored("${Icons.XMARK} ${r.type.removePrefix("minecraft:")}", t.danger)
                    RecordStatus.HELD -> Widgets.colored("${Icons.PAUSE} ${r.type.removePrefix("minecraft:")}", t.warning)
                    RecordStatus.REPLACED -> Widgets.colored("${Icons.PEN} ${r.type.removePrefix("minecraft:")}", t.info)
                    RecordStatus.RELEASED -> Widgets.colored("${Icons.PLAY} ${r.type.removePrefix("minecraft:")}", t.success)
                    RecordStatus.LOGGED -> Widgets.colored("${Icons.TAG} ${r.type.removePrefix("minecraft:")}", t.accent)
                    else -> ImGui.text(r.type.removePrefix("minecraft:"))
                }
                if (r.ruleHits.isNotEmpty() && ImGui.isItemHovered()) ImGui.setTooltip("rules: ${r.ruleHits.joinToString()}")
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

    // ------------------------------------------------------------------ Graph

    private fun renderGraph() {
        val store = PacketCapture.store
        val (ins, outs) = store.history(60)
        val maxIn = ins.maxOrNull() ?: 0f; val maxOut = outs.maxOrNull() ?: 0f
        val w = ImGui.getContentRegionAvailX()
        Widgets.label("inbound packets / s (last 60 s)")
        ImGui.plotHistogram("##in", ins, ins.size, 0, "now: ${ins.last().toInt()}/s · peak ${maxIn.toInt()}", 0f, maxOf(maxIn, 1f), w, 90f)
        Widgets.label("outbound packets / s")
        ImGui.plotHistogram("##out", outs, outs.size, 0, "now: ${outs.last().toInt()}/s · peak ${maxOut.toInt()}", 0f, maxOf(maxOut, 1f), w, 70f)
        Widgets.sectionHeader("Top types · last 5 s")
        val rates = store.rates().entries.sortedByDescending { it.value.first }.take(12)
        val top = rates.firstOrNull()?.value?.first ?: 1.0
        val t = Theme.current
        for ((type, v) in rates) {
            val x = ImGui.getCursorScreenPosX(); val y = ImGui.getCursorScreenPosY()
            val barW = (w * 0.55f * (v.first / top)).toFloat()
            ImGui.getWindowDrawList().addRectFilled(x, y + 2f, x + barW, y + ImGui.getTextLineHeight() - 2f, ImGui.getColorU32(t.accent.x, t.accent.y, t.accent.z, 0.35f), 3f)
            ImGui.text("  " + type.removePrefix("minecraft:"))
            ImGui.sameLine(w * 0.58f); Widgets.muted("%.1f/s".format(v.first) + if (v.second > 0) "  %s/s".format(human(v.second.toLong())) else "")
        }
    }

    // ------------------------------------------------------------------ Rules / breakpoints

    private val ruleName = ImString(64)
    private val ruleType = ImString(128)
    private val ruleScript = ImString(2048)
    private var ruleDir = 0     // 0 both, 1 IN, 2 OUT
    private var ruleAction = 0  // index into RuleAction
    private var ruleError: String? = null

    private fun renderRules() {
        val t = Theme.current
        // ---- held queue first: that is what you are waiting on when a breakpoint hits
        val held = PacketRules.heldList()
        if (held.isNotEmpty()) {
            Widgets.statusText("${held.size} packet(s) held at a breakpoint", Widgets.Tone.WARNING)
            ImGui.sameLine()
            if (Widgets.primaryButton("${Icons.PLAY}  Resume all")) PacketRules.releaseAll(false)
            ImGui.sameLine()
            if (Widgets.dangerButton("${Icons.XMARK}  Drop all")) PacketRules.releaseAll(true)
            if (ImGui.beginChild("##held", 0f, minOf(160f, 28f * held.size + 8f), true)) {
                for (h in held) {
                    ImGui.pushID(h.record.id.toInt())
                    if (Widgets.ghostButton(Icons.PLAY)) PacketRules.release(h.record.id, false)
                    ImGui.sameLine(); if (Widgets.ghostButton(Icons.XMARK)) PacketRules.release(h.record.id, true)
                    ImGui.sameLine(); if (Widgets.ghostButton(Icons.EYE)) { selectedId = h.record.id }
                    ImGui.sameLine(); Widgets.colored(h.dir.name, if (h.dir == Direction.IN) t.info else t.success)
                    ImGui.sameLine(); ImGui.text(h.record.type.removePrefix("minecraft:"))
                    ImGui.sameLine(); Widgets.muted(h.record.summary)
                    ImGui.popID()
                }
            }
            ImGui.endChild()
            ImGui.spacing()
        }

        // ---- rule list
        Widgets.sectionHeader("Rules")
        val rules = PacketRules.rules
        if (rules.isEmpty()) Widgets.muted("No rules. LOG tags packets, DROP swallows them, PAUSE is a breakpoint, SCRIPT runs JS that can rewrite/drop/hold.")
        for (r in rules) {
            ImGui.pushID(r.id)
            if (Widgets.toggleButton(if (r.enabled) Icons.CHECK else "  ", r.enabled)) r.enabled = !r.enabled
            ImGui.sameLine(); Widgets.semibold(r.name)
            ImGui.sameLine(); Widgets.badge(r.action.name, when (r.action) { RuleAction.DROP -> Widgets.Tone.DANGER; RuleAction.PAUSE -> Widgets.Tone.WARNING; RuleAction.SCRIPT -> Widgets.Tone.INFO; RuleAction.LOG -> Widgets.Tone.NEUTRAL })
            ImGui.sameLine(); Widgets.muted("${r.direction?.name ?: "IN+OUT"} · ${r.typeRegex?.pattern ?: "*"} · ${r.hits.get()} hits")
            ImGui.sameLine(); if (Widgets.ghostButton("${Icons.TRASH}##del")) PacketRules.remove(r.id)
            r.script?.let { Widgets.faint("   " + it.lineSequence().first().take(90)) }
            r.lastError?.let { Widgets.statusText(it, Widgets.Tone.DANGER) }
            ImGui.popID()
        }

        // ---- add rule
        Widgets.sectionHeader("Add rule")
        Widgets.textField("##rname", ruleName, "name", width = 140f); ImGui.sameLine()
        if (Widgets.toggleButton("both", ruleDir == 0)) ruleDir = 0; ImGui.sameLine(0f, 2f)
        if (Widgets.toggleButton("IN", ruleDir == 1)) ruleDir = 1; ImGui.sameLine(0f, 2f)
        if (Widgets.toggleButton("OUT", ruleDir == 2)) ruleDir = 2
        Widgets.textField("##rtype", ruleType, "type regex (empty = all), e.g. set_health", width = -1f)
        for ((i, a) in RuleAction.entries.withIndex()) { if (i > 0) ImGui.sameLine(0f, 2f); if (Widgets.toggleButton(a.name, ruleAction == i)) ruleAction = i }
        ImGui.inputTextMultiline("##rscript", ruleScript, -1f, 70f)
        if (ImGui.isItemHovered()) ImGui.setTooltip(if (ruleAction == RuleAction.SCRIPT.ordinal) "JS: packet, type, dir, get(), set(packet,'field',v) → replacement, drop(), hold(), log()" else "optional JS condition, e.g. get(packet,'health') < 10")
        if (Widgets.primaryButton("${Icons.BOLT}  Add rule")) {
            ruleError = try {
                PacketRules.add(ruleName.get().ifBlank { "rule ${PacketRules.rules.size + 1}" }, when (ruleDir) { 1 -> Direction.IN; 2 -> Direction.OUT; else -> null }, ruleType.get(), RuleAction.entries[ruleAction], ruleScript.get())
                ruleName.set(""); ruleType.set(""); ruleScript.set(""); null
            } catch (e: Throwable) { "${e::class.simpleName}: ${e.message}" }
        }
        ruleError?.let { Widgets.statusText(it, Widgets.Tone.DANGER) }
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
