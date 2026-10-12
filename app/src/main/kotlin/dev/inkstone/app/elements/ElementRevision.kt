package dev.inkstone.app.elements

import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.storage.Element

/**
 * ⭐ An Element as the editor's Done left it, and how far its origin moved in the edit (SPEC §4.9.7).
 *
 * One value rather than two parameters, because the pair is meaningless apart: replacing copies with
 * [element] at their old placements is right only after shifting by [originMoved], and a caller that
 * forgot the shift would slide every unchanged piece of every copy by however much ink was added
 * above or to the left — on notes the user is not looking at.
 */
class ElementRevision(val element: Element, val originMoved: PagePoint)
