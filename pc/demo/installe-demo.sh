#!/usr/bin/env bash
# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Installs the SatMe-PC demonstration (a made-up station, virtual hardware,
# nothing real opened) as a service, on a Linux VM or a Raspberry Pi
# (Debian, Ubuntu, Raspberry Pi OS 64-bit):
#
#   sudo ./installe-demo.sh                  # install or update, then start
#   ./installe-demo.sh --sec                 # dry run: says what it would do, changes nothing
#   sudo ./installe-demo.sh --port 8095      # another port (8090 by default)
#   sudo ./installe-demo.sh --desinstalle    # the service, the program and the demo's data removed
#
# What it does, nothing more: Java 17+ when missing (apt), the program into
# /opt/satme-demo, a system user « satme-demo » (no login), the service
# « satme-demo » (satme --demo --port 8090 --reseau, data in
# /var/lib/satme-demo), the port opened in ufw or firewalld when one of them
# runs. The service sees no device at all (PrivateDevices): no serial port,
# no sound card, no SDR, whatever is plugged in.
set -euo pipefail

PORT=8090; SEC=0; DESINSTALLE=0
for a in "$@"; do
  case "$a" in
    --sec|--dry-run) SEC=1 ;;
    --desinstalle|--uninstall) DESINSTALLE=1 ;;
    --port=*) PORT="${a#--port=}" ;;
    --port) PORT="__suivant__" ;;
    -h|--help|--aide) sed -n '2,21p' "$0"; exit 0 ;;
    *) if [ "$PORT" = "__suivant__" ]; then PORT="$a"; else echo "option inconnue / unknown option: $a" >&2; exit 2; fi ;;
  esac
done
case "$PORT" in ''|*[!0-9]*|__suivant__) echo "port ? (--port 8090)" >&2; exit 2 ;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || { echo "port ? (1-65535)" >&2; exit 2; }

ICI=$(cd "$(dirname "$0")" && pwd)
PROG=/opt/satme-demo
DONNEES=/var/lib/satme-demo
COMPTE=satme-demo
SERVICE=/etc/systemd/system/satme-demo.service

# ------------------------------------------------------------ words, FR or EN
FR=0; case "${LANG:-}${LC_ALL:-}" in *fr*) FR=1 ;; esac
m() { if [ "$FR" = 1 ]; then printf '%s' "$1"; else printf '%s' "$2"; fi; }
titre() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
dit() { printf '%s\n' "$1"; }
erreur() { printf '\033[1;31m%s\033[0m\n' "$1" >&2; exit 1; }

# The dry run says each command and each file instead of doing it.
run() { if [ "$SEC" = 1 ]; then printf '  [sec] %s\n' "$*"; else "$@"; fi; }
ecrit() { if [ "$SEC" = 1 ]; then printf '  [sec] %s :\n' "$1"; sed 's/^/        | /'; else cat > "$1"; fi; }

# ------------------------------------------------------------ what is here
[ "$(uname -s)" = Linux ] || erreur "$(m "Linux seulement." "Linux only.")"
command -v systemctl >/dev/null 2>&1 || erreur "$(m "Il faut systemd (Debian, Ubuntu, Raspberry Pi OS)." "Needs systemd (Debian, Ubuntu, Raspberry Pi OS).")"
if [ "$SEC" = 0 ] && [ "$(id -u)" -ne 0 ]; then erreur "$(m "À lancer avec sudo (ou --sec pour voir ce qui serait fait)." "Run it with sudo (or --sec to see what would be done).")"; fi
[ "$SEC" = 1 ] && dit "$(m "Essai à sec : rien n'est changé." "Dry run: nothing is changed.")"

ACTIF=0; systemctl is-active -q satme-demo 2>/dev/null && ACTIF=1
UFW=0; command -v ufw >/dev/null 2>&1 && grep -qs "^ENABLED=yes" /etc/ufw/ufw.conf && systemctl is-active -q ufw 2>/dev/null && UFW=1
FIREWALLD=0; command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active -q firewalld 2>/dev/null && FIREWALLD=1

# ============================================================ removal
if [ "$DESINSTALLE" = 1 ]; then
  titre "$(m "Désinstallation de la démonstration" "Removing the demonstration")"
  run systemctl disable --now satme-demo 2>/dev/null || true
  run rm -f "$SERVICE"
  run systemctl daemon-reload
  [ "$UFW" = 1 ] && { run ufw delete allow "$PORT/tcp" >/dev/null 2>&1 || true; }
  [ "$FIREWALLD" = 1 ] && { run firewall-cmd --permanent --remove-port="$PORT/tcp" >/dev/null 2>&1 || true; run firewall-cmd --reload >/dev/null || true; }
  run rm -rf "$PROG" "$DONNEES"
  id "$COMPTE" >/dev/null 2>&1 && run userdel "$COMPTE"
  dit "$(m "Fait (Java reste installé)." "Done (Java stays installed).")"
  exit 0
fi

[ -x "$ICI/satme/bin/satme" ] || erreur "$(m "Le dossier satme/ n'est pas à côté de ce script : décompresser l'archive entière." "The satme/ folder is not next to this script: extract the whole archive.")"

titre "$(m "Cette machine" "This machine")"
ARCH=$(uname -m)
case "$ARCH" in
  x86_64|amd64|aarch64|arm64) dit "  $ARCH" ;;
  armv7l|armv6l) dit "  $ARCH — $(m "système 32 bits : ça marche, mais Raspberry Pi OS 64 bits est conseillé" "32-bit system: it works, but Raspberry Pi OS 64-bit is advised")" ;;
  *) dit "  $ARCH — $(m "non essayé" "not tried")" ;;
