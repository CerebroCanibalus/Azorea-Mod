#!/usr/bin/env python3
"""Muestra las lineas SKIP (contenido mixto: correcto no-ascii + corrupto)."""
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

FILES = [
    "v1_21_1/src/main/java/com/azorea/mod/tracker/AzoreaInviteService.java",
    "v1_21_1/src/main/java/com/azorea/mod/v1211/client/screen/AzoreaHostSessionScreen.java",
    "v1_21_1/src/main/java/com/azorea/mod/v1211/client/screen/AzoreaInviteAcceptScreen.java",
]


def fix_once(s: str) -> str:
    return s.encode("cp1252").decode("utf-8")


def is_ascii(s: str) -> bool:
    return all(ord(c) < 128 for c in s)


for f in FILES:
    print("=" * 70)
    print(f)
    for i, line in enumerate(Path(f).read_text(encoding="utf-8").split("\n"), 1):
        if is_ascii(line):
            continue
        cur, n = line, 0
        while n < 8:
            try:
                nxt = fix_once(cur)
            except Exception:
                break
            if nxt == cur:
                break
            cur, n = nxt, n + 1
        if n == 0:
            cps = " ".join("U+%04X" % ord(c) for c in line if ord(c) > 127)
            print(f"  L{i} SKIP: {line.strip()[:130]}")
            print(f"        nonascii: {cps[:200]}")
