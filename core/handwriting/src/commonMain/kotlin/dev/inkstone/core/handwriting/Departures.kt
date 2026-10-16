package dev.inkstone.core.handwriting

/**
 * Q8: what a pass forgets because a note has left the device. A note leaves for good one way only,
 * by being purged from the local trash after thirty days, so its samples go then and not when it is
 * trashed. Restoring a note from the trash loses nothing.
 *
 * ⚠️ **`onDevice` must be read off the files, `notes/` and the local trash, never off the library
 * index.** The index is a cache the app rebuilds from `notes/` when it is lost (SPEC §13.1). An index
 * that is empty or half rebuilt makes every note look purged. The first version of the learner asked
 * the index, so a lost database would have withdrawn every word ever learned. Worse, the per-note
 * watermarks would have gone on saying those notes were already harvested, so nothing would have
 * learned them again.
 *
 * Forgetting a note therefore drops its watermarks as well as its samples. A note wrongly taken for
 * departed is then harvested afresh the next time it is seen, and the user's corrections come back with
 * it, because `labels.json` is keyed by the strokes and not by the sample.
 */
public object Departures {

    /**
     * The notes that [samples] or [state]'s watermarks mention and [onDevice] does not hold.
     *
     * The result is empty when [onDevice] is empty. A device with learned words and no notes at all,
     * not even in the trash, is far likelier to have storage that could not be listed than a library
     * purged to nothing, and guessing wrong would cost every word learned. A library that really was
     * emptied loses its words the first time it holds a note again.
     */
    public fun of(samples: Collection<WordSample>, state: ProfileState, onDevice: Set<String>): Set<String> {
        if (onDevice.isEmpty()) return emptySet()
        val mentioned = HashSet<String>()
        samples.forEach { mentioned += it.noteId.value }
        mentioned += state.harvestedNotes.keys
        state.harvestedPages.keys.forEach { mentioned += ProfileState.noteOfPageKey(it) }
        // Quick-start samples belong to no note (QuickStart.NOTE_ID) and so never depart with one.
        return mentioned - onDevice - QuickStart.NOTE_ID
    }
}
