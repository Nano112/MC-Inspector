package dev.harrison.mcinspector.input

/** Friendly key name -> GLFW key code. Pure table so it is unit-testable without MC. */
object KeyNames {
    // GLFW constants (org.lwjgl.glfw.GLFW) hard-coded to keep this class MC/LWJGL-free.
    const val MOD_SHIFT = 0x1
    const val MOD_CONTROL = 0x2
    const val MOD_ALT = 0x4
    const val MOD_SUPER = 0x8
    const val PRESS = 1
    const val RELEASE = 0

    private val table: Map<String, Int> = buildMap {
        ('a'..'z').forEach { put(it.toString(), it.uppercaseChar().code) }
        ('0'..'9').forEach { put(it.toString(), it.code) }
        (1..25).forEach { put("f$it", 289 + it) }
        val named = mapOf(
            "space" to 32, "apostrophe" to 39, "comma" to 44, "minus" to 45, "period" to 46, "slash" to 47,
            "semicolon" to 59, "equal" to 61, "left_bracket" to 91, "backslash" to 92, "right_bracket" to 93,
            "grave_accent" to 96, "grave" to 96,
            "escape" to 256, "esc" to 256, "enter" to 257, "return" to 257, "tab" to 258, "backspace" to 259,
            "insert" to 260, "delete" to 261, "right" to 262, "left" to 263, "down" to 264, "up" to 265,
            "page_up" to 266, "page_down" to 267, "home" to 268, "end" to 269,
            "caps_lock" to 280, "scroll_lock" to 281, "num_lock" to 282, "print_screen" to 283, "pause" to 284,
            "kp_0" to 320, "kp_1" to 321, "kp_2" to 322, "kp_3" to 323, "kp_4" to 324, "kp_5" to 325,
            "kp_6" to 326, "kp_7" to 327, "kp_8" to 328, "kp_9" to 329, "kp_decimal" to 330, "kp_divide" to 331,
            "kp_multiply" to 332, "kp_subtract" to 333, "kp_add" to 334, "kp_enter" to 335, "kp_equal" to 336,
            "left_shift" to 340, "left_control" to 341, "left_alt" to 342, "left_super" to 343,
            "right_shift" to 344, "right_control" to 345, "right_alt" to 346, "right_super" to 347, "menu" to 348,
            // aliases
            "lshift" to 340, "shift" to 340, "lctrl" to 341, "ctrl" to 341, "control" to 341, "lalt" to 342, "alt" to 342,
            "rshift" to 344, "rctrl" to 345, "ralt" to 346, "cmd" to 343, "super" to 343, "win" to 343,
            "arrowup" to 265, "arrowdown" to 264, "arrowleft" to 263, "arrowright" to 262,
            "pageup" to 266, "pagedown" to 267, "del" to 261, "ins" to 260, "bksp" to 259,
        )
        putAll(named)
    }

    /** Accepts "e", "E", "escape", "key.keyboard.escape", "left.shift", "Left_Shift", "ArrowUp". */
    fun resolve(name: String): Int? {
        var n = name.trim().lowercase().removePrefix("key.keyboard.").replace('.', '_').replace('-', '_').replace(' ', '_')
        if (n.length == 1) return table[n]
        return table[n] ?: table[n.replace("_", "")]
    }

    fun modifiers(list: List<String>): Int = list.fold(0) { acc, m ->
        acc or when (m.lowercase()) {
            "shift" -> MOD_SHIFT
            "ctrl", "control" -> MOD_CONTROL
            "alt" -> MOD_ALT
            "super", "cmd", "meta", "win" -> MOD_SUPER
            else -> throw IllegalArgumentException("unknown modifier '$m' (use shift/ctrl/alt/super)")
        }
    }

    fun mouseButton(name: String?): Int = when (name?.lowercase()) {
        null, "left", "0" -> 0
        "right", "1" -> 1
        "middle", "2" -> 2
        else -> name.toIntOrNull() ?: throw IllegalArgumentException("unknown mouse button '$name'")
    }
}
