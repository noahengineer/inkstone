/* Inkstone reader.
 *
 * One page, three views (a lecture, a note, a set of search results) and a library down the side.
 * No framework and no build step: this is a local tool that has to keep working after nobody has
 * touched it for a year, and a `package.json` is a thing that rots.
 *
 * The one piece worth reading properly is `drawRibbon`. Everything else is plumbing.
 */

'use strict';

const TOKEN = new URLSearchParams(location.search).get('token');

/* ~164 words a minute while somebody is actually speaking — measured over the real 48-minute
 * lesson (docs/M12-AUDIO.md §15.7), not a textbook figure. Used only to bound how long a
 * transcript paragraph is drawn as speech on the ribbon. */
const MS_PER_WORD = 366;

const el = (id) => document.getElementById(id);
const dom = {
  statusDot: el('status-dot'), statusText: el('status-text'),
  libraryList: el('library-list'), libraryFoot: el('library-foot'),
  welcome: el('welcome'), lecture: el('lecture'), noteView: el('note-view'), results: el('results'),
  title: el('lecture-title'), meta: el('lecture-meta'), actions: el('lecture-actions'),
  ribbon: el('ribbon'), axis: el('ribbon-axis'), hovered: el('hovered'),
  clock: el('clock'), duration: el('duration'),
  transcript: el('transcript'), transcriptNote: el('transcript-note'),
  panePage: el('pane-page'), pageFrame: el('page-frame'), pageOpen: el('page-open'),
  noteTitle: el('note-title'), noteMeta: el('note-meta'), noteFrame: el('note-frame'),
  noteOpen: el('note-open'), handwriting: el('handwriting'), paneText: el('pane-text'),
  resultsTitle: el('results-title'), resultsMeta: el('results-meta'), resultsList: el('results-list'),
  banner: el('banner'), searchForm: el('search-form'), searchInput: el('search-input'),
  refresh: el('refresh'),
  connect: el('connect'), connectForm: el('connect-form'), connectUrl: el('connect-url'),
  connectGo: el('connect-go'), connectWaiting: el('connect-waiting'), connectHref: el('connect-href'),
  connectCountdown: el('connect-countdown'), connectError: el('connect-error'),
  connectLead: el('connect-lead'),
};

const state = {
  scan: null,
  filter: 'all',
  recording: null,   // the loaded recording detail
  positionMs: 0,
  selectedKey: null,
};

/* ------------------------------------------------------------------ util */

function api(path, params, options) {
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null) url.searchParams.set(k, v);
  }
  if (TOKEN) url.searchParams.set('token', TOKEN);
  const init = { headers: { 'Accept': 'application/json' }, ...(options || {}) };
  if (init.body) init.headers['Content-Type'] = 'application/json';
  return fetch(url, init).then(async (response) => {
    const payload = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
    if (!response.ok) throw Object.assign(new Error(payload.error || 'Request failed'), payload);
    return payload;
  });
}

function media(path, params) {
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(params || {})) url.searchParams.set(k, v);
  if (TOKEN) url.searchParams.set('token', TOKEN);
  return url.toString();
}

function clock(ms) {
  const total = Math.max(0, Math.floor((ms || 0) / 1000));
  const h = String(Math.floor(total / 3600)).padStart(2, '0');
  const m = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
  const s = String(total % 60).padStart(2, '0');
  return `${h}:${m}:${s}`;
}

