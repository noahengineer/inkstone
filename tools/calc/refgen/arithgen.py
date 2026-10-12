"""Random Dec arithmetic cases, answered by Python's decimal (exact, then ONE rounding to 15
significant digits ROUND_HALF_UP; |x| < 1e-99 -> 0; overflow kept)."""
import random
from decimal import Decimal as D, getcontext, localcontext, ROUND_HALF_UP

getcontext().prec = 200
getcontext().Emax = 999999
getcontext().Emin = -999999
rng = random.Random(20261003)


def operand():
    shape = rng.randrange(6)
    n = rng.randint(1, 15)
    if shape == 0:
        digits = "9" * n
    elif shape == 1:
        digits = "1" + "0" * (n - 1)
    elif shape == 2:
        digits = "5" * n
    else:
        digits = str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(n - 1))
    exp = rng.randint(-20, 20) if rng.random() < 0.8 else rng.randint(-99, 99)
    sign = "-" if rng.random() < 0.4 else ""
    return D(sign + digits + "E" + str(exp - n + 1))


def r15(v):
    if v == 0 or abs(v) < D("1e-99"):
        return "0"
    with localcontext() as c:
        c.prec = 15
        c.rounding = ROUND_HALF_UP
        c.Emax = 999999
        c.Emin = -999999
        r = +v
    return "{:E}".format(r).replace("E+", "E")


def lit(v):
    return "{:E}".format(v).replace("E+", "E")


ops = "+-*/"
for i in range(400):
    a = operand()
    b = operand()
    op = ops[i % 4]
    # Make a fraction of the +/- cases cancel or nearly cancel, where rounding is hardest.
    if op in "+-" and rng.random() < 0.3:
        b = (-a if op == "+" else a) + operand() * D("1E-" + str(rng.randint(5, 18)))
        with localcontext() as c:
            c.prec = 15
            c.rounding = ROUND_HALF_UP
            b = +b
    if op == "+":
        v = a + b
    elif op == "-":
        v = a - b
    elif op == "*":
        v = a * b
    else:
        v = a / b
    print('        Case("%s", \'%s\', "%s", "%s"),' % (lit(a), op, lit(b), r15(v)))
