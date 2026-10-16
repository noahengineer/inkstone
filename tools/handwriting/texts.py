"""What the synthetic writers write (docs/HANDWRITING-SYNTHESIS.md §7.2).

The words below were written for this file: common English, the vocabulary of a school's science
notes, and nothing copied from a corpus, so the text carries no licence of its own. Pseudo-words
fill in the letters ordinary English under-uses (q, z, x, j), capitals, digits and punctuation, so
every character of ALPHABET is seen often enough to be learned.
"""

from __future__ import annotations

import random

# The model's alphabet (§4.4: index = position). Position 0 is the space.
ALPHABET = " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,'\"-()?!:;/+=%&"

COMMON = """
the of and to in is it you that he was for on are with as his they be at one have this from or had
by not word but what some we can out other were all there when up use your how said an each she
which do their time if will way about many then them write would like so these her long make thing
see him two has look more day could go come did number sound no most people my over know water than
call first who may down side been now find any new work part take get place made live where after
back little only round man year came show every good me give our under name very through just form
sentence great think say help low line differ turn cause much mean before move right boy old too
same tell does set three want air well also play small end put home read hand port large spell add
even land here must big high such follow act why ask men change went light kind off need house
picture try us again animal point mother world near build self earth father head stand own page
should country found answer school grow study still learn plant cover food sun four between state
keep eye never last let thought city tree cross farm hard start might story saw far sea draw left
late run while press close night real life few north open seem together next white children begin
got walk example ease paper group always music those both mark often letter until mile river car
feet care second book carry took science eat room friend began idea fish mountain stop once base
hear horse cut sure watch colour face wood main enough plain girl usual young ready above ever red
list though feel talk bird soon body dog family direct pose leave song measure door product black
short numeral class wind question happen complete ship area half rock order fire south problem
piece told knew pass since top whole king space heard best hour better true during hundred five
remember step early hold west ground interest reach fast verb sing listen six table travel less
morning ten simple several vowel toward war lay against pattern slow centre love person money serve
appear road map rain rule govern pull cold notice voice unit power town fine certain fly fall lead
cry dark machine note wait plan figure star box noun field rest correct able pound done beauty drive
stood contain front teach week final gave green oh quick develop ocean warm free minute strong
special mind behind clear tail produce fact street inch multiply nothing course stay wheel full force
blue object decide surface deep moon island foot system busy test record boat common gold possible
plane stead dry wonder laugh thousand ago ran check game shape equate hot miss brought heat snow tire
bring yes distant fill east paint language among
"""

SCIENCE = """
energy charge current voltage resistance circuit series parallel ohm ampere volt watt joule power
electron proton neutron atom ion cation anion molecule compound element mixture solution solvent
solute reaction product reactant catalyst enzyme acid base salt alkali oxide oxygen hydrogen carbon
nitrogen sodium chloride calcium magnesium potassium iron copper zinc silver gold aluminium sulfur
phosphorus helium neon argon bond covalent ionic metallic valence electronegativity periodic table
group period mass moles molar volume density pressure temperature kelvin celsius heat work force
newton acceleration velocity speed displacement distance momentum impulse friction gravity weight
field magnetic electric induction transformer motor generator coil flux wave frequency amplitude
wavelength period refraction reflection lens mirror image focal spectrum photon quantum nucleus
isotope decay radiation alpha beta gamma half life equilibrium rate concentration titration
indicator oxidation reduction redox electrolysis anode cathode cell battery terminal graph gradient
intercept axis variable constant formula equation calculate measure observe conclude hypothesis
method result experiment data error uncertainty precision accuracy average mean median range
function derivative integral limit vector scalar matrix probability ratio percentage fraction
""".split()

WORDS = sorted(set(COMMON.split()) | set(SCIENCE))

# Letters weighted roughly as English uses them, then flattened a little so q, z, x and j are seen.
_LETTERS = "etaoinshrdlcumwfgypbvkjxqz"
_WEIGHTS = [12, 9, 8, 7.5, 7, 6.7, 6.3, 6, 6, 4.3, 4, 2.8, 2.8, 2.4, 2.4, 2.2, 2, 2, 2, 1.5, 1.2, 1, 1, 1, 1, 1]
_PUNCT_AFTER = [",", ".", "?", "!", ":", ";"]