function shortClock(ms) {
  const total = Math.max(0, Math.floor((ms || 0) / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

function bytes(n) {
  if (!n) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} kB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

/* An error stays until it is dismissed; an `info` clears itself.
 *
 * An error banner that cannot be got rid of covers the bottom of the page for the rest of the
 * session, and one that vanishes on a timer is a message the user may never have read. Dismissal
 * is the only combination that is neither. */
function clearBanner() {
  // ⚠️ Called whenever something that had failed succeeds. Without it the banner was written by
  // `banner()` and cleared only by the Dismiss button or an `info` timer — so "Nextcloud is
  // unreachable" stayed on screen after Refresh had visibly repopulated the library, and the page
  // contradicted itself until the user tidied it away by hand.
  if (!dom.banner.classList.contains('info')) dom.banner.hidden = true;
}

function banner(message, kind) {
  dom.banner.textContent = message;
  dom.banner.className = `banner ${kind || ''}`;
  dom.banner.hidden = false;
  if (kind === 'info') {
    setTimeout(() => { dom.banner.hidden = true; }, 6000);
  } else {
    const close = document.createElement('button');
    close.className = 'banner-close';
    close.type = 'button';
    close.textContent = 'Dismiss';
    close.addEventListener('click', () => { dom.banner.hidden = true; });
    dom.banner.append(close);
  }
}

function show(which) {
  for (const view of [dom.welcome, dom.lecture, dom.noteView, dom.results, dom.connect]) view.hidden = true;
  which.hidden = false;
}

/* --------------------------------------------------------------- library */

const STATES = ['transcribed', 'awaiting', 'partial'];
const STATE_LABEL = { transcribed: 'Transcribed', awaiting: 'Awaiting transcript', partial: 'Incomplete' };

function stateTag(recording) {
  const kind = enumOf(recording.state, STATES, 'partial');
  return `<span class="tag tag-${kind}">${STATE_LABEL[kind]}</span>`;
}

function renderLibrary() {
  const scan = state.scan;
  if (!scan) return;
  const parts = [];

  const lectures = scan.recordings;
  const notes = scan.notes;

  if (state.filter !== 'notes' && lectures.length) {
    const byFolder = new Map();
    for (const r of lectures) {
      if (!byFolder.has(r.folder)) byFolder.set(r.folder, []);
      byFolder.get(r.folder).push(r);
    }
    for (const [folder, items] of byFolder) {
      parts.push(`<div class="group-label">${escapeHtml(folder || 'Home')} · lectures</div>`);
      for (const r of items) {
        const key = `${r.documentId}/${r.recordingId}`;
        const active = state.selectedKey === key ? ' is-active' : '';
        /* ⛔ This was `~N min` from `estimatedMinutes`, which is bytes ÷ bitrate over the `.opus`
         * — and `tools/m12` trashes that file once the transcript is verified. So every lesson
         * you can actually read showed no length at all, and a length appeared only for the ones
         * still awaiting transcription. A transcribed lesson now shows the clock `align`
         * measured off the container; an untranscribed one keeps the `~` to say it is a guess. */
        const mins = r.durationMs > 0
          ? clock(r.durationMs)
          : r.estimatedMinutes ? `~${num(r.estimatedMinutes, '')} min` : '';
        parts.push(
          `<button class="item${active}" data-kind="lecture" data-document="${escapeAttr(r.documentId)}" data-recording="${escapeAttr(r.recordingId)}">
             <span class="item-title">${escapeHtml(r.noteTitle || r.documentId)}</span>
             <span class="item-sub">${stateTag(r)}<span>${mins}</span></span>
           </button>`);
      }
    }
  }

  if (state.filter !== 'lectures' && notes.length) {
    const byFolder = new Map();
    for (const n of notes) {
      if (!byFolder.has(n.folder)) byFolder.set(n.folder, []);
      byFolder.get(n.folder).push(n);
    }
    for (const [folder, items] of byFolder) {
      parts.push(`<div class="group-label">${escapeHtml(folder || 'Home')} · notes</div>`);
      for (const n of items) {
        const active = state.selectedKey === n.path ? ' is-active' : '';
        const pages = n.pages ? `${num(n.pages, 0)} pages` : bytes(Number(n.size) || 0);
        parts.push(
          `<button class="item${active}" data-kind="note" data-path="${escapeAttr(n.path)}">
             <span class="item-title">${escapeHtml(n.title)}</span>
             <span class="item-sub"><span>${pages}</span></span>
           </button>`);
      }
    }
  }

  if (!parts.length) {
    // ⚠️ Say which of the two reasons it is. The message used to blame the walk depth in every
    // case, including the common one where the user had simply switched to a filter with nothing
    // in it — sending somebody to reorganise their Nextcloud when the fix was one click away.
    const filtered = state.filter !== 'all' && (lectures.length || notes.length);
    parts.push(filtered
      ? `<p class="empty">Nothing under this filter. There ${lectures.length === 1 ? 'is' : 'are'}
         ${lectures.length} lecture${lectures.length === 1 ? '' : 's'} and ${notes.length}
         note${notes.length === 1 ? '' : 's'} — try <strong>All</strong>.</p>`
      : `<p class="empty">Nothing found here yet. If your notes live in a subfolder deeper
         than three levels, the walk stops before it — that bound is deliberate.</p>`);
  }

  // ⚠️ Remember what had focus before replacing every button in this list.
  //
  // `renderLibrary` runs on the first line of `openLecture`/`openNote`, so activating an item by
  // keyboard destroyed the very element that had focus. Focus fell back to <body>, and the next
  // Tab restarted from the top of the document — a keyboard user was thrown back to the start of
  // the page every time they opened a lesson.
  const focusedKey = document.activeElement && document.activeElement.closest
    ? (document.activeElement.closest('.item') || {}).dataset : null;

  dom.libraryList.innerHTML = parts.join('');

  if (focusedKey) {
    const again = [...dom.libraryList.querySelectorAll('.item')].find((item) =>
      item.dataset.path === focusedKey.path &&
      item.dataset.document === focusedKey.document &&
      item.dataset.recording === focusedKey.recording);
    if (again) again.focus();
  }

  dom.libraryFoot.innerHTML =
    `<span class="micro">${lectures.length} lecture${lectures.length === 1 ? '' : 's'} ·
     ${notes.length} note${notes.length === 1 ? '' : 's'} · ${scan.walked} folders in ${scan.tookMs} ms</span>`;
}

/* Everything interpolated into `innerHTML` below goes through one of these three.
 *
 * The content is the user's own — their note titles, their lessons — but it is not therefore
 * trustworthy: a transcript is a third-party recogniser's rendering of classroom audio, a note
 * title can arrive from a shared folder, and a filename is whatever somebody typed. Escaping at
 * every site is cheap; deciding per site which inputs are "ours" is how the one that is not
 * gets missed.
 *
 * `num` and `enumOf` exist so that values which are *supposed* to be a number or one of a fixed
 * set cannot become markup merely because a future server version changed a field's type.
 */
function escapeHtml(s) {
  return String(s === undefined || s === null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function escapeAttr(s) { return escapeHtml(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
function num(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? String(n) : (fallback === undefined ? '?' : fallback);
}
function enumOf(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback;
}

/* -------------------------------------------------------------- the ribbon
 *
 * The signature of this page, and the one place it says something the files do not say on their
 * own: two tracks over one time axis.
 *
 *   ink    — how much was being written, bucketed. From `<recordingId>.timeline`.
 *   voice  — when somebody was talking, and who. From the transcript's own timestamps.
 *
 * Reading them together is the whole of docs/M12-AUDIO.md §1: the page ends up holding `58.5`,
 * and the twenty minutes of explanation around it are what this recovers. A stretch with a voice
 * band and no ink is the part of the lesson the page cannot hold — which is exactly where a
 * reader should be looking.
 */

/* Wipe the ribbon. A canvas keeps what was painted on it until something paints over it, so
 * every path that stops showing a lecture has to say so explicitly — `innerHTML = ''` on the
 * elements around it does nothing here. */
function clearRibbon() {
  const canvas = dom.ribbon;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.floor(canvas.clientWidth * ratio));
  canvas.height = Math.max(1, Math.floor(canvas.clientHeight * ratio));
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
}

function drawRibbon() {
  const canvas = dom.ribbon;
  const recording = state.recording;
  if (!recording) { clearRibbon(); return; }

  // A recording with no transcript and no timeline has nothing to say how long it is, and every
  // coordinate below divides by the duration. Draw the reason instead of a NaN-filled canvas.
  if (!(recording.durationMs > 0)) {
    const ctx0 = canvas.getContext('2d');
    const ratio0 = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.floor(canvas.clientWidth * ratio0));
    canvas.height = Math.max(1, Math.floor(canvas.clientHeight * ratio0));
    ctx0.setTransform(ratio0, 0, 0, ratio0, 0, 0);
    ctx0.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
    ctx0.fillStyle = '#A1A1AA';
    ctx0.font = '12px system-ui, sans-serif';
    ctx0.fillText('This recording has no timeline and no transcript, so its length is unknown.',
      10, canvas.clientHeight / 2);
    return;
  }

  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  canvas.width = Math.max(1, Math.floor(width * ratio));
  canvas.height = Math.max(1, Math.floor(height * ratio));
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const duration = recording.durationMs || 1;
  const x = (ms) => (ms / duration) * width;

  const INK_TOP = 8, INK_H = 56;
  const VOICE_TOP = 78, VOICE_H = 30;
  const inkBase = INK_TOP + INK_H;

  // --- quiet stretches, behind everything: the shape of the lesson's working time.
  const quiet = (recording.timeline && recording.timeline.quiet) || [];
  ctx.fillStyle = 'rgba(63, 63, 70, 0.16)';
  for (const q of quiet) ctx.fillRect(x(q.fromMs), INK_TOP, Math.max(1, x(q.toMs) - x(q.fromMs)), INK_H);

  // --- ink track
  const activity = (recording.timeline && recording.timeline.activity) || [];
  if (activity.length) {
    const peak = Math.max(1, ...activity);
    const bw = width / activity.length;
    ctx.fillStyle = '#E4E4E7';
    for (let i = 0; i < activity.length; i++) {
      if (!activity[i]) continue;
      // sqrt, not linear: one dense block of 208 strokes would otherwise flatten every other
      // block to nothing, and the measured lesson is exactly that bursty (§15.6).
      const h = Math.max(1.5, Math.sqrt(activity[i] / peak) * INK_H);
      ctx.fillRect(i * bw, inkBase - h, Math.max(1, bw - 0.6), h);
    }
  } else {
    // ⚠️ #3F3F46 here was 1.73:1 against the composited panel — a message about missing data,
    // drawn so faintly that missing data and a missing *message* looked the same. Canvas text
    // gets no help from the stylesheet, so the contrast has to be chosen at the draw call.
    ctx.fillStyle = '#A1A1AA';
    ctx.font = '12px system-ui, sans-serif';
    ctx.fillText('No timeline for this recording, so nothing is known about when it was written.',
      10, inkBase - 20);
  }
  ctx.strokeStyle = 'rgba(63, 63, 70, 0.55)';
  ctx.beginPath(); ctx.moveTo(0, inkBase + 0.5); ctx.lineTo(width, inkBase + 0.5); ctx.stroke();

  // --- voice track
  const segments = (recording.transcript && recording.transcript.segments) || [];
  const speech = segments.filter((s) => s.kind === 'speech');
  const speakers = (recording.transcript && recording.transcript.speakers) || [];
  const teacher = recording.transcript && recording.transcript.likelyTeacher;
  ctx.fillStyle = 'rgba(63, 63, 70, 0.20)';
  ctx.fillRect(0, VOICE_TOP, width, VOICE_H);
  for (let i = 0; i < speech.length; i++) {
    const from = speech[i].at_ms;
    // ⚠️ A paragraph's band runs until the next one starts **or until it plausibly finished**,
    // whichever is sooner.
    //
    // A transcript records when a paragraph *began* and nothing about when it ended, so running
    // each band to the next start painted every silence as continuous speech — and the last
    // paragraph's band ran to the end of the recording, so a lesson that finished with ten quiet
    // minutes showed ten minutes of talking. The measured lesson has speech over only 39% of its
    // length (M12 §15.7), so most of that track was wrong.
    //
    // The estimate is the same measurement's other half: ~164 words per minute *while speaking*,
    // so ~366 ms a word. It is an estimate and is allowed to be: it can only ever shorten a band
    // that would otherwise have been asserted at full length on no evidence at all.
    const words = String(speech[i].text || '').split(/\s+/).filter(Boolean).length;
    const spoken = Math.max(800, words * MS_PER_WORD);
    const next = i + 1 < speech.length ? speech[i + 1].at_ms : duration;
    const w = Math.max(1, x(Math.min(next, from + spoken)) - x(from));
    const isTeacher = speakers.length > 1 && speech[i].speaker === teacher;
    // Two speakers get two heights as well as two tints, so the track stays legible to anyone
    // who cannot separate the hues.
    const h = isTeacher || speakers.length < 2 ? VOICE_H : VOICE_H * 0.55;
    ctx.fillStyle = isTeacher || speakers.length < 2 ? 'rgba(56, 189, 248, 0.72)' : 'rgba(148, 163, 184, 0.55)';
    ctx.fillRect(x(from), VOICE_TOP + (VOICE_H - h), w, h);
  }

  // --- dead runs punched out of the voice track
  ctx.fillStyle = '#09090B';
  for (const s of segments) {
    if (s.kind !== 'dead') continue;
    ctx.fillRect(x(s.at_ms), VOICE_TOP, Math.max(1, x(s.to_ms) - x(s.at_ms)), VOICE_H);
  }

  // --- microphone gaps: audio that is missing, not silent. Loud on purpose.
  const gaps = (recording.timeline && recording.timeline.gaps) || [];
  for (const g of gaps) {
    const gx = x(g.fromMs), gw = Math.max(2, x(g.toMs) - x(g.fromMs));
    ctx.fillStyle = 'rgba(239, 68, 68, 0.30)';
    ctx.fillRect(gx, INK_TOP, gw, VOICE_TOP + VOICE_H - INK_TOP);
    ctx.strokeStyle = '#EF4444';
    ctx.setLineDash([3, 3]);
    ctx.beginPath(); ctx.moveTo(gx, INK_TOP); ctx.lineTo(gx, VOICE_TOP + VOICE_H); ctx.stroke();
    ctx.setLineDash([]);
  }

  // --- page turns
  const spans = (recording.timeline && recording.timeline.pageSpans) || [];
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.85)';
  ctx.fillStyle = 'rgba(56, 189, 248, 0.85)';
  ctx.font = '9px ui-monospace, monospace';
  const numbers = recording.pageNumbers || {};
  for (const span of spans) {
    const px = x(span.firstMs);
    ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, INK_TOP); ctx.stroke();
    // The PDF page where it is knowable, otherwise the order this page was first written in.
    // Those differ whenever a page is returned to, and the PDF number is the one you can turn to.
    ctx.fillText(String(numbers[span.page] || span.index + 1), px + 3, 8);
  }

  // --- playhead
  const px = x(state.positionMs);
  ctx.strokeStyle = '#E4E4E7';
  ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, VOICE_TOP + VOICE_H + 4); ctx.stroke();
  ctx.lineWidth = 1;
  ctx.fillStyle = '#E4E4E7';
  ctx.beginPath(); ctx.arc(px, VOICE_TOP + VOICE_H + 6, 3, 0, Math.PI * 2); ctx.fill();
}

function ribbonSeek(event) {
  // The ribbon is on screen from the moment `show(dom.lecture)` runs, which is before the fetch
  // that fills `state.recording` returns. A click in that window used to throw on a null.
  if (!state.recording) return;
  const rect = dom.ribbon.getBoundingClientRect();
  const fraction = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
  seek(fraction * (state.recording.durationMs || 0));
}

/* -------------------------------------------------------------- transcript */

function renderTranscript() {
  const recording = state.recording;
  const transcript = recording.transcript;
  if (!transcript) {
    /* ⚠️ Say what is true and what happens next, without implying the reader can press
     * anything. The watcher runs beside this tool rather than inside it — it holds an AssemblyAI
     * key and it WRITES to Nextcloud, which is the one thing this package refuses to be able to do
     * (`source.py` exposes no PUT). So a button here would be a lie about which process is which.
     * What was wrong with the old wording is that it read as an instruction to go and run a
     * PowerShell script, which is not something a person on the hosted reader can do at all. */
    dom.transcript.innerHTML = `<p class="empty">
      <strong>Not transcribed yet.</strong> The recording and its timeline are in Nextcloud, and
      the ribbon below already shows when this lesson was written. The transcript appears here
      once the watcher has run — it is a separate background job, and nothing needs doing on
      this page.</p>`;
    return;
  }
  if (transcript.empty) {
    dom.transcript.innerHTML = `<p class="empty">Nothing was transcribed. The recording may be silent.</p>`;
    return;
  }

  const teacher = transcript.likelyTeacher;
  const parts = [];

  /* ⭐ Page turns come from the TIMELINE where it has them, and from the transcript otherwise.
   *
   * `align.render` only writes page headings when the timeline it was given had a page column, so
   * a lesson transcribed before the app recorded page ids has none — while the timeline sitting
   * beside it, re-read now, may well have them. Reading the higher-resolution source directly also
   * fixes the second-resolution rounding: the markdown says 00:04:11, the timeline says 251,447 ms.
   *
   * Order matters, so these are merged into the speech by time rather than rendered as a block. */
  const spans = (recording.timeline && recording.timeline.pageSpans) || [];
  const turns = spans.length
    ? spans.map((s) => ({ at_ms: s.firstMs, page: s.page }))
    : transcript.segments.filter((s) => s.kind === 'page').map((s) => ({ at_ms: s.at_ms, page: s.page }));
  let nextTurn = 0;

  const pageTurn = (turn) => {
    const number = (recording.pageNumbers || {})[turn.page];
    const label = number ? `page ${num(number)}` : `page ${escapeHtml(turn.page)}`;
    const jump = number && recording.notePath
      ? ` <button class="seg-jump" data-page="${num(number)}">Show it</button>` : '';
    return `<div class="seg-pageturn"><span>${label} · writing starts ${clock(turn.at_ms)}</span>${jump}</div>`;
  };
  for (const g of (recording.timeline && recording.timeline.gaps) || []) {
    parts.push(`<div class="seg-gap">No audio between ${clock(g.fromMs)} and ${clock(g.toMs)} —
      something else took the microphone. That speech is missing, not silent.</div>`);
  }
  for (let i = 0; i < transcript.segments.length; i++) {
    const s = transcript.segments[i];
    while (nextTurn < turns.length && turns[nextTurn].at_ms <= s.at_ms) {
      parts.push(pageTurn(turns[nextTurn++]));
    }
    if (s.kind === 'speech') {
      const who = s.speaker ? `<span class="seg-who">${escapeHtml(s.speaker)}${s.speaker === teacher ? ' · likely teacher' : ''}</span>` : '';
      parts.push(
        `<button class="seg${s.speaker === teacher ? ' speaker-teacher' : ''}" data-at="${num(s.at_ms, 0)}" data-index="${num(i, 0)}">
           <span class="seg-head"><span class="seg-at">${clock(s.at_ms)}</span>${who}</span>
           <span class="seg-text">${escapeHtml(s.text)}</span>
         </button>`);
    } else if (s.kind === 'dead') {
      parts.push(`<div class="seg-dead"><span>${clock(s.at_ms)} – ${clock(s.to_ms)} · nothing said, nothing written</span></div>`);
    } else if (s.kind === 'page') {
      // Already emitted by the merge above — `turns` is built from these when the timeline has
      // no pages of its own, so rendering here too would double every marker.
    } else if (s.text) {
      parts.push(`<div class="seg-dead"><span>${escapeHtml(s.text)}</span></div>`);
    }
  }
  while (nextTurn < turns.length) parts.push(pageTurn(turns[nextTurn++]));

  dom.transcript.innerHTML = parts.join('');
  // The previous lecture's highlighted node is detached now. Holding it would make the first
  // `highlightNow` compare against a node that is not in the document, so the new lecture's first
  // segment would not scroll into view.
  lastActive = null;
}

let lastActive = null;

/* Highlight the line the playhead is on, and show it.
 *
 * ⛔ This took a `follow` flag distinguishing the two ways the playhead could move: *playing*
 * (scroll only when the line changes, so a 48-minute lesson reads by itself) and *seeking*
 * (scroll every time, because somebody who just clicked 23 minutes in is asking to be shown that
 * moment). With the audio gone there is no clock running, so **the playhead moves only when the
 * user moves it** — every call is a seek, `follow` was always true, and both the smooth-scroll
 * branch and the `prefers-reduced-motion` fix behind it were unreachable code that read as a
 * considered accessibility feature.
 *
 * An instant scroll is the correct answer for a deliberate jump, and it is also the one reduced
 * motion asks for — so the setting has nothing left to suppress here rather than being ignored.
 */
function highlightNow() {
  const nodes = dom.transcript.querySelectorAll('.seg');
  let current = null;
  for (const node of nodes) {
    if (Number(node.dataset.at) <= state.positionMs) current = node;
    else break;
  }
  const changed = current !== lastActive;
  if (changed) {
    if (lastActive) lastActive.classList.remove('is-now');
    if (current) current.classList.add('is-now');
    lastActive = current;
  }
  if (current) centreInTranscript(current, 'auto');
}

/* ⚠️ Not `scrollIntoView`. It scrolls **every** scrollable ancestor, so following the transcript
 * also scrolled the stage — carrying the ribbon and the transport off the top of the window on
 * each seek, which is the one thing that must stay put while you are scrubbing. Scroll the
 * transcript's own box and nothing else. */
function centreInTranscript(node, behavior) {
  const box = dom.transcript;
  const boxRect = box.getBoundingClientRect();
  const nodeRect = node.getBoundingClientRect();
  const offset = (nodeRect.top - boxRect.top) - (box.clientHeight - nodeRect.height) / 2;
  box.scrollTo({ top: box.scrollTop + offset, behavior });
}

/* ---------------------------------------------------------------- lecture */

async function openLecture(documentId, recordingId, atMs) {
  state.selectedKey = `${documentId}/${recordingId}`;
  renderLibrary();
  show(dom.lecture);
  dom.title.textContent = 'Loading…';
  dom.meta.textContent = '';
  try {
    state.recording = await api('/api/recording', { documentId, recordingId });
  } catch (error) {
    // ⚠️ Clear the view. The heading, the ribbon, the transcript and the PDF pane still held the
    // PREVIOUS lecture, and the sidebar had already marked this one active — so a failed open
    // showed one lesson's transcript under another's name, captioned "Loading…", with no error
    // anywhere on the page except a banner that could be dismissed. Every part of that is a
    // convincing lie.
    state.recording = null;
    dom.title.textContent = 'Could not open this lesson';
    dom.meta.textContent = error.message;
    dom.transcript.innerHTML = '';
    dom.actions.innerHTML = '';
    dom.axis.innerHTML = '';
    dom.panePage.hidden = true;
    dom.pageFrame.removeAttribute('src');
    /* ⚠️ …and the two things that are drawn rather than written, which the first version of this
     * cleanup missed for exactly that reason. The ribbon is a canvas, so clearing `innerHTML`
     * everywhere around it left the PREVIOUS lesson's ink and voice tracks painted underneath the
     * words "Could not open this lesson" — the most confident-looking part of the page still
     * showing another lecture. The clock and the duration are the same: a transport reading
     * `00:11:24 / 48:49` describes a lesson that failed to load. */
    clearRibbon();
    state.positionMs = 0;
    dom.clock.textContent = clock(0);
    dom.duration.textContent = '';
    dom.hovered.textContent = '';
    lastActive = null;
    banner(error.message);
    return;
  }
  const r = state.recording;
  const t = r.transcript;

  dom.title.textContent = r.noteTitle || r.documentId;
  const bits = [];
  if (r.durationMs) bits.push(clock(r.durationMs));
  if (t && t.recorded) bits.push(t.recorded);
  if (t && t.words) bits.push(`${t.words.toLocaleString()} words`);
  if (r.timeline) bits.push(`${r.timeline.strokes.toLocaleString()} strokes`);
  if (r.timeline && r.timeline.pages) bits.push(`${r.timeline.pages} pages`);
  if (t && t.recogniser) bits.push(t.recogniser);
  dom.meta.textContent = bits.join('  ·  ');

  dom.transcriptNote.textContent = t && t.unanchored
    ? 'not anchored to pages — this recording predates the page column'
    : '';

  dom.duration.textContent = `/ ${clock(r.durationMs)}`;

  dom.actions.innerHTML = t
    ? `<a class="chip chip-quiet" href="${media('/media/transcript', { documentId, recordingId })}" download>Download transcript</a>`
    : '';

  if (r.notePath) {
    dom.panePage.hidden = false;
    dom.pageFrame.src = media('/media/note', { path: r.notePath }) + '#view=FitH';
    dom.pageOpen.onclick = () => window.open(media('/media/note', { path: r.notePath }), '_blank', 'noopener');
  } else {
    dom.panePage.hidden = true;
    dom.pageFrame.removeAttribute('src');
  }

  renderTranscript();
  const axis = [];
  for (let i = 0; i <= 4; i++) axis.push(`<span>${shortClock((r.durationMs || 0) * i / 4)}</span>`);
  dom.axis.innerHTML = axis.join('');

  seek(atMs || 0);
  drawRibbon();
}

/* Move to a moment in the lesson.
 *
 * ⭐ There is no audio to move, and that is the design rather than a limitation. `tools/m12` trashes
 * the recording once its transcript is written and **verified present on the server**
 * (`watch.py:206`), so a lesson in the steady state is a transcript and a timeline. This reader
 * used to carry a media element, a Play chip, a byte-range route and a 12 MB cache for a file that
 * exists only in the gap between recording and transcription — a whole transport built for a
 * transient state.
 *
 * `state.positionMs` is a position in the **lesson**. The ribbon's playhead, the clock and the
 * highlighted transcript line all follow it, which is what dragging the ribbon was always for. */
function seek(ms) {
  state.positionMs = Math.max(0, Math.min(ms, state.recording ? state.recording.durationMs : 0));
  dom.clock.textContent = clock(state.positionMs);
  /* The ribbon is `role="slider"` (index.html), so a move has to be announced or a screen-reader
   * user gets a control that reports the same value however far they drive it. `aria-valuetext`
   * is what makes that announcement a clock instead of "2 929 000". */
  dom.ribbon.setAttribute('aria-valuemax', String(state.recording ? state.recording.durationMs : 0));
  dom.ribbon.setAttribute('aria-valuenow', String(state.positionMs));
  dom.ribbon.setAttribute('aria-valuetext', clock(state.positionMs));
  highlightNow();
  drawRibbon();
}

/* ------------------------------------------------------------------- note */

async function openNote(path) {
  state.selectedKey = path;
  renderLibrary();
  show(dom.noteView);
  dom.noteTitle.textContent = 'Opening…';
  dom.noteMeta.textContent = 'Downloading the PDF and reading the bundle inside it.';
  dom.noteFrame.src = media('/media/note', { path }) + '#view=FitH';
  dom.noteOpen.onclick = () => window.open(media('/media/note', { path }), '_blank', 'noopener');
  let detail;
  try {
    detail = await api('/api/note', { path });
  } catch (error) {
    dom.noteTitle.textContent = 'Could not open this note';
    dom.noteMeta.textContent = error.message;
    dom.handwriting.innerHTML = '';
    dom.noteFrame.removeAttribute('src');
    banner(error.message);
    return;
  }
  dom.noteTitle.textContent = detail.title || detail.name.replace(/\.pdf$/i, '');
  const bits = [];
  if (detail.isInkstone) {
    bits.push(`${detail.pages} pages`);
    if (detail.imported) bits.push('imported PDF — the original is kept verbatim');
    bits.push(detail.bundleVerified ? 'bundle verified' : (detail.bundlePresent ? 'bundle unverified' : 'no bundle'));
  } else {
    bits.push('not an Inkstone note — an ordinary PDF in the same folder');
  }
  bits.push(bytes(detail.size));
  dom.noteMeta.textContent = bits.join('  ·  ');

  const pages = (detail.pageList || []).filter((p) => p.text);
  if (pages.length) {
    dom.paneText.hidden = false;
    dom.handwriting.innerHTML = pages.map((p) =>
      `<div class="handwriting-page"><h4>Page ${num(p.number)} · ${num(p.lines, 0)} lines</h4><pre>${escapeHtml(p.text)}</pre></div>`
    ).join('');
  } else {
    dom.paneText.hidden = false;
    dom.handwriting.innerHTML = detail.isInkstone
      ? `<p class="empty">No recognised text in this note. Handwriting recognition runs on the tablet a few seconds after the pen stops, so a note written on an older build has none.</p>`
      : `<p class="empty">Nothing to read — this PDF was not written by Inkstone.</p>`;
  }
}

/* ----------------------------------------------------------------- search */

async function runSearch(query) {
  show(dom.results);
  dom.resultsTitle.textContent = `“${query}”`;
  dom.resultsMeta.textContent = 'Searching…';
  dom.resultsList.innerHTML = '';
  let found;
  try {
    found = await api('/api/search', { q: query });
  } catch (error) {
    banner(error.message);
    return;
  }
  const meta = [`${found.hits.length} hit${found.hits.length === 1 ? '' : 's'}`];
  if (found.notesUnindexed > 0) {
    // Saying which notes were *not* searched is the difference between "no hits" and
    // "nothing was looked at", which this project has been bitten by often enough to make it
    // a visible number rather than a footnote.
    meta.push(`${found.notesUnindexed} of ${found.notesTotal} notes not yet indexed — handwriting in those was not searched`);
  }
  dom.resultsMeta.textContent = meta.join('  ·  ');

  // ⚠️ And offer the fix. Reporting "not yet indexed" with no way to index was a route
  // (`/api/index`) that existed, worked, and had no caller on the page at all — reachable only
  // over MCP. Naming a shortfall the user cannot act on is worse than not naming it.
  dom.resultsMeta.append(indexButton(query, found.notesUnindexed));

  if (!found.hits.length) {
    dom.resultsList.innerHTML = `<p class="empty">Nothing matched.</p>`;
    return;
  }
  dom.resultsList.innerHTML = found.hits.map((hit) => {
    const where = hit.kind === 'transcript'
      ? `${escapeHtml(hit.noteTitle)} · said`
      : `${escapeHtml(hit.noteTitle)} · written, page ${num(hit.page)}`;
    const at = hit.kind === 'transcript' ? `<span class="result-at">${escapeHtml(hit.at)}${hit.speaker ? ' · ' + escapeHtml(hit.speaker) : ''}</span>` : '';
    const data = hit.kind === 'transcript'
      ? `data-kind="transcript" data-document="${escapeAttr(hit.documentId)}" data-recording="${escapeAttr(hit.recordingId)}" data-at="${num(hit.atMs, 0)}"`
      : `data-kind="note" data-path="${escapeAttr(hit.path)}"`;
    return `<button class="result" ${data}>
      <span class="result-head"><span class="result-where">${where}</span>${at}</span>
      <span class="result-text">${highlight(hit.text, query)}</span>
    </button>`;
  }).join('');
}

/* The control that makes handwriting searchable.
 *
 * Deliberately here rather than always on screen: indexing downloads every note PDF, so it is
 * offered at the one moment it is worth the bandwidth — when a search has just reported that it
 * could not look at some of them. Returns an empty fragment when there is nothing to index.
 */
function indexButton(query, unindexed) {
  if (!unindexed) return document.createDocumentFragment();
  const button = document.createElement('button');
  button.className = 'chip index-now';
  button.type = 'button';
  button.textContent = `Index ${unindexed} note${unindexed === 1 ? '' : 's'}`;
  button.title = 'Downloads each note PDF once so its handwriting can be searched.';
  button.addEventListener('click', async () => {
    button.disabled = true;
    button.textContent = 'Indexing…';
    try {
      const done = await api('/api/index');
      banner(
        `Indexed ${done.indexed} of ${done.notes} PDFs; ${done.inkstone} are Inkstone notes. ` +
        'Their handwriting is searchable now.' +
        (done.failed.length ? ` ${done.failed.length} could not be read.` : ''),
        'info',
      );
      state.scan = await api('/api/scan', { force: 1 });
      renderLibrary();
      runSearch(query);
    } catch (error) {
      banner(error.message);
      button.disabled = false;
      button.textContent = `Index ${unindexed} note${unindexed === 1 ? '' : 's'}`;
    }
  });
  return button;
}

/* ⚠️ Locate AND slice in the escaped string.
 *
 * The index came from searching the escaped haystack for the escaped needle, and the slice then
 * used the RAW needle's length — so a query containing `&`, `<` or `>` (which escape to five,
 * four and four characters) cut inside an HTML entity. Searching for `R&D` put the `<mark>` in
 * the middle of `&amp;`, emitting `R&a<mark>mp;D` — mangled text from a correct search, on a
 * query somebody would really type. */
function highlight(text, needle) {
  const safe = escapeHtml(text);
  const safeNeedle = escapeHtml(needle);
  const at = safe.toLowerCase().indexOf(safeNeedle.toLowerCase());
  if (at < 0) return safe;
  const end = at + safeNeedle.length;
  return safe.slice(0, at) + '<mark>' + safe.slice(at, end) + '</mark>' + safe.slice(end);
}

/* Point a PDF frame at a page **without re-downloading the file**.
 *
 * ⚠️ Reassigning `iframe.src` reloads it even when only the fragment differs — so "Show it" was
 * fetching the whole PDF again to move between pages, and cancelling the previous fetch mid-flight
 * to do it. On a large scan that is both slow and the thing that produced a `ConnectionAborted`
 * traceback on the server for every press.
 *
 * Same origin, so the frame's own `location` is reachable: setting `hash` moves the built-in
 * viewer with no request at all. `src` is only assigned when the document is genuinely different.
 */
function showPdfPage(frame, baseUrl, page) {
  const wanted = `${baseUrl}#page=${page}`;
  const current = frame.getAttribute('src') || '';
  if (current.split('#')[0] === baseUrl) {
    try {
      frame.contentWindow.location.hash = `#page=${page}`;
      // Keep the attribute in step so a later comparison still matches.
      frame.setAttribute('src', wanted);
      return;
    } catch (_) {
      // A frame that has not finished loading can refuse; fall through to a plain assignment.
    }
  }
  frame.src = wanted;
}

/* -------------------------------------------------- connect your Nextcloud
 *
 * ⛔ This is the fix for a real report: "does not give me an option to sign into nextcloud".
 * `--login` existed on the command line and the page said "mint an app password in Nextcloud →
 * Settings → Security" with nothing to press — a feature that worked and was unreachable, with
 * the error message pointing away from it.
 */

let connectPoll = null;

function showConnect(reason, knownSource) {
  show(dom.connect);
  dom.connectError.hidden = true;
  dom.connectWaiting.hidden = true;
  dom.connectGo.disabled = false;
  dom.connectGo.textContent = 'Sign in';
  if (reason) {
    dom.connectLead.innerHTML = escapeHtml(reason) +
      '<br><br>Type your server’s address below. Your browser opens, you sign in there as you ' +
      'normally would — second factor and all — and Nextcloud hands this reader a credential ' +
      'of its own. <strong>You are never asked for a password here.</strong>';
  }
  // ⚠️ Prefill from the value the SERVER supplied, not from `state.scan`.
  //
  // This is called out of the health check, which runs before the scan — so reading
  // `state.scan.source` left the box empty every time and made the user type an address the
  // server already had on disk. The health response carries it, so use that.
  const source = knownSource || (state.scan && state.scan.source) || '';
  if (!dom.connectUrl.value) {
    const guess = String(source).replace(/^nextcloud:/, '');
    if (guess.startsWith('http')) dom.connectUrl.value = guess;
  }
}

async function startConnect(url) {
  dom.connectError.hidden = true;
  dom.connectGo.disabled = true;
  dom.connectGo.textContent = 'Starting…';
  let begun;
  try {
    begun = await api('/api/nextcloud/begin', {}, { method: 'POST', body: JSON.stringify({ url }) });
  } catch (error) {
    dom.connectError.textContent = error.message;
    dom.connectError.hidden = false;
    dom.connectGo.disabled = false;
    dom.connectGo.textContent = 'Sign in';
    return;
  }

  dom.connectWaiting.hidden = false;
  dom.connectHref.href = begun.browserUrl;
  dom.connectGo.textContent = 'Waiting…';
  // The popup is opened from inside the submit handler's task, so it is not blocked.
  window.open(begun.browserUrl, '_blank', 'noopener');

  clearInterval(connectPoll);
  connectPoll = setInterval(async () => {
    let status;
    try {
      status = await api('/api/nextcloud/poll', { flowId: begun.flowId });
    } catch (error) {
      clearInterval(connectPoll);
      dom.connectError.textContent = error.message;
      dom.connectError.hidden = false;
      dom.connectGo.disabled = false;
      dom.connectGo.textContent = 'Sign in';
      return;
    }
    if (status.state === 'pending') {
      const left = Number(status.secondsLeft) || 0;
      dom.connectCountdown.textContent = `${Math.floor(left / 60)} min left`;
      return;
    }
    clearInterval(connectPoll);
    dom.connectWaiting.hidden = true;
    dom.connectGo.disabled = false;
    dom.connectGo.textContent = 'Sign in';
    if (status.state === 'done') {
      banner(status.message || 'Signed in.', 'info');
      // The server rebuilt its source, so a forced scan is the first thing that will succeed.
      await load(true);
      if (state.scan && !state.scan.error) show(dom.welcome);
    } else {
      dom.connectError.textContent = status.message || 'That did not work.';
      dom.connectError.hidden = false;
    }
  }, 2000);
}

dom.connectForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const url = dom.connectUrl.value.trim();
  if (url) startConnect(url);
});

