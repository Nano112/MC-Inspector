package dev.harrison.mcinspector.imgui

/**
 * One interactive Dear ImGui item seen in a frame. Coordinates are ImGui display units, which are
 * Minecraft's mouse (logical window) units while panel-lib draws into the game window.
 * [open] is the return value of a `begin*` call (menu/combo/tab open), null for plain widgets.
 */
data class ImGuiItem(
    val window: String,
    val label: String,
    val rawLabel: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val open: Boolean? = null,
) {
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f
}

object ImGuiLabels {
    /** What a person sees: the text before `##`, without icon-font glyphs (private use area), whitespace collapsed. */
    fun display(raw: String): String {
        val visible = raw.substringBefore("##")
        return visible.filterNot { it in ''..'' }.replace(Regex("\\s+"), " ").trim()
    }

    /** A window's name: its visible title, or its id when the title is hidden (`##MainMenuBar`). */
    fun window(raw: String): String = display(raw).ifEmpty { raw.trimStart('#').substringAfterLast("###") }
}

/**
 * Records the items ImGui draws, one frame at a time, so an agent can address them by label.
 * Fed from the render thread by the imgui.ImGui hooks; read from MCP threads via [items].
 * Recording costs native calls per item, so it only runs for a while after [arm].
 */
class ImGuiItemIndex {
    @Volatile private var armedUntil = 0L
    private var current = ArrayList<ImGuiItem>()

    /** True while the frame being built is recorded (decided once per frame, in [beginFrame]). */
    @Volatile var recording = false
        private set

    /** Items of the last completed recorded frame. */
    @Volatile var items: List<ImGuiItem> = emptyList()
        private set

    /** Completed frames, recorded or not; callers wait on it. */
    @Volatile var frame = 0L
        private set

    fun arm(nowMs: Long = System.currentTimeMillis(), forMs: Long = ARM_MS) {
        armedUntil = maxOf(armedUntil, nowMs + forMs)
    }

    fun beginFrame(nowMs: Long = System.currentTimeMillis()) {
        recording = nowMs < armedUntil
        current = ArrayList()
    }

    fun endFrame() {
        if (recording) items = current
        recording = false
        frame++
    }

    fun record(window: String, kind: String, raw: String, x: Float, y: Float, w: Float, h: Float, open: Boolean? = null) {
        if (!recording) return
        current.add(ImGuiItem(window, ImGuiLabels.display(raw), raw, kind, x, y, w, h, open))
    }

    companion object {
        const val ARM_MS = 30_000L

        /**
         * The [index]th item matching [query]: an exact visible or raw label first, then a prefix, then
         * a substring (case-insensitive). A `##id` query matches the label's ImGui id exactly, so it keeps
         * working when the visible text changes. [window] narrows to windows whose path contains it.
         */
        fun find(items: List<ImGuiItem>, query: String, window: String? = null, index: Int? = null): ImGuiItem? {
            val pool = items.filter { window == null || it.window.contains(window, ignoreCase = true) }
            if (query.startsWith("##")) return pool.filter { it.rawLabel.endsWith(query) }.getOrNull(index ?: 0)
            val q = query.trim()
            val matches = pool.filter { it.label.equals(q, ignoreCase = true) || it.rawLabel == query }
                .ifEmpty { pool.filter { it.label.startsWith(q, ignoreCase = true) } }
                .ifEmpty { pool.filter { it.label.contains(q, ignoreCase = true) } }
            return matches.getOrNull(index ?: 0)
        }
    }
}