def pseudo_word(rng: random.Random) -> str:
    """A pronounceable-ish string drawn from flattened English letter frequencies."""
    n = rng.choice([2, 3, 3, 4, 4, 5, 5, 6, 7, 8])
    return "".join(rng.choices(_LETTERS, weights=_WEIGHTS, k=n))


def number(rng: random.Random) -> str:
    kind = rng.random()
    if kind < 0.35:
        return str(rng.randint(0, 99))
    if kind < 0.55:
        return str(rng.randint(100, 9999))
    if kind < 0.75:
        return f"{rng.randint(0, 99)}.{rng.randint(0, 99)}"
    if kind < 0.85:
        return f"{rng.randint(1, 100)}%"
    if kind < 0.93:
        return f"{rng.randint(1, 9)}/{rng.randint(2, 12)}"
    return f"{rng.randint(1, 20)}+{rng.randint(1, 20)}={rng.randint(2, 40)}"


def token(rng: random.Random) -> str:
    """One word as a writer might write it: usually real, sometimes capitalised, numeric or punctuated."""
    r = rng.random()
    if r < 0.08:
        return number(rng)
    w = pseudo_word(rng) if r < 0.25 else rng.choice(WORDS)
    c = rng.random()
    if c < 0.18:
        w = w[0].upper() + w[1:]
    elif c < 0.21:
        w = w.upper()[:5]
    p = rng.random()
    if p < 0.14:
        w += rng.choice(_PUNCT_AFTER)
    elif p < 0.16:
        w = f"({w})"
    elif p < 0.18:
        w = f"\"{w}\""
    elif p < 0.20:
        w += "'s"
    elif p < 0.22 and len(w) > 3:
        cut = rng.randint(1, len(w) - 1)
        w = w[:cut] + "-" + w[cut:]
    elif p < 0.23:
        w = w + " &"
    return w


def phrase(rng: random.Random, max_words: int = 3) -> str:
    """One to `max_words` words, the unit a training sequence is made of."""
    n = rng.choices(range(1, max_words + 1), weights=[5, 3, 2][:max_words], k=1)[0]
    text = " ".join(token(rng) for _ in range(n))
    return "".join(ch for ch in text if ch in ALPHABET).strip() or "a"


# === Corpus v2: v1's alphabet plus maths, and maths-weighted text (docs §18.10 item 6) =============
#
# Everything above this line is corpus v1 and must not change: data/synth-v1, the shipped model and
# test_pipeline's pinned digests depend on it. v2 only ever adds.
#
# The user writes school maths and science and asked for numbers above all -- "especially random
# numbers and decimals". v1 saw 8 % numbers, every decimal NN.NN, no leading zeros, negatives or long
# decimals, and none of the symbols below. About half of v2's words are numeric or mathematical (a
# third was the aim; FOCUS_WEIGHTS says why it is more); the rest are v1's own tokens, as many of them
# as v1's whole corpus had. Every formula, unit and species below was written for this file.

# Appended, never interleaved, so v1's indices keep their meaning. All in the BMP: Kotlin indexes the
# alphabet by Char.
NEW_V2 = "^*<>[]{}|_~×÷±√π°≤≥≠≈∞θΔμΩλ"
ALPHABET_V2 = ALPHABET + NEW_V2

# A whitespace-separated word counts as numeric or mathematical when it holds one of these, or a digit.
MATHS_CHARS = set(NEW_V2) | set("=+%/")

