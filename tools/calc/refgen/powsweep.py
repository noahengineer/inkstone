import random, sys
sys.path.insert(0, r"C:\Users\NoahB\AppData\Local\Temp\claude\C--Users-NoahB-OneDrive-Documents-Dev-Claude-Notes-app\e45f9470-8338-45c8-abbd-1c04b4985d26\scratchpad\calc-dev\refgen")
import importlib.util
spec = importlib.util.spec_from_file_location("powgen_mod", sys.path[0] + r"\powgen.py")
from decimal import Decimal as D, getcontext
from fractions import Fraction
src = open(sys.path[0] + r"\powgen.py", encoding="utf8").read().split("CASES = [")[0]
ns = {}
exec(src, ns)
power, round15 = ns["power"], ns["round15"]
rng = random.Random(20261004)
qs = [2, 4, 5, 8, 10, 16, 20, 25, 32, 40]
out = []
while len(out) < 40:
    q = rng.choice(qs)
    p = rng.randint(-120, 120)
    if p == 0 or Fraction(p, q).denominator != q:
        continue
    y = D(p) / D(q)
    n = rng.randint(1, 15)
    digits = str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(n - 1))
    e = rng.randint(-30, 30)
    x = D(digits + "E" + str(e - n + 1))
    if q % 2 == 1 and rng.random() < 0.4:
        x = -x
    if x > 0 or True:
        lg = abs(y * (abs(x)).log10())
    if lg > 95:
        continue
    yt = "{:E}".format(y.normalize()).replace("E+", "E")
    xt = "{:E}".format(x).replace("E+", "E")
    out.append('        Pow("%s", "%s", "%s"),' % (xt, yt, round15(power(xt, yt))))
print("\n".join(out))
