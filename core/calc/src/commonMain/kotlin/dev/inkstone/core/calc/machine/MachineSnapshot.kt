package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.FractionFormat
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.TableFunctions
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.parse.Slot
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/**
 * What the real calculator keeps while switched off (docs/CALCULATOR.md D4; research 02 §9):
 * the mode, every SETUP item and the contrast, the nine variables, Ans, PreAns, the matrix and
 * vector slots, and each mode's own data ([ModeController.snapshot]). **Not** kept: the input
 * line, the screen, and the history — ON clears history (research 02 §10.2) and switching on is
 * pressing ON, so no power cycle could show it.
 *
 * Format, one `key=value` per line after a version line:
 *
 * ```
 * inkstone-calc/1
 * mode=COMP
 * io=MATH_MATH
 * …
 * var.A=F1/2
 * ans=D0.125
 * mat.A=M 2 2 F1/1 F0/1 F0/1 F1/1
 * mat.B=
 * ctl.BASE_N=HEX
 * ```
 *
 * Values are [ValueCodec] words; an empty value is an empty matrix or vector slot; a controller's
 * text is escaped (`\\`, `\n`) so it may span lines. Unknown keys are ignored, so a later build
 * can add some; a missing or malformed one rejects the whole text, and the calculator starts
 * fresh rather than half-restored.
 */
internal object MachineSnapshot {
    const val VERSION = "inkstone-calc/1"

    /** Everything [read] validated, applied in one go by [Machine.load]. */
    class Saved(val mode: Mode, val setup: Setup, private val values: Map<String, Value?>) {
        fun memory(into: Memory) {
            Variable.entries.forEach { into.variables[it] = values[VAR + it.name] ?: Memory.ZERO }
            into.ans = values[ANS] ?: Memory.ZERO
            into.preAns = values[PRE_ANS] ?: Memory.ZERO
            Slot.entries.forEach { into.matrices[it] = values[MAT + it.name] as? MatrixValue }
            Slot.entries.forEach { into.vectors[it] = values[VCT + it.name] as? VectorValue }
        }
    }

    fun write(machine: Machine): String {
        val lines = mutableListOf(VERSION, "mode=${machine.mode.name}")
        SETUP_FIELDS.forEach { lines += it.key + "=" + it.write(machine.setup) }
        val memory = machine.memory
        val values = Variable.entries.associate { VAR + it.name to memory.variables[it] } +
            mapOf(ANS to memory.ans, PRE_ANS to memory.preAns) +
            Slot.entries.associate { MAT + it.name to memory.matrices[it] } +
            Slot.entries.associate { VCT + it.name to memory.vectors[it] }
        values.forEach { (key, value) -> lines += key + "=" + (value?.let(ValueCodec::encode) ?: "") }
        machine.registry().all.forEach { controller ->
            controller.snapshot()?.let { lines += CTL + controller.mode.name + "=" + escape(it) }
        }
        return lines.joinToString("\n")
    }

    /** Restores [text] into [machine]; false — and the caller starts fresh — if any of it is unreadable. */
    fun read(text: String, machine: Machine): Boolean {
        val fields = fields(text) ?: return false
        val saved = saved(fields) ?: return false
        if (!machine.registry().all.all { restore(it, fields[CTL + it.mode.name]) }) return false
        machine.load(saved)
        return true
    }