DIGITS = "0123456789"
VARIABLES = "xxxyyyzabcnmtkpqrsuvfghEFVIRPQTWLCAm"
GREEK_VARS = ["θ", "λ", "μ", "π", "Δx", "Δt", "Ω"]
UNITS = [
    "m", "kg", "g", "s", "N", "J", "W", "V", "A", "Hz", "mol", "K", "cm", "mm", "km", "nm", "kJ", "kW",
    "kPa", "Pa", "eV", "ms", "m/s", "m/s^2", "N/m", "J/kg", "g/mol", "mol/L", "°C", "°", "Ω", "kΩ",
    "μA", "μF", "μm", "μs", "mA", "MHz", "kN", "cm^3", "dm^3", "m^2", "kg/m^3",
]
FORMULAS = [
    "y=mx+c", "E=mc^2", "F=ma", "V=IR", "P=IV", "P=VI", "a^2+b^2=c^2", "v=u+at", "v^2=u^2+2as", "W=Fd",
    "p=mv", "Q=It", "E=Pt", "n=m/M", "c=n/V", "pV=nRT", "F=kx", "v=fλ", "λ=v/f", "f=1/T", "E=hf",
    "T=2π√(l/g)", "A=πr^2", "C=2πr", "V=4/3πr^3", "y=ax^2+bx+c", "sin^2θ+cos^2θ=1", "tanθ=sinθ/cosθ",
    "Q=mcΔT", "Ek=0.5mv^2", "Ep=mgh", "g=9.81", "c=3×10^8", "h=6.63×10^-34", "e=1.6×10^-19",
    "NA=6.02×10^23", "R=8.31", "a=Δv/Δt", "v=Δs/Δt", "m=Δy/Δx", "I=V/R", "R=V/I", "μ=F/N", "F=μN",
    "Z=√(R^2+X^2)", "x=-b/2a", "b^2-4ac≥0", "|a+b|≤|a|+|b|", "f(x)=x^2", "y=2x+3", "2x+3", "3x-5=7",
    "x^2-4=0", "(x+1)^2", "y=x^3", "e^x", "ln(x)", "log10", "10^-3", "x_1+x_2", "a_n=a_1+(n-1)d",
    "S_n", "u_n", "E_k", "F_net", "v_0", "T_1", "{x|x>0}", "[0, 1]", "[H+]", "[OH-]", "λ_max",
    "ΔH=-286", "ΔG<0", "θ=30°", "Δx", "2π", "√2", "x≥3", "x≤-1", "x≠0", "π≈3.14", "g≈9.8",
]
SPECIES = [
    "CaCO3", "H2O", "NO2", "CO2", "H2SO4", "NaCl", "HCl", "NaOH", "CH4", "C6H12O6", "Fe2O3", "NH3",
    "KMnO4", "MgO", "CuSO4", "AgNO3", "C2H5OH", "O2", "N2", "Cl2", "H2", "CaO", "SO2", "HNO3", "K2O",
    "Al2O3", "ZnCl2", "CH3COOH", "C3H8", "Na+", "Cl-", "OH-", "H+", "Cu2+", "Fe3+", "SO4^2-", "CO3^2-",
    "NH4+", "Mg2+", "NO3-",
]
REACTIONS = [
    "2H2+O2", "2H2 + O2 -> 2H2O", "CaCO3 -> CaO + CO2", "CH4+2O2", "N2+3H2", "HCl+NaOH",
    "Zn+2HCl", "2Mg+O2", "C+O2", "H+ + OH-", "2Na+Cl2", "Fe2O3+3CO",
]


def _digits(rng, n):
    return "".join(rng.choice(DIGITS) for _ in range(n))


def integer(rng, max_digits: int = 5) -> str:
    """0 to 99999, every length about equally likely rather than every value."""
    d = rng.choices(range(1, max_digits + 1), weights=[3, 3, 2.5, 2, 1.5][:max_digits], k=1)[0]
    return str(rng.randint(0, 9)) if d == 1 else str(rng.randint(10 ** (d - 1), 10 ** d - 1))


def decimal(rng, places: int | None = None) -> str:
    """1-5 places, often below one (0.5), often with zeros straight after the point (0.05, 0.0375)."""
    places = places or rng.choices([1, 2, 3, 4, 5], weights=[3, 3, 2.5, 1.6, 1.1], k=1)[0]
    r = rng.random()
    whole = ("0" if r < 0.4 else str(rng.randint(1, 9)) if r < 0.75
             else str(rng.randint(10, 99)) if r < 0.92 else str(rng.randint(100, 9999)))
    frac = _digits(rng, places)
    if whole == "0" and places > 1 and rng.random() < 0.45:
        z = rng.randint(1, places - 1)
        frac = "0" * z + frac[z:]
    if frac[-1] == "0" and rng.random() < 0.6:
        frac = frac[:-1] + rng.choice("123456789")
    return f"{whole}.{frac}"


