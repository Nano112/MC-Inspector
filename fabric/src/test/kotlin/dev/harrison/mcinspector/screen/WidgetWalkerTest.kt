package dev.harrison.mcinspector.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetWalkerTest {
    private fun w(i: Int, text: String) = WidgetInfo(i, "Button", text, 0, 0, 10, 10, true, false, true, "$i")
    private val list = listOf(w(0, "Options..."), w(1, "Done"), w(2, "Add Server"), w(3, "Edit"))

    @Test fun exactBeatsContains() = assertEquals(1, WidgetWalker.find(list, null, "done")?.index)
    @Test fun containsFallback() = assertEquals(0, WidgetWalker.find(list, null, "option")?.index)
    @Test fun byIndex() = assertEquals("Edit", WidgetWalker.find(list, 3, null)?.text)
    @Test fun missing() { assertNull(WidgetWalker.find(list, null, "zzz")); assertNull(WidgetWalker.find(list, 9, null)) }
}
