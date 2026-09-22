#!/usr/bin/env python3
"""How long an Ogg Opus recording is, read from the container rather than guessed.

The Python counterpart of `core/storage/.../OggOpus.kt`, and it exists for the reason M12 §15.3
records: **a duration must be read from the container, never estimated from bytes ÷ bitrate.**
`MediaRecorder` treats `setAudioEncodingBitRate` as a hint and Opus is VBR — asked for 24 kbit/s
it produced 34.3 — so the estimate came out **42 % high** and was being shown to a person as a
number.

The reader needs this for exactly one case, and it is a case that matters: a recording that has
**no transcript yet**. A transcribed one carries its duration in the merged markdown's front
matter, but an un-transcribed one has nothing to say how long it is, and the alternative fallback
— the offset of the last stroke — is a *lie about the tail*. The measured lesson's last stroke is
at 2,907.8 s against 2,928.1 s of audio, and twenty-four of its pauses run longer than fifteen
seconds, so a lesson that ended with the teacher talking would appear to stop when the writing did
and every bar in the histogram would be stretched to fill it.

## How it works

An Ogg file is a sequence of pages, each beginning with the capture pattern `OggS`. Byte 6 of a
page header starts a 64-bit little-endian **granule position**: for Opus, the number of samples
decoded at 48 kHz once that page is finished. The last page's granule is therefore the length of
the stream, minus the encoder's pre-skip, which the `OpusHead` packet in the very first page
declares.

So: read the tail, find the last `OggS`, take its granule; read the head, find `OpusHead`, take
its pre-skip. Two small range reads for an exact answer, against twelve megabytes for a wrong one.

⚠️ **A truncated file still answers**, which is the whole reason M12 chose Ogg over MP4: a process
killed mid-lecture leaves a shorter recording rather than an unplayable one. The last *complete*
page is what this finds, so a torn tail costs the fragment after it and nothing else.
"""

from __future__ import annotations

import struct

SAMPLE_RATE = 48_000
"""Opus granule positions are always at 48 kHz, whatever the input rate was."""

TAIL_BYTES = 96 * 1024
"""What to read from the end. `OggOpus.kt` uses the same figure; an Ogg page is at most 65,307
bytes, so this always contains at least one complete page boundary."""

HEAD_BYTES = 4 * 1024
"""Enough for the first page, which is where `OpusHead` is required to be."""

CAPTURE = b"OggS"
_HEAD_MAGIC = b"OpusHead"

# Page header: "OggS" version(1) type(1) granule(8) serial(4) seq(4) crc(4) segments(1) = 27 bytes.
_GRANULE_OFFSET = 6
_MIN_PAGE_HEADER = 27

# OpusHead: magic(8) version(1) channels(1) pre-skip(2, LE) ...
_PRESKIP_OFFSET = 10


def last_granule(tail: bytes) -> int | None:
    """The granule position of the last complete page in `tail`, or None."""
    position = tail.rfind(CAPTURE)
    while position >= 0:
        if position + _MIN_PAGE_HEADER <= len(tail):
            granule = struct.unpack_from("<q", tail, position + _GRANULE_OFFSET)[0]
            # -1 means "this page holds no completed packet", which is legal; keep walking back.
            if granule >= 0:
                return granule
        position = tail.rfind(CAPTURE, 0, position)
    return None


def pre_skip(head: bytes) -> int:
    """The encoder delay the `OpusHead` declares, in 48 kHz samples. 0 when it cannot be found."""
    position = head.find(_HEAD_MAGIC)
    if position < 0 or position + _PRESKIP_OFFSET + 2 > len(head):
        return 0
    return struct.unpack_from("<H", head, position + _PRESKIP_OFFSET)[0]


def duration_ms(head: bytes, tail: bytes) -> int | None:
    """Milliseconds of audio, or None when this does not look like an Ogg Opus stream."""
    granule = last_granule(tail)
    if granule is None:
        return None
    samples = max(0, granule - pre_skip(head))
    return samples * 1000 // SAMPLE_RATE


def duration_of(source, path: str, size: int) -> int | None:  # noqa: ANN001 - source.Source
    """Read both ends of `path` through `source` and return its length in milliseconds.

    Returns None rather than raising for anything unexpected — an unreadable file, a server that
    refuses ranges, bytes that are not Ogg. The caller has a worse answer available and should be
    free to fall back to it without a try block.
    """
    if size <= 0:
        return None
    try:
        head = source.read_range(path, 0, min(HEAD_BYTES, size) - 1)
        tail = source.read_range(path, max(0, size - TAIL_BYTES), size - 1)
    except Exception:  # noqa: BLE001
        return None
    if not head.startswith(CAPTURE):
        return None
    return duration_ms(head, tail)
