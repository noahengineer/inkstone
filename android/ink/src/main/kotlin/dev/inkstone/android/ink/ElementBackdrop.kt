package dev.inkstone.android.ink

/**
 * ⭐ The Element editor's canvas in place of paper (SPEC §4.9.7) — a grid, so it reads as *"this is
 * just the Element"* and not as a page.
 *
 * ⭐ **White by default.** It was dark first, as first asked, and then: *"having a black background
 * was a bad call on my end, I think it would be better white or maybe make it so you can switch"*.
 * Dark also hid the default ink, which is near-black. [DARK] stays as the other side of the editor's
 * switch; either is a viewing choice and changes nothing that is saved.
 */
public enum class ElementBackdrop(
    internal val groundArgb: Int,
    internal val minorArgb: Int,
    internal val majorArgb: Int,
) {
    LIGHT(groundArgb = 0xFFFFFFFF.toInt(), minorArgb = 0xFFEDEDF0.toInt(), majorArgb = 0xFFD4D4D8.toInt()),
    DARK(groundArgb = 0xFF101114.toInt(), minorArgb = 0xFF1E2026.toInt(), majorArgb = 0xFF2C2F38.toInt()),
}
