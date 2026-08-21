package dev.harrison.mcinspector.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyNamesTest {
    @Test fun letters() { assertEquals(69, KeyNames.resolve("e")); assertEquals(69, KeyNames.resolve("E")); assertEquals(87, KeyNames.resolve("key.keyboard.w")) }
    @Test fun digits() { assertEquals(49, KeyNames.resolve("1")) }
    @Test fun named() {
        assertEquals(256, KeyNames.resolve("escape")); assertEquals(256, KeyNames.resolve("Esc"))
        assertEquals(257, KeyNames.resolve("enter")); assertEquals(32, KeyNames.resolve("space"))
        assertEquals(292, KeyNames.resolve("f3")); assertEquals(340, KeyNames.resolve("left_shift"))
        assertEquals(340, KeyNames.resolve("key.keyboard.left.shift")); assertEquals(340, KeyNames.resolve("lshift"))
        assertEquals(265, KeyNames.resolve("ArrowUp")); assertEquals(265, KeyNames.resolve("up"))
    }
    @Test fun unknown() { assertNull(KeyNames.resolve("banana")) }
    @Test fun modifiers() { assertEquals(KeyNames.MOD_CONTROL or KeyNames.MOD_SHIFT, KeyNames.modifiers(listOf("ctrl", "Shift"))) }
    @Test fun mouseButtons() { assertEquals(0, KeyNames.mouseButton(null)); assertEquals(1, KeyNames.mouseButton("right")); assertEquals(2, KeyNames.mouseButton("middle")) }
}
