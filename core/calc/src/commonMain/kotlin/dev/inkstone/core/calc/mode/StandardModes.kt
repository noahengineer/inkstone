package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.machine.ModeRegistry

/**
 * The calculator's modes: COMP, CMPLX and BASE-N built into the machine, plus every controller in
 * this package. A mode with no controller here shows the machine's placeholder. Add a new
 * controller to [registry] and nothing else — see `machine.ModeController` for how one is written,
 * and [RatioMode] for one that is.
 */
object StandardModes {
    /** A fresh set: controllers keep their mode's data, so every calculator needs its own. */
    fun registry(): ModeRegistry = ModeRegistry.standard(listOf(RatioMode(), StatMode()))
}
