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
