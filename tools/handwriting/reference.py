"""Shared reference trajectories for the sigma-lognormal model (docs/HANDWRITING-SYNTHESIS.md §7.3).

Python (`sigmalognormal.py`) and Kotlin (`SigmaLognormal.kt`) both compute positions in closed
form. The reference here does NOT: it integrates each pulse's velocity numerically, in log-time,
with composite Gauss-Legendre quadrature -- so a mistake both closed forms shared would still be
caught, the way tools/calc/refgen checks DecMath against an independent computation.

    py -3 tools/handwriting/reference.py

writes
    tools/handwriting/reference/sigma_lognormal.json      (read by test_sigmalognormal.py)
    core/handwriting/src/commonTest/.../motion/SigmaLognormalReferenceTest.kt   (generated)

The planned cases record Python's `plan` output as the reference for Kotlin's `plan`: there the
claim is that the two sides agree, and test_sigmalognormal.py checks the plan's own properties.
"""

from __future__ import annotations

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import sigmalognormal as sl  # noqa: E402

REPO = os.path.dirname(os.path.dirname(HERE))
JSON_OUT = os.path.join(HERE, "reference", "sigma_lognormal.json")
KOTLIN_OUT = os.path.join(
    REPO, "core", "handwriting", "src", "commonTest", "kotlin", "dev", "inkstone", "core", "handwriting",
    "motion", "SigmaLognormalReferenceTest.kt",
)

# Gauss-Legendre, 5 points on [-1, 1].
_GL_X = [0.0, -0.5384693101056831, 0.5384693101056831, -0.9061798459386640, 0.9061798459386640]
_GL_W = [0.5688888888888889, 0.4786286704993665, 0.4786286704993665, 0.2369268850561891, 0.2369268850561891]
PANELS = 4000
TAIL_SIGMAS = 12.0


def _integrand(c: sl.Component, u: float) -> tuple[float, float]:
    """Velocity times dt/du at log-time u = ln(t - t0): the 1/(t - t0) cancels against e^u."""
    z = (u - c.mu) / c.sigma
    speed = c.D / (c.sigma * math.sqrt(2.0 * math.pi)) * math.exp(-0.5 * z * z)
    cdf = 0.5 * (1.0 + math.erf(z / math.sqrt(2.0)))
    phi = c.theta_s + (c.theta_e - c.theta_s) * cdf
    return speed * math.cos(phi), speed * math.sin(phi)


def integrated_displacement(c: sl.Component, t: float) -> tuple[float, float]:
    if t <= c.t0:
        return 0.0, 0.0
    lo = c.mu - TAIL_SIGMAS * c.sigma
    hi = math.log(t - c.t0)
    if hi <= lo:
        return 0.0, 0.0
    h = (hi - lo) / PANELS
    sx = sy = 0.0
    for p in range(PANELS):
        mid = lo + (p + 0.5) * h
        for x, w in zip(_GL_X, _GL_W):
            vx, vy = _integrand(c, mid + 0.5 * h * x)
            sx += w * vx
            sy += w * vy
    return sx * 0.5 * h, sy * 0.5 * h


def integrated_position(comps, t, start=(0.0, 0.0)):
    x, y = start
    for c in comps:
        dx, dy = integrated_displacement(c, t)
        x += dx
        y += dy
    return x, y


def _c(t0, D, mu, sigma, ts, te):
    return sl.Component(t0, D, mu, sigma, ts, te)


# A hand-drawn 'a', a 'g' with a descender and an 'e' -- skeletons in x-height units, y up.
POLYLINES = {
    "a": [(0.55, 0.85), (0.35, 0.98), (0.12, 0.85), (0.02, 0.5), (0.1, 0.12), (0.3, 0.0), (0.5, 0.15),
          (0.6, 0.6), (0.6, 0.95), (0.6, 0.4), (0.62, 0.08), (0.75, 0.0)],
    "g": [(0.6, 0.8), (0.4, 0.98), (0.15, 0.85), (0.08, 0.5), (0.2, 0.2), (0.45, 0.22), (0.62, 0.55),
          (0.62, 0.95), (0.6, -0.2), (0.5, -0.5), (0.3, -0.6), (0.1, -0.45)],
    "e": [(0.1, 0.45), (0.6, 0.5), (0.62, 0.75), (0.45, 0.98), (0.18, 0.9), (0.05, 0.5), (0.15, 0.12),
          (0.4, 0.0), (0.65, 0.12)],
    # Down the stem and back up it: a cusp at the baseline (sigmalognormal.CUSP).
    "h": [(0.05, 1.8), (0.02, 0.9), (0.0, 0.0), (0.02, 0.6), (0.2, 0.92), (0.45, 0.95), (0.6, 0.7),
          (0.62, 0.0)],
}

