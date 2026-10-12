package dev.inkstone.android.ink

/**
 * ⭐ The Element editor's canvas in place of paper (SPEC §4.9.7) — a grid, so it reads as *"this is
 * just the Element"* and not as a page.
 *
 * Dark by default, because that is what was asked for. ⚠️ **The default ink is near-black**, and
 * near-black on near-black is invisible, so [LIGHT] exists for checking dark ink; it is a viewing
 * choice and changes nothing that is saved.
 */
public enum class ElementBackdrop(
    internal val groundArgb: Int,
    internal val minorArgb: Int,
    internal val majorArgb: Int,
) {
    DARK(groundArgb = 0xFF101114.toInt(), minorArgb = 0xFF1E2026.toInt(), majorArgb = 0xFF2C2F38.toInt()),
    LIGHT(groundArgb = 0xFFF4F4F5.toInt(), minorArgb = 0xFFE4E4E7.toInt(), majorArgb = 0xFFD4D4D8.toInt()),
}
