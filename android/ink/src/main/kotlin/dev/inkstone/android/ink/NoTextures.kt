package dev.inkstone.android.ink

import android.graphics.Bitmap
import androidx.ink.brush.TextureBitmapStore

/**
 * A texture store that has no textures.
 *
 * None of the brushes this app uses is textured (see the note on
 * [InkBrushKind]): `marker`, `pressurePen` and `highlighter` are all solid, and
 * the grain SPEC §3.4 wants for Pencil is deferred — the stock grain lives under
 * a texture id private to `androidx.ink`, and shipping our own is M10 work.
 *
 * `androidx.ink` ships `StockTextureBitmapStore` for exactly this slot, but it
 * is `@RestrictTo(LIBRARY_GROUP)`. Since there is nothing for it to load, a
 * three-line implementation of the public [TextureBitmapStore] interface is
 * both sufficient and honest. When Pencil grows a grain texture, this is where
 * it gets registered.
 */
internal object NoTextures : TextureBitmapStore {
    override fun get(clientTextureId: String): Bitmap? = null
}