TIMINGS = {
    "fluent": sl.Timing(mu=-1.7, sigma=0.3, gap=0.35, size_mu=0.35, d_ref=0.6),
    "careful": sl.Timing(mu=-1.4, sigma=0.22, gap=0.7, size_mu=0.3, d_ref=0.6),
}


def cases():
    """Explicit component sets, then planned skeletons."""
    explicit = {
        "straight": [_c(0.0, 1.0, -1.5, 0.25, 0.3, 0.3)],
        "left_turn": [_c(0.0, 0.8, -1.6, 0.3, 0.0, 2.0)],
        "right_turn": [_c(0.05, 1.2, -1.3, 0.2, 1.0, -1.5)],
        "overlapping_loop": [
            _c(0.0, 0.7, -1.8, 0.3, 3.0, 4.2),
            _c(0.06, 0.9, -1.6, 0.28, 4.2, 6.0),
            _c(0.14, 0.6, -1.7, 0.32, 6.0, 7.1),
            _c(0.2, 0.5, -1.9, 0.25, 0.7, 0.7),
        ],
    }
    out = []
    for name, comps in explicit.items():
        out.append({"name": name, "kind": "explicit", "components": comps})
    for letter, pts in POLYLINES.items():
        for tname, timing in TIMINGS.items():
            comps = sl.plan(pts, timing)
            out.append({
                "name": f"plan_{letter}_{tname}",
                "kind": "plan",
                "polyline": pts,
                "timing": timing,
                "components": comps,
            })
    return out


def times_for(comps, n=24):
    """n times spread over the movement, including onsets, the middle and well past the end."""
    t_begin = min(c.t0 for c in comps)
    t_end = t_begin + sl.duration(comps)
    ts = [t_begin + (t_end - t_begin) * k / (n - 1) for k in range(n)]
    ts.append(t_end * 3.0 + 1.0)
    ts.append(t_begin - 0.01)
    return ts


def build():
    data = []
    for case in cases():
        comps = case["components"]
        ts = times_for(comps)
        pts = [integrated_position(comps, t) for t in ts]
        entry = {
            "name": case["name"],
            "kind": case["kind"],
            "components": [[c.t0, c.D, c.mu, c.sigma, c.theta_s, c.theta_e] for c in comps],
            "times": ts,
            "positions": [list(p) for p in pts],
        }
        if case["kind"] == "plan":
            t = case["timing"]
            entry["polyline"] = [list(p) for p in case["polyline"]]
            entry["timing"] = [t.mu, t.sigma, t.gap, t.size_mu, t.d_ref]
        data.append(entry)
    return data


def _kt_doubles(values, indent: int) -> list[str]:
    """`doubleArrayOf(...)` exactly as ktlint formats a wrapped argument list: one value per line."""
    pad = " " * (indent + 4)
    return [" " * indent + "doubleArrayOf("] + [f"{pad}{float(v)!r}," for v in values] + [" " * indent + "),"]


def _kt_list(rows, indent: int) -> list[str]:
    out = [" " * indent + "listOf("]
    for row in rows:
        out += _kt_doubles(row, indent + 4)
    out.append(" " * indent + "),")
    return out


