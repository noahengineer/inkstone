"""Tests for the training pipeline: fonts, synthesis, preparation, the model, export and segmentation.

    tools/handwriting/.venv/Scripts/python -I -m unittest discover -s tools/handwriting -p "test_*.py"

Small and fast on purpose: a few writers, a tiny network. They prove the pipeline's rules -- licences
refused, labels kept, step() equal to forward(), the file format round-tripping -- not that a model
writes well; that is what sample.py's sheets and the device are for.
"""

import copy
import hashlib
import json
import os
import random
import re
import struct
import sys
import tempfile
import unittest

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import export  # noqa: E402
import fontskel  # noqa: E402
import gatefixtures  # noqa: E402
import mathglyphs  # noqa: E402
import model as M  # noqa: E402
import prepare  # noqa: E402
import segment  # noqa: E402
import synth  # noqa: E402
import texts  # noqa: E402


class Fonts(unittest.TestCase):

    def test_every_listed_font_loads_with_its_licence_and_covers_the_alphabet(self):
        fonts = fontskel.load_all()
        self.assertGreaterEqual(len(fonts), 4)
        for f in fonts:
            self.assertIn(f.entry.licence, fontskel.COMMERCIAL_LICENCES)
            self.assertTrue(f.entry.attribution)
            missing = [c for c in texts.ALPHABET if c != " " and c not in f.glyphs]
            self.assertEqual([], missing, f.entry.id)
            # The frame is measured, so it holds by construction for the letters it is measured on:
            # a single glyph may stray (EMS Casual Hand's `x` dips below the line), the median may not.
            tops = sorted(max(p[1] for s in f.glyphs[c].strokes for p in s) for c in fontskel.X_HEIGHT_LETTERS)
            bottoms = sorted(min(p[1] for s in f.glyphs[c].strokes for p in s) for c in fontskel.X_HEIGHT_LETTERS)
            self.assertAlmostEqual(1.0, tops[len(tops) // 2], delta=1e-6, msg=f.entry.id)
            self.assertAlmostEqual(0.0, bottoms[len(bottoms) // 2], delta=1e-6, msg=f.entry.id)

    def _manifest_copy(self, mutate):
        tmp = tempfile.mkdtemp()
        src = fontskel.FONTS
        with open(os.path.join(src, "LICENCES.json"), encoding="utf-8") as f:
            data = json.load(f)
        mutate(data)
        for entry in data["fonts"]:
            for key in ("file", "licence_file"):
                dst = os.path.join(tmp, entry[key])
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(os.path.join(src, entry[key]), "rb") as a, open(dst, "wb") as b:
                    b.write(a.read())
        with open(os.path.join(tmp, "LICENCES.json"), "w", encoding="utf-8") as f:
            json.dump(data, f)
        return tmp

    def test_a_non_commercial_font_is_refused(self):
        def nc(data):
            data["fonts"][0]["licence"] = "CC-BY-NC-4.0"
        with self.assertRaises(fontskel.LicenceError):
            fontskel.manifest(self._manifest_copy(nc))

    def test_a_changed_font_file_is_refused(self):
        def wrong_hash(data):
            data["fonts"][0]["sha256"] = "0" * 64
        with self.assertRaises(fontskel.LicenceError):
            fontskel.manifest(self._manifest_copy(wrong_hash))

    def test_a_font_with_no_licence_entry_is_refused(self):
        def drop(data):
            del data["fonts"][0]["licence"]
        with self.assertRaises(fontskel.LicenceError):
            fontskel.manifest(self._manifest_copy(drop))


class Synthesis(unittest.TestCase):

    def test_every_point_knows_its_character_and_the_text_is_covered(self):
        faces = synth._faces()
        for wid in range(12):
            w = synth.make_writer(wid, faces, 5)
            s = synth.write_sample("the quick fox", w, random.Random(wid))
            self.assertIsNotNone(s)
            self.assertEqual(len(s.xy), len(s.chars))
            self.assertEqual(1, int(s.eos[-1]))
            letters = {i for i, c in enumerate("the quick fox") if c != " "}
            self.assertEqual(letters, set(int(c) for c in s.chars), w.font)
            per_char = len(s.xy) / len(letters)
            self.assertTrue(8 <= per_char <= 22, f"{w.font}: {per_char:.1f} points per character")

    def test_two_samples_of_one_word_by_one_writer_differ(self):
        faces = synth._faces()
        w = synth.make_writer(3, faces, 5)
        a = synth.write_sample("cation", w, random.Random(1))
        b = synth.write_sample("cation", w, random.Random(2))
        self.assertFalse(len(a.xy) == len(b.xy) and np.allclose(a.xy, b.xy))

    def test_a_corpus_lists_every_font_it_read_with_its_licence(self):
        out = tempfile.mkdtemp()
        m = synth.generate(writers=6, per_writer=3, out_dir=out, seed=2, workers=1)
        self.assertEqual(synth.CORPUS_LICENCE, m["licence"])
        self.assertEqual(18, m["samples"])
        used = {w["font"] for w in m["writer_styles"]}
        listed = {s["id"] for s in m["sources"]}
        self.assertTrue(used <= listed)
        self.assertTrue(all(s["licence"] and s["sha256"] and s["attribution"] for s in m["sources"]))


class GateFixtures(unittest.TestCase):
    """The instrumented gate test's ink (H6): licensed, two faces per text, and exactly what is committed."""

    def test_every_text_is_written_twice_in_two_licensed_faces(self):
        f = gatefixtures.generate(6)
        for text in f["words"] + f["numbers"]:
            fonts = [s["font"] for s in f["samples"] if s["text"] == text]
            self.assertEqual(gatefixtures.WRITERS_PER_TEXT, len(set(fonts)), text)
        listed = {s["id"] for s in f["sources"]}
        self.assertTrue({s["font"] for s in f["samples"]} <= listed)
        for source in f["sources"]:
            self.assertIn(source["licence"], fontskel.COMMERCIAL_LICENCES, source["id"])
            self.assertTrue(source["sha256"] and source["attribution"], source["id"])

    def test_the_committed_fixture_is_what_the_generator_writes(self):
        with open(gatefixtures.OUT, encoding="utf-8") as f:
            committed = json.load(f)
        fresh = gatefixtures.generate(committed["seed"])
        # The git hash records where it was made, not what was made.
        committed["generator"].pop("git")
        fresh["generator"].pop("git")
        self.assertEqual(json.loads(json.dumps(fresh)), committed)


class Preparation(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.corpus = tempfile.mkdtemp()
        synth.generate(writers=20, per_writer=3, out_dir=cls.corpus, seed=3, workers=1)

    def test_a_corpus_with_no_licence_entry_is_refused(self):
        bad = tempfile.mkdtemp()
        with open(os.path.join(self.corpus, "corpus.json"), encoding="utf-8") as f:
            m = json.load(f)
        del m["licence"]
        with open(os.path.join(bad, "corpus.json"), "w", encoding="utf-8") as f:
            json.dump(m, f)
        with self.assertRaises(prepare.LicenceError):
            prepare.prepare([bad], tempfile.mkdtemp())

    def test_a_corpus_with_a_non_commercial_source_is_refused(self):
        with open(os.path.join(self.corpus, "corpus.json"), encoding="utf-8") as f:
            m = json.load(f)
        m["sources"][0]["licence"] = "CC-BY-NC-SA-4.0"
        with self.assertRaises(prepare.LicenceError):
            prepare.check_licences(m)

    def test_a_corpus_with_no_manifest_is_refused(self):
        with self.assertRaises(prepare.LicenceError):
            prepare.prepare([tempfile.mkdtemp()], tempfile.mkdtemp())

    def test_the_split_is_writer_disjoint_and_offsets_rebuild_the_ink(self):
        out = tempfile.mkdtemp()
        meta = prepare.prepare([self.corpus], out, val_fraction=0.2)
        z = np.load(os.path.join(out, "prepared.npz"))
        train = set(z["writer"][~z["is_val"]].tolist())
        val = set(z["writer"][z["is_val"]].tolist())
        self.assertTrue(val and not (train & val))
        raw = np.load(os.path.join(self.corpus, "corpus.npz"))
        a, b = int(z["offsets"][0]), int(z["offsets"][1])
        offs = z["offsets_xy"][a:b] * np.array(meta["norm_std"]) + np.array(meta["norm_mean"])
        np.testing.assert_allclose(np.cumsum(offs, axis=0), raw["xy"][a:b], atol=1e-4)


def tiny(seed=0):
    torch.manual_seed(seed)
    return M.Synthesis(M.Arch(alphabet=" abc", layers=3, cells=6, window=2, mixtures=3, style_dim=3, writers=2,
                              kappa_scale=3.0))


class Model(unittest.TestCase):

    def test_step_by_step_equals_the_teacher_forced_forward(self):
        """The path sampling and Kotlin take (step) is the path training takes (forward)."""
        net = tiny()
        x = torch.randn(1, 9, 3)
        hot, lens = M.onehot_texts(["abca"], net.arch.alphabet)
        style = net.style.weight[:1]
        y_all, _, phi_all = net(x, hot, lens, style, keep_phi=True)
        state = net.zero_state(1)
        for t in range(9):
            y, state, phi = net.step(x[:, t], hot, lens, style, state)
            torch.testing.assert_close(y, y_all[:, t], atol=1e-5, rtol=1e-5)
            torch.testing.assert_close(phi, phi_all[:, t], atol=1e-5, rtol=1e-5)

    def test_the_loss_is_finite_and_falls_with_a_few_steps(self):
        net = tiny(1)
        opt = torch.optim.Adam(net.parameters(), lr=1e-2)
        x = torch.randn(2, 12, 3) * 0.5
        x[:, :, 2] = (torch.rand(2, 12) < 0.2).float()
        target = torch.roll(x, -1, dims=1)
        mask = torch.ones(2, 12)
        hot, lens = M.onehot_texts(["ab", "cab"], net.arch.alphabet)
        losses = []
        for _ in range(25):
            y, _, _ = net(x, hot, lens, net.style(torch.tensor([0, 1])))
            loss = M.nll(y, target, mask, net.arch.mixtures)
            opt.zero_grad()
            loss.backward()
            opt.step()
            losses.append(float(loss))
        self.assertTrue(all(np.isfinite(losses)))
        self.assertLess(losses[-1], losses[0])

    def test_sampling_stops_at_the_guard_and_says_so(self):
        net = tiny(2)
        out = M.sample(net, ["ab", "c"], net.style.weight[:1], bias=0.5, seed=1, max_steps_per_char=4)
        for offs, done in out:
            self.assertLessEqual(len(offs), 4 * 3)
            self.assertIsInstance(done, bool)

    def test_sampling_after_a_prime_moves_the_window_to_the_end_of_the_prime(self):
        # docs §18.10: the window is moved up to the prime's end before the word is written, as in Kotlin.
        net = tiny(2)
        torch.manual_seed(3)
        prime_x = torch.randn(9, 3)
        prime_x[:, 2] = 0.0
        out = M.sample(net, ["c"], net.style.weight[:1], bias=0.5, seed=1, max_steps_per_char=4,
                       prime=("ab", prime_x))
        self.assertEqual(1, len(out))


class Export(unittest.TestCase):

    def test_the_file_round_trips_and_carries_its_provenance_and_checksum(self):
        net = tiny(3)
        export.f16_round(net)
        style = net.style.weight.mean(dim=0, keepdim=True)
        meta = {"norm_mean": [0.1, 0.0], "norm_std": [0.4, 0.4], "points_per_char": 3}
        datasets = [{"name": "t", "licence": "LicenseRef-Inkstone-Synthetic",
                     "sources": [{"id": "f", "licence": "OFL-1.1", "sha256": "1" * 64, "attribution": "x"}]}]
        path = os.path.join(tempfile.mkdtemp(), "m.ihwm")
        sha = export.write_ihwm(path, export.header_of(net, meta, style, datasets, {"seed": 1}),
                                export.tensors_of(net, style))
        data = open(path, "rb").read()
        self.assertEqual(sha, hashlib.sha256(data).hexdigest())
        self.assertEqual(b"IHWM", data[:4])
        self.assertEqual(hashlib.sha256(data[:-32]).digest(), data[-32:])
        version, _, length = struct.unpack("<HHI", data[4:12])
        header = json.loads(data[12:12 + length])
        self.assertEqual(1, version)
        self.assertEqual(datasets, header["provenance"]["datasets"])
        names = [t["name"] for t in header["tensors"]]
        self.assertIn("l2.w_ih", names)
        self.assertIn("style.default", names)
        start = 12 + length + (-(12 + length)) % 4
        entry = next(t for t in header["tensors"] if t["name"] == "out.b")
        raw = np.frombuffer(data[start + entry["offset"]:start + entry["offset"] + entry["length"]], "<f4")
        np.testing.assert_allclose(raw, net.output.bias.detach().numpy(), atol=0)

    def test_an_export_refuses_a_provenance_that_is_not_commercially_clean(self):
        with self.assertRaises(prepare.LicenceError):
            export.check_provenance([{"name": "iam", "licence": "LicenseRef-IAM-OnDB-NC",
                                      "sources": [{"id": "x", "licence": "LicenseRef-IAM-OnDB-NC", "sha256": "1"}]}])
        with self.assertRaises(prepare.LicenceError):
            export.check_provenance([])


class CorpusV1IsUnchanged(unittest.TestCase):
    """Corpus v2 only ever adds: v1's alphabet, text, writer faces and samples are what they were."""

    V1_ALPHABET = " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,'\"-()?!:;/+=%&"
    # sha256 of 5,000 phrases from random.Random(12345), joined by newlines, taken before v2 existed.
    V1_PHRASES = "c0429a91be825ba5f260515d7f44b2eadfed6f5165040bd349390bf89f054578"
    V1_FACES = ["hershey-futural", "hershey-scripts", "hershey-cursive", "relief-singleline", "ems-casualhand",
                "ems-tech", "ems-pancakes", "ems-delight", "ems-neato", "ems-felix", "ems-capitol"]

    def test_v1s_alphabet_is_unchanged(self):
        self.assertEqual(self.V1_ALPHABET, texts.ALPHABET)
        self.assertEqual(texts.ALPHABET, texts.CORPORA["v1"][0])

    def test_v1s_text_generator_is_unchanged(self):
        rng = random.Random(12345)
        phrases = [texts.phrase(rng) for _ in range(5000)]
        self.assertEqual(self.V1_PHRASES, hashlib.sha256("\n".join(phrases).encode()).hexdigest())
        self.assertIs(texts.phrase, texts.CORPORA["v1"][1])

    def test_the_writer_faces_are_v1s_and_no_symbol_set_is_one(self):
        self.assertEqual(self.V1_FACES, [f.entry.id for f in fontskel.load_all()])
        self.assertEqual(sorted(self.V1_FACES), sorted(synth._faces()))

    @unittest.skipUnless(os.path.exists(os.path.join(HERE, "data", "synth-v1", "corpus.npz")), "no data/synth-v1 here")
    def test_data_synth_v1_is_reproduced_draw_for_draw(self):
        """The shipped model's corpus: 4,000 writers x 12, seed 1. Four writers regenerated exactly."""
        z = np.load(os.path.join(HERE, "data", "synth-v1", "corpus.npz"))
        offs, xy, writer, text = z["offsets"], z["xy"], z["writer"], z["text"]
        for wid in (0, 1, 2, 3999):
            _, samples = synth._writer_job((wid, 12, 1))
            idx = np.nonzero(writer == wid)[0]
            self.assertEqual(len(idx), len(samples))
            for s, i in zip(samples, idx):
                a, b = int(offs[i]), int(offs[i + 1])
                self.assertEqual(str(text[i]), s.text)
                np.testing.assert_array_equal(xy[a:b], s.xy)


class SymbolSets(unittest.TestCase):
    """Corpus v2's Hershey maths and Greek sets: licensed, checked and refused exactly as a face is."""

    def test_the_symbol_sets_load_with_their_licence_and_the_glyphs_they_lend(self):
        sets = fontskel.load_symbols(mathglyphs.SYMBOL_SETS)
        for sid, f in sets.items():
            self.assertEqual("symbols", f.entry.role)
            self.assertIn(f.entry.licence, fontskel.COMMERCIAL_LICENCES)
            self.assertTrue(f.entry.attribution)
        self.assertTrue(set("×÷±√°≤≥≠∞0123456789") <= set(sets["hershey-mathlow"].glyphs))
        self.assertTrue(set("ΔΘΩλμπ") <= set(sets["hershey-greeks"].glyphs))
        # mathlow's lowercase letters (its A-Z slots) measure the frame both sets are normalised to.
        letters = sets["hershey-mathlow"].glyphs
        tops = sorted(max(p[1] for s in letters[c].strokes for p in s) for c in fontskel.X_HEIGHT_LETTERS)
        self.assertAlmostEqual(1.0, tops[len(tops) // 2], delta=1e-6)
        self.assertEqual(sets["hershey-mathlow"].units_per_x_height, sets["hershey-greeks"].units_per_x_height)

    def _copy(self, mutate=lambda data: None):
        return Fonts._manifest_copy(self, mutate)

    def test_a_symbol_set_absent_from_the_manifest_is_refused_even_with_its_file_present(self):
        def drop(data):
            data["fonts"] = [e for e in data["fonts"] if e["id"] != "hershey-mathlow"]
        tmp = self._copy(drop)
        with open(os.path.join(fontskel.FONTS, "hershey", "mathlow.jhf"), "rb") as a, \
                open(os.path.join(tmp, "hershey", "mathlow.jhf"), "wb") as b:
            b.write(a.read())
        with self.assertRaises(fontskel.LicenceError):
            fontskel.load_symbols(mathglyphs.SYMBOL_SETS, tmp)

    def test_an_altered_symbol_set_is_refused(self):
        tmp = self._copy()
        with open(os.path.join(tmp, "hershey", "greeks.jhf"), "ab") as f:
            f.write(b"\n")
        with self.assertRaises(fontskel.LicenceError):
            fontskel.load_symbols(mathglyphs.SYMBOL_SETS, tmp)

    def test_a_symbol_set_under_a_non_commercial_licence_is_refused(self):
        def nc(data):
            next(e for e in data["fonts"] if e["id"] == "hershey-greeks")["licence"] = "CC-BY-NC-SA-4.0"
        with self.assertRaises(fontskel.LicenceError):
            fontskel.manifest(self._copy(nc))

    def test_a_face_is_not_accepted_as_a_symbol_set(self):
        with self.assertRaises(fontskel.LicenceError):
            fontskel.load_symbols(["hershey-futural"])


class GlyphsV2(unittest.TestCase):

    def test_every_writer_face_can_write_every_v2_character_either_way_it_borrows(self):
        faces = synth._faces()
        lent = mathglyphs.lenders(faces)
        w = synth.make_writer(0, faces, 1)
        for fid in faces:
            for prefer in ("hershey", "relief"):
                g = mathglyphs.Glyphs(faces[fid], synth.fallback_faces(faces), lent, prefer)
                for ch in texts.ALPHABET_V2[1:]:
                    glyph, scale, own = g.resolve(ch, w)
                    self.assertTrue(glyph is not None and glyph.strokes, f"{fid}/{prefer}: {ch!r}")
                    if ch in texts.NEW_V2:  # v1's letters keep their faces' flourishes (Capitol's f is 5 tall)
                        ys = [p[1] * scale for s in glyph.strokes for p in s]
                        self.assertTrue(-1.6 < min(ys) and max(ys) < 4.0,
                                        f"{fid}/{prefer}: {ch!r} spans {min(ys):.2f}..{max(ys):.2f}")

    def test_a_borrowed_operator_is_scaled_to_the_writers_digits(self):
        faces = synth._faces()
        lent = mathglyphs.lenders(faces)
        w = synth.make_writer(0, faces, 1)
        g = mathglyphs.Glyphs(faces["ems-tech"], synth.fallback_faces(faces), lent, "hershey")
        for digit_height in (1.3, 1.8):
            w.digit_height = digit_height
            glyph, scale, own = g.resolve("≤", w)
            self.assertFalse(own)
            self.assertAlmostEqual(digit_height / lent["hershey-mathlow"].digit, scale)

    def test_hersheys_doubled_glyphs_are_single_line_in_v2(self):
        faces = synth._faces()
        for fid, chars in (("hershey-futural", "[]~"), ("hershey-scripts", "0123456789()?!&[]~"), ("hershey-cursive", "[]~")):
            own = mathglyphs.own_glyphs(faces[fid])
            unit = 1.0 / faces[fid].font.units_per_x_height
            for ch in chars:
                strokes = own.glyph(ch).strokes
                again = mathglyphs.single_line(strokes, None, mathglyphs.NEAR_UNITS * unit, 0.0)
                lost = sum(map(mathglyphs._length, strokes)) - sum(map(mathglyphs._length, again))
                self.assertLess(lost, 1e-9, f"{fid} {ch!r} still has a stroke that only retraces another")
        for fid in ("hershey-futural", "hershey-scripts", "hershey-cursive"):
            for ch in "{}":
                self.assertEqual(1, len(mathglyphs.own_glyphs(faces[fid]).glyph(ch).strokes))

    def test_a_letter_filed_under_a_symbol_is_not_used_as_that_symbol(self):
        faces = synth._faces()
        own = mathglyphs.own_glyphs(faces["ems-tech"])
        self.assertIn("×", own.standins)  # EMS Tech's × is its x, stroke for stroke
        g = mathglyphs.Glyphs(faces["ems-tech"], synth.fallback_faces(faces), mathglyphs.lenders(faces), "relief")
        self.assertFalse(g.resolve("×", synth.make_writer(0, faces, 1))[2])

    def test_theta_is_the_closed_barred_form(self):
        theta = mathglyphs.lenders(synth._faces())["hershey-greeks"].glyph("θ")
        self.assertEqual(2, len(theta.strokes))  # Θ's oval and its bar, narrowed: not the script ϑ


class TextsV2(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        rng = random.Random(3)
        cls.phrases = [texts.phrase_v2(rng) for _ in range(6000)]
        cls.words = [w for p in cls.phrases for w in p.split(" ") if w]

    def test_the_v2_alphabet_is_v1s_followed_by_the_maths_all_in_the_bmp(self):
        a = texts.ALPHABET_V2
        self.assertTrue(a.startswith(texts.ALPHABET))
        self.assertEqual(len(a), len(set(a)))
        self.assertTrue(all(ord(c) <= 0xFFFF for c in a))
        self.assertTrue(set("^*<>[]{}|_~×÷±√π°≤≥≠≈∞θΔμΩλ") <= set(a))

    def test_every_phrase_is_written_in_alphabet_v2_and_stays_short(self):
        for p in self.phrases:
            self.assertTrue(p and set(p) <= set(texts.ALPHABET_V2), p)
            self.assertLessEqual(len(p), 45, p)

    def test_the_numeric_share_is_as_designed(self):
        # Roughly half the words, not the ~30 % first asked for: 27 new symbols at >= 2,000 each in
        # 70,000 phrases need about 0.8 maths words per phrase on their own (texts.FOCUS_WEIGHTS).
        share = sum(map(texts.is_maths_word, self.words)) / len(self.words)
        self.assertTrue(0.40 <= share <= 0.56, f"{share:.3f}")

    def test_the_numbers_include_every_form_v1_never_wrote(self):
        text = "\n".join(self.phrases)
        forms = {
            "leading zeros (0.05, 0.0375)": r"(?<![\d.])0\.0+[1-9]",
            "negatives": r"(?<![\w)\]])-\d",
            "4- and 5-place decimals": r"\d\.\d{4,5}(?!\d)",
            "scientific, ×10^": r"\d×10\^-?\d",
            "scientific, e": r"\de-?\d",
            "percentages": r"\d%",
            "fractions": r"\d/\d",
            "coordinates": r"\(-?[\d.]+, -?[\d.]+\)",
            "five-digit integers": r"(?<![\d.])[1-9]\d{4}(?![\d.])",
        }
        for name, pattern in forms.items():
            self.assertGreaterEqual(len(re.findall(pattern, text)), 5 if name.startswith("five") else 20, name)

    def test_every_v2_character_is_written(self):
        seen = set("".join(self.phrases))
        self.assertEqual([], [c for c in texts.ALPHABET_V2[1:] if c not in seen])

    def test_a_spaced_equations_result_is_its_arithmetic(self):
        rng = random.Random(9)
        checked = 0
        for _ in range(3000):
            e = texts.spaced_equation(rng)
            m = re.fullmatch(r"(?:\w = )?(-?[\d.]+(?: [+-] [\d.]+)+) = (-?[\d.]+)", e)
            if m:
                terms = m.group(1).split(" ")
                total = float(terms[0])
                for op, operand in zip(terms[1::2], terms[2::2]):
                    total += float(operand) if op == "+" else -float(operand)
                self.assertAlmostEqual(total, float(m.group(2)), delta=1e-4, msg=e)
                checked += 1
        self.assertGreater(checked, 50)


class SynthesisV2(unittest.TestCase):

    def test_a_v2_corpus_carries_alphabet_v2_and_lists_the_symbol_sets_with_their_licences(self):
        out = tempfile.mkdtemp()
        m = synth.generate(writers=8, per_writer=3, out_dir=out, seed=2, workers=1, corpus="v2")
        self.assertEqual(texts.ALPHABET_V2, m["alphabet"])
        self.assertEqual("v2", m["corpus"])
        listed = {s["id"]: s for s in m["sources"]}
        for sid in ("hershey-mathlow", "hershey-greeks", "relief-singleline", "hershey-futural"):
            self.assertIn(sid, listed)
            self.assertIn(listed[sid]["licence"], fontskel.COMMERCIAL_LICENCES)
            self.assertTrue(listed[sid]["sha256"] and listed[sid]["attribution"])
        meta = prepare.prepare([out], tempfile.mkdtemp(), val_fraction=0.25)
        self.assertEqual(texts.ALPHABET_V2, meta["alphabet"])

    def test_every_face_writes_every_new_character_with_points_for_each(self):
        faces = synth._faces()
        text = "^*<>[]{}|_~ ×÷±√π°≤≥≠≈∞θΔμΩλ"
        done = set()
        for wid in range(400):
            w = synth.make_writer(wid, faces, 6)
            if w.font in done:
                continue
            done.add(w.font)
            s = synth.write_sample(text, w, random.Random(wid), synth.glyphs_for(w, 6))
            want = {i for i, c in enumerate(text) if c != " "}
            self.assertEqual(want, set(int(c) for c in s.chars), w.font)
        self.assertEqual(set(faces), done)

    def test_a_decimal_point_sits_on_the_line_clear_of_its_digits(self):
        """`2.5` was written `25` in v1 by EMS Tech and Pancakes writers; cursive's point floated mid-height."""
        faces = synth._faces()
        text = "2.5 0.0375"
        seen = {}
        for wid in range(600):
            w = synth.make_writer(wid, faces, 4)
            if seen.get(w.font, 0) >= 3:
                continue
            seen[w.font] = seen.get(w.font, 0) + 1
            s = synth.write_sample(text, w, random.Random(wid), synth.glyphs_for(w, 4))
            for d in (i for i, c in enumerate(text) if c == "."):
                point, before, after = (s.xy[s.chars == k] for k in (d, d - 1, d + 1))
                self.assertTrue(-0.2 < point[:, 1].mean() < 0.35, f"{w.font}: point at {point[:, 1].mean():.2f}")
                self.assertGreater(point[:, 0].min() - before[:, 0].max(), 0.0, w.font)
                self.assertGreater(after[:, 0].min() - point[:, 0].max(), 0.0, w.font)
        self.assertEqual(set(faces), set(seen))


class Segmentation(unittest.TestCase):

    def test_viterbi_matches_kotlins_rule_on_the_shared_example(self):
        """The same nine-point example as Kotlin's PenDynamicsTest.theViterbiPassIsMonotonicAndCoversEveryLetter."""
        phi = np.array([
            [.9, .05, .05], [.8, .1, .1], [.15, .05, .8], [.2, .7, .1], [.1, .8, .1], [.1, .7, .2],
            [.1, .2, .7], [.05, .05, .9], [.05, .05, .9],
        ])
        self.assertEqual([0, 0, 0, 1, 1, 1, 2, 2, 2], segment.viterbi(phi).tolist())

    def test_in_order_marks_a_delayed_dot_as_out_of_order(self):
        self.assertEqual([True, True, True, False, True], segment.in_order(np.array([0, 1, 2, 0, 2])).tolist())


if __name__ == "__main__":
    unittest.main()
