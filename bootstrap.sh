#!/usr/bin/env bash
# Bootstrap Gradle wrapper for Azorea (Git Bash / WSL / Linux / macOS).
# Uso: ./bootstrap.sh
# Descarga Gradle 8.10, genera el wrapper, limpia temporales.
# Solo se ejecuta una vez; si ./gradlew ya existe, sale sin hacer nada.

set -euo pipefail

GRADLE_VERSION="9.7.1"
GRADLE_DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
TEMP_DIR="$(mktemp -d)"
ZIP_PATH="${TEMP_DIR}/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_HOME="${TEMP_DIR}/gradle-${GRADLE_VERSION}"

if [ -x "./gradlew" ]; then
    echo "[bootstrap] gradlew ya existe. Nada que hacer."
    exit 0
fi

echo "[bootstrap] Descargando Gradle ${GRADLE_VERSION}..."
curl -fsSL -o "$ZIP_PATH" "$GRADLE_DIST_URL"

echo "[bootstrap] Extrayendo..."
unzip -q "$ZIP_PATH" -d "$TEMP_DIR"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
    echo "[bootstrap] ERROR: no se encontró gradle en ${GRADLE_HOME}/bin/gradle" >&2
    exit 1
fi

echo "[bootstrap] Generando wrapper..."
"${GRADLE_HOME}/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin

echo "[bootstrap] Limpiando temporales..."
rm -rf "$TEMP_DIR"

echo "[bootstrap] Listo. Ahora puedes usar: ./gradlew :v1_21_1:build"
echo "[bootstrap] El primer build descargara JDK 21 desde Adoptium (Gradle toolchain)."
