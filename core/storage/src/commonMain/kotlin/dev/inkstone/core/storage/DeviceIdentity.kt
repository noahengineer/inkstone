package dev.inkstone.core.storage

import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory

/**
 * ⭐ **This install's identity — the Lamport tiebreak's second component (SPEC §4.1).**
 *
 * ## The bug this exists to fix
 *
 * SPEC §4.1 fixes the comparator as `compareBy({ counter }, { deviceId })`, and
 * [dev.inkstone.core.model.Register.write] rests its whole correctness argument on the
 * tiebreak being *total*:
 *
 * > *"Equal clocks are impossible between different devices (the `deviceId` tiebreak)
 * > and impossible on one device (the counter only increases), so 'not later' resolving
 * > to the incumbent is a defensive choice with no observable behaviour."*
 *
 * Until M6 that sentence was **false**, in two different ways, and both were written down
 * as temporary with M5 named as the milestone that would fix them:
 *
 * - `NoteEditor` took the id from the note it opened — `note.document.deviceId` — i.e.
 *   from the manifest, i.e. from whichever device *created* the note. So two tablets
 *   editing one note stamped every op with the **same** `deviceId`. Two concurrent ops at
 *   counter 47 then compare **equal**, `Register.write` keeps the incumbent, the incumbent
 *   is whichever op that device's fold happened to absorb first, and the two devices hold
 *   different values **forever — with every op present, in the same total order, and no
 *   error anywhere.** That is SPEC §13.2 row 3, failing underneath every other row.
 * - `LibraryRepository` minted a fresh id per **process**, so a device broke ties
 *   differently after every restart, and notes created in different sessions on one
 *   tablet disagreed about who made them.
 *
 * ## Why a file and not a database row
 *
 * Because the id must survive the database being deleted. SPEC §13.1's disaster-recovery
 * story is *"delete the SQLite database, rebuild the library by scanning a folder of
 * PDFs"* — and a rebuild that also re-minted this device's identity would re-open every
 * tie in every note the device had ever contributed to. The files are the truth here
 * exactly as they are for notes.
 *
 * It is a sibling of `notes/`, not a member of it: it belongs to the install, not to any
 * note, and it is the one piece of state that must not travel with a note when the note is
 * copied, duplicated, published or synced.
 *
 * ## Losing it is survivable, and that is by design
 *
 * A reinstall gets a new id, which SPEC §4.1 anticipates: *"A reinstall generates a new
 * deviceId; without the max + 1 rule its ops sort behind all history."* The `max + 1` rule
 * is [dev.inkstone.core.model.LamportClockGenerator]'s and is already enforced. So a lost
 * identity costs the *ability to break a tie the same way as before*, which is unobservable
 * for ties that have already been resolved and folded.
 */
public class DeviceIdentity(private val fs: FileSystem, private val ids: IdFactory) {

    private var cached: String? = null

    /**
     * This install's device id, minting and persisting one on first call.
     *
     * Idempotent and cheap after the first call. Not thread-safe, and deliberately not
     * synchronised: it is read once at startup on whatever scope owns it, and a mutex on
     * a path this hot would be a cost with no benefit (CLAUDE.md rule 7). Two concurrent
     * first calls **on one instance** each mint an id and the second write wins — still
     * *an* id, consistently readable afterwards, so that costs a wasted UUID.
     *
     * ⚠️ **Across instances it is not that tidy, and the argument above does not cover
     * it.** There are three — `NoteEditor`, `LibraryRepository` and `NoteSessionHost` —
     * each holding its own [cached]. If two of them race on the very first launch, both
     * mint, one write wins the *file*, and each instance keeps **its own** value for the
     * rest of the process: one launch can therefore write ops under two device ids. The
     * file converges, the caches do not until the next start.
     *
     * That is survivable, and it is a different failure from the one this class exists to
     * fix. Two ids on one device behave like two devices: the clocks stay *unequal*, so
     * `Register.write` still resolves, both devices still agree, and nothing diverges —
     * the collapse in the class KDoc needed the ids to be the **same**. What it costs is
     * SPEC §4.1's reinstall case, which the `max + 1` rule already covers. Making this a
     * `@Singleton` would close it outright, and is worth doing the next time DI in `:app`
     * is opened up; it is recorded here rather than done because M6's review found it and
     * could not construct a divergence from it.
     */
    public suspend fun get(): String {
        cached?.let { return it }

        val path = NoteLayout.DEVICE_ID
        if (fs.exists(path)) {
            // Trimmed, because a file a human has looked at acquires a trailing newline,
            // and a deviceId with a newline in it sorts differently from the same id
            // without one — which would silently reintroduce exactly the divergence this
            // class exists to remove.
            val stored = fs.readBytes(path).decodeToString().trim()
            if (stored.isNotEmpty()) {
                cached = stored
                return stored
            }
        }

        val minted = ids.newDeviceId()
        fs.writeBytesAtomically(path, minted.encodeToByteArray())
        cached = minted
        return minted
    }
}
