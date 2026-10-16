# Inkstone

A handwriting-first note-taking app for Android tablets, storing notes as ordinary PDFs in your Nextcloud, working fully offline.

`SPEC.md` is the technical specification. `ROADMAP.md` is the build order. `CLAUDE.md` is the repo rules. `START-HERE.md` explains how the three fit together.

**Current state: M6 — two-way Nextcloud sync.** A note binds to a folder on your Nextcloud and
uploads itself; the app polls for changes, downloads a peer's version, and **merges** it — union
the op logs, sort by Lamport clock, fold from empty, so two people writing in different corners of
one page both keep their work with no prompt. Page order merges by fractional key and never drops a
page. What cannot be merged automatically — a file from a newer build, a peer's file that lost its
bundle, a 412 that survives a merge — becomes a **conflicted copy holding the remote bytes
verbatim**, with both versions kept and the reason listed under *Needs attention*. **No modal
dialogs, ever.** A remote delete goes to local trash. Signing out pauses your notes instead of
unbinding them. Library folders can be **pinned** to any remote path, and everything beneath the pin
follows.

`./gradlew build` is green — **555 unit tests per variant** (counted from
`*/build/test-results/testDebugUnitTest/*.xml`; `build` runs the same suite again as
`testReleaseUnitTest`, so it executes 1,110 and there are 555), `detekt`, Android lint, **guardrail 1
plus the iOS klib compile — not the iOS framework link, which is macOS-only** (see *The two
guardrails*), and SQLDelight migration verification against a committed schema fixture (now at
schema version 6).

Last forced clean run: `detekt spotlessCheck checkCoreIsPlatformFree testDebugUnitTest
testReleaseUnitTest --no-build-cache --rerun-tasks` → **444 actionable tasks: 444 executed**, 555
tests per variant, 0 failures, 0 skipped. Quoting "444 executed" rather than "BUILD SUCCESSFUL" is
the point: see the warning below.

⚠️ Do **not** read a test count off Gradle's last line. `build` ends with `1068 actionable tasks: …`,
and that 1,068 was quoted as "1068 JVM tests" through two milestones. It is a task count.

⚠️ And a green `./gradlew build` may have executed **zero** tests. If its `test*UnitTest` tasks log
`UP-TO-DATE` or `FROM-CACHE`, the line says nothing about the suite — deleting `build/test-results`
is not enough, because the build cache restores them. Force it with
`./gradlew --no-build-cache :core:model:testDebugUnitTest --rerun …` per module. This matters most
for the live matrix, which reaches over the network and is exactly what an up-to-date check will skip.

All 21 rows of the merge matrix are automated across **28 tests**, and 26 of them also run against a
real Nextcloud 32.0.13 — those bodies run twice, in-process and live, and the live half now
measures request counts too (see [docs/M6-SYNC.md](docs/M6-SYNC.md) §7, which has a per-row `Live`
column). 24 assert their own claims live. Rows 15 and 12b reach the server and return at a trigger
needing a shell on the instance (`occ maintenance:data-fingerprint`; a fillable quota), and row 19
has no server in it by design.

Every module's instrumented tests have run on an AVD this milestone: `:core:database` **47 tests**
(including the first on-device schema migration this project has ever run), `:app` **6** in a source
set that did not exist before, and `:android:ink` **15 with 10 failures that are all M1's** and all
x86_64-only — see *What an emulator cannot tell you*. SPEC §13.3's four process kills exist for the
first time, and the M6 app has been driven end-to-end over `adb`.

What is **outstanding**: M6's manual gates, and a pass on the actual Galaxy Tab, which the emulator
does not substitute for. Three of the gates need **two peers**, and that is now set up — a second AVD,
with `device.id` confirmed different on each (see *Two emulators as two sync peers*) — but they still
need an **HTTPS** Nextcloud and a browser sign-in on each peer, because the app refuses cleartext at
three separate gates and only one of them is a resource file. One gate needs seven calendar days.
`ROADMAP.md` M6 says per gate what would satisfy it and which automated row stands in meanwhile.