def small(rng) -> str:
    """An operand: an integer or a decimal, mostly short."""
    r = rng.random()
    if r < 0.4:
        return str(rng.randint(0, 20)) if rng.random() < 0.7 else integer(rng, 3)
    return decimal(rng, rng.choice([1, 1, 2, 2, 3]))


def signed(rng) -> str:
    s = small(rng)
    return s if s.strip("0.") == "" or rng.random() < 0.75 else "-" + s


def fraction(rng) -> str:
    s = f"{rng.randint(1, 15)}/{rng.randint(2, 32)}"
    return "-" + s if rng.random() < 0.15 else s


def scientific(rng) -> str:
    """6.02×10^23, 1.6×10^-19, 3e8, 2.5e-3, 10^-6."""
    mant = str(rng.randint(1, 9)) if rng.random() < 0.3 else f"{rng.randint(1, 9)}.{_digits(rng, rng.choice([1, 2, 2, 3]))}"
    exp = str(rng.randint(-34, 34) or 1)
    r = rng.random()
    if r < 0.6:
        return f"{mant}×10^{exp}"
    if r < 0.85:
        return f"{mant}e{exp}"
    return f"10^{exp}"


def number_v2(rng) -> str:
    """One number as written in science notes: the heart of v2."""
    k = rng.random()
    if k < 0.19:
        return integer(rng)
    if k < 0.47:
        return decimal(rng)
    if k < 0.59:
        s = decimal(rng) if rng.random() < 0.6 else integer(rng, 3)
        return "-" + (s if s != "0" else "1")
    if k < 0.70:
        return (decimal(rng, rng.choice([1, 1, 2])) if rng.random() < 0.4 else str(rng.randint(0, 100))) + "%"
    if k < 0.78:
        return fraction(rng)
    if k < 0.90:
        return scientific(rng)
    return quantity(rng, glued=True)


def quantity(rng, glued: bool | None = None) -> str:
    """A number and its unit, glued (5kg) or spaced (9.81 m/s^2)."""
    n = number_v2(rng) if rng.random() < 0.25 else (decimal(rng) if rng.random() < 0.6 else integer(rng, 4))
    if "%" in n or "/" in n:
        n = decimal(rng)
    glued = rng.random() < 0.5 if glued is None else glued
    return f"{n}{rng.choice(UNITS)}" if glued else f"{n} {rng.choice(UNITS)}"


def _fmt(value: float) -> str:
    s = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def _value(s: str):
    try:
        return float(s)
    except ValueError:
        return None


def spaced_equation(rng) -> str:
    """Operators as words of their own, the way the user writes: `x = 0.25 + 1.75 - 3/8 = 1.625`."""
    ops = ["+", "-", "×", "÷", "/", "*"]
    weights = [5, 4, 2.5, 1.5, 1.5, 0.8]
    terms = rng.choice([2, 2, 2, 2, 3, 3, 4])
    parts = [signed(rng)]
    value = _value(parts[0])
    for _ in range(terms - 1):
        op = rng.choices(ops, weights=weights, k=1)[0]
        operand = small(rng) if rng.random() < 0.85 else fraction(rng)
        parts += [op, operand]
        b = _value(operand)
        if value is None or b is None:
            value = None
        elif op == "+":
            value += b
        elif op == "-":
            value -= b
        elif op in "×*":
            value *= b
        else:
            value = value / b if b else None
    if terms == 2 and rng.random() < 0.25:
        parts = ["("] + parts + [")"]
        parts = [parts[0] + parts[1]] + parts[2:-2] + [parts[-2] + parts[-1]]
        follow = rng.choice(["/", "×", "+"])
        operand = small(rng)
        parts += [follow, operand]
        b = _value(operand)
        if value is not None and b is not None:
            value = value / b if follow == "/" and b else value * b if follow == "×" else value + b if follow == "+" else None
        else:
            value = None
    if rng.random() < 0.35:
        parts = [rng.choice(VARIABLES), "="] + parts
    if rng.random() < 0.6:
        parts += ["=", _fmt(value) if value is not None and abs(value) < 1e6 else small(rng)]
        if rng.random() < 0.08 and value is not None:
            parts += ["or", _fmt(value * 100) + "%"]
    return " ".join(parts)


