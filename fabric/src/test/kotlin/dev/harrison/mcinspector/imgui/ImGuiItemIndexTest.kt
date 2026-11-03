package dev.harrison.mcinspector.imgui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImGuiItemIndexTest {
    private fun item(window: String, raw: String, kind: String = "button", x: Float = 0f) =
        ImGuiItem(window, ImGuiLabels.display(raw), raw, kind, x, 10f, 40f, 20f)

    @Test fun displayLabelDropsIdsAndIconGlyphs() {
        assertEquals("Browse", ImGuiLabels.display("Browse###schematioconnector:browse"))
        assertEquals("Run", ImGuiLabels.display("Run##tool-run"))
        assertEquals("Communities", ImGuiLabels.display("Communities  "))
        assertEquals("", ImGuiLabels.display("##search"))
    }

    @Test fun windowNameFallsBackToTheIdWhenTheLabelIsHidden() {
        assertEquals("Browse", ImGuiLabels.window("Browse###schematioconnector:browse"))
        assertEquals("MainMenuBar", ImGuiLabels.window("##MainMenuBar"))
    }

    @Test fun onlyACompletedFrameIsVisible() {
        val index = ImGuiItemIndex()
        index.arm(nowMs = 0, forMs = 1_000)
        index.beginFrame(nowMs = 10)
        index.record("Browse", "button", "All", 1f, 2f, 3f, 4f)
        assertTrue(index.items.isEmpty())
        index.endFrame()
        assertEquals(listOf("All"), index.items.map { it.label })
        assertEquals(1, index.frame)
    }

    @Test fun nothingIsRecordedUnlessArmed() {
        val index = ImGuiItemIndex()
        index.beginFrame(nowMs = 10)
        assertFalse(index.recording)
        index.record("Browse", "button", "All", 1f, 2f, 3f, 4f)
        index.endFrame()
        assertTrue(index.items.isEmpty())
        assertEquals(1, index.frame, "frames still count so callers can wait for one")
    }

    @Test fun armingExpires() {
        val index = ImGuiItemIndex()
        index.arm(nowMs = 0, forMs = 100)
        index.beginFrame(nowMs = 50); assertTrue(index.recording); index.endFrame()
        index.beginFrame(nowMs = 150); assertFalse(index.recording); index.endFrame()
    }

    @Test fun exactLabelBeatsPrefixBeatsContains() {
        val items = listOf(item("Browse", "Mine all"), item("Browse", "All"), item("Browse", "Show all"))
        assertEquals("All", ImGuiItemIndex.find(items, "all")?.label)
        assertEquals("Mine all", ImGuiItemIndex.find(items, "mine")?.label)
        assertEquals("Show all", ImGuiItemIndex.find(items, "how")?.label)
        assertNull(ImGuiItemIndex.find(items, "zzz"))
    }

    @Test fun hiddenIdsMatchByRawLabel() {
        val items = listOf(item("Browse", "##search", kind = "input"))
        assertEquals("##search", ImGuiItemIndex.find(items, "##search")?.rawLabel)
    }

    @Test fun anIdQueryMatchesTheLabelsIdWhateverItsVisibleText() {
        val items = listOf(item("Browse", "QA Connector s43m  ##communities"), item("Browse", "Mine"))
        assertEquals("QA Connector s43m", ImGuiItemIndex.find(items, "##communities")?.label)
        assertNull(ImGuiItemIndex.find(items, "##comm"))
    }

    @Test fun windowFilterAndIndexPickAmongDuplicates() {
        val items = listOf(item("Tools", "Run", x = 1f), item("Browse", "Run", x = 2f), item("Browse", "Run", x = 3f))
        assertEquals(2f, ImGuiItemIndex.find(items, "Run", window = "browse")?.x)
        assertEquals(3f, ImGuiItemIndex.find(items, "Run", window = "browse", index = 1)?.x)
        assertNull(ImGuiItemIndex.find(items, "Run", window = "browse", index = 2))
    }

    @Test fun windowFilterMatchesAnyPartOfTheWindowPath() {
        val items = listOf(item("Browse > Communities", "QA Connector rzsd", kind = "menu_item"))
        assertEquals("QA Connector rzsd", ImGuiItemIndex.find(items, "QA Connector", window = "Communities")?.label)
    }

    @Test fun centerIsTheMiddleOfTheRect() {
        val i = ImGuiItem("w", "a", "a", "button", 10f, 20f, 40f, 10f)
        assertEquals(30f, i.centerX); assertEquals(25f, i.centerY)
    }
}
