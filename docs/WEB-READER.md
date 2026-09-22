# The reader — a website and an MCP server over your Nextcloud

**Status: built and driven end to end against a real 48-minute lesson, locally and in real
Chrome. §9 is the hosted shape — Docker, auth, and MCP over HTTP.**

**⚠️ Two things are unproven: nothing has run against a live Nextcloud (the app password answers
401), and the image has never been built (no Docker on this machine). §7 and §10.**

---

## 1. What it is for

Two complaints, one cause.

*"It is inefficient to make an AI look through Nextcloud to look at the notes and timelines."*
An assistant given WebDAV has to crawl directories to find anything, cannot tell a note from any
other PDF, cannot read the handwriting inside one, and has to download a whole transcript to find
one sentence. Every question costs a directory walk.

*"It is annoying to look through the notes in Nextcloud myself."* The web UI shows a folder of
PDFs and a hidden `.inkstone-media` directory of files named after UUIDs. A recorded lesson is
three files whose relationship is invisible, and the transcript is a wall of markdown with no way
to move around it.

Both are the same missing thing: **nothing knows what these files mean**. This does, and it serves
that understanding to a person through a browser and to an assistant through MCP — from one index,
so the two can never disagree about what is in Nextcloud.

```
tools/web/
  config.py       credentials, from watcher.local.properties or the environment
  source.py       where bytes come from: live Nextcloud, or a local folder
  timeline.py     <recordingId>.timeline -> strokes, page spans, the writing histogram
  transcript.py   the merged .md -> structure. The inverse of tools/m12/align.py's render
  notepdf.py      a published PDF -> its identity, its page order, its handwriting
  library.py      the index both front ends sit on
  server.py       the website
  mcp_server.py   the MCP server
  static/         the page: index.html, app.css, app.js
  oggopus.py      a recording's length, read from the container rather than guessed
  test_web.py     82 checks   test_mcp.py  30 checks
  run-reader.ps1  the launcher
```

---

## 2. Running it

```bash
tools\web\run-reader.cmd -Open
```

```bash
tools\web\run-reader.cmd -Mirror "C:\Users\me\Nextcloud"
```

⚠️ **The `.cmd`, not the `.ps1`.** Windows defaults the `CurrentUser` execution policy to
Restricted, so `run-reader.ps1` fails with *"running scripts is disabled on this system"* on an
untouched machine — the `.ps1` was written first and never run from a shell that had not already
been given a bypass, which is exactly the "written, not run" bucket §6 is about, arriving in a
launcher. A `.cmd` is not subject to execution policy, so it needs no `Set-ExecutionPolicy` and no
decision about one. The `.ps1` is kept for anyone who has already relaxed the policy.

Both launchers accept the PowerShell spelling (`-Open`, `-Mirror`, `-Port`, `-BindHost`) and the
argparse one (`--open`, …). `server.normalise_flags` rewrites a single-dash multi-character token,
because otherwise argparse reads `-Open` as the bundled short flags `-O -p -e -n` and fails
complaining about `-O`.

⚠️ **`py`, not `python`.** With the Microsoft Store app-execution aliases enabled — the default —
`python` and `python3` resolve to WindowsApps stubs that fail with *"The system cannot find the
path specified"*. Measured on this machine: `py --version` printed 3.14.5 and both of the others
failed. The launchers **test** each candidate by running it rather than trusting `where`, which
reports the stub as a perfectly good match. By hand: `py tools\web\server.py --open`.

### Signing in

```bash
tools\web\run-reader.cmd -Login
```

Type your server's address, log in in the browser that opens, done. ⭐ **You are never asked for a
password and this tool never sees one** — that is SPEC §7.1's Login Flow v2, the same exchange the
tablet uses, implemented in `login.py`. Nextcloud issues a credential of its own and it lands in
`watcher.local.properties`, so one sign-in serves the reader *and* the M12 transcript watcher.

Add `-SecretsDir secrets` to also write `secrets/nc_password` for the container (§9), so the
credential never has to appear on a screen.

**Why not just take a username and a password?** Because what comes out of this is better than
what you would have typed, in ways that only show up later:

* **It works with two-factor authentication.** An account password is *refused* for WebDAV on any
  account with 2FA — so the simpler-looking route is the one that breaks for the people most
  careful about their accounts.
* **It is revocable on its own.** It appears in Settings → Security as "Inkstone Reader"; delete it
  and this tool stops and nothing else does.
* **It carries no second factor and cannot change the account.** A leaked config file costs file
  access, not the account.
* ⚠️ **A stale password is worse than an inconvenience.** Nextcloud throttles failed logins by IP
  (rule 5). A stored *account* password that goes stale is a background process repeatedly failing
  to log in as you; a stale app password fails as a credential nobody is using.

If the credential is ever revoked or expires, run `-Login` again — it overwrites the old one and
leaves every other setting in the file alone.

Credentials otherwise come from **`watcher.local.properties`** — the same gitignored file the M12
watcher uses. If `run-watcher.ps1` works, this works, with nothing new to configure.

The MCP server is registered for this project in `.mcp.json` and needs approving once:

```
claude mcp list            # shows: inkstone - Pending approval
```

Run `claude` in this repo and approve it. ⚠️ **`${CLAUDE_PROJECT_DIR}` is not expanded inside
`.mcp.json`** — it is set in the spawned server's environment, which is after expansion happens,
and Claude Code reports *"Missing environment variables: CLAUDE_PROJECT_DIR"*. The entry therefore
uses a repo-relative path. If the server fails to start, an absolute path is the fallback.

For Claude Desktop, add an `mcpServers` key to
`%APPDATA%\Claude\claude_desktop_config.json`. ⚠️ **Add the key — do not replace the file**, which
is what the official documentation says to do; that file already holds `coworkUserFilesPath` and
`preferences`, and replacing it discards them.

