# M8c follow-up — a text box is resized by its **frame**, and there is a way back into the field

**Status: implemented.** §4 says which check each claim rests on.

The report, in full:

> *"i dont like how the text is currently done when creating text that selection thing should not
> stretch or resize the text it should just change the bounds of the text and when the text is
> actually there you can select it and get the option to click a button that says "EDIT TEXT" where
> it brings you to that same thing of not stretch or resize and you can edit the actual text"*

Two asks, and they turn out to be one design: a text box's size is a **wrap width**, not a scale, and
the field that edits its words is the same place you change it.

---

## 1. What was wrong

`DryTextBox`'s KDoc argued the old behaviour and argued it well:

> *"The box's width never changes … a resize is a `SetTransform`, so the wrap width is fixed for the
> object's life and a scaled text box re-wraps nothing. That is also the honest behaviour — dragging
> a corner scales the words with the frame, exactly as it scales a photo."*

Honest and wrong, and it is the same family as the entries CLAUDE.md already collects: **a KDoc that
explains why the restriction is a feature is the expensive kind.** The analogy to a photo is where it
fails. Scaling a photo *is* resizing it — there is nothing inside a bitmap that could re-flow. A
paragraph has a line-breaking algorithm inside it, and every text tool a user has ever met puts that
algorithm on the resize handle. So "make this box wider to fit one more word" returned the same words
at a larger point size, on the same number of lines, and the only way to actually re-wrap a label was
to delete it and place a wider one.

There was no second half either: once a box was placed, the only route back into its words was to
switch to the Text tool and tap it. Selecting one — which is what a user does with an object they
want to change — offered Delete, Duplicate, Front/Back and a colour strip, none of which is *"edit
the text"*.

## 2. The frame is a `SetFormat` key

`ObjectFormat` gains `boxWidthPt` / `boxHeightPt`, wire keys `w` and `h`. Four properties make it the
right home rather than a new op or a payload field, and all four are `ObjectFormat`'s own arguments
re-checked against this value:

| | |
|---|---|
| **Absolute** | The resulting width, never a delta — CLAUDE.md rule 8. Two devices widening one box converge on one of the two widths rather than on the product. |
| **Its own register** | Not `SetTextContent`. Widening a label must not discard a paragraph a peer typed, which is the whole reason the two registers are two (SPEC §4.9.2). |
| **Not the payload** | `AddObject` is first-write-wins, so a payload field could be written once and never changed. |
| **Nullable, meaning "as placed"** | A default number here would resize every text box in every note written before this build. `frameWidth(payload)` is the one place the fallback lives. |

`ShapeGeometry.MIN_EXTENT_PT` is the floor, refused at decode rather than clamped: an unreadable
value leaves the property alone, which means the size the user has already seen.

### 2.1 The reads that had to move with it

The frame is a second source for a number four layers were reading off the payload. Every one of them
is a place where a resized box would have been *drawn* at one size and *measured* at another —
CLAUDE.md's "selectable with a lasso but not with a tap", arrived at through a stale extent:

- `DryBox.widthPt` / `heightPt` are now properties with `setFrame`, which also rebuilds the
  `outline` the tap test and the eraser share. `DryTextBox.applyFrame` runs **before** `buildLayout`,
  so a resize re-wraps in the same frame rather than one write later.
- `InkstonePdfMaterializer.textBoxFor` lays out to the frame. Without it a published PDF wraps at
  the width the box was *placed* at — valid, parseable, and disagreeing with the screen about every
  line after the first, which is M4's lesson in its exact original shape.
- `InkObject.pageBounds(format)` and `topmostAt(point, formatOf)` take the format, so the Text tool's
  tap hits the box where it is drawn. Without it, tapping the part of a box the user had just widened
  into would place a *second* box on top of the first.
- `InkSelection.soleTextBox` reports the live frame, not `obj.widthPt`.

`ElementLibrary`'s capture still measures the payload; an element cut from a resized text box is
placed by a box that is too small. It is noted rather than fixed — capture takes a dragged *area* and
is already documented as approximate there.

## 3. What the two gestures are

**Resizing.** Three grips on the editing frame — right edge, bottom edge, and the corner that does
both. Three and not eight because the top-left corner is the object's origin, and growing from any
other anchor moves it: the origin is `SetTransform`, a *different* register, so a left-edge drag
would write two absolute values a peer can resolve independently and disagree about. Three handles is
the whole capability with one register to express it.

Nothing is written while the finger is down. `ObjectToolState.resizeFrame` moves the on-screen frame
and the register is written on release — the shape `dragCrop` already had, and for its reason: a
`SetFormat` per drag frame is a journal entry *and an undo entry* per frame, of which only the last
could ever have mattered.

⚠️ **The drag is divided by `TextEditing.scaleX`.** A box the selection handles have already scaled
has a page-space box larger than its frame, so a drag measured in page points would be magnified by
that scale a second time. The pair `(bounds, frame)` is carried from the one layer that holds both,
rather than guessed here — the `tapImage` mistake, avoided by construction.