esac
[ -r /proc/device-tree/model ] && grep -qa "Raspberry Pi" /proc/device-tree/model && dit "  $(tr -d '\0' </proc/device-tree/model)"
MO=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
dit "  $(m "mémoire" "memory") : ${MO} Mo"
[ "$MO" -gt 0 ] && [ "$MO" -lt 900 ] && dit "  $(m "peu de mémoire : la démonstration demande ~400 Mo" "little memory: the demonstration needs ~400 MB")"

# ------------------------------------------------------------ Java 17 or later
titre "Java"
java_ok() { command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])'; }
if java_ok; then dit "  $(java -version 2>&1 | head -1)"
else
  command -v apt-get >/dev/null 2>&1 || erreur "$(m "Java 17+ manque et apt n'est pas là : l'installer à la main." "Java 17+ is missing and apt is not here: install it by hand.")"
  dit "  $(m "Java 17+ manque : installation (sans interface graphique)" "Java 17+ missing: installing it (headless)")"
  run apt-get update -q
  run env DEBIAN_FRONTEND=noninteractive apt-get install -y -q default-jre-headless
  # An older Debian's default is Java 11: the 17 then.
  if [ "$SEC" = 0 ] && ! java_ok; then run env DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-17-jre-headless || true; fi
  [ "$SEC" = 1 ] || java_ok || erreur "$(m "Java 17 ou plus est nécessaire." "Java 17 or later is needed.")"
fi

# ------------------------------------------------------------ the user, the program
titre "$(m "Compte et programme" "Account and program")"
if id "$COMPTE" >/dev/null 2>&1; then dit "  $(m "compte $COMPTE déjà là" "account $COMPTE already there")"
else run useradd --system --user-group --home-dir "$DONNEES" --shell /usr/sbin/nologin --comment "SatMe-PC demo" "$COMPTE"; fi
run install -d -o "$COMPTE" -g "$COMPTE" -m 750 "$DONNEES"
[ "$ACTIF" = 1 ] && run systemctl stop satme-demo
run rm -rf "$PROG.nouveau"
run cp -a "$ICI/satme" "$PROG.nouveau"
run chown -R root:root "$PROG.nouveau"
run rm -rf "$PROG"
run mv "$PROG.nouveau" "$PROG"
dit "  $PROG ($("$ICI/satme/bin/satme" --version 2>/dev/null || echo SatMe-PC))"

# ------------------------------------------------------------ the service
titre "$(m "Service satme-demo" "satme-demo service")"
ecrit "$SERVICE" <<EOF
[Unit]
Description=SatMe-PC demonstration (made-up data, virtual hardware)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$COMPTE
Group=$COMPTE
Environment=HOME=$DONNEES
Environment=SATME_SANS_SERIE=1
ExecStart=$PROG/bin/satme --demo --port $PORT --reseau --dossier $DONNEES/demo
Restart=always
RestartSec=3
# Nothing real: no device at all (serial ports, sound cards, SDR), the system read-only but for its data.
PrivateDevices=yes
DevicePolicy=closed
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=$DONNEES
PrivateTmp=yes
NoNewPrivileges=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK

[Install]
WantedBy=multi-user.target
EOF
run systemctl daemon-reload
run systemctl enable satme-demo
run systemctl restart satme-demo

# ------------------------------------------------------------ the port
titre "$(m "Port" "Port") $PORT"
if [ "$UFW" = 1 ]; then run ufw allow "$PORT/tcp" comment "SatMe demo"
elif [ "$FIREWALLD" = 1 ]; then run firewall-cmd --permanent --add-port="$PORT/tcp"; run firewall-cmd --reload
else dit "  $(m "aucun pare-feu actif (ufw, firewalld) : rien à ouvrir" "no firewall running (ufw, firewalld): nothing to open")"; fi

# ------------------------------------------------------------ ready
titre "$(m "C'est prêt" "Ready")"
MDP=""
if [ "$SEC" = 0 ]; then
  # The password drawn at the first start, as the service's log says it.
  for i in $(seq 1 60); do
    MDP=$(journalctl -u satme-demo -o cat --no-pager -n 200 2>/dev/null | sed -n 's/.*mot de passe « \(.*\) ».*/\1/p' | tail -1)
    [ -n "$MDP" ] && break; sleep 1
  done
fi
for ip in $(hostname -I 2>/dev/null || true); do case "$ip" in *:*) ;; *) dit "  http://$ip:$PORT/" ;; esac; done
dit "  http://$(hostname 2>/dev/null || echo localhost).local:$PORT/"
if [ -n "$MDP" ]; then dit "  $(m "mot de passe des autres machines" "password for other machines") : $MDP"
else dit "  $(m "mot de passe : sudo journalctl -u satme-demo | grep 'mot de passe'" "password: sudo journalctl -u satme-demo | grep 'mot de passe'")"; fi
dit "  $(m "le changer : Réglages › Accès à distance, depuis la machine elle-même (ou par un tunnel SSH)" "change it: Réglages › Accès à distance, from the machine itself (or through an SSH tunnel)")"
dit "  $(m "remettre à neuf : le bouton du bandeau « DÉMO », ou" "make it new again: the « DÉMO » band's button, or") sudo -u $COMPTE touch $DONNEES/demo/.satme-demo-reset && sudo systemctl restart satme-demo"
dit "  $(m "journal" "log") : sudo journalctl -u satme-demo -f"
