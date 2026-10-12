package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The thirteen priority levels of research 02 §12, an edge case at a time. Each row is keys typed
 * in Natural display (unless the third element says LineIO) and the tree they must parse to.
 */
class PriorityTest {
    private val levels = listOf(
        // 7 omitted × binds tighter than ÷ (research 02 §7.1's INFERRED consequence).
        "1 ÷ 2 π" to "(/ 1 (imp 2 pi))",
        "6 ÷ 2 ( 1 + 2 )" to "(/ 6 (imp 2 (+ 1 2)))",
        "2 π x²" to "(imp 2 (square pi))",
        "2 A B" to "(imp (imp 2 A) B)",
        "2 sin 3 0 ) cos 6 0 )" to "(imp (imp 2 (sin 30)) (cos 60))",
        // 5 (−) is looser than postfix and fractions, tighter than everything binary.
        "(−) 2 x²" to "(neg (square 2))",
        "2 × (−) 3" to "(* 2 (neg 3))",
        "(−) (−) 2" to "(neg (neg 2))",
        "(−) 2 π" to "(imp (neg 2) pi)",
        // 11 and 10 associate left to right.
        "1 + 2 × 3" to "(+ 1 (* 2 3))",
        "1 − 2 − 3" to "(- (- 1 2) 3)",
        "2 × 3 ÷ 4" to "(/ (* 2 3) 4)",
        "8 ÷ 4 ÷R 3" to "(/R (/ 8 4) 3)",
        // 8 nPr nCr ∠ sit between omitted × and ×.
        "1 0 nPr 2 × 3" to "(* (P 10 2) 3)",
        "2 × 1 0 nCr 2" to "(* 2 (C 10 2))",
        "2 A nPr 3" to "(P (imp 2 A) 3)",
        "2 ∠ 3 0 + 1" to "(+ (∠ 2 30) 1)",
        // 9 Dot sits between ∠ and ×.
        "VctA · VctB × 2" to "(* (dot VctA VctB) 2)",
        "VctA + VctB · VctC" to "(+ VctA (dot VctB VctC))",
        // 12 and, 13 or xor xnor: the loosest of all.
        "1 and 2 or 3" to "(or (and 1 2) 3)",
        "1 or 2 and 3" to "(or 1 (and 2 3))",
        "1 + 2 and 3" to "(and (+ 1 2) 3)",
        "1 xor 2 xnor 3" to "(xnor (xor 1 2) 3)",
        // 3 postfix chains left to right, and % is postfix.
        "2 0 % x²" to "(square (percent 20))",
        "3 ! x²" to "(square (factorial 3))",
        "9 0 deg" to "(deg 90)",
        "( 2 x^ 3 ▶ ) x^ 2" to "(^ (^ 2 3) 2)",
        "2 x² ◀ x²" to "(square (square 2))",
        // 6 conversions and estimates: looser than (−), tighter than omitted ×.
        "2 A CONV02" to "(imp 2 (conv2 A))",
        "2 A x̂" to "(imp 2 (est_x A))",
        "(−) 3 1 CONV38" to "(conv38 (neg 31))",
    )

    private val lineLevels = listOf(
        // 4 fractions: looser than postfix, tighter than (−) and omitted ×.
        "2 FRAC 3 x²" to "(frac 2 (square 3))",
        "(−) 2 FRAC 3" to "(neg (frac 2 3))",
        "2 FRAC 3 π" to "(imp (frac 2 3) pi)",
        "1 FRAC 2 FRAC 3 x²" to "(mixed 1 2 (square 3))",
        "2 ^( 3 ) ^( 2 )" to "(^ (^ 2 3) 2)",
        "2 ⁿ√ 6 4 ) x²" to "(square (root 2 64))",
        // 7 omitted × before a base-n prefix: "2 × h123" ([S570] p.E-11, INFERRED for the ZA).
        "2 h 1 2 3" to "(imp 2 hex:123)",
        "1 0 b 1 0 1 + 1" to "(+ (imp 10 bin:101) 1)",
    )