The *paper-change* half of the known live-editing symptom is **fixed**: `InkSurfaceView.setPageMeta`
exists, so a peer's paper change — and the undo of a local one — reaches the screen without moving
the camera or eating the stroke. It also uncovered two bugs the `= Unit` stub had been hiding: undo
was applied to whichever page was *on screen* rather than the one the gesture happened on, and no
structural edit (add/delete/move/duplicate a page, change the paper) enqueued an upload or refreshed
the library tile. A peer's *existing object* moving is still M8's — nothing in this build can move an
object, and the renderer never reads `InkObject.transform`. Also owed:
guardrail 2's iOS **framework** link (macOS CI only; it has not run for this branch), a committed
bundle fixture per schema version, and from earlier milestones M3's two performance criteria (500
notes at 60fps, no jank while thumbnails generate) and M1's latency gates — all of which need the
tablet or a Mac.

**M6 is not done.** Everything automatable is automated and green; nothing that requires a week or
the physical tablet has been done at all. M6 itself is committed (`4df34cd`), as are the follow-ups.
⚠️ `git remote -v` is **empty**, which is why guardrail 2's macOS CI job has never run for this
repository.

Milestone notes, newest first:

- [docs/M6-SYNC.md](docs/M6-SYNC.md) — the **four silent data-loss bugs** the pull side had to fix
  before it could be tested (every device sharing one `deviceId` is the one to read), the six places
  SPEC was wrong, the ETag-ping-pong termination argument, the defects an adversarial review found
  *after* the matrix was green — a permanent-loss path, two mass-trash paths and a 401 ladder among
  them (§5.5–§5.8) — and why *automated* and *automated and run* are different claims.
- [docs/M5-NEXTCLOUD.md](docs/M5-NEXTCLOUD.md) — Login Flow v2's six rules and the two that
  contradict each other, the outbox's full semantics, the closed Appendix B #4 spike (a published
  PDF round-trips a Nextcloud PUT byte-identically), and the two bugs only a device found.
- [docs/M4-PDF.md](docs/M4-PDF.md) — the M4.0 spike result (**`androidx.pdf` cannot write `/Ink`
  annotations, and the platform has no ink annotation type at all**), so PdfBox-Android behind
  `PdfWriter`, and what `saveIncremental` requires of every object you create.
- [docs/M3-LIBRARY.md](docs/M3-LIBRARY.md) — why thumbnail invalidation is keyed by `local_rev` and not a timestamp, what the folder model owes M5's Nextcloud mirror, and why title search is not FTS5 yet.
- [docs/M2-MODEL.md](docs/M2-MODEL.md) — the op log, the journal, and the binding bug that made the app record nothing on the tablet.
- [docs/M1-INK.md](docs/M1-INK.md) — the spike result (**`androidx.ink` does not predict internally — `input-motionprediction` is kept**), three places `androidx.ink` 1.0.0 did not match SPEC, and §4's exact measurement commands.
- [docs/M0-VERIFICATION.md](docs/M0-VERIFICATION.md) — skeleton, pinned coordinates, and both guardrails with their planted-violation tests.

---

## Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | **21 (LTS)** | Gradle 8.14 supports Java 17–24. **JDK 25/26 will not work** — the daemon refuses to start, and the error doesn't say why. |
| Android SDK | API 36 | Easiest via Android Studio. |
| macOS | only for the iOS guardrail | Kotlin/Native cannot build Apple targets on Windows or Linux. CI covers it. |

```bash
winget install Google.AndroidStudio
```

Open this folder in Android Studio once. It installs the SDK, writes `local.properties` (gitignored), and runs Gradle on its own bundled JBR 21 — so builds from inside the IDE work regardless of what's on your `PATH`.

### ⚠️ For command-line builds, set `JAVA_HOME`