def spaced_relation(rng) -> str:
    """x ≥ 3, a ≠ b, 0.5 ≤ p ≤ 1, π ≈ 3.14."""
    rel = rng.choice(["<", ">", "≤", "≥", "≠", "≈", "=", "≤", "≥", "<", ">"])
    lhs = rng.choice([rng.choice(VARIABLES), rng.choice(GREEK_VARS), signed(rng)])
    out = [lhs, rel, signed(rng)]
    if rel in "<≤" and rng.random() < 0.3:
        out = [signed(rng), rel] + out
    return " ".join(out)


def _v(rng):
    return rng.choice(VARIABLES)


# Small facts the focus words state, computed rather than made up, so an `=` is never followed by
# a number that is wrong: a model of handwriting does not care, but a reader of the sheets does.
def _power(r):
    a, b = r.randint(2, 9), r.randint(2, 4)
    return f"{a}^{b}={a ** b}"


def _times(r, sign="×"):
    a, b = r.randint(2, 12), r.randint(2, 12)
    return f"{a}{sign}{b}={a * b}"


def _divide(r):
    b, q = r.randint(2, 12), r.randint(2, 12)
    return f"{b * q}÷{b}={q}"


def _root(r):
    n = r.randint(2, 99)
    if r.random() < 0.4:
        k = r.randint(2, 12)
        return f"√{k * k}={k}"
    return f"√{n}≈{n ** 0.5:.{r.choice([2, 3])}f}"


def _approx_root(r):
    n = r.choice([2, 3, 5, 6, 7, 8, 10])
    return f"√{n}≈{n ** 0.5:.3f}"


