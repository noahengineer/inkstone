"""Reference values for DecMath, from Python's decimal module at 120 digits.

Built-ins (exp, ln, log10, sqrt, power) are correctly rounded at the context precision; trig is
Machin pi plus Taylor series, written here independently of the Kotlin code. Each value is then
rounded ONCE to 15 significant digits, ROUND_HALF_UP (= half away from zero), and anything below
1e-99 in magnitude becomes 0 (CALCULATOR.md section 3).
"""
from decimal import Decimal as D, getcontext, localcontext, ROUND_HALF_UP, ROUND_HALF_EVEN

getcontext().prec = 130
EPS = D(10) ** -125


def atan_inv(n):
    x = D(1) / n
    n2 = n * n
    s = x
    term = x
    k = 1
    while True:
        term = term / n2
        t = term / (2 * k + 1)
        if abs(t) < EPS:
            break
        s = s - t if k % 2 else s + t
        k += 1
    return s


PI = 16 * atan_inv(5) - 4 * atan_inv(239)


def taylor_sin(x):
    s = x
    term = x
    x2 = x * x
    k = 1
    while abs(term) > EPS:
        term = -term * x2 / ((2 * k) * (2 * k + 1))
        s += term
        k += 1
    return s


def taylor_cos(x):
    s = D(1)
    term = D(1)
    x2 = x * x
    k = 1
    while abs(term) > EPS:
        term = -term * x2 / ((2 * k - 1) * (2 * k))
        s += term
        k += 1
    return s


def reduce(x):
    two_pi = 2 * PI
    k = (x / two_pi).to_integral_value()
    return x - k * two_pi


def sin(x):
    return taylor_sin(reduce(x))


def cos(x):
    return taylor_cos(reduce(x))


def tan(x):
    r = reduce(x)
    return taylor_sin(r) / taylor_cos(r)


def atan(x):
    # Euler's series: atan x = sum 2^(2n) (n!)^2 / (2n+1)! * x^(2n+1) / (1+x^2)^(n+1)
    if x < 0:
        return -atan(-x)
    if x > 1:
        return PI / 2 - atan(1 / x)
    y = x * x / (1 + x * x)
    term = x / (1 + x * x)
    s = term
    n = 1
    while abs(term) > EPS:
        term = term * y * (2 * n) / (2 * n + 1)
        s += term
        n += 1
    return s


def asin(x):
    if abs(x) == 1:
        return PI / 2 * (1 if x > 0 else -1)
    return atan(x / (1 - x * x).sqrt())


def acos(x):
    return PI / 2 - asin(x)


def sinh(x):
    return (x.exp() - (-x).exp()) / 2


def cosh(x):
    return (x.exp() + (-x).exp()) / 2


def tanh(x):
    e = (2 * x).exp()
    return (e - 1) / (e + 1)


def asinh(x):
    if x < 0:
        return -asinh(-x)
    return (x + (x * x + 1).sqrt()).ln()


def acosh(x):
    return (x + (x * x - 1).sqrt()).ln()


def atanh(x):
    return ((1 + x) / (1 - x)).ln() / 2


def cbrt(x):
    if x < 0:
        return -cbrt(-x)
    return x ** (D(1) / 3)


def round15(v):
    if v == 0 or abs(v) < D("1e-99"):
        return "0"
    with localcontext() as c:
        c.prec = 15
        c.rounding = ROUND_HALF_UP
        r = +v
    return "{:E}".format(r).replace("E+", "E")