Installing JDK 21 does **not** make it the default. Oracle's `javapath` shim stays ahead of it on `PATH`, so `java -version` keeps reporting 26 and Gradle refuses to start:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
```

To make it permanent, set `JAVA_HOME` in System Properties → Environment Variables, and move the JDK 21 `\bin` above `C:\Program Files\Common Files\Oracle\Java\javapath` in `PATH`.

## First run

The wrapper is committed, so there is nothing to bootstrap:

```bash
./gradlew verifyCatalogCoordinates
```

```bash
./gradlew build
```

(`tools/bootstrap-wrapper.ps1` regenerates the wrapper if it is ever lost, and checks your JDK first.)

---

## Getting a build onto the tablet

CLAUDE.md's definition of done includes *"it has been used on the actual Galaxy
Tab, not just an emulator"*, so this happens at the end of every milestone.

```bash
powershell -ExecutionPolicy Bypass -File tools\send-to-tablet.ps1
```

Builds `:app:assembleDebug` and copies the APK to the tablet's **Download**
folder over MTP. Then on the tablet: **My Files → Download → tap it.** Android
asks once for permission to install unknown apps from My Files.

The file is named after the commit — `inkstone-<sha>.apk`, or
`inkstone-<sha>-dirty.apk` when the tree has uncommitted changes — and the
script prints the name it used. That is not cosmetic. It means **the copy lands
under a name nothing on the tablet has, and older builds are deleted only after
the new one's byte count has been verified.** A 95 MB MTP transfer takes about
four minutes; when the earlier fixed name meant deleting first (an MTP overwrite
raises a prompt, and the script has no console to answer one on), an interrupted
run left the tablet with *no* APK rather than the previous build. Re-running
after an interruption is cheap: a complete copy of the same build is detected and
skipped.

Requirements: the tablet plugged in, **unlocked**, with its USB mode set to
**File transfer** — not *Charging only*, which hides the filesystem entirely.
`-NoBuild` skips Gradle; `-Device <name>` picks one when several are plugged in.

⚠️ **Run it with a timeout longer than the transfer, or in the background.** The
transfer is four minutes on top of however long the build takes, and a caller
that kills the script partway is the *only* way it has ever failed. Exit 137 is
that, not a tablet problem.

**Why not `adb install`?** Use it when you can — it is one command:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

But it needs USB debugging *authorized*, and `adb devices` will say
`unauthorized` until the "Allow USB debugging?" dialog is accepted **on the
tablet**. That dialog only appears in File transfer mode, has to be re-accepted
after a Revoke, and cannot be dismissed from the desktop. When it does not show
up, the MTP script is the way through — it is a plain file copy and needs no
debugging at all.

⚠️ **Measuring still needs adb.** `dumpsys gfxinfo` — the M1 frame-rate gate —
has no MTP equivalent. Getting a build onto the tablet and measuring it are
different problems, and the script only solves the first.

---

## Testing on an emulator

### Start here: `adb devices`

```bash
adb devices -l
```

**Do this before deciding what can be verified, not after writing the code.** An emulator is
usually attached, and the tablet showing as `unauthorized` does not mean there is no device —
those are two independent facts.

M6 is the cautionary tale. This section already existed, CLAUDE.md already said "get there via
an emulator", and a whole session still proceeded on the assumption that no device was
available. A reconciler, folder pinning and a PDF fix all shipped without running anywhere; two
of the three were **inert in the app while green in every test** — the exact class of bug an
emulator finds immediately and a JVM suite structurally cannot.

The tablet is required *at the end* of a milestone. It is a slow way to find
bugs on the way there, and if USB debugging is not authorized it cannot run
instrumented tests at all. **Create an AVD** — Android Studio → Device Manager →
Create Virtual Device, any tablet image at API 35+ — and most of a milestone's
device work can be driven from the command line.

Added in M5, which found three bugs this way that no JVM test could have.

```bash
adb devices                                               # emulator-5554
./gradlew :core:database:connectedDebugAndroidTest        # 67 tests, incl. the schema migration
./gradlew :app:connectedDebugAndroidTest                  # 19: crash safety + row 10's wet half
./gradlew :android:ink:connectedDebugAndroidTest          # 110 — all green since `SyntheticInk.turn`
adb install -r app/build/outputs/apk/debug/app-debug.apk  # …then drive the app
```

⚠️ **In that order.** `connectedAndroidTest` **uninstalls the app** when it finishes, so anything set
up by driving the UI is gone if a suite runs afterwards.

⚠️ **The emulator has no `sqlite3` and `adb root` is refused** on a Play-Store image, so there is no
way to edit the app's database in place. Reading it is `adb exec-out run-as dev.inkstone cat` (below);
writing it back means `run-as … base64 -d` fed in chunks on the command line, because `run-as` cannot
read `/sdcard` and `adb push` cannot target private storage.

### Driving the app

`tools/emulator-ui.py` does the dump-find-tap loop, so a step reads as what it means rather than
as a coordinate:

```bash
PYTHONIOENCODING=utf-8 python tools/emulator-ui.py emulator-5554 dump
PYTHONIOENCODING=utf-8 python tools/emulator-ui.py emulator-5554 tap "Add"
```

`PYTHONIOENCODING=utf-8` is not optional on Windows: the app labels controls with `✎`, `⇩` and
`›`, and cp1252 stdout raises on all three. The raw commands, when you want them:

```bash
adb shell uiautomator dump /sdcard/ui.xml   # then read bounds= and tap the centre
adb shell input tap <x> <y>
adb shell cmd connectivity airplane-mode enable      # …and disable
adb shell am force-stop dev.inkstone
adb exec-out run-as dev.inkstone cat databases/inkstone.db > local.db
```

⚠️ **Pull the database with `exec-out`, and take `-journal`/`-wal` with it.** `adb shell run-as …
cat` translates LF to CRLF and the copy opens as *"database disk image is malformed"*. And a live
SQLite database is not the whole story on its own — the sidecar file is where the last transaction
is.

**To see whether the screen actually changed**, compare framebuffers rather than eyeballing
screenshots. `screencap` with no `-p` is raw RGBA behind a 12- or 16-byte header (width, height,
format, and a colour space on newer images — infer which by checking `len - hdr == w*h*4`):

```bash
adb -s emulator-5554 exec-out screencap > before.raw
```

Two of M6's follow-up fixes were confirmed this way and could not have been confirmed otherwise:
picking a paper and pressing undo left the page **pixel-identical** to before (0 of 61,440 sampled
pixels differing) while 8.3% differed with the new paper up. A reader that is not the writer, which
is the fourth of CLAUDE.md's four checks.

**To run a background job now** rather than when the device is charging and idle — recognition, the
handwriting learner, a sync — force it by its job id. WorkManager's jobs live in a job namespace, and
without `-n` the answer is *"Could not find job"*:

```bash
adb shell dumpsys jobscheduler | grep "androidx.work.systemjobscheduler:u0a"
adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler dev.inkstone <job id>
```

**To measure line segmentation on a device's own notes**, a debug build has two broadcast tools
(`docs/LINE-SEGMENTATION-V2.md` §3). Both queue a job, so they work on a locked tablet:

```bash
adb shell am broadcast -n dev.inkstone/.app.debug.InkExportReceiver
adb shell am broadcast -n dev.inkstone/.app.debug.LineReadReceiver --es plan segmenter
```

The first writes `files/debug/ink-export.json`, which `RealInkSegmentationReport` reads on the JVM
(set `INKSTONE_INK_CORPUS` and `INKSTONE_INK_OUT`). The second reads every line with ML Kit into
`files/debug/segmenter.out.json`. ⚠️ **Work a receiver runs in its own coroutine is frozen** by the
cached-apps freezer a few seconds after the broadcast returns. That is why these queue a job. It
presents as a hang with every thread in `do_freezer_trap`.

**To walk the recognition model's download panel** (`docs/M7-RECOGNITION.md` §5.1), put the device
back into a fresh install's state first. This is ML Kit's own delete (it took 37 s on an AVD), and it
never downloads — that is a press in the app, and a ~20 MB transfer:

```bash
adb shell am broadcast -n dev.inkstone/.app.debug.RecognitionModelReceiver --es action delete
adb shell am broadcast -n dev.inkstone/.app.debug.RecognitionModelReceiver --es action status
```

Both log under `ModelDebug`. ⚠️ The AVDs here run at animator scale 0, so the *downloading* bar
shows only its track there; that is the reduced-motion case, not a bug.

### Two emulators as two sync peers

Rows about two devices need two *installs*, and `applicationId` has no debug suffix — so a second
copy needs a second AVD, not a second APK. Clone the one you have by copying **only its
`config.ini`**:

```bash
mkdir -p ~/.android/avd/Test_Tablet_B.avd
sed -e 's/^AvdId=Test_Tablet$/AvdId=Test_Tablet_B/' \
    -e 's/^avd.ini.displayname=Test Tablet$/avd.ini.displayname=Test Tablet B/' \
    ~/.android/avd/Test_Tablet.avd/config.ini > ~/.android/avd/Test_Tablet_B.avd/config.ini
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' 'C:\Users\<you>\.android\avd\Test_Tablet_B.avd' \
  > ~/.android/avd/Test_Tablet_B.ini