# Words built around one new symbol each, so every symbol of NEW_V2 is seen often. Each entry draws a
# small, plausible piece of school maths or science that uses the symbol, usually more than once.
FOCUS = {
    "^": [lambda r: f"{_v(r)}^{r.randint(2, 4)}", lambda r: f"10^{r.randint(-9, 12)}",
          _power,
          lambda r: f"({_v(r)}+{r.randint(1, 9)})^2", lambda r: f"{_v(r)}^2+{_v(r)}^2", lambda r: "e^x",
          lambda r: f"{small(r)}×10^{r.randint(-12, 12)}"],
    "*": [lambda r: _times(r, "*"), lambda r: f"{_v(r)}*{_v(r)}",
          lambda r: f"{small(r)} * {small(r)}", lambda r: f"{r.randint(2, 9)}*{_v(r)}*{_v(r)}",
          lambda r: f"n*(n+{r.randint(1, 3)})"],
    "<": [lambda r: f"{_v(r)}<{signed(r)}", lambda r: f"0<{_v(r)}<{r.randint(1, 10)}",
          lambda r: f"{signed(r)} < {_v(r)} < {small(r)}", lambda r: f"p<0.0{r.randint(1, 5)}",
          lambda r: f"{small(r)}<{small(r)}"],
    ">": [lambda r: f"{_v(r)}>{signed(r)}", lambda r: f"{_v(r)} > {small(r)}", lambda r: f"{small(r)}>{small(r)}",
          lambda r: f"n>{r.randint(0, 99)}", lambda r: f"{r.randint(1, 9)}>{_v(r)}>{signed(r)}"],
    "[": [lambda r: f"[{signed(r)}, {signed(r)}]", lambda r: f"[{r.choice(SPECIES[29:])}]",
          lambda r: f"[{r.choice(SPECIES[:12])}]={decimal(r)}", lambda r: f"[{_v(r)}, {_v(r)}]",
          lambda r: f"[{signed(r)}, ∞)", lambda r: f"f[{_v(r)}]"],
    "{": [lambda r: "{" + ", ".join(str(r.randint(0, 20)) for _ in range(r.randint(2, 4))) + "}",
          lambda r: "{" + _v(r) + "}", lambda r: "{" + f"{_v(r)}|{_v(r)}>{r.randint(0, 9)}" + "}",
          lambda r: "{" + f"{_v(r)}, {_v(r)}" + "}"],
    "|": [lambda r: f"|{_v(r)}|", lambda r: f"|{signed(r)}|", lambda r: f"|{_v(r)}-{r.randint(1, 9)}|≤{r.randint(1, 9)}",
          lambda r: f"P(A|B)", lambda r: f"|{_v(r)}|={small(r)}", lambda r: f"|{_v(r)}-{_v(r)}|"],
    "_": [lambda r: f"{_v(r)}_{r.randint(0, 3)}", lambda r: f"{_v(r)}_n", lambda r: f"{_v(r)}_{r.randint(1, 2)}={signed(r)}",
          lambda r: f"{r.choice(['F', 'E', 'v', 'T', 'R', 'V'])}_{r.choice(['net', 'k', 'p', 'max', 'min', 'total', '0', '1', '2'])}",
          lambda r: f"{_v(r)}_{r.randint(1, 2)}+{_v(r)}_{r.randint(1, 3)}"],
    "~": [lambda r: f"~{small(r)}", lambda r: f"~{scientific(r)}", lambda r: f"{_v(r)}~{_v(r)}",
          lambda r: f"~ {integer(r, 3)}", lambda r: f"~{r.randint(1, 99)}%", lambda r: f"~{small(r)}-~{small(r)}",
          lambda r: f"{_v(r)}~N({small(r)}, {small(r)})"],
    "×": [lambda r: f"{scientific(r)}", lambda r: f"{small(r)}×{small(r)}", lambda r: f"{small(r)} × 10^{r.randint(-9, 9)}",
          _times, lambda r: f"{_v(r)}×{_v(r)}"],
    "÷": [lambda r: f"{small(r)}÷{small(r)}", lambda r: f"{r.randint(2, 99)} ÷ {r.randint(2, 12)}",
          lambda r: f"{_v(r)}÷{r.randint(2, 9)}", _divide],
    "±": [lambda r: f"±{small(r)}", lambda r: f"{decimal(r)}±{decimal(r, 1)}", lambda r: f"{_v(r)}=±{small(r)}",
          lambda r: f"{small(r)} ± {decimal(r)}", lambda r: f"±{r.randint(1, 9)}%", lambda r: f"{_v(r)}=±√{r.randint(2, 50)}",
          lambda r: f"({decimal(r)}±{decimal(r, 1)})×10^{r.randint(-6, 6)}", lambda r: f"{_v(r)}≠±{r.randint(1, 9)}"],
    "√": [lambda r: f"√{r.randint(2, 99)}", _root,
          lambda r: f"√({_v(r)}+{r.randint(1, 9)})", lambda r: f"{r.randint(2, 9)}√{r.randint(2, 7)}",
          lambda r: f"√{decimal(r)}", lambda r: f"√{_v(r)}", lambda r: f"√({_v(r)}^2+{_v(r)}^2)",
          lambda r: f"√{r.randint(2, 9)}×√{r.randint(2, 9)}", _root],
    "π": [lambda r: f"{r.randint(2, 9)}π", lambda r: "πr^2", lambda r: f"π/{r.randint(2, 6)}", lambda r: "2πr",
          lambda r: f"{r.randint(1, 5)}π/{r.randint(2, 6)}", lambda r: f"π≈3.14{r.choice(['', '1', '16', '159'])}",
          lambda r: f"π/{r.randint(2, 6)}<θ<π", lambda r: f"{r.randint(2, 4)}π-π/{r.randint(2, 6)}"],
    "°": [lambda r: f"{r.randint(0, 360)}°", lambda r: f"{signed(r)}°C", lambda r: f"{r.choice([30, 45, 60, 90, 120, 180, 270])}°",
          lambda r: f"{decimal(r, 1)}°", lambda r: f"{r.randint(0, 100)} °C", lambda r: f"θ={r.randint(0, 90)}°",
          lambda r: f"{r.randint(90, 180)}°-{r.randint(1, 89)}°", lambda r: f"{r.randint(10, 99)}°+{r.randint(10, 99)}°"],
    "≤": [lambda r: f"{_v(r)}≤{signed(r)}", lambda r: f"0≤{_v(r)}≤{r.randint(1, 10)}", lambda r: f"{_v(r)} ≤ {small(r)}",
          lambda r: f"{signed(r)}≤{_v(r)}"],
    "≥": [lambda r: f"{_v(r)}≥{signed(r)}", lambda r: f"{_v(r)} ≥ {small(r)}", lambda r: f"n≥{r.randint(0, 9)}",
          lambda r: f"{small(r)}≥{_v(r)}≥{signed(r)}"],
    "≠": [lambda r: f"{_v(r)}≠{signed(r)}", lambda r: f"{_v(r)}≠{_v(r)}", lambda r: f"{_v(r)} ≠ {small(r)}",
          lambda r: f"{small(r)}≠{small(r)}", lambda r: f"{_v(r)}≠{r.randint(0, 9)}, {_v(r)}≠{signed(r)}",
          lambda r: f"{_v(r)}≠±{r.randint(1, 9)}"],
    "≈": [lambda r: f"π≈3.14{r.choice(['', '1', '16', '159'])}", lambda r: f"{_v(r)}≈{decimal(r)}", lambda r: f"≈{small(r)}",
          _approx_root, lambda r: f"{small(r)} ≈ {small(r)}",
          lambda r: f"{_v(r)}≈{decimal(r)}≈{decimal(r, 1)}", lambda r: f"≈{scientific(r)}"],
    "∞": [lambda r: "∞", lambda r: f"[{signed(r)}, ∞)", lambda r: "-∞<x<∞", lambda r: f"{_v(r)}=∞",
          lambda r: f"(-∞, {signed(r)})", lambda r: "1/∞", lambda r: "(-∞, ∞)", lambda r: f"{_v(r)}=±∞"],
    "θ": [lambda r: f"θ={r.choice([30, 45, 60, 90, 15, 37])}°", lambda r: "sinθ", lambda r: f"cos θ={decimal(r, 3)}",
          lambda r: f"tanθ={small(r)}", lambda r: f"{r.randint(2, 4)}θ", lambda r: "θ/2", lambda r: "θ",
          lambda r: "sinθ/cosθ", lambda r: "θ_1=θ_2", lambda r: f"{r.randint(2, 3)}θ={r.randint(10, 180)}°"],
    "Δ": [lambda r: "Δx", lambda r: "Δy/Δx", lambda r: f"ΔT={signed(r)}", lambda r: f"ΔH={signed(r)}",
          lambda r: "Δt", lambda r: f"ΔE={small(r)}J", lambda r: "Δv/Δt", lambda r: "Δs/Δt"],
    "μ": [lambda r: f"{small(r)}μA", lambda r: f"μ={decimal(r, 2)}", lambda r: f"{small(r)}μF", lambda r: f"{r.randint(1, 999)}μm",
          lambda r: "μ", lambda r: f"{small(r)} μs", lambda r: f"μ_{r.choice(['s', 'k', '0'])}={decimal(r, 2)}",
          lambda r: f"{r.randint(1, 99)}μA+{r.randint(1, 99)}μA"],
    "Ω": [lambda r: f"{r.randint(1, 999)}Ω", lambda r: f"R={small(r)}Ω", lambda r: f"{decimal(r, 1)}kΩ",
          lambda r: f"{small(r)} Ω", lambda r: "Ω", lambda r: f"{r.randint(1, 99)}Ω+{r.randint(1, 99)}Ω",
          lambda r: f"{r.randint(1, 99)}Ω||{r.randint(1, 99)}Ω"],
    "λ": [lambda r: f"λ={r.randint(380, 750)}nm", lambda r: "λ", lambda r: "v=fλ", lambda r: f"{r.randint(2, 5)}λ",
          lambda r: "λ/2", lambda r: f"λ={decimal(r)}m", lambda r: "nλ=dsinθ", lambda r: "λ_1/λ_2",
          lambda r: f"{r.randint(2, 5)}λ/{r.randint(2, 4)}=λ"],
}


