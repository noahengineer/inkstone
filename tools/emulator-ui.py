"""Drive Inkstone on an emulator over adb: dump the view tree, find a node, tap it.

    python tools/emulator-ui.py <serial> dump
    python tools/emulator-ui.py <serial> tap "Add"
    python tools/emulator-ui.py <serial> has "Needs attention"
    python tools/emulator-ui.py <serial> text "hello"

`dump` prints every node that has a label, with its bounds and whether it is clickable;
`tap` presses the centre of the first match; `has` exits 0/1 so it can gate a shell step.

WHY THIS EXISTS
---------------
CLAUDE.md's definition of done #6: *"every feature the milestone claims has been reached
the way a user reaches it"*, because a feature can be correct in every test and inert in
the app -- which has happened six times in this repo. The cheapest check that catches all
six is driving the real app through its real UI and reading the result out of
`databases/inkstone.db` with `run-as`. This is the driving half.

    adb -s <serial> exec-out "run-as dev.inkstone cat databases/inkstone.db" > db      # the reading half
    adb -s <serial> exec-out screencap > before.raw                                    # and the seeing half

⚠️ **Drawing needs `adb shell input stylus`, not `input tap`/`input swipe`.** `PalmGuard`
accepts `TOOL_TYPE_STYLUS` only (SPEC §3.3), so an ordinary swipe draws nothing and says
nothing about it. This script's `tap` is for controls, never for the canvas.

⚠️ Set `PYTHONIOENCODING=utf-8`. The app labels controls with `✎`, `⇩`, `↓` and `›`, and
Windows' default cp1252 stdout raises on all of them.

Matching is case-insensitive substring over both `text` and `content-desc`, and prefers a
clickable ancestor when the matched node is not itself clickable -- Compose puts the label
on a child of the button often enough that the label's own centre lands outside the ripple.

The only XML this parses is `uiautomator dump` output from an emulator we started, so
stdlib ElementTree is fine here. Don't lift it somewhere that parses XML from elsewhere
without swapping in defusedxml.
"""

import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


def _adb() -> str:
    """`adb` if it is on PATH, else the SDK's own copy.

    Not on PATH is the normal case on this machine -- the README never asks you to add
    `platform-tools`, so a tool that only worked after you had would fail with WinError 2
    and say nothing about why.
    """
    found = shutil.which("adb")
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(os.path.join(local, "Android", "Sdk"))
    for root in filter(None, roots):
        for name in ("adb.exe", "adb"):
            candidate = os.path.join(root, "platform-tools", name)
            if os.path.exists(candidate):
                return candidate
    raise SystemExit("adb not found: put platform-tools on PATH or set ANDROID_HOME")


ADB = _adb()


def sh(serial, *args, binary=False):
    p = subprocess.run([ADB, "-s", serial, *args], capture_output=True)
    if p.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {p.stderr.decode(errors='replace')}")
    return p.stdout if binary else p.stdout.decode("utf-8", errors="replace")


def tree(serial):
    sh(serial, "shell", "uiautomator", "dump", "/sdcard/__ui.xml")
    raw = sh(serial, "exec-out", "cat", "/sdcard/__ui.xml", binary=True)
    # uiautomator sometimes emits a stray trailing newline plus the "dumped to" line
    start = raw.find(b"<?xml")
    return ET.fromstring(raw[start:].decode("utf-8", errors="replace"))


def centre(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def walk(node, parents=()):
    yield node, parents
    for kid in node:
        yield from walk(kid, parents + (node,))


def label(node):
    return f"{node.get('text', '')} | {node.get('content-desc', '')}".strip(" |")


def find(serial, needle):
    want = needle.lower()
    hits = []
    for node, parents in walk(tree(serial)):
        hay = (node.get("text", "") + " " + node.get("content-desc", "")).lower()
        if want in hay:
            target = node
            if node.get("clickable") != "true":
                for anc in reversed(parents):
                    if anc.get("clickable") == "true":
                        target = anc
                        break
            hits.append((node, target))
    return hits


def main():
    serial, cmd = sys.argv[1], sys.argv[2]
    if cmd == "dump":
        for node, _ in walk(tree(serial)):
            if label(node):
                print(f"{label(node)}  @{node.get('bounds')}  clickable={node.get('clickable')}")
        return
    needle = sys.argv[3]
    hits = find(serial, needle)
    if cmd == "has":
        print("YES" if hits else "NO")
        raise SystemExit(0 if hits else 1)
    if not hits:
        raise SystemExit(f"no node matching {needle!r}")
    node, target = hits[0]
    if cmd == "tap":
        x, y = centre(target.get("bounds"))
        print(f"tap {label(node)!r} at {x},{y}")
        sh(serial, "shell", "input", "tap", str(x), str(y))
    elif cmd == "text":
        sh(serial, "shell", "input", "text", needle.replace(" ", "%s"))
    else:
        raise SystemExit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
