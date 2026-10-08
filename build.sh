#!/usr/bin/env bash
# build.sh — compila :v1_21_1 y copia el jar a test-server/mods/.
# Uso (Git Bash / WSL / Linux / macOS): ./build.sh
# Sin tocar mundos ni configs del test-server.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "[build] Compilando :v1_21_1..."
./gradlew :v1_21_1:build --no-daemon

# Localizar jar generado (excluyendo -sources y -javadoc).
JAR="$(ls v1_21_1/build/libs/v1_21_1-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "[build] ERROR: no se encontró jar en v1_21_1/build/libs/" >&2
    exit 1
fi

MODS_DIR="$ROOT/test-server/mods"
mkdir -p "$MODS_DIR"

# Eliminar SOLO jars viejos del mod (no mundos, configs, eula.txt, etc.).
# § Seguridad: respetar test-server/ (AGENTS.md § Reglas operativas).
echo "[build] Limpiando jars viejos en test-server/mods/..."
rm -f "$MODS_DIR"/v1_21_1-*.jar
rm -f "$MODS_DIR"/azorea-*.jar

echo "[build] Copiando $(basename "$JAR") a test-server/mods/"
cp "$JAR" "$MODS_DIR/"

echo "[build] OK. Jar listo en: test-server/mods/$(basename "$JAR")"