```

⚠️ **Copy the `config.ini` and nothing else. Never a snapshot, never `userdata-qemu.img`.**
`DeviceIdentity` is a *file* beside `notes/`, minted once per install and never again — so a
userdata copy hands two "peers" the **same `deviceId`**, which collapses SPEC §4.1's tiebreak:
two concurrent ops at the same counter compare equal, `Register.write` keeps the incumbent, and the
two devices diverge permanently with every op present and no error. It also duplicates every
`DocumentId` and every `remoteBinding`, so both claim the same remote file. Confirm they differ
before trusting anything a pair of them tells you:

```bash
for s in emulator-5554 emulator-5556; do
  adb -s $s exec-out "run-as dev.inkstone cat files/device.id"; echo " $s"
done
```

⚠️ **`connectedAndroidTest` destroys a peer.** It uninstalls the app, which takes `filesDir` with it
— so `files/device.id` is gone and a **new** identity is minted on next launch, along with an empty
database and no remote bindings. Half of a two-peer pairing therefore does not survive an instrumented
run on that emulator. Run the instrumented suites *first*, pair the peers *after*, in that order — and
re-read both ids if in doubt (observed: peer A went from `019feb4b-77e7-…` to `019feb9c-24d0-…` across
one `connectedDebugAndroidTest`).

⚠️ **Launch the second one detached, not from a shell whose lifetime is shorter than the boot.**
A backgrounded `emulator … | tail` gets torn down with the pipe *after* the guest has booted, which
looks like a boot failure and is not. On Windows use `Start-Process -RedirectStandardOutput`.

⚠️ **`airplane-mode disable` is not instant.** Re-enabling took **more than six seconds** to restore
connectivity on one of the two AVDs — long enough that a check run straight afterwards reads as *still
offline*, which is exactly the wrong conclusion in a test about going back online. Poll for the
network rather than sleeping a fixed amount, and `adb shell settings get global airplane_mode_on` to
confirm the setting itself landed.

⚠️ **A serial is a port, not an AVD.** `emulator-5554` is whichever AVD booted first. On
2026-10-13, Test_Tablet had gone down overnight and Test_Tablet_B, another session's peer, had come
back up as 5554. A command aimed at "Test_Tablet's" serial took B out of airplane mode for 91
seconds. Resolve the serial by name before **every** command, and refuse to run when the name is
wrong:

```bash
adb -s emulator-5554 emu avd name            # or: adb -s … shell getprop ro.boot.qemu.avd_name
```

⚠️ **A second AVD may get no Wi-Fi at all.** Emulator 37 runs Wi-Fi through a shared `netsimd`. The
second instance found the access point and timed out associating, again and again, through Wi-Fi
toggles and a guest reboot. Launching it with the built-in Wi-Fi instead fixed it:
`emulator -avd Test_Tablet -feature -WiFiPacketStream`. Two related things are not the fix. `ping`
is not a connectivity test on the built-in Wi-Fi, because its NAT drops ICMP. Ask Android instead:
`adb shell dumpsys connectivity | grep VALIDATED`.

⚠️ **A quick boot restores the last snapshot the emulator saved, disk included.** An AVD that went
down without saving can come back days old, with an older APK and none of the walk's data. Check
`dumpsys package dev.inkstone | grep lastUpdateTime` after any restart. Restored snapshots can also
leave the guest's clock and vsync confused. The symptoms are `Frame time is … in the future`, taps
that land but never redraw, and a `monkey` launch whose window stays `Surface: shown=false`. A cold
boot (`-no-snapshot-load`) cleared all three. Until then, `am start -n dev.inkstone/.app.MainActivity`
showed the window where `monkey` did not.

⚠️ **First launch on a fresh AVD can ANR.** `Reason: No response to onStartJob` — WorkManager's job
service, arriving while the app is still doing its first-run work at ~118% CPU on a shared host. The
second launch was 9.4 s and clean. Give it a cold run before concluding anything.

**They cannot both sign in to the throwaway test container**, and no resource or manifest change
fixes that. There are three gates, not one: the platform network-security config (which a
`src/debug` overlay could address), `LoginFlowV2.begin`'s `isSecure` check on the typed URL — in
`commonMain`, shared with iOS, refusing `http://` *before a socket is opened* — and the app password
itself, which only the login flow can write into the Keystore. Weakening shared `:core:sync` code so
a test can use cleartext is a worse trade than SPEC §14 is worth, so **two peers need an HTTPS
Nextcloud** the platform already trusts. Signing in is the user's step regardless: the app never
sees the password (SPEC §7.1 — Login Flow v2 authorises in the browser and Nextcloud issues an app
password per device).