ARGS = {
    "exp": ["1", "-1", "0.5", "2.30258509299405", "100", "-100", "230", "-227", "1E-10",
            "12.345678901234", "0.000123456789", "-50.5", "7"],
    "ln": ["2", "10", "0.5", "1.00000000000001", "0.999999999999999", "123456789.012345",
           "1E-99", "9.99999999999999E99", "3", "1.5", "0.001", "2.71828182845904"],
    "log10": ["2", "3", "0.002", "7.5E50", "1.00000000000001", "99"],
    "sqrt": ["2", "3", "0.5", "1E-99", "99999999999999.9", "123.456", "10", "8.5E99"],
    "cbrt": ["2", "-27", "10", "0.001", "1E-98", "-5.5", "1E99"],
    "tenPow": ["0.5", "-0.5", "2.5", "99.9", "0.30102999566398", "-98.7", "1E-10"],
    "sin": ["1", "0.5", "2", "3", "-1", "10", "100", "100000", "10000000000", "157079632.6",
            "1E-10", "0.785398163397448", "1.5707963267949", "3.14159265358979", "3.1415926535898",
            "6.28318530717959", "1E50", "-123456.789"],
    "cos": ["1", "0.5", "2", "3", "-1", "10", "100", "100000", "10000000000", "157079632.6",
            "1E-10", "1.5707963267949", "3.1415926535898", "1E50"],
    "tan": ["1", "0.5", "2", "-1", "10", "1000", "1.5707963267949", "1.57079632679", "1E-10",
            "3.1415926535898", "10000000000"],
    "atan": ["1", "0.5", "2", "-3", "10000000000", "1E-10", "1E99", "0.1", "-0.999999999999999"],
    "asin": ["0.5", "-0.5", "0.999999999999999", "1E-10", "0.1", "1", "-1"],
    "acos": ["0.5", "-0.5", "0.999999999999999", "1E-10", "0.1", "1", "-1", "0"],
    "sinh": ["1", "0.5", "-2", "10", "100", "230", "1E-10", "20", "0.999"],
    "cosh": ["1", "0.5", "-2", "10", "100", "230", "1E-10", "20"],
    "tanh": ["1", "0.5", "-2", "10", "1E-10", "20", "0.999", "50"],
    "asinh": ["1", "0.5", "-2", "10000000000", "1E-10", "1E99"],
    "acosh": ["1.5", "2", "10", "1E50", "1.00000000000001", "1"],
    "atanh": ["0.5", "-0.5", "0.999999999999999", "1E-10", "0.1"],
}

POW_ARGS = [("2", "0.5"), ("1.5", "2.5"), ("10", "-0.5"), ("0.5", "100.5"), ("123.456", "7.89"),
            ("2", "331"), ("-2", "331"), ("-3", "4"), ("1.0000001", "1000000"), ("7", "-3"),
            ("0.3", "-50"), ("123.45675", "2"), ("9.87654321", "0.123456789")]

FUNCS = {"exp": lambda x: x.exp(), "ln": lambda x: x.ln(), "log10": lambda x: x.log10(),
         "sqrt": lambda x: x.sqrt(), "cbrt": cbrt, "tenPow": lambda x: D(10) ** x, "sin": sin,
         "cos": cos, "tan": tan, "atan": atan, "asin": asin, "acos": acos, "sinh": sinh,
         "cosh": cosh, "tanh": tanh, "asinh": asinh, "acosh": acosh, "atanh": atanh}

if __name__ == "__main__":
    print("// constants")
    print("pi", round15(PI), str(+PI)[:60])
    print("ln2", round15(D(2).ln()), str(D(2).ln())[:60])
    print("ln10", round15(D(10).ln()), str(D(10).ln())[:60])
    print("sqrt2", round15(D(2).sqrt()), str(D(2).sqrt())[:60])
    print("e", round15(D(1).exp()), str(D(1).exp())[:60])
    print("sin1", round15(sin(D(1))), str(sin(D(1)))[:60])
    print("cos1", round15(cos(D(1))), str(cos(D(1)))[:60])
    print("atan1", round15(atan(D(1))), str(atan(D(1)))[:60])
    for name, args in ARGS.items():
        for a in args:
            print('            Row("%s", "%s", "%s"),' % (name, a, round15(FUNCS[name](D(a)))))
    for x, y in POW_ARGS:
        print('            Pow("%s", "%s", "%s"),' % (x, y, round15(D(x) ** D(y))))