/* ------------------------------------------------------------------ wiring */

dom.libraryList.addEventListener('click', (event) => {
  const item = event.target.closest('.item');
  if (!item) return;
  if (item.dataset.kind === 'lecture') openLecture(item.dataset.document, item.dataset.recording);
  else openNote(item.dataset.path);
});

dom.resultsList.addEventListener('click', (event) => {
  const hit = event.target.closest('.result');
  if (!hit) return;
  if (hit.dataset.kind === 'transcript') openLecture(hit.dataset.document, hit.dataset.recording, Number(hit.dataset.at));
  else openNote(hit.dataset.path);
});

dom.transcript.addEventListener('click', (event) => {
  const jump = event.target.closest('.seg-jump');
  if (jump) {
    // The browser's own PDF viewer honours `#page=N`, so turning the pane to the page a stretch of
    // the lesson was written on costs one fragment rather than a PDF library.
    const r = state.recording;
    if (r && r.notePath) showPdfPage(dom.pageFrame, media('/media/note', { path: r.notePath }), jump.dataset.page);
    return;
  }
  const seg = event.target.closest('.seg');
  if (seg && seg.dataset.at !== undefined) seek(Number(seg.dataset.at));
});

document.querySelectorAll('.chip-tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.chip-tab').forEach((t) => t.classList.remove('is-active'));
    tab.classList.add('is-active');
    state.filter = tab.dataset.filter;
    renderLibrary();
  });
});

