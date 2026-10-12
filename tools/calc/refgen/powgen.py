"""Reference values for DecPowers' rational-exponent path (review pass).

Exact where the result is exact: x^(p/q) computed as an exact rational root when it terminates,
otherwise at 200 digits; then ONE rounding to 15 significant digits, ROUND_HALF_UP.
Negative bases: (-a)^(p/q) with q odd is (-1)^p * a^(p/q).
"""
from decimal import Decimal as D, getcontext, localcontext, ROUND_HALF_UP
from fractions import Fraction

getcontext().prec = 200


def round15(v):
    if v == 0 or abs(v) < D("1e-99"):
        return "0"
    with localcontext() as c:
        c.prec = 15
        c.rounding = ROUND_HALF_UP
        r = +v
    return "{:E}".format(r).replace("E+", "E")


def power(x, y):
    fx = Fraction(D(x))
    fy = Fraction(D(y))
    sign = 1
    if fx < 0:
        assert fy.denominator % 2 == 1, "even denominator"
        sign = -1 if fy.numerator % 2 else 1
        fx = -fx
    # exact attempt: is fx^(p/q) a terminating decimal? try integer root of fx^p
    p, q = fy.numerator, fy.denominator
    base = fx ** abs(p)
    num, den = base.numerator, base.denominator

    def iroot(n, k):
        lo, hi = 0, 1
        while hi ** k <= n:
            hi *= 2
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if mid ** k <= n:
                lo = mid
            else:
                hi = mid
        return lo

    rn, rd = iroot(num, q), iroot(den, q)
    if rn ** q == num and rd ** q == den:
        exact = Fraction(rn, rd)
        if p < 0:
            exact = 1 / exact
        v = D(exact.numerator) / D(exact.denominator)
    else:
        v = (D(fx.numerator) / D(fx.denominator)) ** (D(p) / D(q))
    return sign * v


CASES = [
    ("10001000025", "1.5"), ("625", "5.5"), ("16", "-5.5"), ("2.25", "1.5"),
    ("-32", "0.2"), ("-8", "0.4"), ("-1", "0.04"), ("-243", "-0.2"), ("-32", "1.2"),
    ("-2", "0.008"), ("-7.5", "0.6"), ("2", "0.5"), ("123.456", "0.25"),
    ("0.0001", "0.75"), ("1.00000000000001", "2.5"), ("9.87654321", "-1.25"),
]

for x, y in CASES:
    print('        Pow("%s", "%s", "%s"),' % (x, y, round15(power(x, y))))
