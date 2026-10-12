package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.value.Res

/**
 * What one step of an evaluation produced: a value; an error screen together with the node it
 * belongs to, so `[◀][▶]:Goto` can put the cursor just after that node (research 02 §11: `14÷0×2`
 * goes back with the cursor after the `0`, the end of `14÷0`); or [Stop], because AC broke a long
 * calculation (research 07 §4.6).
 *
 * Errors are values here for the reason they are in the value package (CLAUDE.md, Setup.kt's
 * `CalcError`): a `÷0` three levels inside an integrand has to reach the screen, not unwind the
 * key machine. [Res] cannot say where an error happened or that AC was pressed, hence a second
 * type; [at] converts at the boundary.
 */
internal sealed interface Ev<out T> {
    data class Ok<out T>(val value: T) : Ev<T>

    /** [node] is null only for an error that has no place in the input (nothing here makes one). */
    data class Fail(val error: CalcError, val node: Node?) : Ev<Nothing>

    data object Stop : Ev<Nothing>
}

/**
 * The value, or whatever [escape] does with the failure — which, being `Nothing`, has to leave:
 * `val x = eval(n).orElse { return it }` hands an error or a stop straight to the caller, the
 * same non-local-return idiom as `Res.valueOr`.
 */
internal inline fun <T> Ev<T>.orElse(escape: (Ev<Nothing>) -> Nothing): T = when (this) {
    is Ev.Ok -> value
    is Ev.Fail -> escape(this)
    Ev.Stop -> escape(Ev.Stop)
}

internal inline fun <T, R> Ev<T>.map(transform: (T) -> R): Ev<R> = when (this) {
    is Ev.Ok -> Ev.Ok(transform(value))
    is Ev.Fail -> this
    Ev.Stop -> Ev.Stop
}

internal inline fun <T, R> Ev<T>.then(next: (T) -> Ev<R>): Ev<R> = when (this) {
    is Ev.Ok -> next(value)
    is Ev.Fail -> this
    Ev.Stop -> Ev.Stop
}

/** A value-package answer, its error (if any) attributed to [node]. */
internal fun <T> Res<T>.at(node: Node): Ev<T> = when (this) {
    is Res.Ok -> Ev.Ok(value)
    is Res.Err -> Ev.Fail(error, node)
}

internal fun failAt(error: CalcError, node: Node): Ev<Nothing> = Ev.Fail(error, node)