⚠️ **Drawing needs `input stylus`, not `input`.** `PalmGuard` accepts only
`TOOL_TYPE_STYLUS` and `TOOL_TYPE_ERASER` (SPEC §3.3), so a plain
`input swipe` lands on the canvas and draws **nothing** — silently, with no
error and a stroke count that stays at zero:

```bash
adb shell input stylus swipe 600 700 1600 900 400
```

⚠️ **Re-dump before every tap.** The UI changes under you — tapping "Save here"
switches the folder browser to the accounts pane — and coordinates from a stale
dump land on whatever moved into that spot. In M5 that was **Sign out**, which
took a while to understand.

⚠️ **In Git Bash, prefix device paths with `MSYS_NO_PATHCONV=1`**, or
`/sdcard/ui.xml` is rewritten to `C:/Program Files/Git/sdcard/ui.xml`.

### What an emulator cannot tell you

- **Ink feel and frame timing.** The whole reason CLAUDE.md's definition of done
  says *"not just an emulator"*.
- **`:android:ink`'s instrumented tests**, which fail on x86_64 and pass on
  arm64: M1's synthetic-ink fixture does not clamp orientation to `[0, 2π)` and
  x86_64 rounding puts it a thousandth outside, which `androidx.ink` rejects.
- Anything about **the tablet's own SQLite build**, which is the argument
  `:core:database` makes for its tests being instrumented in the first place.

