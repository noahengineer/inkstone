#!/usr/bin/env python3
"""Tests for M12's aligner. Stdlib only, no network, no credentials.

    python tools/m12/test_align.py

Everything here runs against hand-written data, deliberately: the whole point of `align.py` being
a pure function is that the alignment can be proven right before a key exists or a lecture is
uploaded. The properties tested are the ones where being wrong is *silent* — mixed units, a torn
tail, a dead run swallowing speech — because a mis-aligned transcript reads as entirely plausible.
"""

import json
import unittest

from align import (
    DEAD_RUN_MS,
    Gap,
    Stroke,
    Timeline,
    Word,
    clock,
    dead_runs,
    dominant_speaker,
    page_spans,
    parse_timeline,
    parse_transcript,
    render,
)


class ParseTimelineTest(unittest.TestCase):
    def test_reads_strokes_gaps_and_pages(self):
        t = parse_timeline("obj-a 1200 page-1\n#gap 60000 195000\nobj-b 240500 page-2\n")
        self.assertEqual([("obj-a", 1200, "page-1"), ("obj-b", 240500, "page-2")],
                         [(s.object_id, s.offset_ms, s.page) for s in t.strokes])
        self.assertEqual([(60000, 195000)], [(g.from_ms, g.to_ms) for g in t.gaps])

    def test_two_column_files_still_read(self):
        """The format shipped without a page column; those recordings must still align."""
        t = parse_timeline("obj-a 1200\nobj-b 2400\n")
        self.assertEqual(2, len(t.strokes))
        self.assertIsNone(t.strokes[0].page)

    def test_a_torn_tail_stops_the_read(self):
        """A process killed mid-append: keep what is whole, and stop. Never skip and continue."""
        t = parse_timeline("obj-a 1200\nobj-b notanumber\nobj-c 9999\n")
        self.assertEqual(["obj-a"], [s.object_id for s in t.strokes])

    def test_unknown_extra_columns_are_ignored(self):
        """Forward compatibility, matching the Kotlin writer: a newer column must not truncate."""
        t = parse_timeline("obj-a 1200 page-1 pressure 0.4\nobj-b 2400 page-1\n")
        self.assertEqual(2, len(t.strokes))


class TranscriptTest(unittest.TestCase):
    def test_words_are_milliseconds(self):
        """⚠️ `words[].start/end` are ms while `audio_duration` is seconds. Mixing them is the
        single easiest way to mis-align a whole lesson by a factor of a thousand."""
        words = parse_transcript({"words": [{"text": "hello", "start": 1500, "end": 1900}]})
        self.assertEqual(1500, words[0].start_ms)
        self.assertEqual("00:00:01", clock(words[0].start_ms))

    def test_dominant_speaker_is_the_one_talking_longest(self):
        words = [
            Word("a", 0, 10_000, "A"),
            Word("b", 10_000, 11_000, "B"),
            Word("c", 11_000, 20_000, "A"),
        ]
        self.assertEqual("A", dominant_speaker(words))

    def test_no_diarization_means_no_guess(self):
        self.assertIsNone(dominant_speaker([Word("a", 0, 100)]))


class PageSpanTest(unittest.TestCase):
    def test_span_is_first_to_last_stroke_in_first_written_order(self):
        t = Timeline(strokes=[
            Stroke("a", 5_000, "p1"),
            Stroke("b", 400_000, "p2"),
            Stroke("c", 9_000, "p1"),
        ])
        self.assertEqual([("p1", 5_000, 9_000), ("p2", 400_000, 400_000)], page_spans(t))

    def test_no_pages_is_not_an_error(self):
        self.assertEqual([], page_spans(Timeline(strokes=[Stroke("a", 1, None)])))


class DeadRunTest(unittest.TestCase):
    def test_a_run_with_neither_speech_nor_ink_is_dead(self):
        words = [Word("one", 0, 1_000), Word("two", 600_000, 601_000)]
        runs = dead_runs(words, Timeline(), 601_000)
        self.assertEqual([(1_000, 600_000)], runs)

    def test_writing_keeps_a_silent_stretch_alive(self):
        """⚠️ The improvement over an audio-only threshold. Someone quietly working through a
        problem is the *opposite* of dead time, and an amplitude cut would delete it."""
        words = [Word("one", 0, 1_000), Word("two", 600_000, 601_000)]
        timeline = Timeline(strokes=[Stroke("a", 300_000, "p1")])
        runs = dead_runs(words, timeline, 601_000)
        self.assertTrue(all(end - start < 600_000 for start, end in runs))
        self.assertNotIn((1_000, 600_000), runs)

    def test_a_microphone_gap_is_not_dead_time(self):
        """No audio was captured there. "Nothing recorded" and "nothing happened" are different
        claims, and only one of them is true."""
        timeline = Timeline(gaps=[Gap(1_000, 600_000)])
        self.assertEqual([], dead_runs([Word("one", 0, 1_000)], timeline, 601_000))

    def test_short_pauses_are_left_alone(self):
        words = [Word("one", 0, 1_000), Word("two", 1_000 + DEAD_RUN_MS - 1, 30_000)]
        self.assertEqual([], dead_runs(words, Timeline(), 30_000))