dom.searchForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const query = dom.searchInput.value.trim();
  if (query) runSearch(query);
});

dom.refresh.addEventListener('click', () => load(true));

let dragging = false;
dom.ribbon.addEventListener('pointerdown', (event) => {
  dragging = true;
  dom.ribbon.setPointerCapture(event.pointerId);
  ribbonSeek(event);
});
dom.ribbon.addEventListener('pointermove', (event) => {
  if (!state.recording) return;
  const rect = dom.ribbon.getBoundingClientRect();
  const fraction = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
  dom.hovered.textContent = clock(fraction * (state.recording ? state.recording.durationMs : 0));
  if (dragging) ribbonSeek(event);
});
/* ⚠️ `pointercancel` as well as `pointerup`, and they are not the same event.
 *
 * The browser fires `pointercancel` — and no `pointerup` — when it takes the pointer over: a
 * touch becomes a scroll or a pinch, the window loses focus mid-drag, a pen leaves the digitiser.
 * With only the `pointerup` listener, `dragging` stayed true afterwards, so `pointermove` kept
 * seeking with no button held and the playhead simply followed the cursor around the ribbon.
 * Recoverable by clicking once, and baffling until you do. */
for (const ending of ['pointerup', 'pointercancel']) {
  dom.ribbon.addEventListener(ending, (event) => {
    dragging = false;
    try { dom.ribbon.releasePointerCapture(event.pointerId); } catch (_) { /* already released */ }
  });
}
dom.ribbon.addEventListener('pointerleave', () => { dom.hovered.textContent = ''; });

