#!/usr/bin/env bash
# =============================================================================
# Azorea - setup de VPS (Oracle Cloud / Ubuntu 24.04 aarch64)
#
# Instala:
#   1. Java 21 (ARM)
#   2. tracker-server  -> systemd, puerto 9090  (RENDezvous, DA-12)
#   3. MC NeoForge 21.1.250 -> systemd, puerto 25565, c/ Azorea en mods/
#
# Uso (despues de scp de los 3 ficheros):
#   bash vps-setup.sh [--mc-user NOMBRE] [--firewall] [--no-mc] [--no-tracker]
#
#   --mc-user N   -> pone white-list=true y anade N (RECOMENDADO: el server va
#                    en online-mode=false porque tus clientes dev son offline,
#                    asi que SIN whitelist cualquiera en internet puede entrar)
#   --firewall    -> activa ufw (por defecto NO: la Security List de Oracle ya
#                    filtra, y activarlo a ciego puede dejarte sin SSH)
#   --no-mc       -> solo el tracker
#   --no-tracker  -> solo MC
# =============================================================================
set -euo pipefail

MC_PORT=25565
TRACKER_PORT=9090
NF_VERSION="21.1.250"          # MC 1.21.1 (DA-4). NO cambiar sin leer gradle.properties.
BASE="/opt/azorea"
MC_DIR="$BASE/mc"
TRK_DIR="$BASE/tracker"
TRK_JAR="tracker-server-1.1.1.jar"   # origen en build/libs (el 0.1.0 es obsoleto: 29 KB vs 86 KB)
TRK_DEST="azorea-tracker.jar"        # nombre con el q/ queda instalado -> sin ambiguedades
MOD_JAR="v1_21_1-1.1.1.jar"

MC_USER=""
DO_FIREWALL=0
DO_MC=1
DO_TRACKER=1
for a in "$@"; do
  case "$a" in
    --mc-user)    ;;                                  # valor viene despues
    --mc-user=*)  MC_USER="${a#--mc-user=}" ;;
    --firewall)   DO_FIREWALL=1 ;;
    --no-mc)      DO_MC=0 ;;
    --no-tracker) DO_TRACKER=0 ;;
    -*) echo "flag desconocido: $a"; exit 2 ;;
    *)  if [ -n "$MC_USER" ]; then :; else MC_USER="$a"; fi ;;
  esac
done

