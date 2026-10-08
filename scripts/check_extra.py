#!/usr/bin/env python3
"""Aplica el detector de mojibake a una lista explicita de ficheros."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from fix_mojibake import uncorrupt_line  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

FILES = [
    "AGENTS.md",
    "README.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "gradle.properties",
    "settings.gradle",
    "build.gradle",
    "v1_21_1/build.gradle",
    "bootstrap.ps1",
    "bootstrap.sh",
    "build.sh",
    "build.ps1",
    "test.sh",
    "scripts/azorea.ps1",
]

for name in FILES:
    p = Path(name)
    if not p.exists():
        continue
    try:
        text = p.read_text(encoding="utf-8")
    except UnicodeDecodeError as e:
        print(f"[{p.name}] READ-ERROR: {e}")
        continue
    fixed_lines = []
    skip_lines = []
    for i, line in enumerate(text.split("\n"), 1):
        out, n = uncorrupt_line(line)
        if out is None:
            if any(ord(c) > 127 for c in line):
                skip_lines.append((i, line))
        elif out != line:
            fixed_lines.append((i, line, out))
    if fixed_lines:
        print(f"[CORRUPTO] {p.name}: {len(fixed_lines)} lineas reparables")
        for i, old, new in fixed_lines[:5]:
            print(f"    L{i}: {old.strip()[:70]!r}")
            print(f"       -> {new.strip()[:70]!r}")
    elif skip_lines:
        print(f"[ok] {p.name}: sin corrupcion ({len(skip_lines)} lineas no-ascii correctas)")
    else:
        print(f"[ok] {p.name}: solo ascii")
