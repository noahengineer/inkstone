# Layouts you can keep, and a layout editor that can change anything

> *"I like all of this and the snapping behavior and everything, I want you to make it so that you can
> do all of this in the layout editor for anything you would want or be able to go back to how it was.
> Basically, you should be able to do anything to the layout. I also want it that you can save multiple
> layouts so that you can switch between them easily"*

Follows `docs/UI-RAILS.md` (the corner "L" and the panel column) and `docs/CUSTOM-LAYOUT.md` (the
layout editor). Asked what that meant, the user chose all four of: **every panel like the calculator**,
**the panel column itself**, **style switches**, and **bars snapping while dragged**; *"go back to how
it was"* meant *"switching to the default and custom ones"*; and switching is **only in the editor**.

## 1. Saved layouts (`SavedLayouts`)

- **Default** is `ChromeLayout()`: read-only, always first in the list, always there to go back to.
- Every other layout is the user's, with a name (32 characters at most; a blank rename is refused).
- **The layout on screen is always one of them.** `InkUiState.layout` is the working copy, and every
  write to it goes through `SavedLayouts.synced`:
  - on one of the user's layouts, the change is written into it — there is no *Save*;
  - on the Default, the first change starts a new layout (*Layout 1*, *Layout 2* …) and switches to it.
    So the Default is never edited, and switching away never loses anything, because there is no
    unsaved state to lose.
- `adopt` (open, `ON_START`) and `switchLayout` write the working copy **without** syncing, because
  neither is a change the user made to the layout on screen.
- Editor, *Layouts* tab: tap a layout to put it on screen; *Save as new*; *Rename*; *Delete* asks twice
  on the chip itself (no dialog). Deleting the layout on screen puts the Default there.

### Storage

`layouts.list` names the layouts, `layouts.active` the one on screen, and each layout is its own
`ChromeLayoutCodec` table under `layouts.<id>.` — so a saved layout is read by exactly the code that
reads the one on screen and gets every rule it has. ⚠️ **Only layouts `layouts.list` names are read**:
`putAll` cannot delete a deleted layout's rows, and read blindly they would bring it back (the
`ui.floats` rule). Ids only grow, so a new layout is never numbered into a gap.

⚠️ **The screen wins.** When `ui.*` and the active layout's copy disagree — a build that knew nothing of
saved layouts may have rearranged the screen since — decoding syncs the copy to the screen.

**Migration.** A table with no `layouts.list` was written before saved layouts existed: a screen the
user had arranged is kept as **My layout** and stays on screen; an untouched one is the Default.

### What a layout holds

Everything `ChromeLayout` holds: bars (place, size, shape, chips, hidden chips, floating bars), the
overall and per-panel sizes, and now the panel column's **side** and **order**, which panels are
**floating** and where, and the two **style switches**.

⚠️ **Not the calculator's window.** It keeps its own place (`CalculatorPrefs`), because the library
shows the calculator too and the library has no layout. Its place in the column's *order* is part of
the layout; where it floats once dragged out is not.

## 2. Every panel like the calculator (`PanelWindow`)

The Elements library, the colour panel, the size panel and the More (diagnostics) panel each sit in
the panel column until **dragged out by their name** (`PanelHeader` — one composable, so no panel can
be the one that forgot to be movable), then stay where they were let go. A **corner grip** sizes them
(`ChromeLayout.panelScales`); **Dock** — or letting go against the column's edge — puts them back.

- ⚠️ The grip is **outside** the panel's `ScaledChrome`: resizing changes that density, and Compose
  restarts a gesture whose density changes under it (CLAUDE.md). The name is inside, which is safe —
  a move changes no density.
- Both gestures are live while the finger is down and **written once, on lift**.
- A floating panel tells the column where it is (`PanelDock.reportLoose`), so contextual bars keep off
  it, and it snaps to the screen and the bars while dragged (§4).
- Docking on release: `PanelDock.wantsDock` — the panel's outer edge within 48 dp of the column's edge.
  While it is in that zone the guide drawn is the column's edge. The calculator follows the same rule
  now (`CalculatorWindow`), as well as its own *Dock* chip.

## 3. The panel column (`PanelDock`)

- **Side**: right (stock) or left. On the left it starts beside anything standing upright against
  that edge — the tools, in the stock layout — and under the pen's row. `DockFlow.place(fromLeft)`
  mirrors the flow: columns are added further from the edge, and a wide panel still never slips
  under a narrower column.
- **Order**: the user's, top first (`ChromeLayout.orderedPanels`: the stored order, then any panel it
  does not name, so a panel a later build adds still has a place). Editor, *Panels* tab: ▲ / ▼ per
  panel, and *Dock* beside any panel that is floating.