def kotlin_source(data) -> str:
    lines = [
        "// GENERATED by tools/handwriting/reference.py -- do not edit. Regenerate with",
        "//     py -3 tools/handwriting/reference.py",
        "package dev.inkstone.core.handwriting.motion",
        "",
        "import kotlin.math.abs",
        "import kotlin.test.Test",
        "import kotlin.test.assertEquals",
        "import kotlin.test.assertTrue",
        "",
        "/**",
        " * docs/HANDWRITING-SYNTHESIS.md §7.3: Kotlin's sigma-lognormal agrees with Python's on shared",
        " * reference trajectories. The positions were integrated numerically from the velocity, so this",
        " * checks the closed form against something other than itself; the planned components are",
        " * Python's `plan`, so those cases check that the two sides plan the same pulses.",
        " */",
        "class SigmaLognormalReferenceTest {",
        "",
        "    @Test",
        "    fun positionsMatchTheIntegratedVelocity() {",
        "        for (case in cases) {",
        "            val cs = comps(case)",
        "            for (k in case.times.indices) {",
        "                val p = SigmaLognormal.position(cs, case.times[k])",
        "                val want = case.positions[k]",
        "                assertTrue(",
        "                    abs(p[0] - want[0]) < TOLERANCE && abs(p[1] - want[1]) < TOLERANCE,",
        "                    \"${case.name} t=${case.times[k]}: ${p.toList()} vs ${want.toList()}\",",
        "                )",
        "            }",
        "        }",
        "    }",
        "",
        "    @Test",
        "    fun planningAgreesWithPython() {",
        "        for (case in cases.filter { it.polyline != null }) {",
        "            val poly = requireNotNull(case.polyline)",
        "            val t = requireNotNull(case.timing)",
        "            val xs = DoubleArray(poly.size) { poly[it][0] }",
        "            val ys = DoubleArray(poly.size) { poly[it][1] }",
        "            val planned = SigmaLognormal.plan(xs, ys, SigmaLognormal.Timing(t[0], t[1], t[2], t[3], t[4]))",
        "            val want = comps(case)",
        "            assertEquals(want.size, planned.size, case.name)",
        "            for (j in want.indices) {",
        "                val a = planned[j]",
        "                val b = want[j]",
        "                val diffs = listOf(",
        "                    a.t0 - b.t0,",
        "                    a.distance - b.distance,",
        "                    a.mu - b.mu,",
        "                    a.sigma - b.sigma,",
        "                    a.thetaS - b.thetaS,",
        "                    a.thetaE - b.thetaE,",
        "                )",
        "                assertTrue(diffs.all { abs(it) < PLAN_TOLERANCE }, \"${case.name} pulse $j: $a vs $b\")",
        "            }",
        "        }",
        "    }",
        "",
        "    private companion object {",
        "        const val TOLERANCE = 1e-9",
        "        const val PLAN_TOLERANCE = 1e-12",
        "    }",
        "}",
        "",
        "private class Case(",
        "    val name: String,",
        "    val components: List<DoubleArray>,",
        "    val times: DoubleArray,",
        "    val positions: List<DoubleArray>,",
        "    val polyline: List<DoubleArray>? = null,",
        "    val timing: DoubleArray? = null,",
        ")",
        "",
        "private fun comps(c: Case) = c.components.map {",
        "    SigmaLognormal.Component(it[0], it[1], it[2], it[3], it[4], it[5])",
        "}",
        "",
        "private val cases = listOf(",
    ]
    for e in data:
        lines.append("    Case(")
        lines.append(f"        \"{e['name']}\",")
        lines += _kt_list(e["components"], 8)
        lines += _kt_doubles(e["times"], 8)
        lines += _kt_list(e["positions"], 8)
        if "polyline" in e:
            lines += _kt_list(e["polyline"], 8)
            lines += _kt_doubles(e["timing"], 8)
        lines.append("    ),")
    lines += [")", ""]
    return "\n".join(lines)


def main():
    data = build()
    os.makedirs(os.path.dirname(JSON_OUT), exist_ok=True)
    with open(JSON_OUT, "w", newline="\n", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
        f.write("\n")
    os.makedirs(os.path.dirname(KOTLIN_OUT), exist_ok=True)
    with open(KOTLIN_OUT, "w", newline="\n", encoding="utf-8") as f:
        f.write(kotlin_source(data))
    print(f"{len(data)} cases -> {os.path.relpath(JSON_OUT, REPO)}, {os.path.relpath(KOTLIN_OUT, REPO)}")


if __name__ == "__main__":
    main()