---

## Running the merge matrix against a real Nextcloud

SPEC §13.2's 21 rows run on every `./gradlew build`, against an in-process server that speaks
enough Nextcloud to keep the real transport, WebDAV client and multistatus parser in the loop.
Each row **also** has a live half, which skips loudly unless a server is configured — a suite
that reports success without having run is worse than no suite.

```bash
bash tools/nextcloud-docker/up.sh        # brings up a server and prints the exact test command
```

Or point it at any Nextcloud you have, with four environment variables:

```bash
INKSTONE_NC_URL=https://cloud.example.com \
INKSTONE_NC_LOGIN=you@example.com INKSTONE_NC_UID=you INKSTONE_NC_APP_PASSWORD=… \
  ./gradlew :app:testDebugUnitTest --tests '*MergeMatrix*' --rerun-tasks
```

`nextcloud-test.local.properties` in the repo root does the same thing and is gitignored — a
Gradle `Test` task re-runs on a changed input file, which it does not do for a changed environment
variable. `_LOGIN` and `_UID` are two values on purpose (SPEC §0.1 #3).

⚠️ **The live half runs under `testDebugUnitTest` only**, so 21 rows do not make every round trip
twice, and each run claims its own remote folder so two runs cannot collide and manufacture a false
conflict.

The live half goes through a counting reverse proxy on loopback (`CountingProxy`), which is what lets
rows about **economy** — one PROPFIND per idle poll, one PUT per five hundred saves — be asserted
against a real server rather than printed as unchecked. Two consequences worth knowing before you
point it at a Nextcloud you care about:

- **Row 20 authenticates with a deliberately wrong app password**, on purpose: that is the only
  honest way to get a real 401 without `occ`. It spends **two** of the instance's brute-force
  attempts per run (the default is roughly ten per thirty minutes, per IP). Two is asserted, not
  hoped for, so a re-run cannot lock the account out — but it is not zero.
- **Row 16 makes the proxy rewrite every `ETag`/`OC-ETag` to `W/"…"`** for the duration of that row.
  Nothing is written to the server that a normal client would not write.

Rows 15 and 12b need a shell on the instance (`occ maintenance:data-fingerprint`, and a quota that
can be filled) and skip their live half loudly. Set `occ=` in the properties file — or
`INKSTONE_NC_OCC` — to a command that reaches it, and row 15 runs too.

---

## Reading your notes and lectures from a desktop

First time only — sign in to Nextcloud in your browser:

```bash
tools\web\run-reader.cmd -Login
```

You type your server's address; your browser opens; you log in as you normally would, second
factor and all. Nextcloud hands the tool its own credential and stores it in
`watcher.local.properties`, which the M12 watcher reads too. **You are never asked for a password
here and this tool never sees one** — there is no app password to mint by hand. Then:

```bash
tools\web\run-reader.cmd -Open
```

⚠️ Use the **`.cmd`**, not the `.ps1`. Windows defaults the `CurrentUser` execution policy to
Restricted, so `run-reader.ps1` fails with *"running scripts is disabled on this system"* on a
machine where nobody has changed it — and a `.cmd` is not subject to execution policy at all. It
also picks an interpreter that works: with the Microsoft Store aliases enabled, `python` and
`python3` resolve to stubs that fail, while `py` does not. `py tools\web\server.py --open` is the
same thing by hand.

A local, **read-only** website for everything in your Nextcloud: the notes, and every recorded
lesson with its transcript and a scrubbable picture of when you were writing. It uses
the same `watcher.local.properties` the transcript watcher does, so if `run-watcher.ps1` works this
does too.

`-Mirror "C:\path\to\a\sync\folder"` reads a local folder instead, with no credentials and no
network — useful on a train, and what the tests run against.

The same index is exposed to Claude over MCP (`.mcp.json`, approve it once with `claude`), so
asking *"what did she say while I was writing this?"* is one call rather than a WebDAV crawl.
`docs/WEB-READER.md` has the design, §6 the per-claim evidence, and §7 what is still unproven.

There is no `PUT`, `DELETE` or `MKCOL` anywhere in `tools/web` — it cannot change or lose anything.

To run it on a server instead, `tools/web/compose.yaml` puts it in a container behind your reverse
proxy, with a passphrase for the website and a bearer token for Claude, and exposes the MCP tools
over HTTP so Claude on any machine can use them. `docs/WEB-READER.md` §9 is the setup and the
reasoning; §9.6 is the two bugs that only appeared once it was actually running.

---

## The two guardrails

`:core:*` must stay platform-free, and the iOS target is what proves it (SPEC §2.3).

**Guardrail 1 — no platform imports in shared code.** Runs as part of `check`, on every OS:

```bash
./gradlew checkCoreIsPlatformFree
```

**Guardrail 2 — every `:core:*` module still builds for iOS.** The full framework link is macOS-only:

```bash
./gradlew linkIosCore
```

Guardrail 2 is the one that matters. OkHttp and dav4jvm contain no `android.*` import at all, so they sail past Guardrail 1 while killing the iPad path silently.

⚠️ **So `./gradlew build` on Windows is not both guardrails, and a milestone note that says it is
overstates what was checked.** What a Windows `build` really runs, per `:core:*` module:
`checkCoreIsPlatformFree`, `compileKotlinIosArm64` / `compileKotlinIosSimulatorArm64` and their
`*MainKlibrary` tasks, and `compileTestKotlinIos*` — which is the pass that rejects a comma in a
backtick test name. What it logs as `SKIPPED`, because Kotlin/Native cannot target Apple here:
`linkDebugFrameworkIosSimulatorArm64`, `linkReleaseFramework*`, `linkDebugTestIosSimulatorArm64` and
`iosSimulatorArm64Test`. `linkIosCore` is a separate root task and belongs to the macOS CI job
(`.github/workflows/ci.yml`) — so guardrail 2's klib half is proved locally and its link half only
in CI. Say which half when reporting a build.

Kotlin/Native *cross-compiles* Apple klibs on Windows and Linux — only linking a framework needs macOS. So the guardrail's substance is testable anywhere. Prove both, on any OS:

```bash
bash tools/verify-guardrails.sh all
```

It plants a real violation for each (an `android.content.Context` import in `:core:model`, an OkHttp dependency in `:core:sync` **commonMain**), asserts the right one fails, and reverts. Off macOS the iOS half uses `compileKotlinIosSimulatorArm64`; the macOS CI job runs the real link.

---

## Module graph

```
:core:model       KMP  Document/Page/Op types, IDs, coordinate math. Zero platform deps.
:core:storage     KMP  Bundle + journal serialization. Stroke geometry is opaque bytes.
:core:database    KMP  SQLDelight schema + queries.
:core:sync        KMP  Nextcloud client (Ktor), outbox, reconciler, merge engine.
:core:search      KMP  Tokenizer + FTS index over recognised text.
:core:calc        KMP  fx-991ZA PLUS II calculator engine: keys in, LCD frame out.
:core:handwriting KMP  Handwriting synthesis: learns the user's hand from their pen strokes.

:android:ink      AND  androidx.ink, MotionEvent handling, renderer.
:android:pdf      AND  PDF import, render, materialize.
:android:ocr      AND  ML Kit Digital Ink Recognition, line segmentation.

:app              AND  Compose UI, Hilt, WorkManager, platform impls of core interfaces.
```

Dependency direction is one-way: `:app` → `:android:*` → `:core:*`.

## Useful tasks

| Task | What it does |
|---|---|
| `verifyCatalogCoordinates` | Resolves every pinned coordinate, including ones no module uses yet |
| `checkCoreIsPlatformFree` | Guardrail 1 |
| `linkIosCore` | Guardrail 2 (macOS) |
| `spotlessApply` | Formats Kotlin with ktlint |
| `detekt` | Static analysis |
| `build` | Assemble + all checks |

| Script | What it does |
|---|---|
| `tools\send-to-tablet.ps1` | Build the debug APK and copy it to the tablet over MTP |
| `tools/emulator-ui.py` | Dump/find/tap the app's UI over adb — the driving half of definition-of-done #4 |
| `tools/verify-guardrails.sh` | Plant a real violation of each guardrail, assert the right one fails, revert |
| `tools\bootstrap-wrapper.ps1` | Regenerate the Gradle wrapper if it is ever lost |