# How often each symbol is the one a focus word is built around. Not even: a symbol the rest of the
# text already uses often (× and ^ in every 6.02×10^23, ° in every 25°C) needs fewer words of its own,
# and one that comes alone (~) needs more. Solved from a measurement over 70,000 phrases so the new
# symbols come out roughly level, each above 2,000 (synth.py prints the counts for every corpus it
# writes). The floor is what sets the maths share near half the words rather than a third: 27
# symbols x 2,000 is about 0.8 symbol words per phrase before a single plain number is written.
FOCUS_WEIGHTS = {
    "^": 0.15, "*": 1.19, "<": 0.62, ">": 0.82, "[": 1.82, "{": 1.56, "|": 0.41, "_": 0.66, "~": 1.38, "×": 0.15,
    "÷": 1.27, "±": 1.24, "√": 1.09, "π": 1.05, "°": 1.05, "≤": 1.03, "≥": 1.12, "≠": 1.13, "≈": 1.06, "∞": 1.02,
    "θ": 0.88, "Δ": 0.98, "μ": 1.25, "Ω": 1.13, "λ": 1.21,
}
_FOCUS_KEYS = list(FOCUS)


def focus(rng, symbol: str | None = None) -> str:
    """A word (or two) built around `symbol`, or around one drawn by FOCUS_WEIGHTS. A bracket brings its pair."""
    symbol = symbol or rng.choices(_FOCUS_KEYS, weights=[FOCUS_WEIGHTS[s] for s in _FOCUS_KEYS], k=1)[0]
    return rng.choice(FOCUS[symbol])(rng)