window.addEventListener('resize', () => { if (state.recording) drawRibbon(); });

document.addEventListener('keydown', (event) => {
  // ⚠️ Only while the lecture is the view on screen. `state.recording` outlives the view, so
  // without this the arrow keys moved a lecture the user had navigated away from.
  if (!state.recording || dom.lecture.hidden) return;
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;

  /* ⛔ The guard here used to be `closest('input, textarea, select, button, a[href],
   * [contenteditable]')` with an exemption for `.seg`, and it was **right for Space and wrong for
   * the arrows**. It was written when Space started playback: Space is a button's own activation
   * key, so binding it document-wide with `preventDefault` took it from Refresh, the filter tabs,
   * Search and Open PDF — a replacement rather than an addition.
   *
   * Space went with the audio. The guard did not, and a guard sized for Space is far too wide for
   * an arrow key: **opening a lecture leaves focus on the library item you clicked**, which is a
   * `<button>` and is not `.seg`, so the arrows did nothing at all until you happened to click
   * somewhere else. Dead immediately after the one action that makes them relevant.
   *
   * Left and Right are not a button's keys. They belong to a text cursor, a select's option list
   * and a slider — so those are what is exempted, and nothing else. Same lesson as CLAUDE.md's
   * *"when two guards on one decision measure different quantities, only the tighter one is
   * real"*: check what the key actually means to the focused control rather than whether the
   * control is focusable. */
  /* ⚠️ `closest?.()`, because `event.target` is not always an Element. A keydown delivered with
   * nothing focused can target `document` itself, which has no `closest` — and the bare call then
   * throws a TypeError out of the handler, which in a document-level listener means **the key does
   * nothing and the page logs an error nobody is looking at**. Reproduced in the browser here. The
   * wide guard this replaced had the same shape and the same latent throw. */
  if (event.target.closest?.('input, textarea, select, [contenteditable]')) return;

  event.preventDefault();
  const step = (event.shiftKey ? 30000 : 5000) * (event.key === 'ArrowLeft' ? -1 : 1);
  seek(state.positionMs + step);
});

