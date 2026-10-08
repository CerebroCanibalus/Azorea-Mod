#!/usr/bin/env bash
# build.sh — compila un subproyecto del mod y copia el jar a test-server/mods/.
# Uso: ./build.sh [version]        version: v1_21_1 (default) | v1_21_3
# Sin tocar mundos ni configs del test-server.

set -euo pipefail

VERSION="${1:-v1_21_1}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "[build] Compilando :$VERSION..."
./gradlew ":$VERSION:build" --no-daemon

# Localizar jar generado (excluyendo -sources y -javadoc).
JAR="$(ls "$VERSION"/build/libs/"$VERSION"-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "[build] ERROR: no se encontró jar en $VERSION/build/libs/" >&2
    exit 1
fi

MODS_DIR="$ROOT/test-server/mods"
mkdir -p "$MODS_DIR"

# Eliminar SOLO jars viejos del mod (no mundos, configs, eula.txt, etc.).
# Seguridad: respetar test-server/ (AGENTS.md -> Reglas operativas).
echo "[build] Limpiando jars viejos del mod en test-server/mods/..."
rm -f "$MODS_DIR"/v1_21_*.jar
rm -f "$MODS_DIR"/azorea-*.jar

echo "[build] Copiando $(basename "$JAR") a test-server/mods/"
cp "$JAR" "$MODS_DIR/"

echo "[build] OK. Jar listo en: test-server/mods/$(basename "$JAR")"