    @Test
    fun `every level binds as the priority table says in Natural display`() {
        for ((keys, expected) in levels) assertEquals(expected, tree(keys), keys)
    }

    @Test
    fun `fractions and powers bind as the priority table says in LineIO`() {
        for ((keys, expected) in lineLevels) assertEquals(expected, tree(keys, natural = false), keys)
    }

    private val literals = listOf(
        "1 ×10 3" to "1e3",
        "×10 3" to "e3",
        "1 , 5 ×10 (−) 2" to "1,5e-2",
        ", 5" to ",5",
        "5 ," to "5,",
        "0 , 0 0 1 2 5" to "0,00125",
        "2 DMS 3 0 DMS" to "(dms 2 30)",
        "(−) 2 DMS 3 0 DMS" to "(neg (dms 2 30))",
        "( 1 + 1 ) DMS" to "(dms (+ 1 1))",
        "2 DMS 3 0 DMS × 2" to "(* (dms 2 30) 2)",
    )

    @Test
    fun `number literals keep their digits comma and exponent as typed`() {
        for ((keys, expected) in literals) assertEquals(expected, tree(keys), keys)
    }

    private val statements = listOf(
        "Y EQ X + 5 ; Y" to "(= Y (+ X 5) ;Y)",
        "X x² + 2 X − 2 ; X" to "(= (- (+ (square X) (imp 2 X)) 2) _ ;X)",
        "A EQ B + C" to "(= A (+ B C))",
        "X + Y : X ( X + Y )" to "(: (+ X Y) (imp X (+ X Y)))",
        "1 : 2 : 3" to "(: 1 2 3)",
        "( ( 1 + 2" to "(+ 1 2)",
        "sin ( 3 0" to "(sin 30)",
        "√ ( 2 + 3 ▶ × 2" to "(* (sqrt (+ 2 3)) 2)",
        "1 + Abs ( 1 − 5 ▶ + 1" to "(+ (+ 1 (abs (- 1 5))) 1)",
    )

    @Test
    fun `statements equations and omitted closing brackets`() {
        for ((keys, expected) in statements) assertEquals(expected, tree(keys), keys)
    }

    /**
     * SOLVE reads a trailing `;V` as the solution variable even when a bracket before it was
     * never closed ([S570] p.E-29, with the ZA's `;` for its `,`): `Y=X×log(2;Y` is log₁₀2 solved
     * for Y, while the closed `Y=X×log(2;Y)` is log₂Y solved for X. Outside SOLVE the same `;Y`
     * is log's second argument, which is what CALC and `=` see.
     */
    private val solves = listOf(
        "Y EQ X × log 2 ; Y" to "(= Y (* X (log 2)) ;Y)",
        "Y EQ X × log 2 ; Y )" to "(= Y (* X (log 2 Y)))",
        "Y EQ X + 5 ; Y" to "(= Y (+ X 5) ;Y)",
        "Y EQ ( X + 5 ; Y" to "(= Y (+ X 5) ;Y)",
        "X x² − 2 ; X" to "(= (- (square X) 2) _ ;X)",
        "X x² − 6 X EQ 0" to "(= (- (square X) (imp 6 X)) 0)",
    )

    @Test
    fun `SOLVE takes a trailing variable even after an unclosed bracket`() {
        for ((keys, expected) in solves) {
            val ok = assertIs<ParseResult.Ok>(parse(Keys.type(keys).expr, solve = true), keys)
            assertEquals(expected, show(ok.node), keys)
        }
        assertEquals("(= Y (* X (log 2 Y)))", tree("Y EQ X × log 2 ; Y"))
        val empty = assertIs<ParseResult.Failure>(parse(Keys.type("Y EQ ; X").expr, solve = true))
        assertEquals(Cursor(index = 2), empty.at)
    }
}
