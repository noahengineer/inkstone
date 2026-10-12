package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.CalcError

/**
 * What every function in this package answers: a value, or the error screen the calculator would
 * show instead. Errors are values here, never exceptions (CLAUDE.md, Setup.kt's [CalcError]): a
 * `÷0` deep inside a matrix inverse has to reach the screen as `Math ERROR`, not unwind the key
 * machine.
 *
 * Three errors come out of this package, and the guide keeps them apart (research 02 §11):
 * - [CalcError.MATH] — a result outside ±1×10¹⁰⁰, an input outside a function's range, `÷0`;
 * - [CalcError.DIMENSION] — matrices or vectors whose sizes do not fit the operation;
 * - [CalcError.ARGUMENT] — reserved for the evaluator; nothing here produces it, because the guide
 *   never names a value-level case for it (research 02 §11 lists it without one).
 */
sealed interface Res<out T> {
    data class Ok<out T>(val value: T) : Res<T>

    data class Err(val error: CalcError) : Res<Nothing>

    companion object {
        val MATH: Err = Err(CalcError.MATH)
        val DIMENSION: Err = Err(CalcError.DIMENSION)
    }
}

/** The value, or null for an error — for callers that only need to know whether it worked. */
fun <T> Res<T>.getOrNull(): T? = (this as? Res.Ok)?.value

/** The error, or null for a value. */
val Res<*>.errorOrNull: CalcError? get() = (this as? Res.Err)?.error

inline fun <T, R> Res<T>.map(transform: (T) -> R): Res<R> = when (this) {
    is Res.Ok -> Res.Ok(transform(value))
    is Res.Err -> this
}

/** Chains a step that can fail; the first error wins and later steps never run. */
inline fun <T, R> Res<T>.then(next: (T) -> Res<R>): Res<R> = when (this) {
    is Res.Ok -> next(value)
    is Res.Err -> this
}

/**
 * Two results combined: [combine] runs only when both are values, and the first error (this one's,
 * then [other]'s) wins. `RealArith.square(a).zip(RealArith.square(b), RealArith::add)` is `a² + b²`
 * with every Math ERROR on the way passed through.
 */
inline fun <A, B, R> Res<A>.zip(other: Res<B>, combine: (A, B) -> Res<R>): Res<R> =
    then { a -> other.then { b -> combine(a, b) } }

/**
 * The value, or whatever [onError] does with the error — which, being `Nothing`, has to leave:
 * `val x = RealArith.add(a, b).valueOr { return it }` hands the error straight back to the
 * enclosing function's caller. That non-local return is what keeps a cofactor expansion readable
 * without a ladder of [then]s.
 */
inline fun <T> Res<T>.valueOr(onError: (Res.Err) -> Nothing): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> onError(this)
}
