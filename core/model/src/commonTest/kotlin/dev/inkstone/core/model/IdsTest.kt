package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * M0 smoke test: proves `commonTest` compiles and runs on both the JVM/Android
 * and the Kotlin/Native side of every `:core:*` module. The document model's
 * real tests arrive with M2.
 */
class IdsTest {

    @Test
    fun documentIdsWithTheSameValueAreEqual() {
        val a = DocumentId("0192f0c8-1c0d-7000-8000-000000000001")
        val b = DocumentId("0192f0c8-1c0d-7000-8000-000000000001")
        assertEquals(a, b)
        assertEquals(a.value, b.value)
    }

    @Test
    fun documentIdsWithDifferentValuesDiffer() {
        val a = DocumentId("0192f0c8-1c0d-7000-8000-000000000001")
        val b = DocumentId("0192f0c8-1c0d-7000-8000-000000000002")
        assertNotEquals(a, b)
    }

    @Test
    fun materializedFileCarriesTheRevItWasBuiltFrom() {
        // SPEC §7.6: without this, a note edited during its own upload gets
        // marked SYNCED and the edit becomes invisible to sync forever.
        val file = MaterializedFile(
            path = "notes/0192f0c8/document.pdf",
            sizeBytes = 1_024,
            sha256 = "0".repeat(64),
            localRev = 7,
        )
        assertEquals(7, file.localRev)
    }
}