---

## 3. ⭐ It cannot write, and that is structural rather than careful

There is no `PUT`, no `DELETE` and no `MKCOL` anywhere in `tools/web`. `source.py` exposes
`listing`, `read` and `exists` and nothing else, so no code path in the website or the MCP server
*can* mutate Nextcloud — including a future one written by somebody who did not read this.

That is CLAUDE.md rule 1 satisfied by construction. It also settles the question an MCP server
otherwise raises: an assistant given these tools can read a lesson and cannot lose one.

The two exceptions are not exceptions: `index_notes` downloads, and the caches are local.

---

## 4. The decisions worth recording

### 4.1 It reuses `tools/m12/nextcloud.py` rather than speaking WebDAV again

CLAUDE.md: *"a second client for a protocol the project already speaks is where the old bugs come
back — diff it against the first one."* M12's own watcher shipped three bugs that `:core:sync` had
got right years earlier. That client already percent-encodes per segment, flags the `Depth: 1`
self-entry rather than dropping it, sends a real User-Agent so a Cloudflare-fronted server does not
403 everything, and refuses an XML document declaring a DTD. Reusing it is four bugs this tool does
not get to have.

`timeline.py` goes further and **imports `align.parse_timeline`** when it can, falling back to its
own copy only when `tools/m12` is missing. `test_web.py` asserts the two agree on the real file, so
they cannot drift.

⚠️ **One change was needed in `nextcloud.py`: `listing()` was requesting `d:getetag` and
`oc:fileid` and then discarding both.** The watcher never needed them — its idempotency is *"is
there a `.md` beside it"* — so nothing noticed for two milestones. The reader caches on the ETag,
and without it every page load would re-download every note PDF. Two keys added; `test_watch.py`'s
17 checks pass unchanged.

### 4.2 The walk keeps finished recordings, which is the one place it differs from the watcher

`watch.discover` returns *"every recording that has no transcript yet"* — correct, because its job
is to find work. A finished lesson is invisible to it. This tool's job is to show you your lessons,
and a finished lesson is the only kind worth reading, so the walk is re-implemented with the
watcher's three rules (breadth-first to `MAX_DEPTH`, skip `is_self`, stop at `.inkstone-media`) and
the opposite filter.

### 4.3 ⭐ A note's title, page order and handwriting are read out of the published PDF

`notepdf.py` is the piece that makes the rest worth having. A published note carries two things
(SPEC §5.2, §5.3, §5.6):

* an **uncompressed** XMP marker with the `documentId`, the schema version and the bundle's
  SHA-256;
