"""Seeded random sweep for DecMath (research 07 T11 in spirit): random 15-digit arguments,
answers from refgen's Python decimal implementations at 130 digits, rounded once to 15 digits
ROUND_HALF_UP."""
import random
from decimal import Decimal as D
import refgen

rng = random.Random(1103)


def dec15(lo_exp, hi_exp, negative_ok=True):
    digits = str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(14))
    e = rng.randint(lo_exp, hi_exp)
    sign = "-" if negative_ok and rng.random() < 0.5 else ""
    return D(sign + digits[0] + "." + digits[1:] + "E" + str(e))


def uniform(lo, hi):
    v = D(rng.uniform(lo, hi))
    with refgen.localcontext() as c:
        c.prec = 15
        return +v


GEN = {
    "exp": lambda: uniform(-227, 230),
    "ln": lambda: dec15(-99, 99, False),
    "log10": lambda: dec15(-99, 99, False),
    "sqrt": lambda: dec15(-99, 99, False),
    "cbrt": lambda: dec15(-99, 99),
    "sin": lambda: dec15(-8, 9),
    "cos": lambda: dec15(-8, 9),
    "tan": lambda: dec15(-8, 9),
    "atan": lambda: dec15(-20, 20),
    "asin": lambda: dec15(-12, -1),
    "sinh": lambda: uniform(-230, 230),
    "tanh": lambda: uniform(-25, 25),
    "asinh": lambda: dec15(-30, 30),
}

N = 30
for name, gen in GEN.items():
    for _ in range(N):
        a = gen()
        arg = "{:E}".format(a).replace("E+", "E")
        print('        Row("%s", "%s", "%s"),' % (name, arg, refgen.round15(refgen.FUNCS[name](a))))
for _ in range(N):
    x = dec15(-3, 2, False)
    y = uniform(-20, 20)
    xs = "{:E}".format(x).replace("E+", "E")
    ys = "{:E}".format(y).replace("E+", "E")
    print('        Row("pow", "%s;%s", "%s"),' % (xs, ys, refgen.round15(x ** y)))
