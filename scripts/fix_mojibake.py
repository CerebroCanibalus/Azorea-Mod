#!/usr/bin/env python3
"""Azorea - reparador de mojibake UTF-8 <-> CP1252.

Diagnóstico confirmado (hexdump de AzoreaInviteAcceptScreen.java:68):
  bytes C3 83 C6 92 C3 86 E2 80 99 ... = 'ÃƒÆ'Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§a'
  donde deberia haber '§a'.

Corrupcion = aplicar N veces:  s -> s.encode('utf-8').decode('cp1252')
Inversa    = aplicar N veces:  s -> s.encode('cp1252').decode('utf-8')

Propiedades clave (por eso es seguro):
  * ASCII sobrevive intacto (identidad)          -> lineas ASCII no cambian
  * Un '§' CORRECTO hace fallar la inversa       -> se preserva, no se toca
  * Un texto corrupto tiene chars (ƒ ' ‚ € š)
    que SI estan en cp1252 y reconstruyen los bytes UTF-8 originales

Si la inversa falla en una linea, esa linea contiene texto correcto
no-ASCII mezclado -> se marca como SKIP y NO se modifica (a revisar manualmente).

Uso:
  python scripts/fix_mojibake.py --dry      # solo reporta
  python scripts/fix_mojibake.py --apply    # escribe cambios
"""

import argparse
import sys
from pathlib import Path

# La consola Windows (cp1252) no puede imprimir -> ni f esritable.
# Forzamos UTF-8 para que el reporte muestre los chars reales.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

MAX_ITER = 8

# --- Mapa byte<->char de cp1252 INCLUYENDO los slots indefinidos ----------------
# Python rechaza 0x81/0x8D/0x8F/0x90/0x9D, pero la corrupcion original la hizo
# .NET/Java (que SI los mapea a U+00xx). Sin esto, las lineas que contienen
# un '—' (em dash) corrupto no se pueden invertir.
_B2C = []
for _i in range(256):
    try:
        _B2C.append(bytes([_i]).decode("cp1252"))
    except UnicodeDecodeError:
        _B2C.append(chr(_i))
B2C = tuple(_B2C)
C2B = {ch: i for i, ch in enumerate(B2C)}


def fix_once(s: str) -> str:
    """Inversa de un ciclo de corrupcion.

    1) chars -> bytes usando el mapa cp1252 (KeyError si el char no pertenece
       a cp1252, p.ej. alguno correcto)  -> marca 'no corrupto'
    2) bytes -> chars como UTF-8         (UnicodeDecodeError si no es UTF-8 valido,
       p.ej. un '§' correcto cuyo byte es 0xA7) -> marca 'no corrupto'
    """
    bs = bytes(C2B[ch] for ch in s)
    return bs.decode("utf-8")


def is_pure_ascii(s: str) -> bool:
    return all(ord(c) < 128 for c in s)


def uncorrupt_line(line: str):
    """Devuelve (linea_reparada, iteraciones) o (None, 0) si no se pudo/requerido."""
    if is_pure_ascii(line):
        return line, 0

    cur = line
    n = 0
    while n < MAX_ITER:
        try:
            nxt = fix_once(cur)
        except (UnicodeEncodeError, UnicodeDecodeError, KeyError):
            # KeyError  => char fuera de cp1252 (p.ej. '⊘', '→' correcto)
            # Unicode*  => byte invalido como UTF-8 (p.ej. '§' correcto = 0xA7)
            break
        if nxt == cur:
            break
        cur = nxt
        n += 1

    if n == 0:
        return None, 0
    return cur, n


def process_file(path: Path, apply: bool):
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as e:
        return {"path": path, "error": f"read: {e}"}

    lines = text.split("\n")
    out = []
    stats = {"fixed": 0, "skip": 0, "ascii": 0, "unchanged_nonascii": 0, "iters": {}}
    samples = []

    for idx, line in enumerate(lines, 1):
        fixed, n = uncorrupt_line(line)
        if fixed is None:
            if is_pure_ascii(line):
                stats["ascii"] += 1
                out.append(line)
            else:
                # No-ascii que no se pudo corregir: o es puro ascii (arriba)
                # o contiene texto correcto no-ascii -> SKIP (preservar).
                stats["skip"] += 1
                out.append(line)
                if len(samples) < 4:
                    samples.append((idx, "SKIP", line.strip()[:110]))
            continue

        if n == 0:
            stats["ascii"] += 1
            out.append(line)
        elif fixed == line:
            stats["unchanged_nonascii"] += 1
            out.append(line)
        else:
            stats["fixed"] += 1
            stats["iters"][n] = stats["iters"].get(n, 0) + 1
            out.append(fixed)
            if len(samples) < 4:
                samples.append((idx, f"FIX x{n}", line.strip()[:70] + "  ==>  " + fixed.strip()[:70]))

    changed = "\n".join(out) != text
    if changed and apply:
        path.write_text("\n".join(out), encoding="utf-8", newline="")

    return {"path": path, "stats": stats, "changed": changed, "samples": samples}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="escribir cambios")
    ap.add_argument("--dry", action="store_true", help="solo reportar (default)")
    ap.add_argument("--root", default="v1_21_1/src/main", help="raiz a escanear")
    ap.add_argument("--ext", default=".java,.toml", help="extensiones")
    args = ap.parse_args()

    apply = bool(args.apply)
    exts = tuple(e.strip() for e in args.ext.split(",") if e.strip())
    root = Path(args.root)
    files = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix in exts)

    print(f"Escaneando {len(files)} archivos en {root}  [modo={'APPLY' if apply else 'DRY-RUN'}]\n")

    total_fixed = total_skip = total_changed = 0
    problem_files = []

    for f in files:
        r = process_file(f, apply)
        if "error" in r:
            print(f"[ERROR] {f}: {r['error']}")
            problem_files.append(f)
            continue
        st = r["stats"]
        if not r["changed"] and st["skip"] == 0:
            continue
        total_changed += 1 if r["changed"] else 0
        total_fixed += st["fixed"]
        total_skip += st["skip"]
        it = ",".join(f"x{k}:{v}" for k, v in sorted(st["iters"].items()))
        flag = "SKIP!" if st["skip"] else "ok"
        print(f"[{flag}] {f.name}: fixed={st['fixed']} skip={st['skip']} iter=[{it}]")
        for ln, kind, txt in r["samples"]:
            print(f"        L{ln} {kind}: {txt}")
        if st["skip"]:
            problem_files.append(f)

    print()
    print("=" * 70)
    print(f"Archivos con cambios : {total_changed}")
    print(f"Lineas reparadas     : {total_fixed}")
    print(f"Lineas SKIP (mixtas) : {total_skip}")
    if problem_files:
        print(f"\nArchivos con SKIP (revisar a mano): {len(problem_files)}")
        for p in problem_files:
            print(f"  - {p}")
    if not apply:
        print("\nMODO DRY-RUN: nada escrito. Re-ejecuta con --apply para escribir.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
