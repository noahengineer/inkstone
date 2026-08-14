package dev.inkstone.core.model

import kotlin.jvm.JvmInline

/**
 * SPEC §4.1 — identity types.
 *
 * All IDs are UUIDv7 strings in value classes. Never derive identity from array
 * position, title, or file path (CLAUDE.md, "Identity"): a note's position
 * changes when a page is reordered, its title changes when the user renames it,
 * and its path changes the moment it is moved in the Nextcloud web UI.
 *
 * The value classes exist so that a `PageId` cannot be passed where an
 * `ObjectId` is expected. The generation of UUIDv7 itself lands in M2, together
 * with the document model.
 */
@JvmInline
public value class DocumentId(public val value: String)

@JvmInline
public value class PageId(public val value: String)

/**
 * A library folder (SPEC §9).
 *
 * An id and not a path, for the same reason a note has one: SPEC §9 says folders
 * *"mirror Nextcloud both directions"*, and a folder renamed or moved in the
 * Nextcloud web UI is the same folder at a different path. Keying on the path
 * would make that a delete plus a create, which is how a sync loses a hundred
 * notes to a rename.
 */
@JvmInline
public value class FolderId(public val value: String)

/** Strokes, images, text boxes, shapes — anything an op can target. */
@JvmInline
public value class ObjectId(public val value: String)

/**
 * A saved Element — a reusable stamp (SPEC §4.9.4), added in M8c.
 *
 * ⚠️ **Not an [ObjectId], and the distance between the two is the whole design.** An Element is a
 * *template*: it holds payloads and relative geometry, and every insertion mints **fresh**
 * [ObjectId]s and a fresh asset name for each picture in it. Reusing the saved objects' ids
 * instead — replaying stored `AddObject` ops — would put two objects with one identity into a
 * grow-only union, where `PageFolder`'s first-write-wins payload rule gives you **one** object
 * carrying the first stamp's payload at the second stamp's transform. The first stamp visibly
 * vanishes; nothing reports anything. That is SPEC §5.6's duplicate-`ObjectId` corruption arriving
 * from the other direction, and a separate id type is what stops an element id ever being handed
 * to an op.
 */
@JvmInline
public value class ElementId(public val value: String)