class RenderTest(unittest.TestCase):
    def lesson(self):
        words = [
            Word("So", 10_000, 10_400, "A"),
            Word("the", 10_400, 10_600, "A"),
            Word("gradient", 10_600, 11_200, "A"),
            Word("Question?", 700_000, 700_800, "B"),
            Word("Yes", 701_000, 701_400, "A"),
        ]
        timeline = Timeline(
            strokes=[Stroke("a", 12_000, "p1"), Stroke("b", 705_000, "p2")],
            gaps=[Gap(300_000, 360_000)],
        )
        return words, timeline

    def test_the_transcript_is_whole_and_in_order(self):
        """The revision §15.6 records: annotate, never fragment. Every word appears once, in
        order, and no sentence is cut across a page boundary."""
        words, timeline = self.lesson()
        md = render(words, timeline, 800_000, "Straight lines", "doc-1", "2026-08-11", "universal-3-5-pro")
        for w in ("So", "gradient", "Question?", "Yes"):
            self.assertEqual(1, md.count(w), f"{w!r} appeared {md.count(w)} times")
        self.assertLess(md.index("gradient"), md.index("Question?"))

    def test_pages_are_anchored_by_time_not_reproduced_as_text(self):
        words, timeline = self.lesson()
        md = render(words, timeline, 800_000, "Straight lines", "doc-1", "2026-08-11", "m")
        self.assertIn("page `p1`", md)
        self.assertIn("written 00:00:12", md)
        # Nothing recognises the writing here; the PDF already holds it.
        self.assertNotIn("**Wrote:**", md)

    def test_a_microphone_gap_is_reported_as_missing_not_silent(self):
        words, timeline = self.lesson()
        md = render(words, timeline, 800_000, "T", "doc-1", "2026-08-11", "m")
        self.assertIn("No audio captured between 00:05:00 and 00:06:00", md)
        self.assertIn("missing, not silent", md)

    def test_the_speaker_guess_is_labelled_as_a_guess(self):
        """⚠️ Diarization returns anonymous letters. §13 spike 1 assumed it identified the
        teacher; it does not, so the artifact must not claim it did."""
        words, timeline = self.lesson()
        md = render(words, timeline, 800_000, "T", "doc-1", "2026-08-11", "m")
        self.assertIn("likelyTeacher: A", md)
        self.assertIn("heuristic", md)
        # And the other speaker is kept — a student's question is often why a line was written.
        self.assertIn("**B:**", md)

    def test_a_recording_with_no_speech_says_so(self):
        md = render([], Timeline(), 60_000, "T", "doc-1", "2026-08-11", "m")
        self.assertIn("Nothing was transcribed", md)

    def test_a_recording_with_no_writing_still_produces_the_transcript(self):
        """A lesson you listened to without writing is ordinary, and the speech is still the point."""
        md = render([Word("hello", 0, 500)], Timeline(), 60_000, "T", "doc-1", "2026-08-11", "m")
        self.assertIn("hello", md)
        self.assertIn("not anchored", md)


class EndToEndTest(unittest.TestCase):
    def test_a_realistic_response_shape_renders(self):
        """AssemblyAI's documented shape, including the seconds/milliseconds split."""
        payload = {
            "audio_duration": 2928.13,
            "speech_model_used": "universal-3-5-pro",
            "words": [{"text": "Right", "start": 6_100, "end": 6_500, "speaker": "A"}],
        }
        words = parse_transcript(payload)
        audio_ms = int(float(payload["audio_duration"]) * 1000)
        self.assertEqual(2_928_130, audio_ms)
        md = render(words, parse_timeline("obj-a 6100 p1\n"), audio_ms, "L", "d", "2026-08-11",
                    payload["speech_model_used"])
        self.assertIn("duration: 00:48:48", md)
        self.assertIn("assemblyai/universal-3-5-pro", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