def coordinate(rng) -> str:
    r = rng.random()
    if r < 0.6:
        return f"({signed(rng)}, {signed(rng)})"
    if r < 0.8:
        return f"({_v(rng)}, {_v(rng)})"
    return f"({signed(rng)}, {signed(rng)}, {signed(rng)})"


def chemistry(rng) -> str:
    r = rng.random()
    if r < 0.55:
        return rng.choice(SPECIES)
    if r < 0.8:
        return rng.choice(REACTIONS)
    return f"{rng.randint(2, 4)}{rng.choice(SPECIES[:20])}"


def maths_token(rng) -> str:
    """One numeric or mathematical word, glued: a number, a formula or a symbol's word."""
    r = rng.random()
    if r < 0.40:
        return number_v2(rng)
    if r < 0.88:
        return focus(rng)
    if r < 0.94:
        return rng.choice(FORMULAS)
    return chemistry(rng)


def maths_phrase(rng) -> str:
    """A phrase that is all maths: symbols' words, an equation, a relation, numbers, a quantity."""
    r = rng.random()
    if r < 0.55:
        return " ".join(focus(rng) for _ in range(rng.choice([2, 2, 3, 3])))
    if r < 0.67:
        return spaced_equation(rng)
    if r < 0.72:
        return spaced_relation(rng)
    if r < 0.82:
        return " ".join(number_v2(rng) for _ in range(rng.choice([1, 1, 2, 3])))
    if r < 0.87:
        return quantity(rng, glued=False) if rng.random() < 0.7 else f"{rng.choice(VARIABLES)} = {quantity(rng)}"
    if r < 0.92:
        return rng.choice(FORMULAS) + ("" if rng.random() < 0.6 else " " + focus(rng))
    if r < 0.97:
        return chemistry(rng)
    return coordinate(rng)


P_MATHS_PHRASE = 0.31
P_MATHS_TOKEN = 0.03
MAX_V2_CHARS = 30


def token_v2(rng: random.Random) -> str:
    """A word of a mixed phrase: v1's own, except that v1's numbers become v2's (every kind, not NN.NN)
    and a few more words become numeric or mathematical."""
    w = token(rng)
    return maths_token(rng) if is_maths_word(w) or rng.random() < P_MATHS_TOKEN else w


def phrase_v2(rng: random.Random, max_words: int = 3) -> str:
    """Corpus v2's unit of training: a maths phrase, or one to three words much as v1 wrote them."""
    if rng.random() < P_MATHS_PHRASE:
        text = maths_phrase(rng)
        while len(text) > MAX_V2_CHARS:  # a long equation is cut at a word, so sequences stay short
            text = text.rsplit(" ", 1)[0] if " " in text else maths_token(rng)
    else:
        n = rng.choices(range(1, max_words + 1), weights=[5, 3, 2][:max_words], k=1)[0]
        text = " ".join(token_v2(rng) for _ in range(n))
    return "".join(ch for ch in text if ch in ALPHABET_V2).strip() or "a"


def is_maths_word(word: str) -> bool:
    """What `phrase_v2`'s numeric share counts: a word holding a digit or a maths character, or a lone minus."""
    return word == "-" or any(c.isdigit() or c in MATHS_CHARS for c in word)


# The corpora synth.py can write: each an alphabet and the text its writers write.
CORPORA = {
    "v1": (ALPHABET, phrase),
    "v2": (ALPHABET_V2, phrase_v2),
}