⚠️ **A box that does not exist yet writes nothing**, and that is not a gap: `insertTextBox` takes its
extents from these very bounds, so the frame the user dragged is already what the first words will
create. `ObjectToolState.place` drops `w`/`h` from the format it carries forward for the same reason
— the new box's record would otherwise contradict its own payload from the first op.

**"Edit text".** First chip on the selection bar, shown only for a sole selected text box
(`InkSelection.soleTextBox`, exactly `soleImage`'s argument). It opens the field, the frame and the
formatting bar on the selected box and clears the selection.

⚠️ **It deliberately does not change the tool.** `InkScreen`'s `LaunchedEffect(note.pageIndex,
ui.tool)` commits the field and calls `ObjectToolState.reset` whenever the tool changes, so switching
to `TEXT` here would close the field the chip had just opened, one frame later, looking exactly like
a button that does nothing. Everything works from Select; `Done` is the way out. This is worth
knowing before adding any other control that sets `ui.tool` as a side effect.

⚠️ `ObjectToolState.focusText` **ignores a re-report while a field is open.** `onSelectionChanged`
fires for reasons that are not the user changing their mind — a peer's op landing, a bounds refresh
after a drag — and dropping the focus then would take the frame handles off the screen mid-edit.

## 4. What was checked, and how

Per CLAUDE.md's definition-of-done #6, which of *written* / *automated* / *run on a device* each row
is:

| Claim | Check |
|---|---|
| The frame round-trips through the wire format, keeps the font beside it, and refuses a degenerate value | automated — `ObjectFormatTest`, 4 new cases |
| An absent key means the extents the box was placed with | automated — `ObjectFormatTest` |
| A drag on a scaled box is divided by the scale before it is written | automated — `TextFrameTest` |
| Placing a new box carries the formatting and drops the last frame | automated — `TextFrameTest` |
| A selection re-report cannot close an open field | automated — `TextFrameTest` |
| The grips are reachable, and dragging one re-wraps rather than magnifies | **device** — see §5 |
| "Edit text" appears on a selected text box and opens the field | **device** — see §5 |
| The published PDF wraps at the frame | *not separately checked* — the layout path is shared with the screen and the width now comes from one function, but no PDF was re-read after a resize |
| A resize writes one op, on release, in the format register only | **device** — read out of `journal.ops`, §5 |

## 5. The emulator walk

⚠️ Drawing and tapping on the ink surface need `adb shell input stylus`, not plain `input`:
`PalmGuard` accepts only `TOOL_TYPE_STYLUS`, and an ordinary tap selects nothing while looking
exactly like a hit-test bug (CLAUDE.md — the fourth time that note has been earned).

Ten minutes on `Test_Tablet` (2560 x 1600, API 36), driven entirely over `adb`:

1. `TEXT`, then `input stylus tap 700 700`. The frame appears **with its three grips** — right edge,
   bottom edge, corner. This is the check that a Compose overlay drawn after the field is actually
   on top of it and not swallowed by it.
2. `input text` a nine-word sentence. It wraps to two lines at 14 pt, and the formatting bar floats
   above the box.
3. `input swipe 1619 792 1200 792 600` — the width grip, dragged left by about a third of the box.
   The paragraph **re-wraps to three lines at the same 14 pt**, in the field and in the dry layer
   both. This is the whole report, answered: the words did not stretch.
4. `journal.ops` after the drag holds **exactly one** new op for the whole gesture:

   ```
   {"t":"fmt", "g":"01a01edb-…", "fr":"if1;w=123.254;h=44"}
   ```

   One op, not one per drag frame; the format register, not the transform; absolute.
5. `DONE`, then `SELECT` and `input stylus tap` on the words. The selection bar comes up with
   **`EDIT TEXT`** as its first chip, and the dashed selection box hugs the **resized** frame — which
   is `soleTextBox` and `pageBounds(format)` reading the live width rather than the payload's.
6. `EDIT TEXT` opens the field, the frame, the grips and the formatting bar on that box, with the
   selection cleared. Typing appends to the paragraph, and the journal's next op is a `txt` and not a
   `fmt` — the two registers stayed independent across the whole walk.

### What the walk also showed, and did not fix

⚠️ **While a field is open over an existing box, the words are drawn twice** — once by the Compose
field and once by the dry layer underneath it — and between a keystroke and the 600 ms debounce the
two strings differ, so the box is briefly illegible. This is not new and is not caused by the frame:
it is what editing an existing text box has done since M8c, and it was simply hard to reach before,
because the only route in was to switch to the Text tool and tap. `EDIT TEXT` makes it the normal
way in, so it is now the most visible thing left on this path.

The fix is a hidden-while-editing id on the surface — `PagePainter.paintObject` skipping one object,
with the display-list revision bumped so the cached recording re-records. It is deliberately **not**
in this change: it touches the dry layer's caching invariants, which is the one place in this module
where a quiet mistake is expensive, and it is a different defect from the one that was reported.