/* -------------------------------------------------------------------- boot */

async function load(force) {
  try {
    const health = await api('/api/health');
    dom.statusDot.className = `dot ${health.ok ? 'ok' : 'bad'}`;
    dom.statusText.textContent = health.uid
      ? `${health.uid} · ${health.source.replace(/^nextcloud:https?:\/\//, '')}`
      : health.source;
    if (!health.ok) {
      // ⛔ A source failure is now an OFFER, not just a complaint. The banner used to be the whole
      // response, and it told the user to go and mint an app password by hand.
      clearBanner();
      showConnect(health.kind === 'reauth'
        ? 'This reader is not signed in to your Nextcloud any more — the stored credential was revoked or expired.'
        : 'This reader could not reach your Nextcloud.', health.source);
      return;
    }
    clearBanner();
    // Shown once per page load. Not an error — the connection worked — but a fact about the
    // connection the user is entitled to know.
    if (Array.isArray(health.tlsNotices) && health.tlsNotices.length && !state.tlsNoticeShown) {
      state.tlsNoticeShown = true;
      banner(health.tlsNotices.join(' '), 'info');
    }
  } catch (error) {
    dom.statusDot.className = 'dot bad';
    dom.statusText.textContent = 'unreachable';
    banner(error.message);
  }

  try {
    state.scan = await api('/api/scan', force ? { force: 1 } : {});
    if (state.scan.error) banner(state.scan.error); else clearBanner();
    renderLibrary();
  } catch (error) {
    dom.libraryList.innerHTML = `<p class="empty">${escapeHtml(error.message)}</p>`;
    banner(error.message);
  }
}

load(false);
