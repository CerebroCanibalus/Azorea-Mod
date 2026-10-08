#!/usr/bin/env python3
"""Prueba: recupera el mapa char->byte de cp1252 incluyendo los slots indefinidos
(0x81,0x8D,0x8F,0x90,0x9D) y comprueba si invierte el mojibake real."""
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# byte -> char para los 256 bytes.
# Python RECHAZA los slots indefinidos de cp1252 (0x81,0x8D,0x8F,0x90,0x9D),
# pero .NET/Java (que hicieron la corrupcion original) SI los mapean a U+00xx.
# Los reconstruimos manualmente para que la inversa sea completa.
_b2c = []
for _i in range(256):
    try:
        _b2c.append(bytes([_i]).decode("cp1252"))
    except UnicodeDecodeError:
        _b2c.append(chr(_i))  # slot indefinido -> control C1 equivalente
b2c = tuple(_b2c)
print("byte 0x9D -> U+%04X" % ord(b2c[0x9D]))
print("byte 0x81 -> U+%04X" % ord(b2c[0x81]))
print("byte 0xE2 -> U+%04X (%s)" % (ord(b2c[0xE2]), b2c[0xE2]))
c2b = {ch: i for i, ch in enumerate(b2c)}


def corrupt(s, n):
    """Simula la corrupcion original (hecha por .NET/Java, que SI decodifica 0x9D)."""
    for _ in range(n):
        s = "".join(b2c[b] for b in s.encode("utf-8"))
    return s


def fix_custom(s):
    """Inversa usando mapa propio (acepta slots indefinidos de cp1252)."""
    bs = bytes(c2b[ch] for ch in s)  # KeyError => char no pertenece a cp1252
    return bs.decode("utf-8")  # UnicodeDecodeError => no es utf-8 valido


print("\n--- ciclo de corrupcion de '—' (U+2014, em dash) ---")
s = "Host Game — Active"
for n in range(0, 5):
    c = corrupt(s, n)
    cps = " ".join("U+%04X" % ord(x) for x in c if ord(x) > 127)
    print(f"  x{n}: {c!r}  nonascii=[{cps}]")

print("\n--- inversion con mapa propio ---")
real = "Host Game \u2014 Active"
for n in (1, 2, 3, 4):
    c = corrupt(real, n)
    try:
        r = fix_custom(c)
        ok = "OK " if r == real else "MAL"
        print(f"  x{n}: {ok} -> {r!r}")
    except Exception as e:
        print(f"  x{n}: FAIL {type(e).__name__}: {e}")

print("\n--- ¿preserva texto CORRECTO? (debe fallar, no tocar) ---")
for good in ["// \u00a7 F8.x: relay info", "sesi\u00f3n activa", "Dise\u00f1o", "ascii puro"]:
    try:
        r = fix_custom(good)
        print(f"  CAMBIA (!): {good!r} -> {r!r}")
    except Exception as e:
        print(f"  preserva OK ({type(e).__name__}): {good!r}")

print("\n--- ¿preserva '—' CORRECTO? ---")
try:
    r = fix_custom("Host Game \u2014 Active")
    print(f"  CAMBIA (!): {r!r}")
except Exception as e:
    print(f"  preserva OK ({type(e).__name__})")
