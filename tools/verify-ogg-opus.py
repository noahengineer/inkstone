#!/usr/bin/env python3
"""Verify an Ogg/Opus file **without using the encoder that wrote it** (M12, docs/M12-AUDIO.md §9).

CLAUDE.md's standing lesson: *"Verifying a PDF by re-parsing it with the library that wrote it
proves almost nothing."* M4's published PDFs were valid, parseable and blank in every viewer that
was not PdfBox. The same trap is waiting here, and it is worse: the whole reason M12 chose Ogg over
MP4 is the claim that *a file whose process was killed at minute 50 is still fifty playable
minutes*, and `MediaRecorder` is exactly the wrong thing to ask.

So this walks the container by hand:

  * every page's `OggS` capture pattern and its **CRC-32**, with Ogg's own polynomial
    (0x04c11db7, non-reflected, init 0, no final xor). A page that was half-written when the
    process died fails this, which is how a torn tail is distinguished from corruption;
  * the `OpusHead` identification packet — channel count, pre-skip, input sample rate;
  * the `OpusTags` comment packet that must follow it;
  * the final granule position, which gives the duration in 48 kHz samples. That is the number
    that says how much of the lecture survived.

Exit code 0 means the file is a well-formed Opus stream up to its last complete page. A truncated
tail is **reported, not failed** — that is the expected shape of a killed recording and is the
property being verified.

    python tools/verify-ogg-opus.py recording.opus
"""

import struct
import sys

OGG_MAGIC = b"OggS"
SAMPLE_RATE = 48000
PAGE_HEADER_BYTES = 27


def segment_table_len(data: bytes, offset: int) -> int:
    """How many segment-table bytes this page's header declares."""
    return data[offset + 26]


def crc32_ogg(data: bytes) -> int:
    """Ogg's CRC: polynomial 0x04c11db7, MSB-first, no reflection, init 0, no final xor."""
    crc = 0
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def pages(data: bytes):
    """Yield (offset, header_type, granule, payload, crc_ok) for every complete page."""
    offset = 0
    while offset + 27 <= len(data):
        if data[offset : offset + 4] != OGG_MAGIC:
            raise ValueError(f"no OggS capture pattern at byte {offset}")
        version = data[offset + 4]
        if version != 0:
            raise ValueError(f"unknown Ogg version {version} at byte {offset}")
        header_type = data[offset + 5]
        granule = struct.unpack_from("<q", data, offset + 6)[0]
        stored_crc = struct.unpack_from("<I", data, offset + 22)[0]
        segments = data[offset + 26]
        table_end = offset + 27 + segments
        if table_end > len(data):
            return  # torn: the segment table itself is incomplete
        payload_len = sum(data[offset + 27 : table_end])
        page_end = table_end + payload_len
        if page_end > len(data):
            return  # torn: the last page was still being written

        # The CRC is computed over the whole page with its own CRC field zeroed.
        page = bytearray(data[offset:page_end])
        page[22:26] = b"\x00\x00\x00\x00"
        yield offset, header_type, granule, data[table_end:page_end], crc32_ogg(bytes(page)) == stored_crc
        offset = page_end


def main(path: str) -> int:
    data = open(path, "rb").read()
    print(f"{path}: {len(data):,} bytes")

    complete = 0
    bad_crc = 0
    last_granule = 0
    head = None
    tags_seen = False
    end_of_last_page = 0

    for offset, _header, granule, payload, crc_ok in pages(data):
        complete += 1
        end_of_last_page = offset + PAGE_HEADER_BYTES + segment_table_len(data, offset) + len(payload)
        if not crc_ok:
            bad_crc += 1
        if payload.startswith(b"OpusHead"):
            channels = payload[9]
            pre_skip = struct.unpack_from("<H", payload, 10)[0]
            input_rate = struct.unpack_from("<I", payload, 12)[0]
            head = (payload[8], channels, pre_skip, input_rate)
        elif payload.startswith(b"OpusTags"):
            tags_seen = True
        if granule > 0:
            last_granule = granule

    if head is None:
        print("  FAIL: no OpusHead packet — this is not an Opus stream")
        return 1
    version, channels, pre_skip, input_rate = head
    print(f"  OpusHead: version={version} channels={channels} pre-skip={pre_skip} input rate={input_rate} Hz")
    print(f"  OpusTags present: {tags_seen}")
    print(f"  complete pages: {complete}   CRC failures: {bad_crc}")

    seconds = max(0, last_granule - pre_skip) / SAMPLE_RATE
    print(f"  final granule: {last_granule:,} -> {seconds:.2f} s of audio recoverable")

    # The torn tail a kill leaves behind: whatever follows the last page that parsed whole.
    # This is the entire loss, and the number that makes the Ogg-over-MP4 decision concrete —
    # an MP4 killed here would have lost the index and therefore all of it.
    print(f"  torn tail after the last complete page: {len(data) - end_of_last_page:,} bytes")

    if bad_crc:
        print("  FAIL: a complete page failed its CRC — the file is corrupt, not merely truncated")
        return 1
    if not tags_seen:
        print("  FAIL: no OpusTags packet")
        return 1
    if seconds <= 0:
        print("  FAIL: no audio decoded")
        return 1
    print("  OK: a well-formed Opus stream, playable up to its last complete page")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
