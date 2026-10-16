"""The sigma-lognormal model of rapid handwriting movements (docs/HANDWRITING-SYNTHESIS.md §7.2).

Plamondon's kinematic theory describes a pen-down stroke as the vector sum of lognormal velocity
pulses. Component j starts at t0, moves a curvilinear distance D along a circular arc that turns
from angle theta_s to theta_e, and its speed is a lognormal in time:

    |v_j(t)| = D / (sigma * sqrt(2 pi) * (t - t0)) * exp(-(ln(t - t0) - mu)^2 / (2 sigma^2))
    phi_j(t) = theta_s + (theta_e - theta_s) * L(t),   L(t) = 1/2 * (1 + erf((ln(t - t0) - mu) / (sigma sqrt 2)))

Because the angle moves linearly with the lognormal CDF L, which is also the fraction of D
travelled, the displacement has a closed form: an arc of length D * L(t) and turn dtheta * L(t).
Positions are therefore exact, with no numerical integration -- which is what lets Kotlin
(`dev.inkstone.core.handwriting.motion.SigmaLognormal`) and this file agree to rounding.

`plan` turns a skeleton polyline (a font's single-line glyph, or a harvested letter) into
components: one per piece between virtual targets, each the circular arc that joins the targets
with the skeleton's own turn. Overlapping the components in time is what rounds the corners the
way a hand does; perturbing their parameters is what makes two samples of one letter differ the
way a hand's do.

Everything here is float64 and deterministic for a given input and RNG.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, replace
from typing import Sequence

SQRT2 = math.sqrt(2.0)
SQRT_2PI = math.sqrt(2.0 * math.pi)

# The smallest turn treated as a curve. Below it an arc is a straight line, and the closed form's
# D / dtheta would only add rounding error.
STRAIGHT = 1e-9


@dataclass(frozen=True)
class Component:
    """One lognormal velocity pulse: when, how far, how fast, and which way it turns."""

    t0: float
    D: float
    mu: float
    sigma: float
    theta_s: float
    theta_e: float

    @property
    def onset_end(self) -> float:
        """When the pulse is over, to 3 sigma -- 99.87% of D travelled."""
        return self.t0 + math.exp(self.mu + 3.0 * self.sigma)


def fraction(c: Component, t: float) -> float:
    """L(t): the fraction of the component's distance travelled by time t."""
    if t <= c.t0:
        return 0.0
    return 0.5 * (1.0 + math.erf((math.log(t - c.t0) - c.mu) / (c.sigma * SQRT2)))


def displacement(c: Component, t: float) -> tuple[float, float]:
    """Where the component has carried the pen by time t, from where it started."""
    lam = fraction(c, t)
    if lam == 0.0:
        return 0.0, 0.0
    dtheta = c.theta_e - c.theta_s
    if abs(dtheta) < STRAIGHT:
        return c.D * lam * math.cos(c.theta_s), c.D * lam * math.sin(c.theta_s)
    r = c.D / dtheta
    a = c.theta_s + dtheta * lam
    return r * (math.sin(a) - math.sin(c.theta_s)), r * (math.cos(c.theta_s) - math.cos(a))


def velocity(c: Component, t: float) -> tuple[float, float]:
    """The component's velocity at t -- used only by the independent reference (reference.py)."""
    if t <= c.t0:
        return 0.0, 0.0
    dt = t - c.t0
    z = (math.log(dt) - c.mu) / c.sigma
    speed = c.D / (c.sigma * SQRT_2PI * dt) * math.exp(-0.5 * z * z)
    phi = c.theta_s + (c.theta_e - c.theta_s) * fraction(c, t)
    return speed * math.cos(phi), speed * math.sin(phi)


def position(components: Sequence[Component], t: float, start: tuple[float, float] = (0.0, 0.0)) -> tuple[float, float]:
    """The pen at time t: the start plus every component's displacement so far."""
    x, y = start
    for c in components:
        dx, dy = displacement(c, t)
        x += dx
        y += dy
    return x, y


def duration(components: Sequence[Component]) -> float:
    """From the first onset until the last pulse has carried 99.87% of its distance."""
    return max(c.onset_end for c in components) - min(c.t0 for c in components)


def sample(components: Sequence[Component], dt: float, start: tuple[float, float] = (0.0, 0.0)) -> list[tuple[float, float, float]]:
    """(t, x, y) every dt from the first onset to the end, plus the exact end point."""
    t_begin = min(c.t0 for c in components)
    t_end = t_begin + duration(components)
    out = []
    k = 0
    while True:
        t = t_begin + k * dt
        if t >= t_end:
            break
        out.append((t,) + position(components, t, start))
        k += 1
    out.append((t_end,) + position(components, t_end, start))
    return out


# --- Planning: a skeleton polyline -> components -------------------------------------------------


@dataclass(frozen=True)
class Timing:
    """How a writer moves: the lognormal shape, and how much successive pulses overlap.

    mu and sigma are the base log-time and log-spread of a pulse (exp(mu) is its median duration
    after onset, in seconds). gap is the onset-to-onset spacing as a fraction of exp(mu): small is
    fluent and rounds corners, large is careful and keeps them. size_mu is the isochrony slope --
    how much longer a longer pulse takes -- and d_ref the distance at which it takes exactly mu.
    """

    mu: float = -1.6
    sigma: float = 0.28
    gap: float = 0.45
    size_mu: float = 0.35
    d_ref: float = 0.6