* the bundle `note.inkstone` — a *stored* ZIP, `/FlateDecode`d into the file — holding
  `manifest.json` (the real title, and the pages with their `sortKey`) and `text/<pageId>.json`
  (M7's recognised handwriting).

So the reader can show a real title instead of a filename, map a recording's page markers onto
actual PDF pages, and — the one that changes what an assistant can do — **search your
handwriting**.

**It scans rather than parsing the object graph, and that is the sanctioned route rather than a
shortcut.** `PdfBoxWriter.writeMarker` writes the marker uncompressed *precisely* so that "a tool
can find the packet by scanning the file rather than by parsing the object graph". And a published
note is an incrementally-updated PDF whose original bytes are a verbatim prefix, which makes its
cross-reference structure the most fragile thing in the file and the thing a scan does not need.

⭐ **The scan can prove itself right.** The marker records `bundleSha256`, so the bytes found by
inflating every stream and looking for a ZIP are checked against a digest *the writer* recorded.
That is a reader verifying itself against the writer's own evidence rather than against its own
opinion — M4's lesson, where PDFs verified with the library that wrote them were blank everywhere
else. A digest mismatch is reported as `bundleVerified: false` rather than hidden.

⚠️ **The prefix alone is not identity.** `marker()` requires the namespace URI
`https://inkstone.dev/ns/pdf/1.0/` to be present, exactly as `InkstoneXmp.parse` does, or any
producer that happened to use the prefix `inkstone` would have its files claimed as notes.

### 4.4 Handwriting search is explicit, and says what it did not search

Reading a note means downloading its PDF, so searching handwriting across a library means
downloading the library. Doing that because somebody typed in a search box would be
indistinguishable from a bug. So `index_notes` is a separate, explicit action, and until it has
run, `search` covers transcripts only.

⚠️ **The result carries `notesUnindexed`, and both front ends show it.** *"No hits"* and
*"nothing was looked at"* are different answers, and this repo has been bitten often enough by the
second wearing the first's clothes to make it a visible number rather than a footnote. The MCP tool
says the same thing in words, so a model does not conclude something is absent when it was merely
not searched.

### 4.5 The duration comes from the transcript, never from the last stroke

The transcript's `duration` is read from the Ogg container's own granule (`OggOpus.durationMillis`,
M12 §15.3 — a duration must be read from the container, never estimated from bytes ÷ bitrate). The
last stroke is not the end of the recording: the measured lesson's last stroke is at 2,907.8 s
against 2,928.1 s of audio, and 24 of its pauses run longer than fifteen seconds.

Using the last stroke would stretch the writing histogram to fill a recording that was really
longer — a plausible-looking lie about when everything happened. `library.recording_detail` prefers
the transcript's duration and falls back only when there is no transcript at all.

---

## 5. ⭐ The lesson ribbon

The one thing on the page that says something the files do not say separately. Two tracks over one
time axis:

```
  ink    ▁▃█▇▂▁      ▁▂▅█▃          ▁▁▂        how much was being written
  voice  ████░░████████░░░░████████████        when somebody was talking
         └ page 2                    └ page 3
```

* **ink** is the stroke histogram from `<recordingId>.timeline`, bucketed. Drawn on a **square-root
  scale**: the measured lesson is violently bursty — 208 strokes in one five-minute block against
  17 in another (M12 §15.6) — and a linear scale flattens every quiet block to nothing.
* **voice** is built from the transcript's own timestamps, with the likely teacher drawn taller and
  in the focus colour so the two speakers are separable without relying on hue alone.
* **dead runs** — neither speech nor ink — are punched out of the voice track.
* **microphone gaps** are drawn in red across both tracks, because that audio is *missing, not
  silent*, and that distinction must survive into the UI.
* the quiet stretches behind the ink track are the lesson's working time.

Reading the two together is the whole of M12 §1: a stretch with a voice band and no ink is the part
of the lesson the page cannot hold, and it is exactly where a reader should be looking.

Clicking or dragging it moves through the lesson: the transcript follows and the clock agrees.

⭐ **There is no player, and that is the design.** `tools/m12/watch.py:206` trashes a recording
once its transcript is written and verified present on the server, so a lesson in the steady
state is a transcript and a timeline. An earlier version of this reader carried a media element,
a Play chip, a `/media/audio` byte-range route and a media cache — a whole transport for a file
that only exists in the gap between recording and transcription. Removing it also removed the
thing this server was least comfortable doing: handing out recordings of a room full of
identifiable people over HTTP.

---

## 6. What was built, and what each claim rests on

CLAUDE.md: *"a checklist tick belongs to a claim, not to a file existing. Say which of written,
automated, automated against the real dependency, and run on a device each one is."*

| Claim | Evidence | Bucket |
|---|---|---|
| The timeline parser reads a real recording | 867 strokes, first 6.1 s, last 2907.8 s, longest quiet run 340 s — every figure **matching M12 §15.6, which measured them with different code** | automated, real data, independent witness |
| …and agrees with `align.py` | `test_web.py` compares both parsers over the real file | automated, real dependency |
| …and a torn tail stops the read | `test_web.py`; a stroke past the end is clamped, never dropped | automated |
| The transcript parser reads the real merged document | 3,111 words, 9 dead runs, longest 00:17:43–00:19:02 — **matching M12 §15.7** | automated, real data, independent witness |
| …with nothing in it unrecognised | `test_web.py` asserts zero `prose` segments over the real file | automated, real data |
| …and round-trips `align.render` | rendered by `align`, re-read by this parser: front matter, page bullets, gaps, page turns, speakers | automated, **reader that is not the writer** |
| A published PDF yields its title, pages and handwriting | `test_web.py`, against a **synthetic** PDF built the way `PdfBoxWriter` builds one | ⚠️ **automated, synthetic fixture only** — see §7 |
| …and a wrong bundle is reported, not served | digest mismatch ⇒ `bundleVerified: false` | automated, synthetic |
| …and a stranger's PDF is not claimed | namespace-URI check | automated, synthetic |
| The walk finds recordings and tells their states apart | `test_web.py` over a mirror built from the real lesson | automated, real data |
| Byte ranges work, including a suffix range | `test_web.py` over a **real socket**: 206, exact slice, `bytes=-8` returns the *last* 8, 416 past the end | automated, real transport |
| A path escaping the static dir or the mirror root is refused | `test_web.py` | automated |
| The token gate refuses and admits | `test_web.py` | automated |
| The MCP server speaks the protocol | `test_mcp.py` drives it as a **subprocess over a real pipe**: 26 checks | automated, real transport |
| …echoing the client's protocol version | `test_mcp.py`; falls back for a version it does not know | automated, real transport |
| …with every stdout line one parseable JSON object | `test_mcp.py` checks every line it received | automated, real transport |
| …an unknown tool is a JSON-RPC error, a failed tool is `isError` | `test_mcp.py` | automated, real transport |
| …and it exits when stdin closes | `test_mcp.py` asserts the exit code | automated, real transport |
| Claude Code accepts `.mcp.json` | `claude mcp list` reports the server, no diagnostics | run against the real client |
| The website works | **driven in a browser**: library, ribbon, scrub, transcript follow, search, hit → moment, page jump | run in a browser |
| The launcher runs | executed; parses clean; zero non-ASCII code lines | run |

| An adversarial review's 31 confirmed defects are fixed | §8, each with a test in `test_web.py` / `test_mcp.py` | automated |
| …including the ribbon's speech coverage | 38.9% computed, against §15.7's independently measured **39%** | automated, independent witness |
| No audio player survives the removal | `test_web.py` asserts app.js, index.html, app.css and server.py mention none of it — including `pendingSeekMs`, `timeupdate` and the `.opus` MIME type, because **a comment can be the dangling thing** | automated |
| …and the page is clean in a browser | fresh tab: zero `<audio>` elements, **zero console errors**, ribbon reports `role="slider"` | run in a browser |
| A transcribed lesson knows how long it is | `durationMs` 2,929,000 over HTTP and `48.8 min` in the library — against §15.3's independently measured 2,928.1 s | automated, real data, independent witness |
| The four defects the removal left behind are fixed | ←/→ after a real click on a library item; a cancelled drag; a failed open; a `document`-targeted keydown — each driven in the browser and each with a source assertion in `test_web.py` | run in a browser + automated |

**350 checks across nine suites**, counted by running each one rather than remembered:

| Suite | Checks | |
|---|---:|---|
| `test_web.py` | 143 | the reader, the server, the hardening |
| `test_safefetch.py` | 48 | SSRF and the multi-user fetch guard |
| `test_accounts.py` | 35 | per-account isolation |
| `test_mcp.py` | 30 | the MCP protocol layer |
| `test_mcp_http.py` | 27 | the Streamable HTTP transport |
| `test_login.py` | 18 | Login Flow v2 |
| `test_tlsdiag.py` | 12 | the TLS-interception diagnosis |
| `tools/m12/test_watch.py` | 17 | M12's, listed because §4.1 changed a file it covers |
| `tools/m12/test_align.py` | 20 | as above |

⚠️ An earlier version of this table said **149 across four suites (82 reader, 30 MCP, …)**, and
it was wrong in both directions: it had gone stale against `test_web.py` and it omitted four
suites entirely. CLAUDE.md's rule about Gradle's `N actionable tasks` line applies to a
hand-written count too — a number in prose is only as good as the last time somebody ran the
thing.

---

## 7. ⚠️ What is NOT proven

Stated plainly, because the alternative is a green tick that means less than it looks like.

0. ⚠️ **The findings in §8 were fixed and tested, but only three of them were re-verified in a
   browser** (the keyboard transport, the page-jump, the sign-in). The rest rest on unit
   tests and on reading. That is a weaker bucket than the ones above it and is worth knowing.

1. ⛔ **Nothing has run against a live Nextcloud.** The app password in
   `watcher.local.properties` answers **401** on `/ocs/v2.php/cloud/user`. The value is a
   real-shaped app password (`xxxxx-xxxxx-xxxxx-xxxxx-xxxxx`), so it has been revoked or has
   expired rather than never having been set. **It was not retried** — CLAUDE.md rule 5, and the
   reason is Nextcloud's brute-force protection, which is how a user gets their own server to
   IP-block them.

   Mint a new one under Settings → Security → *"Create new app password"*, put it in
   `nextcloud.password`, and the first check is:

   ```powershell
   ./tools/web/run-reader.ps1 -Open
   ```

   The status dot at the top right turns green and names your uid when the credential works, and
   the page shows the 401's advice when it does not.

2. ⛔ **The container image has never been built.** Docker is not installed on this machine and
   WSL will not start (the virtualisation feature is off), so `tools/web/Dockerfile` and
   `compose.yaml` are **written, reviewed against current Docker guidance, and never executed** —
   the weakest bucket in this document. What *is* tested is everything the container wraps:
   secrets from files, the refusal to bind publicly without auth, the Host and Origin gates, the
   `/healthz` probe, and the SIGTERM dispatch (`test_web.py`'s `test_container`). The first
   `docker compose up` is what closes it, and a build failure there will be a typo rather than a
   design error.

3. ⚠️ **`notepdf.py` has never read a PDF the app published.** No device was attached
   (`adb devices` is empty) and the server is unreachable, so the only fixture available was a
   synthetic one built to `PdfBoxWriter`'s recipe. This is the weakest claim in the table and it
   is the one carrying the most: note titles, page numbering and handwriting search all rest on it.

   ⚠️ M7's warning applies — *"a synthetic fixture is the worst possible witness"* — though less
   sharply than for a geometric heuristic, since this is a container format with a digest that
   checks itself. **The first real note read end to end settles it**, and `bundleVerified` in
   `/api/note` is the field to look at: true means the bytes matched the digest the tablet wrote.

4. **Page markers can sit up to a second early.** `align.clock` renders whole seconds, so a page
   turn recovered from the merged markdown has one-second resolution. The `.timeline` file keeps
   milliseconds and is preferred wherever both exist; only a recording whose timeline is missing
   falls back to the coarser figure. Asserted in `test_web.py` rather than left to be discovered.

5. **No recording with page ids has been read.** The only real recording available predates the
   page column, so `page_spans`, the ribbon's page ticks and `lecture_timeline`'s page section are
   exercised by fixtures only. The next lesson recorded on the current build is the first that can
   show them — which is the same thing M12 §15.7 is still waiting for.

6. **Search has never run over a real library.** One lesson and no notes is not a corpus, and
   nothing is known about how `index_notes` behaves over a term of lectures — how long it takes,
   or how large the cache gets.

---

## 8. ⭐ What an adversarial review found, and what it cost

Four independent reviewers over the finished code — inert features, security, correctness, and the
page as a user meets it — produced 46 candidate defects; each was then handed to a separate agent
told to **refute** it. **31 survived.** Every one below was fixed and has a test.

The point of listing them is not the count. It is that this code had already been driven end to
end in a browser and was passing 92 checks, and the two most expensive findings were **exactly the
failure this repo keeps finding**: a feature that worked perfectly behind the boundary and was
unreachable in front of it.

### 8.1 The two that were inert

- ⛔ **`/api/index` had no caller.** The search results said *"12 of 12 notes not yet indexed —
  handwriting in those was not searched"* and offered no way to index them. The route existed,
  worked, and was reachable only over MCP — so §4.3's headline claim, *search your handwriting*,
  was true for an assistant and false for the person sitting in front of the page. Naming a
  shortfall the user cannot act on is worse than not naming it; there is a button beside the
  message now.
- ⛔ **`notepdf.Note.page_number` had no caller**, so both front ends printed
  *"page 0199c4f2-7a3e-7c11-…"*. The mapping from a timeline's `pageId` to a page in the published
  PDF had been implemented, tested and never asked for. Page markers now say **"page 2"** and carry
  a *Show it* button that turns the PDF pane to it — and the ribbon's ticks are numbered the same
  way. Page turns are also read from the **timeline** in preference to the transcript now, which
  is both higher-resolution and present for recordings whose transcript predates page ids.

### 8.2 The security ones

- ⛔ **A revoked app password was re-sent on every scan.** Rule 5 was satisfied inside every
  individual call — nothing looped — but the *website* re-scans on load, on Refresh, and when the
  60-second cache expires, and each scan is one PROPFIND per folder. A dead credential was
  therefore a failed login per folder per refresh, forever. **The retry loop was the user**, and it
  was invisible from inside any single call. The first 401 now latches the source shut until the
  process is restarted.
- ⛔ **A ZIP bomb inside a PDF.** `zipfile.read()` inflates without consulting the declared size,
  and the reader opens *every PDF in the account* — a place other people's files arrive. A 1.2 KB
  file could cost 830 MB. There is a per-entry cap now, and `extract_bundle` keeps one candidate
  rather than every one that inflated.
- ⛔ **DNS rebinding.** Binding to `127.0.0.1` is not a threat model: a page on any website can
  point a hostname it controls at loopback and read your lessons through your own browser. The
  `Host` header is checked.
- **`--root` bounded the walk and not the reach**, so it limited what was *listed* and not what
  could be fetched by path. **A non-ASCII token crashed the gate** instead of refusing (`compare_digest`
  requires ASCII `str`). **MCP dispatched by `getattr`**, so every private helper was an undeclared
  tool; the callable set is now derived from `tool_definitions()`.

### 8.3 The correctness ones

- ⭐ **The ribbon's voice track was claiming 85% speech in a lesson that is 39% speech.** Each
  paragraph's band ran to the *next* paragraph's start, and the last ran to the end of the
  recording — so every silence was painted as talking. Bands are bounded by how long their own
  words plausibly took, at the ~164 wpm §15.7 measured. Corrected coverage: **38.9%** against
  §15.7's independently measured **39%**, which is the check that says the fix is right rather
  than merely different.
- **The duration fallback was the thing its own comment forbade.** The comment above it explained
  why the last stroke is a lie about the tail; the code then used the last stroke. A recording with
  no transcript now has its length read from **the Ogg container** (`oggopus.py`, two range reads),
  which is M12 §15.3's rule — a duration is read, never estimated — applied on this side too.
- **A recording could be bound to a sibling PDF already known to be a different document.** The
  sole-sibling guess is now refused when the PDF's `documentId` contradicts it.
- **`_front_matter` truncated any value containing `#`** — and `Exercise #4 - Straight lines` is a
  legal note name, since `RemoteName.FORBIDDEN` deliberately omits `#`.
- **`notesUnindexed` was counted inside the hit limit**, so a query that filled its limit on
  transcripts reported every note as unindexed — the field that exists to distinguish *no hits*
  from *nothing was searched* was itself lying.
- **A reversed byte range sent `Content-Length: -49`** and hung the client.

### 8.4 The interface ones

- **Space was taken from every button on the page** once a lecture was loaded — `preventDefault`
  made it a replacement, not an addition, so Tab-to-Refresh-and-press-Space played the lecture.
  ⭐ Both this and the one below were **fixed and then deleted**: the player they belonged to is
  gone (§5), so what survives is the lesson rather than the code. They are kept here because the
  *shape* of each recurs — and one of them came straight back. Space's guard outlived Space, and a
  guard sized for a button's own activation key is far too wide for an arrow key: **opening a
  lecture parks focus on the library item you clicked**, which is a `<button>`, so ←/→ did nothing
  until you clicked elsewhere. Dead immediately after the one action that makes them relevant.
- **Opening a second lecture left the first one playing.** `removeAttribute('src')` does not stop
  a media element — verified directly in the browser: it kept playing at 2 s — and since a
  transcribed recording has *no* audio by design, this was the ordinary path, not an edge. The
  Play chip then read *"No audio"* and was disabled, so nothing on screen could stop it.
- **A failed open left the previous lecture's transcript on screen under a "Loading…" heading**,
  with the sidebar already marking the new one active.
- **The transport stayed live on the note and search views**, so the arrow keys drove a lecture
  that was not on screen. **Activating a library item destroyed the focused element**, throwing a
  keyboard user back to the top of the document.
- **Three grey tokens sat at 2.34:1** and the ribbon's own empty-state message at **1.7:1** — a
  message about missing data, drawn so faintly that missing data and a missing message looked the
  same. **`prefers-reduced-motion` reached the stylesheet but not the JavaScript smooth-scroll**,
  which is the one piece of motion on this page that repeats for an hour.
- **The banner was not a live region**, and it never cleared when the thing it complained about
  started working — so *"Nextcloud is unreachable"* stayed up over a freshly populated library.

### 8.5 ⚠️ And one lesson that was not about the code

Two of these fixes were verified against a **stale process**. `kill %1` has no job table across
tool invocations, so a "restart" never happened, the server on port 8765 was one started forty
minutes earlier, and Python edits were not in it — while CSS and JS edits *were*, because those are
read from disk per request. That asymmetry is the trap: the page visibly picks up your changes,
so the server looks like it did too.

It very nearly produced a wrong diagnosis — the index button was declared broken when it was the
old code answering. **Check the listening process's start time, not the fact that the port
answers**: `Get-NetTCPConnection -LocalPort … | Get-Process | Select StartTime`.

### 8.6 ⭐ A second sweep, over the removal itself

Removing the player (§5) was swept the same way: 31 candidates, **18 confirmed**. Most were stale
prose, and the interesting thing is that prose was the *right* thing to look for — but four were
live defects, and every one of them was in code the player left behind rather than in the player.

- ⛔ **The arrow keys were dead immediately after opening a lecture.** Space needed a guard
  against every focusable control; Space went, the guard stayed, and opening a lecture parks focus
  on the library item you clicked — a `<button>`. So ←/→ did nothing until you clicked elsewhere.
  A guard sized for one key is not a guard for another: Left and Right belong to a text caret, not
  to a button, so only text entry is exempted now.
- ⛔ **`estimatedMinutes` was 0 for every transcribed lesson**, i.e. for every lesson in the
  steady state, because it was `audio_bytes × 8 / bitrate` and the audio is trashed. The library
  showed a length only for the lessons you can do least with, and MCP's `list_lectures` promised
  *"how long it is"* and printed `approx 0 min`. It reads the transcript's own `duration` now —
  which `align` measured off the Ogg granule, so it is better than the bitrate estimate ever was —
  by a **byte range** over the front matter, so a listing still downloads no transcripts.
  ⚠️ The fixture is what hid it: it wrote a `.opus` beside a transcript, a state the watcher
  never leaves the account in. *A fixture more generous than production*, exactly.
- **A failed open left the previous lesson's ribbon painted** under the words *"Could not open
  this lesson"*, with its clock still in the transport. The cleanup beside it was thorough about
  everything made of text: a canvas keeps what was painted on it, so it has to be told.
- **A cancelled drag never ended.** `pointerup` was handled and `pointercancel` was not, so a
  touch that became a scroll left the playhead following the cursor with no button held.

And one found *by* the browser rather than by review: `event.target.closest` throws when the
target is `document` rather than an element, which in a document-level listener means the key
does nothing and only the console says so. `closest?.()`. The pre-existing guard had the same
latent throw.

⚠️ **`Right` is not `ArrowRight`.** Three device-style checks reported the arrow keys still
broken after the fix, because the browser tool's key name is `ArrowRight` and `Right` is silently
nothing. This is CLAUDE.md's *"suspect the instrument before the feature"* for the fifth time, and
the thing that settled it was dispatching a synthetic event that worked while the "real" press did
not — when two routes to the same code disagree, the one that is lying is the harness.

---

## 9. Hosting it on a server

Everything above is the loopback tool. This is what changes when it goes on a machine other people
can reach, and the honest summary is that **the original security argument does not survive the
move** — *"on 127.0.0.1 the audience is programs already running as this user"* was true,
load-bearing, and is false the moment it has a public name.

```
tools/web/
  auth.py          the gate: two secrets, a session cookie, a rate limiter, an origin check
  mcp_http.py      MCP over Streamable HTTP, so Claude can reach it from another machine
  healthcheck.py   the container's probe
  Dockerfile       python:3.14-slim-trixie, non-root, its own SIGTERM handler
  compose.yaml     no published port, secrets from files, a named cache volume
  env.example      the non-secret settings
  deploy/          worked Caddy and nginx configs
```

### 9.1 Getting it up

```bash
docker network create proxy
```

```bash
cp tools/web/env.example .env
```

Edit `.env` — the Nextcloud URL, your uid, and `INKSTONE_ALLOWED_HOSTS`. Then the three secrets,
each in its own file:

```bash
mkdir -p secrets && printf '%s' 'xxxxx-xxxxx-xxxxx-xxxxx-xxxxx' > secrets/nc_password && printf '%s' 'a passphrase you will remember' > secrets/web_password && openssl rand -hex 32 > secrets/mcp_token && chmod 600 secrets/*
```

```bash
docker compose -f tools/web/compose.yaml up -d --build
```

Then point your proxy at `reader:8765`. `tools/web/deploy/Caddyfile` is the short version;
`deploy/nginx.conf` is there for a machine that already terminates TLS in nginx.

### 9.2 The authentication, and why it is shaped like this

**Two secrets, not one.** `INKSTONE_WEB_PASSWORD` is typed by a person; `INKSTONE_MCP_TOKEN` sits
in a config file on every machine you use Claude from. Different blast radii, so rotating one must
not force the other — sharing a value would mean a leaked MCP token, the one at rest on several
devices, also logs somebody into the website.

**The browser gets a cookie, not a token in the URL.** `?token=` leaks into browser history, into
`Referer`, and into your proxy's access log. The cookie is `HttpOnly` — an XSS bug cannot read it,
verified in real Chrome, where `document.cookie` comes back empty while signed in — plus `Secure`
and `SameSite=Strict`.

**The login is rate limited**, because a login form without one is CLAUDE.md rule 5's own hazard
rebuilt in our front door and aimed at us.

**Sessions live in memory.** A restart logs you out, so `docker restart` is the
revoke-everything button; there is no session store to corrupt and no signing key to leak.

⛔ **`--token` is gone.** It put a secret on a command line, where `ps` can read it, and in a query
string. Fine as a loopback dev switch; not fine as the thing between the internet and a term of
class recordings.

### 9.3 ⭐ The MCP endpoint, and the one decision that is not the specification

`/mcp` speaks **Streamable HTTP** at revision `2025-11-25`. Register it on another machine with:

```bash
claude mcp add --transport http inkstone https://notes.example.com/mcp --header "Authorization: Bearer PASTE_TOKEN_HERE"
```

The committed `.mcp.json` still points at the **stdio** server, which is correct for this machine —
Claude Code connects locally and needs no network for it. Switch it to the URL form only where the
repo is not the server.

⚠️ **Claude Desktop cannot reach it through `claude_desktop_config.json`.** That file is for local
stdio servers only. Desktop's remote support is the *custom connector* UI, and a connector's
connection originates from Anthropic's servers rather than from your machine — so the reader has to
be publicly reachable for that route, not merely on your LAN.

⛔ **An unauthenticated `/mcp` request answers `403`, not `401`, and carries no `WWW-Authenticate`.**

A deliberate divergence from the specification's own mechanism, on measured behaviour rather than
taste. The spec's route is a `401` carrying `WWW-Authenticate: Bearer resource_metadata="…"`, which
points a client at an OAuth authorization server. This server has one user and one token and wants
no OAuth — and a client that decides a server needs OAuth can **ignore a correctly configured
static bearer header**, report itself connected, and expose synthetic `authenticate` tools instead
of any of the real ones. That failure looks exactly like success, which makes it worse than a
refusal. So: 403, no challenge, and **no `/.well-known/oauth-*` route anywhere in the server** —
their mere presence is enough to start that path. `test_mcp_http.py` asserts all three, because
anyone adding an OAuth flow later has to change them together.

### 9.4 What the transport rules cost to get right

Each was read off the specification, and several are the opposite of the obvious guess. They are
listed because every one fails **silently** — the server looks fine and the client simply will not
connect.

| Rule | The guess that breaks it |
|---|---|
| A POSTed notification answers **202 with no body** | 200 with `{}`. `notifications/initialized` is the second message every client sends. |
| A request may be answered with **one JSON object**; SSE is the optional alternative | Implementing SSE because the transport is called "streamable". |
| **GET is refused with 405** | Building a server-to-client stream nothing needs. |
| An **absent** `MCP-Protocol-Version` means `2025-03-26` and must not be rejected | Requiring the header — which refuses every request a client makes after `initialize`. |
| `Origin` is validated **only when present** | Requiring it. Claude Code sends none, so every real connection is refused. |
| Header names are **case-insensitive**, and the client sends them lowercase | An exact-case lookup, which makes the version check decorative. |
| The response **`Content-Type` must be `application/json`** | Leaving it unset. Measured as the commonest cause of `CLIENT_HTTP_UNEXPECTED_CONTENT`. |
| Claude Code opens with a **`server/discover` probe** carrying `2026-07-28` | 400ing it and relying on the client's fallback to `initialize`. |

### 9.5 The container

**`python:3.14-slim-trixie`, not alpine.** The usual slim-over-alpine argument is about compiling
wheels and does not apply to an app with no dependencies at all. The one that does: **musl's DNS
resolver can mis-demultiplex parallel A/AAAA queries**, returning spurious failures under load,
and this process makes outbound HTTPS calls for everything it does. Distroless was the other real
candidate and lost on having no shell to debug with twice a year.

⚠️ **It installs its own SIGTERM handler, and that is not optional.** A process running as PID 1
gets no default signal behaviour, Python installs no `SIGTERM` handler, and `http.server` installs
none — so a plain `CMD ["python", …]` **ignores `docker stop`** and is SIGKILLed ten seconds later.
And `shutdown()` has to be dispatched to another thread: the handler runs on the thread blocked
inside `serve_forever`, so calling it inline waits for a loop that cannot run until the handler
returns. `test_web.py` asserts `shutdown` is invoked on a *different* thread than the handler, and
that test goes red if the dispatch is ever simplified away.

**Secrets come from files, not environment variables** — the standard `*_FILE` convention. An
environment variable is readable through `docker inspect`, through `/proc/<pid>/environ`, and by
every child process. The plain variables still work, because being unable to start quickly is its
own kind of failure.

**No published port.** `expose` documents 8765 and publishes nothing, so only something on the
`proxy` network reaches it. That network is `external` and deliberately **not** `internal`: an
internal network has no egress, and this container's whole job is calling your Nextcloud.

### 9.6 ⚠️ Two bugs the hosted path had, both found by running it

1. ⛔ **The container's healthcheck was broken in both directions at once.** With
   `INKSTONE_ALLOWED_HOSTS` set to a public name, the probe's own `Host: 127.0.0.1` was refused
   **403** — so the container would have reported unhealthy forever. And `/api/health` sits behind
   the auth gate, so a probe treating its `401` as "fine" would have reported healthy *whatever the
   app was doing*. One route choice, two opposite failures. Loopback Hosts are now always accepted
   — a rebinding attack arrives claiming the attacker's hostname, never `127.0.0.1` — and
   `/healthz` is a separate open route that names nothing about the account.

2. ⛔ **Every sign-in failed in real Chrome**, and the cause was the sign-in page's own
   `<meta name="referrer" content="no-referrer">`. Chrome serialises `Origin` as **null** for a
   form-submission navigation under that policy, so the POST arrived with an opaque origin and the
   server's cross-origin check refused it — with an error blaming the caller.

   Worth recording for how it was found. The in-app browser pane failed first, and the obvious
   reading was *"the pane sandboxes the page"* — the instrument rather than the feature, which is
   this repo's single most repeated misdiagnosis. **Real Chrome failed identically**, and that is
   what turned an artefact into a bug. The fix is `same-origin`, which keeps the entire privacy
   intent, and **not** a weaker Origin check: `Origin: null` from a sandboxed iframe is precisely
   what that check exists for. `test_web.py` asserts both halves so neither can be tidied into the
   other.

---

## 10. Letting other people use it — status, and the things that decide whether you should

**Status: the security core is built and tested. The multi-user server itself is not wired up yet.**
`tools/web/{safefetch,accounts,vault,tenants,cachekey}.py` exist with 83 checks over them; the
routes, the sign-in-as-anyone flow and the account UI do not. Nothing below is running.

This section exists because the honest answer to *"host it online without security issues"* is that
three of the risks are **not solvable in the application**, and you should decide about them before
the rest gets built.

### 10.1 ⛔ The credential you would be holding is not read-only

A Nextcloud app password is **unscoped**: full access to the account, including writing and
deleting files. Every line of `tools/web` is a reader and cannot mutate anything — but that is a
property of *this code*, not of the credential it stores.

**If this server is compromised, the attacker has write access to every signed-in user's
Nextcloud.** Not read — write. Nothing in the design changes that; only Nextcloud growing scoped
tokens could. Encryption at rest protects a stolen *disk*, and does nothing for a compromised
*process*, which by definition holds the key.

That is the sentence to weigh against the convenience of hosting it for other people.

### 10.2 ⛔ SSRF: three risks the filter cannot close

Accepting any Nextcloud URL means this server makes requests to addresses strangers choose.
`safefetch.py` is a careful filter — 24 hostile addresses blocked in tests, IDNA canonicalisation
first, DNS pinning, per-hop redirect re-validation, credentials stripped on every hop. It is
**defence in depth, and it is not the defence**:

* **NAT64 with an operator-specific prefix.** `2600:1f14:…::a9fe:a9fe` is a globally-routable
  address that a provider's gateway translates to `169.254.169.254`. It is indistinguishable from
  a legitimate address by inspection. No blocklist closes this.
* **Internal services on globally-routable addresses** — peer VMs with public IPs, a university
  /16, a colo estate. IP classification cannot see a trust boundary.
* **A timing oracle.** Even with one opaque error for every failure, connect latency separates
  "refused" from "filtered" from "something answered".

⭐ **The control that actually closes the first two is at the network, not in Python.** Run the
container in an egress-restricted network with no route to link-local, no route to your VPC, and no
route to the metadata address. Then the filter is a second lock rather than the only one.

If you are not going to do that, the honest recommendation is the allowlist option: name the
Nextcloud servers you will accept, and the whole class disappears.

### 10.3 ⚠️ What you would be taking on legally

Not advice, and worth knowing before rather than after. Hosting this for other people in the UK
means storing their credentials and cached copies of **classroom recordings containing identifiable
children**:

* Most UK organisations processing personal data must **register with the ICO** and pay an annual
  fee (£52 at tier 1), with a penalty of up to £4,000 for not doing so.
* A notifiable breach must reach the ICO **within 72 hours**. Encrypted data whose keys were not
  compromised may not trigger it — which is part of what §11.1's encryption buys.
* **The ICO Children's code** applies to services *likely to be accessed by children*, even when
  they are not the intended audience, and explicitly reaches edtech used in or by a school. It
  imports fifteen standards including high-privacy defaults and data minimisation.
* Users get a **right to erasure** (extending to backups — "beyond use" is accepted where immediate
  overwriting is impossible, provided you say so) and a **right to portability** in a machine-
  readable format, within one month.

The lower-risk shape remains: give people the container and let them run it. They get the same
tool, you hold nothing, and none of this section applies.

### 10.4 What is built

| Piece | What it does | Tests |
|---|---|---|
| `safefetch.py` | SSRF-hardened outbound HTTPS: explicit CIDR blocklist, IDNA canonicalisation, resolve-once-and-pin, per-hop redirect validation, credential stripping, one wall-clock deadline | 48 |
| `accounts.py` | Accounts keyed on **(server, uid)**, sessions and MCP tokens stored as HMAC digests, per-account cache directories | 35 (shared) |
| `vault.py` | AES-GCM at rest, key from a Docker secret, **account id as associated data** so a ciphertext cannot be moved between rows, explicit key ids for rotation | ” |
| `cachekey.py` | Length-prefixed, injective cache keys | ” |
| `tenants.py` | One `Library` per account, bounded LRU, per-account quota | — |
| `login.py` | Login Flow v2, now validating **every** server-supplied URL | 18 |

### 10.5 ⛔ Two real bugs this turn found in code that was already written

1. **The cache keys could serve one user another's file.** Both caches hashed a
   delimiter-joined string, which is not an injective encoding — `("a|b", "c")` and
   `("a", "b|c")` produce one key, and Nextcloud permits `|` in a filename. Worse, the media
   cache keyed on the **server** URL rather than the account, so two students on one school
   Nextcloud with the same relative path and the same file size collided *by construction*. A
   wrong-file bug for one user; a cross-account leak for two. Fixed in `cachekey.py`.

2. ⛔ **Second-order SSRF through the login flow.** Login Flow v2 hands back three more URLs —
   `poll.endpoint`, `login`, and the `server` field that becomes the account's permanent base URL.
   A user supplies `https://evil.tld/` (real domain, valid certificate, public address — passes
   every check), and their server answers with `poll.endpoint` pointing at cloud metadata. It is
   not a redirect, so redirect defences never fire; it is not the URL the user typed, so input
   validation never fires. Setting `server` to an internal host is worse still: **every WebDAV
   request for the life of that account** goes there carrying the credential.

   The code I wrote last turn had this, and the comment above it called taking the server's word
   for its own URL a *feature* — correct for your own Nextcloud, and a durable authenticated
   internal client for anybody else's.

### 10.6 What is not built

The routes and UI: signing in as anyone, the account menu, the token page, "sign out everywhere",
delete-my-account, quota enforcement at the point bytes land, and per-account rate limiting. Also
`requirements.txt` with hash pins for `cryptography`, and the egress-restricted network in
`compose.yaml`.

---

## 11. Ideas deliberately not built


- **Writing anything.** §3.
- **Highlighting a word on the page.** `text/<pageId>.json` carries a page-space box per word, and
  `notepdf.py` deliberately drops them: nothing draws on the page, so carrying them would be weight
  with no reader. The moment something wants to highlight a search hit *on the scan*, that is where
  the geometry already is.
- **A modern-era MCP server.** The current specification is `2026-07-28`, which replaces
  `initialize` with `server/discover`. The Claude Code build here (2.1.218) supports up to
  `2025-11-25` and **has no fall-forward**, so a modern-only server fails permanently. The legacy
  lifecycle is what a modern client must still accept, so it is what this implements.
- **Any audio player at all.** Once a lesson is transcribed its `.opus` is in the Nextcloud trash
  by design (M12 §8), so the steady state has nothing to play. The reader had a player for the
  transient window before transcription; it is gone, along with the route that served it. The
  server still *knows* audio exists — it reads the Ogg container's granule for a truthful
  duration on an untranscribed recording — it just never hands the bytes to a browser.

