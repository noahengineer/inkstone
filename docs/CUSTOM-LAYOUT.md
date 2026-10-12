# Your toolbar, your way — saved preferences that stay, and a layout you can rearrange

> *"make the UI more dynamic so you can like change everything and resize the things, also i am
> having a problem where the favorite colors does not stay like that and the pencil size so
> basically saving preferences"*

Two things, and the bug came first.

## 1. ⛔ The palette and the pen size reverted — on every note but the first

`EditorPreferences` is a process-wide singleton. It read the `setting` table once, at launch, into
`snapshot`, and **every editor seeded from that snapshot**. So:

1. Open a note, pick Pencil, set 8 dp, choose red. Saved correctly — 400 ms later the row is on disk.
2. Back to the library and open *another* note. Same process, same singleton: the new editor starts
   from the **launch-time** palette and pen.
3. `InkScreen`'s `snapshotFlow { ui.toPrefs() }` emits its first value immediately — the stale seed —
   and 400 ms later that is written **over** what the user had just chosen.

Only a force-stop ever showed the saved value, because it is the only thing that re-reads the table.
The Element editor (a second `NoteActivity` stacked on the note's) did the same in the other direction:
change the palette there, go back, and the note still showed the old one — and its next control touch
wrote the old palette back.

`EditorPrefsCodecTest` was green throughout, because the codec was never wrong. Its KDoc named an
`EditorPreferencesTest` as the test that would cover the rest, and that file did not exist.

**Fix.** `EditorPreferences.current()` is the newest value any editor has reported (`changed()` keeps
it synchronously); `snapshot` is now only *"the table has been read"*. `rememberInkUiState` seeds from
`current()`, and a `LifecycleStartEffect` re-adopts it when an editor comes back to the front
(`InkUiState.adopt`). The constructor is `adopt` and nothing else, so a field seeded on open but not on
return is unrepresentable. `ON_START` cannot be mid-gesture, which keeps this clear of the *"two
mechanisms for one change"* rule that made seeding one-way in the first place.

## 2. ⭐ The layout editor

Opened from **⋯ → Customise layout**, or by **long-pressing any bar's background** (padding, a
divider — never a chip, which keeps its own press). The page dims and takes no ink while it is open.

| You can… | How |
|---|---|
| Move any bar — tools, pen settings follow, undo, pages, zoom, record, calculator, your own | Drag its **dotted** handle |
| Resize any bar on its own | Drag its **corner grip** right to grow, left to shrink |
| Lay a bar out as a **row, a column, or 2 / 3 / 4 across** | Tap its **arrange** key (it draws the shape it is) |
| Hide a chip you never use | **Tap** it (it goes faint; tap again to bring it back) |
| Move a chip — within its bar, **into another bar**, or **onto the page as a bar of its own** | **Hold, then drag** it; a sky line shows where it lands |
| Make all the chrome bigger or smaller | **UI size** slider (70–160 %) |
| Size each panel and contextual bar on its own — colour, size, ⋯, selection, text, image | One slider each (60–200 %) |
| Get the controls out of the way | Drag the panel's **Customise layout** caption |
| Start again | **Reset** |
| Finish | **Done**, or Back |

Two chips cannot be hidden: **Draw** (an editor you cannot write in is not an editor) and **⋯** (the
way into the layout editor — hiding it would hide the only way to show it again). Both can still be
moved anywhere. A bar with nothing left in it disappears outside the editor, and inside it is drawn as
a labelled space so chips can be dropped back in; a bar *you* made is deleted the moment it is empty.

The pen-settings row follows the tool bar: **below** it, **above** it once the tool bar is in the lower
half of the screen, and **beside** it once the tool bar is taller than it is wide — so a column of
tools down one edge opens its settings into the page instead of across itself.

Everything is stored with the palette (`EditorPrefs.layout`, `ui.*` keys in `setting`) and goes
through the same debounce and the same fix as §1.

### Round two — *"rearrange buttons … size of specific panels … a grid or vertically"*, buttons anywhere

- **Every chip has an owner** (`ChromeLayout.owner`): its home bar unless something says otherwise.
  Bars are containers (`ChromeContainer`) that draw whichever chips they hold through one renderer
  (`ChromeItemContent`), so `Fit width` in a floating bar fits the width and `Undo` in the tool column
  undoes. A chip moved away and back leaves a layout *equal* to one never touched — a stock bar is
  stored only when it says something its owner rule would not work out alone (`normalised`).
- **Nothing moves until the drop** (`ChromeDrop`). The first editor traded neighbours live, which
  works inside one row and cannot across bars: a chip entering another bar gets a new parent, Compose
  disposes its node, and the drag's own gesture dies with it on the first boundary. So a picture of
  the chip follows the finger, a caret shows the landing place, and the layout changes once.
- **Floating bars are read only if `ui.floats` names them.** `putAll` cannot delete a key, so a
  deleted bar's rows stay in the table; read blindly they would resurrect it, holding a chip the user
  had since moved. (`ChromeLayoutTest` puts a chip back and reads the stale table.)
- **The first editor's settings carry over** (`ui.order.*`, `ui.panelScale`) — the emulator's table
  from round one loaded into round two with its order, hidden chips and sizes intact.

### Decisions

- **One `Layout` for all the chrome (`ChromeHost`)** rather than the old `Row` + `align`s. A bar is
  the same child from open to close; moving it is a placement pass. Moving a bar out of a `Row` into
  a floating layer would have disposed the drag handle on the first pixel of the drag. The stock slots
  are reproduced exactly — including the page bar measured into the gap between history and zoom,
  which `BottomWidgets`' KDoc recorded as a bug fix — so nobody who never opens the editor sees a
  change. Checked on the emulator: dock centred at the top, record and calculator in the top corners,
  history · pages · zoom along the bottom with the page bar between its neighbours.
