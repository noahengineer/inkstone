package dev.inkstone.core.calc.lcd

// Three more faces the SA handout's captures show and research 01 §3.6 does not yet list:
//
//  - TINY, 3 × 5 and proportional: the coefficient and statistics editors draw their headers
//    (`a b c`), row numbers and cell values in it — a cell that holds `1,1428` (research 03 §2.4)
//    is six characters in 24 dots, which only a 3-dot face can do. Lower case keeps a 3-row
//    x-height; M, W, m, w and ∞ are wider because three dots cannot draw them.
//  - MENU, 5 × 7 capitals: the MODE menu's labels fill rows 0–6 of each 8-row band, next to a
//    7 × 7 number cell (SA slides 2 and 15, counted on the 4× and 2× captures). Research 02 §4
//    calls this the small font; the captures disagree, and the captures win.
//  - INVERTED, 5 × 5 digits drawn light inside that number cell.
//
// All drawn for Inkstone (decision D1).

private val TINY_DIGITS = listOf(
    "0" drawn "### #.# #.# #.# ###",
    "1" drawn ".# ## .# .# .#",
    "2" drawn "### ..# ### #.. ###",
    "3" drawn "### ..# ### ..# ###",
    "4" drawn "#.# #.# ### ..# ..#",
    "5" drawn "### #.. ### ..# ###",
    "6" drawn "### #.. ### #.# ###",
    "7" drawn "### ..# .#. .#. .#.",
    "8" drawn "### #.# ### #.# ###",
    "9" drawn "### #.# ### ..# ###",
)

private val TINY_UPPER = listOf(
    "A" drawn ".#. #.# ### #.# #.#",
    "B" drawn "##. #.# ##. #.# ##.",
    "C" drawn ".## #.. #.. #.. .##",
    "D" drawn "##. #.# #.# #.# ##.",
    "E" drawn "### #.. ##. #.. ###",
    "F" drawn "### #.. ##. #.. #..",
    "G" drawn ".## #.. #.# #.# .##",
    "H" drawn "#.# #.# ### #.# #.#",
    "I" drawn "### .#. .#. .#. ###",
    "J" drawn "..# ..# ..# #.# .#.",
    "K" drawn "#.# #.# ##. #.# #.#",
    "L" drawn "#.. #.. #.. #.. ###",
    "M" drawn "#...# ##.## #.#.# #...# #...#",
    "N" drawn "#..# ##.# #.## #..# #..#",
    "O" drawn ".#. #.# #.# #.# .#.",
    "P" drawn "##. #.# ##. #.. #..",
    "Q" drawn ".#. #.# #.# ##. .##",
    "R" drawn "##. #.# ##. #.# #.#",
    "S" drawn ".## #.. .#. ..# ##.",
    "T" drawn "### .#. .#. .#. .#.",
    "U" drawn "#.# #.# #.# #.# ###",
    "V" drawn "#.# #.# #.# #.# .#.",
    "W" drawn "#...# #...# #.#.# ##.## #...#",
    "X" drawn "#.# #.# .#. #.# #.#",
    "Y" drawn "#.# #.# .#. .#. .#.",
    "Z" drawn "### ..# .#. #.. ###",
)

private val TINY_LOWER = listOf(
    "a" drawn "... ... .## #.# .##",
    "b" drawn "#.. #.. ##. #.# ##.",
    "c" drawn "... ... .## #.. .##",
    "d" drawn "..# ..# .## #.# .##",
    "e" drawn "... .#. ### #.. .##",
    "f" drawn ".## .#. ### .#. .#.",
    "g" drawn "... .## #.# .## ##.",
    "h" drawn "#.. #.. ##. #.# #.#",
    "i" drawn "# . # # #",
    "j" drawn ".# .. .# .# #.",
    "k" drawn "#.. #.# ##. #.# #.#",
    "l" drawn "#. #. #. #. .#",
    "m" drawn "..... ..... ##.#. #.#.# #.#.#",
    "n" drawn "... ... ##. #.# #.#",
    "o" drawn "... ... .#. #.# .#.",
    "p" drawn "... ##. #.# ##. #..",
    "q" drawn "... .## #.# .## ..#",
    "r" drawn "... ... #.# ##. #..",
    "s" drawn "... .## #.. ..# ##.",
    "t" drawn ".#. ### .#. .#. ..#",
    "u" drawn "... ... #.# #.# .##",
    "v" drawn "... ... #.# #.# .#.",
    "w" drawn "..... ..... #...# #.#.# .#.#.",
    "x" drawn "... ... #.# .#. #.#",
    "y" drawn "... #.# .## ..# ##.",
    "z" drawn "... ... ##. .#. .##",
)