    /** The `key=value` lines after the version line, or null for another version (or none). */
    private fun fields(text: String): Map<String, String>? {
        val lines = text.split('\n')
        if (lines.firstOrNull() != VERSION) return null
        return lines.drop(1).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun saved(fields: Map<String, String>): Saved? {
        val mode = fields["mode"]?.let { named(it, Mode.entries) } ?: return null
        val setup = SETUP_FIELDS.fold<SetupField, Setup?>(Setup()) { setup, field ->
            setup?.let { fields[field.key]?.let { value -> field.read(setup, value) } }
        } ?: return null
        val values = memoryValues(fields) ?: return null
        return Saved(mode, setup, values)
    }

    /** A controller without a saved entry keeps its fresh state; one whose text it cannot read fails the restore. */
    private fun restore(controller: ModeController, escaped: String?): Boolean {
        if (escaped == null) return true
        val text = unescape(escaped) ?: return false
        return controller.restore(text)
    }

    private fun memoryValues(fields: Map<String, String>): Map<String, Value?>? {
        val values = mutableMapOf<String, Value?>()
        for (key in VARIABLE_FIELDS) values[key] = number(fields[key]) ?: return null
        for (key in ANSWER_FIELDS) values[key] = fields[key]?.let(ValueCodec::decode) ?: return null
        for (slot in Slot.entries) {
            values[MAT + slot.name] = slot(fields[MAT + slot.name], matrix = true) ?: return null
            values[VCT + slot.name] = slot(fields[VCT + slot.name], matrix = false) ?: return null
        }
        return values
    }

    /**
     * A variable's text: the nine variables hold numbers, real or complex (research 02 §9) — a
     * matrix or vector there is no value the calculator could have saved. Null when missing or
     * unreadable. Ans is left to [ValueCodec] alone: what a mode's own answers put there is the
     * mode's business ([CalculationHooks.takeAnswer]).
     */
    private fun number(text: String?): Value? =
        text?.let(ValueCodec::decode)?.takeIf { it is Real || it is ComplexValue }

    /** A slot's text: [EMPTY] for an empty slot, null when it is missing or unreadable. */
    private fun slot(text: String?, matrix: Boolean): Value? = when {
        text == null -> null
        text.isEmpty() -> EMPTY
        matrix -> ValueCodec.decode(text) as? MatrixValue
        else -> ValueCodec.decode(text) as? VectorValue
    }

    /**
     * One SETUP item: its key, how it is written, and how it is read back onto a [Setup] (null when
     * the text is not a value the item can take).
     */
    private class SetupField(val key: String, val write: (Setup) -> String, val read: (Setup, String) -> Setup?)

    private fun <E : Enum<E>> named(text: String, entries: List<E>): E? = entries.firstOrNull { it.name == text }

    private val SETUP_FIELDS: List<SetupField> = listOf(
        SetupField("io", { it.io.name }) { s, v -> named(v, InputOutput.entries)?.let { s.copy(io = it) } },
        SetupField("angle", { it.angle.name }) { s, v -> named(v, AngleUnit.entries)?.let { s.copy(angle = it) } },
        SetupField("format", { format(it.format) }) { s, v -> format(v)?.let { s.copy(format = it) } },
        SetupField("fraction", { it.fraction.name }) { s, v ->
            named(v, FractionFormat.entries)?.let { s.copy(fraction = it) }
        },
        SetupField("complex", { it.complex.name }) { s, v ->
            named(v, ComplexFormat.entries)?.let { s.copy(complex = it) }
        },
        SetupField("frequency", { it.statFrequency.toString() }) { s, v ->
            v.toBooleanStrictOrNull()?.let { s.copy(statFrequency = it) }
        },
        SetupField("table", { it.table.name }) { s, v -> named(v, TableFunctions.entries)?.let { s.copy(table = it) } },
        SetupField("apo", { it.autoPowerOffMinutes.toString() }) { s, v ->
            v.toIntOrNull()?.takeIf { it in APO_CHOICES }?.let { s.copy(autoPowerOffMinutes = it) }
        },
        SetupField("contrast", { it.contrast.toString() }) { s, v ->
            v.toIntOrNull()?.takeIf { it in Setup.MIN_CONTRAST..Setup.MAX_CONTRAST }?.let { s.copy(contrast = it) }
        },
    )

    private fun format(format: NumberFormat): String = when (format) {
        is NumberFormat.Fix -> "Fix:${format.places}"
        is NumberFormat.Sci -> "Sci:${format.digits}"
        is NumberFormat.Norm -> "Norm:${format.range}"
    }

    private fun format(text: String): NumberFormat? {
        val digit = text.substringAfter(':', "").toIntOrNull() ?: return null
        return when (text.substringBefore(':')) {
            "Fix" -> NumberFormat.Fix(digit).takeIf { digit in DIGITS }
            "Sci" -> NumberFormat.Sci(digit).takeIf { digit in DIGITS }
            "Norm" -> NumberFormat.Norm(digit).takeIf { digit in NORM_RANGES }
            else -> null
        }
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n")

    /** Inverse of [escape]; null for a dangling or unknown escape. */
    private fun unescape(text: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\') {
                out.append(UNESCAPES[text.getOrNull(i + 1)] ?: return null)
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private const val VAR = "var."
    private const val ANS = "ans"
    private const val PRE_ANS = "preans"
    private const val MAT = "mat."
    private const val VCT = "vct."
    private const val CTL = "ctl."
    private val VARIABLE_FIELDS: List<String> = Variable.entries.map { VAR + it.name }
    private val ANSWER_FIELDS: List<String> = listOf(ANS, PRE_ANS)
    private val APO_CHOICES = setOf(10, 60)
    private val DIGITS = 0..9
    private val NORM_RANGES = 1..2
    private val UNESCAPES: Map<Char?, Char> = mapOf('\\' to '\\', 'n' to '\n')

    /**
     * What an empty slot reads as before it becomes null in [Saved] — any value that is neither a
     * matrix nor a vector, so `as?` turns it into an empty slot and "empty" stays distinct from
     * "unreadable" (null) while reading.
     */
    private val EMPTY: Value = Memory.ZERO
}
