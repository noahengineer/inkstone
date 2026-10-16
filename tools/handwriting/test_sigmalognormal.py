"""Tests for sigmalognormal.py against the independent reference (reference.py) and its own laws.

    py -3 -m unittest discover -s tools/handwriting -p "test_*.py"
"""

import json
import math
import os
import random
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import reference  # noqa: E402
import sigmalognormal as sl  # noqa: E402


def _load():
    with open(reference.JSON_OUT, encoding="utf-8") as f:
        return json.load(f)


class ReferenceAgreement(unittest.TestCase):
    """The closed form against the integrated velocity, case by case."""

    def test_positions_match_the_integrated_velocity(self):
        for case in _load():
            comps = [sl.Component(*c) for c in case["components"]]
            for t, want in zip(case["times"], case["positions"]):
                got = sl.position(comps, t)
                self.assertAlmostEqual(got[0], want[0], delta=1e-9, msg=f"{case['name']} t={t}")
                self.assertAlmostEqual(got[1], want[1], delta=1e-9, msg=f"{case['name']} t={t}")

    def test_the_committed_reference_is_what_reference_py_builds(self):
        """A change to plan() or to the cases must regenerate the fixture both sides read."""
        fresh = reference.build()
        committed = _load()
        self.assertEqual(len(fresh), len(committed))
        for a, b in zip(fresh, committed):
            self.assertEqual(a["name"], b["name"])
            for ca, cb in zip(a["components"], b["components"]):
                for x, y in zip(ca, cb):
                    self.assertAlmostEqual(x, y, delta=1e-12, msg=a["name"])


class Laws(unittest.TestCase):

    def test_a_complete_pulse_moves_exactly_its_chord(self):
        c = sl.Component(0.0, 1.3, -1.5, 0.25, 0.2, 1.4)
        x, y = sl.position([c], 1e6)
        dtheta = c.theta_e - c.theta_s
        chord = c.D * 2.0 * math.sin(dtheta / 2.0) / dtheta
        self.assertAlmostEqual(math.hypot(x, y), chord, places=12)
        self.assertAlmostEqual(math.atan2(y, x), (c.theta_s + c.theta_e) / 2.0, places=12)

    def test_a_plan_ends_on_the_last_target(self):
        for pts in reference.POLYLINES.values():
            comps = sl.plan(pts, sl.Timing())
            end = sl.position(comps, 1e6, start=pts[0])
            self.assertAlmostEqual(end[0], pts[-1][0], places=9)
            self.assertAlmostEqual(end[1], pts[-1][1], places=9)

    def test_overlap_rounds_a_corner_and_spacing_keeps_it(self):
        """An L: fluent timing cuts inside the corner, careful timing passes close to it."""
        corner = [(0.0, 1.0), (0.0, 0.5), (0.0, 0.0), (0.5, 0.0), (1.0, 0.0)]
        def closest(timing):
            comps = sl.plan(corner, timing, tolerance=0.01)
            pts = sl.sample(comps, 0.002, start=corner[0])
            return min(math.hypot(x, y) for _, x, y in pts)
        fluent = closest(sl.Timing(gap=0.2))
        careful = closest(sl.Timing(gap=1.5))
        self.assertGreater(fluent, careful * 2.0)
        self.assertLess(careful, 0.05)

    def test_a_cusp_keeps_its_ascender_under_fluent_timing(self):
        """`h` retraces its stem; without the cusp wait the two pulses cancel and it reads as `n`."""
        h = reference.POLYLINES["h"]
        comps = sl.plan(h, sl.Timing(gap=0.3))
        pts = sl.sample(comps, 0.002, start=h[0])
        lowest = min(y for _, _, y in pts)
        self.assertLess(lowest, 0.1, "the stem must come down to the baseline before it goes back up")

    def test_perturbing_changes_a_sample_and_zero_amount_does_not(self):
        comps = sl.plan(reference.POLYLINES["a"], sl.Timing())
        self.assertEqual(sl.perturb(comps, random.Random(1), 0.0), comps)
        moved = sl.perturb(comps, random.Random(1), 1.0)
        self.assertNotEqual(moved, comps)
        a = sl.position(comps, 0.4)
        b = sl.position(moved, 0.4)
        self.assertGreater(math.hypot(a[0] - b[0], a[1] - b[1]), 1e-4)

    def test_simplify_keeps_the_ends_and_a_corner(self):
        pts = [(0, 0), (1, 0.001), (2, 0), (2, 1), (2, 2)]
        self.assertEqual(sl.simplify(pts, 0.01), [0, 2, 4])

    def test_sample_is_time_ordered_and_ends_at_the_end(self):
        comps = sl.plan(reference.POLYLINES["e"], sl.Timing())
        pts = sl.sample(comps, 0.01)
        ts = [p[0] for p in pts]
        self.assertEqual(ts, sorted(ts))
        self.assertAlmostEqual(ts[-1], min(c.t0 for c in comps) + sl.duration(comps))


if __name__ == "__main__":
    unittest.main()