def _wrap(a: float) -> float:
    """An angle in (-pi, pi]."""
    while a <= -math.pi:
        a += 2.0 * math.pi
    while a > math.pi:
        a -= 2.0 * math.pi
    return a


def simplify(points: Sequence[tuple[float, float]], tolerance: float) -> list[int]:
    """Ramer-Douglas-Peucker: the indices of the virtual targets, ends always included.

    Iterative and with a deterministic tie-break (the first farthest point), so Kotlin's port picks
    the same targets.
    """
    n = len(points)
    if n <= 2:
        return list(range(n))
    keep = [False] * n
    keep[0] = keep[n - 1] = True
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        best, best_i = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            if length < 1e-12:
                d = math.hypot(px - ax, py - ay)
            else:
                d = abs(dx * (py - ay) - dy * (px - ax)) / length
            if d > best:
                best, best_i = d, i
        if best > tolerance:
            keep[best_i] = True
            stack.append((best_i, b))
            stack.append((a, best_i))
    return [i for i in range(n) if keep[i]]


def _direction(points: Sequence[tuple[float, float]], i: int, step: int) -> float | None:
    """The polyline's heading leaving point i (step=+1) or arriving at it (step=-1), skipping repeats."""
    x0, y0 = points[i]
    j = i + step
    while 0 <= j < len(points):
        x1, y1 = points[j]
        if abs(x1 - x0) > 1e-12 or abs(y1 - y0) > 1e-12:
            return math.atan2(y1 - y0, x1 - x0) if step > 0 else math.atan2(y0 - y1, x0 - x1)
        j += step
    return None


# A pulse turns by at most this much; more and the arc model's single radius stops fitting.
MAX_TURN = math.radians(160.0)

# A corner sharper than this is a cusp: the pen reverses, so its speed must reach zero there. Two
# overlapping pulses in opposite directions cancel instead -- an `h` loses its ascender and reads
# as `n` -- so the next pulse waits until this one is CUSP_SIGMAS sigmas past its median.
CUSP = math.radians(110.0)
CUSP_SIGMAS = 1.5


def plan(points: Sequence[tuple[float, float]], timing: Timing, tolerance: float = 0.04, t0: float = 0.0) -> list[Component]:
    """Components that carry the pen through `points`' virtual targets, in order.

    Each piece between two targets becomes one arc whose turn is the skeleton's own turn over that
    piece (the heading leaving the first target against the heading arriving at the second),
    clamped to MAX_TURN, and whose chord is exactly the targets' difference -- so with every pulse
    complete the pen ends precisely on the last target.
    """
    targets = simplify(points, tolerance)
    comps: list[Component] = []
    onset = t0
    pieces = [(a, b) for a, b in zip(targets, targets[1:])
              if math.hypot(points[b][0] - points[a][0], points[b][1] - points[a][1]) >= 1e-9]
    for n, (a, b) in enumerate(pieces):
        ax, ay = points[a]
        bx, by = points[b]
        cx, cy = bx - ax, by - ay
        chord = math.hypot(cx, cy)
        alpha = math.atan2(cy, cx)
        leave = _direction(points, a, +1)
        arrive = _direction(points, b, -1)
        if leave is None or arrive is None:
            dtheta = 0.0
        else:
            dtheta = _wrap(arrive - leave)
        dtheta = max(-MAX_TURN, min(MAX_TURN, dtheta))
        if abs(dtheta) < STRAIGHT:
            dist = chord
        else:
            dist = chord * (dtheta / 2.0) / math.sin(dtheta / 2.0)
        mu = timing.mu + timing.size_mu * math.log(max(dist, 1e-6) / timing.d_ref)
        comps.append(Component(onset, dist, mu, timing.sigma, alpha - dtheta / 2.0, alpha + dtheta / 2.0))
        onset += _spacing(points, pieces, n, mu, timing)
    return comps


def _spacing(points, pieces, n: int, mu: float, timing: Timing) -> float:
    """Onset to onset: the writer's gap, or, at a cusp, long enough for this pulse to nearly finish."""
    if n + 1 < len(pieces):
        arrive = _direction(points, pieces[n][1], -1)
        leave = _direction(points, pieces[n + 1][0], +1)
        if arrive is not None and leave is not None and abs(_wrap(leave - arrive)) > CUSP:
            return math.exp(mu + CUSP_SIGMAS * timing.sigma)
    return timing.gap * math.exp(mu)


def perturb(components: Sequence[Component], rng, amount: float) -> list[Component]:
    """Each pulse's parameters nudged the way a hand varies them between two samples of one letter.

    `amount` in [0, 1] scales every spread; 0 returns the components unchanged. `rng` is a
    `random.Random`. Distance and angles vary about 6% and 4 degrees at amount 1, timing about 10%.
    """
    if amount <= 0.0:
        return list(components)
    out = []
    shift = 0.0
    for c in components:
        shift += rng.gauss(0.0, 0.01 * amount)
        turn = rng.gauss(0.0, math.radians(4.0) * amount)
        bend = rng.gauss(0.0, math.radians(3.0) * amount)
        out.append(replace(
            c,
            t0=max(0.0, c.t0 + shift),
            D=c.D * math.exp(rng.gauss(0.0, 0.06 * amount)),
            mu=c.mu + rng.gauss(0.0, 0.10 * amount),
            sigma=max(0.08, c.sigma * math.exp(rng.gauss(0.0, 0.10 * amount))),
            theta_s=c.theta_s + turn - bend,
            theta_e=c.theta_e + turn + bend,
        ))
    return out