- ⛔ **The side and order are pushed into the dock from composition, and the read has to be in
  composition too.** The first version read `ui.layout` *inside* the `SideEffect`, so nothing
  recomposed when the layout changed, the effect never ran again, and choosing *Left* moved nothing.
  Found on the emulator.
- ⚠️ **A left-hand column sits under the pen's row, and the row folds away while a contextual bar is
  up**, so the column rose to the top of the screen on every selection and fell back after it.
  `ChromeGeometry.pillsWithFoldedRow` keeps the folded row's place for the column (only for the column;
  a contextual card may still use that space). Found on the emulator.

## 4. Snapping (`BarSnap`)

A bar dragged in the editor, or a panel dragged anywhere, that comes within 12 dp of lining up is
moved the rest of the way: its edge onto the screen's margin, its centre onto the screen's centre, its
edges onto another bar's edges, or its side beside another bar's with the margin between them. Each
axis snaps on its own, to the nearest line, and the line is drawn while the finger is down
(`SnapGuides`). For a bar the snap happens in `ChromeHost`'s placement, so the commit on lift — which
reads the recorded pill — keeps exactly what snapped. The bar being moved and anything that follows it
(the pen's row follows the tools) are left out of what it snaps to.

## 5. Style switches

- **Brushes**: one `FOUNTAIN ▾` chip with a menu (stock, docs/UI-RAILS.md §1) or a chip per brush, as
  in M10 (`ChromeContainer.RowItems`). Only matters in a row; a column, grid or panel already shows
  every brush.
- **Object bars** (selection, text, picture): a card whose lines wrap to the room it has (stock), or
  **one row** that scrolls (`GlassStrip`). The bars' bodies lost their `ColumnScope` receiver to make
  that possible — they only ever emitted `CardFlow` lines.

## 6. Per claim

Emulator `Test_Tablet_B` (2560 × 1600, density 2), debug build, airplane mode.

| Claim | Written | Automated | On the emulator |
|---|---|---|---|
| Changing the Default starts *Layout 1*; the Default is untouched | ✅ | ✅ `SavedLayoutsTest` | ✅ floating a panel from the Default made *Layout 1*, named in the editor's title |
| Each layout keeps its own arrangement; switching restores it | ✅ | ✅ `SavedLayoutsTest` | ✅ *Default* put the calculator back in a right-hand column, *Layout 1* took it to the left again; *Layout 1* (left column, brush buttons) survived a force-stop and relaunch |
| Saved layouts survive the settings table; deleted ones stay deleted; the screen wins over a stale copy; old tables migrate | ✅ | ✅ `SavedLayoutsTest` (codec, stale rows, migration, `EditorPrefs` round trip) | — |
| Every new layout field survives the codec; a table from before them reads as stock | ✅ | ✅ `ChromeLayoutTest` | — |
| A panel drags out by its name, shows *Dock* and a corner grip | ✅ | — | ✅ the More panel dragged out; the calculator moved up into the space it left |
| Letting a panel go against the column's edge docks it | ✅ | — | ✅ back at the top of the column, the calculator back beside it |
| Panel corner resize | ✅ | ✅ `PanelDockTest` (`resized`) | ✅ the colour panel's grip dragged 80 px: the panel ~10 % larger, and kept |
| Column on the left, flowing rightwards beside the tools | ✅ | ✅ `PanelDockTest` (mirrored flow) | ✅ colour, More and calculator side by side from the tools, none overlapping |
| A left-hand column does not move when a selection folds the pen's row | ✅ | ✅ `PanelDockTest` (the row kept at full height through the fold) | ✅ calculator's top at 250 px before and after selecting — 157 px before the second fix |
| Panel order | ✅ | ✅ `ChromeLayoutTest` (`panelMoved`, `orderedPanels`) | ✅ *Calculator ▲* in the *Panels* tab: the column went colour, More, calculator → colour, calculator, More |
| Brushes as buttons; object bars as one row | ✅ | — | ✅ both, from the *Style* tab |
| Bars and panels snap while dragged, with a guide | ✅ | ✅ `BarSnapTest` | ✅ the Undo bar dragged in the editor caught the tools' left edge; the guide was drawn while the finger was down (screenshot taken before `UP`) |
| Feel on the Galaxy Tab | — | — | ❌ not yet |

## 7. Not done / not yet checked

- **A bar the user puts inside the panel column's space is not kept clear by it.** Dragging the Undo
  bar up into the left-hand column's area left the colour panel — taller than the space under it —
  overlapping the bar. The column measures its space from bars at its top and bottom, not from one in
  its middle. Found on the emulator; not fixed.
- **Undo in the editor** was offered and not chosen; *Reset all* and per-bar *Reset* remain.
- Switching layouts does not move a calculator that has been dragged out of the column (§1).