log()  { printf '\033[1;36m[Azorea]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[AVISO]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "ejecutar como root: sudo bash vps-setup.sh"
RUN_USER="${SUDO_USER:-ubuntu}"
id "$RUN_USER" >/dev/null 2>&1 || RUN_USER="root"

# --------------------------------------------------------------- 1. Java 21
log "1/5 Java 21"
if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y >/dev/null
  apt-get install -y curl ca-certificates openjdk-21-jdk-headless >/dev/null
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y curl ca-certificates java-21-openjdk-devel >/dev/null
fi
  JAVA_BIN="$(command -v java || true)"
  [ -n "$JAVA_BIN" ] || die "java 21 no disponible. Ubuntu 20.04 NO trae openjdk-21 -> Create instance > Change image > Ubuntu 24.04 (aarch64), y re-ejecuta."
JVER="$("$JAVA_BIN" -version 2>&1 | head -1)"
echo "      $JVER"
echo "$JVER" | grep -q '"21' || die "se necesita Java 21, no coincide: $JVER"

install -d -o "$RUN_USER" -g "$RUN_USER" "$MC_DIR" "$TRK_DIR"

# ------------------------------------------------------- 2. tracker-server
if [ "$DO_TRACKER" -eq 1 ]; then
  log "2/5 Azorea tracker (rendezvous) en :$TRACKER_PORT"
  SRC_TRK="$(dirname "$0")/$TRK_JAR"
  [ -f "$SRC_TRK" ] || SRC_TRK="$PWD/$TRK_JAR"
  [ -f "$SRC_TRK" ] || SRC_TRK="$PWD/$TRK_DEST"
  [ -f "$SRC_TRK" ] || die "falta el jar d/ Azorea ($TRK_JAR) - subelo con scp junto al script"
  cp -f "$SRC_TRK" "$TRK_DIR/$TRK_DEST"
  chown "$RUN_USER:$RUN_USER" "$TRK_DIR/$TRK_DEST"

  cat > /etc/systemd/system/azorea-tracker.service <<EOF
[Unit]
Description=Azorea tracker-server (rendezvous)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$TRK_DIR
ExecStart=$JAVA_BIN -Xms64m -Xmx512m -Dtracker.port=$TRACKER_PORT -Dtracker.data=$TRK_DIR/tracker-data.json -jar $TRK_DEST
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now azorea-tracker >/dev/null 2>&1
  sleep 2

  # Autochequeo: si /punch/observe da 404, se subio el jar VIEJO (sin DA-12).
  OBS="$(curl -fsS --max-time 5 "http://127.0.0.1:$TRACKER_PORT/punch/observe" || true)"
  if echo "$OBS" | grep -q '"port"'; then
    log "      tracker OK -> $OBS"
  else
    die "tracker responde pero /punch/observe fallo: '$OBS' -> jar viejo (no el 1.1.1)"
  fi
fi

# --------------------------------------------------------- 3. MC NeoForge
if [ "$DO_MC" -eq 1 ]; then
  log "3/5 MC NeoForge $NF_VERSION (MC 1.21.1)"
  if [ ! -f "$MC_DIR/run.sh" ]; then
    INST="$MC_DIR/nf-installer.jar"
    curl -fsSL -o "$INST" \
      "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NF_VERSION/neoforge-$NF_VERSION-installer.jar"
    ( cd "$MC_DIR" && "$JAVA_BIN" -jar "$INST" --installServer ) \
      || die "instalador NeoForge fallo (¿region/URL?)"
    rm -f "$INST"
  else
    log "      NeoForge ya instalado, omito instalador"
  fi

  # eula + server.properties ANTES del primer arranque (ahorra el run doble).
  printf 'eula=true\n' > "$MC_DIR/eula.txt"
  cat > "$MC_DIR/server.properties" <<EOF
motd=Azorea VPS
server-port=$MC_PORT
online-mode=false
max-players=10
view-distance=8
simulation-distance=6
enable-command-block=false
spawn-protection=0
EOF

  # Memoria ADAPTATIVA: el shape puede ser 12 GB (max Always Free) o 6 GB (lo q/ hay
  # ahora en el formulario). Hardcodear -Xmx6G romperia una instancia d/ 6 GB (OOM).
  # Regla: MC = ~la mitad d/ la RAM total; el tracker aparte (-Xmx512m en su unit).
  TOTAL_MB="$(awk '/^MemTotal:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 4096)"
  XMX_MB=$(( TOTAL_MB / 2 ))
  [ "$XMX_MB" -lt 1024 ] && XMX_MB=1024
  [ "$XMX_MB" -gt 8192 ] && XMX_MB=8192
  XMS_MB=$(( XMX_MB / 2 ))
  log "RAM total ${TOTAL_MB} MB -> MC -Xms${XMS_MB}m -Xmx${XMX_MB}m"
  if [ -f "$MC_DIR/user_jvm_args.txt" ]; then
    sed -i "s/^-Xmx.*/-Xmx${XMX_MB}M/" "$MC_DIR/user_jvm_args.txt"
    if grep -q '^-Xms' "$MC_DIR/user_jvm_args.txt"; then
      sed -i "s/^-Xms.*/-Xms${XMS_MB}M/" "$MC_DIR/user_jvm_args.txt"
    else
      sed -i "1i -Xms${XMS_MB}M" "$MC_DIR/user_jvm_args.txt"
    fi
  else
    printf -- '-Xms%sM\n-Xmx%sM\n' "$XMS_MB" "$XMX_MB" > "$MC_DIR/user_jvm_args.txt"
  fi

  # ===== Azorea completo =====
  SRC_MOD="$(dirname "$0")/$MOD_JAR"
  [ -f "$SRC_MOD" ] || SRC_MOD="$PWD/$MOD_JAR"
  [ -f "$SRC_MOD" ] || die "falta $MOD_JAR (es el q/ queremos probar!)"
  mkdir -p "$MC_DIR/mods"
  cp -f "$SRC_MOD" "$MC_DIR/mods/$MOD_JAR"
  log "4/5 mod en mods/: $MOD_JAR"

  # Whitelist: si no va, cualquier desconocido entra en un server publico.
  if [ -n "$MC_USER" ]; then
    printf '%s\n' "$MC_USER" > "$MC_DIR/white-list.txt"
    sed -i 's/^white-list=.*/white-list=true/' "$MC_DIR/server.properties" \
      || echo 'white-list=true' >> "$MC_DIR/server.properties"
    sed -i 's/^enforce-whitelist=.*/enforce-whitelist=true/' "$MC_DIR/server.properties" \
      || echo 'enforce-whitelist=true' >> "$MC_DIR/server.properties"
    log "      whitelist activa -> $MC_USER"
  else
    warn "SIN --mc-user: server en online-mode=false SIN whitelist -> CUALQUIERA puede entrar."
    warn "  pon:  bash vps-setup.sh --mc-user TuNick"
  fi

  chown -R "$RUN_USER:$RUN_USER" "$MC_DIR"

  cat > /etc/systemd/system/azorea-mc.service <<EOF
[Unit]
Description=Minecraft NeoForge $NF_VERSION + Azorea
After=network-online.target azorea-tracker.service
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$MC_DIR
ExecStart=$MC_DIR/run.sh
Restart=on-failure
RestartSec=10
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now azorea-mc >/dev/null 2>&1
  log "5/5 arrancando MC (el 1.er arranque tarda ~1 min)..."
  sleep 25
fi

# --------------------------------------------------------------- firewall
if [ "$DO_FIREWALL" -eq 1 ]; then
  log "firewall ufw"
  ufw allow OpenSSH >/dev/null          # PRIMERO SSH, si no te quedas fuera
  ufw allow "$MC_PORT/tcp" >/dev/null
  ufw allow "$TRACKER_PORT/tcp" >/dev/null
  ufw --force enable >/dev/null
fi

# ------------------------------------------------------------- verificacion
echo
log "===== VERIFICACION ====="
curl -fsS --max-time 5 "http://127.0.0.1:$TRACKER_PORT/punch/observe" >/dev/null 2>&1 \
  && echo "  [OK] tracker  :$TRACKER_PORT  /punch/observe" \
  || echo "  [--] tracker parado/no instalado"
[ "$DO_MC" -eq 1 ] && {
  if systemctl is-active --quiet azorea-mc; then echo "  [OK] MC service activo"; else echo "  [!!] MC NO activo -> journalctl -u azorea-mc"; fi
  [ -f "$MC_DIR/mods/$MOD_JAR" ] && echo "  [OK] mod en mods/ -> $MOD_JAR" || echo "  [!!] mod ausente"
}
PUB="$(curl -fsS --max-time 5 https://ifconfig.me || echo '?')"
echo
echo "  IP publica : $PUB"
echo "  Minecraft  : $PUB:$MC_PORT"
echo "  Rendezvous : http://$PUB:$TRACKER_PORT"
echo
echo "  En esta PC pone en config/azorea.toml:"
echo "      [trackers]"
echo "          urls = [\"http://$PUB:$TRACKER_PORT\"]"
echo
echo "  Estado: systemctl status azorea-tracker azorea-mc"
echo "  Logs  : journalctl -u azorea-mc -f"