private val TINY_SYMBOLS = listOf(
    " " drawn ".. .. .. .. ..",
    "," drawn ".. .. .. .# #.",
    "." drawn ". . . . #",
    "-" drawn "... ... ### ... ...",
    "−" drawn "... ... ### ... ...",
    "+" drawn "... .#. ### .#. ...",
    "×" drawn "... #.# .#. #.# ...",
    "=" drawn "... ### ... ### ...",
    "(" drawn ".# #. #. #. .#",
    ")" drawn "#. .# .# .# #.",
    ":" drawn ". # . # .",
    "/" drawn "..# ..# .#. #.. #..",
    "μ" drawn "... #.# #.# ### #..",
    "∞" drawn "..... ..... .#.#. #.#.# .#.#.",
)

private val MENU_LETTERS = listOf(
    "A" drawn ".###. #...# #...# ##### #...# #...# #...#",
    "B" drawn "####. #...# #...# ####. #...# #...# ####.",
    "C" drawn ".###. #...# #.... #.... #.... #...# .###.",
    "D" drawn "####. #...# #...# #...# #...# #...# ####.",
    "E" drawn "##### #.... #.... ####. #.... #.... #####",
    "F" drawn "##### #.... #.... ####. #.... #.... #....",
    "G" drawn ".###. #...# #.... #.### #...# #...# .####",
    "H" drawn "#...# #...# #...# ##### #...# #...# #...#",
    "I" drawn ".###. ..#.. ..#.. ..#.. ..#.. ..#.. .###.",
    "J" drawn "..### ...#. ...#. ...#. ...#. #..#. .##..",
    "K" drawn "#...# #..#. #.#.. ##... #.#.. #..#. #...#",
    "L" drawn "#.... #.... #.... #.... #.... #.... #####",
    "M" drawn "#...# ##.## #.#.# #.#.# #...# #...# #...#",
    "N" drawn "#...# ##..# ##..# #.#.# #..## #..## #...#",
    "O" drawn ".###. #...# #...# #...# #...# #...# .###.",
    "P" drawn "####. #...# #...# ####. #.... #.... #....",
    "Q" drawn ".###. #...# #...# #...# #.#.# #..#. .##.#",
    "R" drawn "####. #...# #...# ####. #.#.. #..#. #...#",
    "S" drawn ".###. #...# #.... .###. ....# #...# .###.",
    "T" drawn "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..",
    "U" drawn "#...# #...# #...# #...# #...# #...# .###.",
    "V" drawn "#...# #...# #...# #...# .#.#. .#.#. ..#..",
    "W" drawn "#...# #...# #...# #.#.# #.#.# ##.## #...#",
    "X" drawn "#...# #...# .#.#. ..#.. .#.#. #...# #...#",
    "Y" drawn "#...# #...# .#.#. ..#.. ..#.. ..#.. ..#..",
    "Z" drawn "##### ....# ...#. ..#.. .#... #.... #####",
    // Five dots, as `BASE-N`'s hyphen measures on SA slide 2 (columns 81–85, the label's 4th row).
    "-" drawn "..... ..... ..... ##### ..... ..... .....",
    " " drawn "..... ..... ..... ..... ..... ..... .....",
)

private val INVERTED_DIGITS = listOf(
    "0" drawn ".###. #...# #...# #...# .###.",
    "1" drawn "..#.. .##.. ..#.. ..#.. .###.",
    "2" drawn "####. ....# .###. #.... #####",
    "3" drawn "####. ....# .###. ....# ####.",
    "4" drawn "#..#. #..#. ##### ...#. ...#.",
    "5" drawn "##### #.... ####. ....# ####.",
    "6" drawn ".###. #.... ####. #...# .###.",
    "7" drawn "##### ....# ...#. ..#.. ..#..",
    "8" drawn ".###. #...# .###. #...# .###.",
    "9" drawn ".###. #...# .#### ....# .###.",
)

internal fun tinyGlyphs(): List<Pair<String, List<String>>> = TINY_DIGITS + TINY_UPPER + TINY_LOWER + TINY_SYMBOLS

internal fun menuGlyphs(): List<Pair<String, List<String>>> = MENU_LETTERS

internal fun invertedDigits(): List<Pair<String, List<String>>> = INVERTED_DIGITS