- **A position is a fraction of the free space, not a dp offset.** A dock dragged to the bottom of a
  landscape tablet is at y ≈ 730 dp, which is the middle of the screen in portrait. A fraction is on
  the bottom edge in both, and cannot be off screen in either. (`ChromeLayoutTest`.)
- **Size is density, not a graphics-layer scale** (`ScaledChrome`), so a bigger bar has bigger
  *targets*, and layout, hit-testing and text agree.
- **A stored order is completed on read**, so a chip added by a later build lands beside its stock
  neighbour instead of vanishing because an older order did not mention it.
- **Groups decide dividers.** With the stock order they are exactly the design's; a chip moved into
  another group's run grows dividers either side of it.

## 3. What the emulator found that no test could

Three bugs, all invisible to the JVM suite, all found in the first twenty minutes on `Test_Tablet`:

1. ⛔ **A resize grip cancelled its own drag.** Compose restarts a `pointerInput` gesture whenever the
   density under it changes, and the grip lived inside the bar it was resizing — so the first frame of
   a resize changed its density and killed the gesture. Moving was unaffected, because a move changes
   no density. The handles are drawn at the overall size now, outside the bar's own `ScaledChrome`.
2. ⚠️ **The zoom bar's grip was inside the system Back gesture zone.** Every stock bar sits 12 dp from
   a screen edge, so dragging the grip left *from* the edge was Back, and Back closes the editor
   (`startBackNavigation` in logcat at the instant of the drag). The handles use
   `systemGestureExclusion()`.
3. ⚠️ **The page bar had no resize grip.** A scrolling bar takes every pixel it is offered, and the
   `Row` measured it before the grip. The bar is weighted now, so the handles are measured first.

Round two's one bug was caught by a test rather than the emulator: **the pen-settings row's stored
size overwrote the tool bar's on every launch.** The row is drawn at the tool bar's size, and
`scaled()` says so by writing the tool bar — so the codec applying the row's own `1.0` put the tool
bar back to 100 %. The codec round trip went red on exactly that field.
## 4. Per claim

| Claim | Written | Automated | Run on the emulator |
|---|---|---|---|
| A second note in the same process keeps the colour and pencil size | ✅ | ✅ `EditorPreferencesTest` (red against the old seed: `expected PENCIL but was FOUNTAIN`) | ✅ Pencil · 8.0 dp · red on a different note, same process |
| Pen settings survive a process restart | ✅ | ✅ codec round trip | ✅ reinstall and force-stop; read back from the `setting` table |
| An editor coming back to the front takes the other editor's changes | ✅ | ✅ `EditorPreferencesTest` | ❌ Element editor not walked |
| Stock layout unchanged | ✅ | ✅ `ChromeLayoutTest` (order + dividers) | ✅ after Reset, Draw / Undo / Redo / Fit width at the first dump's exact pixels |
| Hide / show a chip | ✅ | ✅ | ✅ Laser, Redo |
| Reorder a chip within its bar | ✅ | ✅ `ChromeDropTest` | ✅ Ruler next to Draw (round one) |
| Move a chip **into another bar** | ✅ | ✅ `ChromeLayoutTest`, `ChromeDropTest` | ✅ Undo carried into the tool column, under Draw; Redo stayed |
| Drop a chip **on the page** as its own bar | ✅ | ✅ | ✅ Fit width became *Bar 1*, and still fits the width (100 % → 11 %) |
| An emptied bar of your own is deleted, and stays deleted | ✅ | ✅ stale-row test | — |
| Row / column / grid per bar | ✅ | ✅ cycle, codec | ✅ tool bar as a column; page bar 3 across |
| Pen settings beside a column-shaped tool bar | ✅ | — | ✅ row starts right of the column |
| Move a bar; second row flips above in the lower half | ✅ | ✅ (position maths) | ✅ (round one) |
| Resize a bar, including from the screen edge | ✅ | — | ✅ Undo grew 100 → 173 px; Zoom shrank 152 → 109 px; no Back |
| A size per panel | ✅ | ✅ | ✅ colour panel at 151 %: swatch 52 → 78 px, other panels unchanged |
| Round one's saved layout loads in round two | ✅ | ✅ legacy-key test | ✅ order, hidden chips and sizes intact after upgrade |
| Long-press a bar's background opens the editor | ✅ | — | ✅ |
| Layout survives leaving and reopening a note | ✅ | ✅ codec | ✅ column, moved Undo, grid and Bar 1 all back |
| Feel with an S Pen on the Galaxy Tab | — | — | ❌ **not done** — definition of done #4 |
## 5. Not done, and deliberately

- **Panels are sized but not moved.** The colour and size panels open under the control that
  summoned them, and that control moves with its bar — so moving a bar moves where they open.
- **Contextual bars** (selection, text, image) follow the object they act on; each has its own size
  but is not placed by the user.
- **The record and calculator chips move and resize but do not join other bars.** Each is a control
  with state of its own (a recorder and its permission flow; the calculator window's anchor) rather
  than a chip, and splitting them into chips is its own piece of work.
- **The erase, select, image and elements rows** are the dock's second row for those tools and are
  not rearranged; only the pen's row is made of chips.
- The Elements panel is not scaled: its drag-and-drop onto the page converts between the panel and
  page coordinates, and scaling one side is a change worth its own check.
- In the Element editor, page chips moved into another bar are left out — an Element has no pages.
- Bars can be dragged on top of each other. They overlap the same way every time (stock bars, then
  yours oldest first), and a chip dropped where two overlap goes into the smaller.