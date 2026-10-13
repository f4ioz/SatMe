#!/usr/bin/env bash
# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Installs a SatMe-node on a Raspberry Pi (Raspberry Pi OS 64-bit, Debian):
# the MiniTiouner next to the antenna, driven over the network by SatMe PC
# or the phone. A Pi 3 is enough: the node only receives, nothing is decoded
# on it.
#
#   sudo ./installe-noeud.sh                # from the unpacked satme.tar
#   sudo ./installe-noeud.sh --port 8090    # another port
#
# It installs Java, builds LongMynd (BATC), lets it open the MiniTiouner
# (udev), copies SatMe to /opt/satme and starts the satme-noeud service.
# Run again to update: settings, code and keys are kept.
set -euo pipefail

PORT=8090
SERVICE=1
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --sans-service) SERVICE=0; shift ;;   # a container or a test: no systemd
    *) echo "option inconnue : $1" >&2; exit 2 ;;
  esac
done

[ "$(id -u)" -eq 0 ] || { echo "À lancer avec sudo." >&2; exit 1; }
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SATME="$(cd "$ICI/.." && pwd)"
[ -x "$SATME/bin/satme" ] || { echo "SatMe introuvable à côté de ce script ($SATME/bin/satme) : décompresser satme.tar d'abord." >&2; exit 1; }

echo "==> Paquets (Java, outils pour LongMynd)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
JRE=openjdk-17-jre-headless
apt-cache show "$JRE" >/dev/null 2>&1 || JRE=openjdk-21-jre-headless
apt-get install -y -q "$JRE" build-essential libusb-1.0-0-dev libasound2-dev curl ca-certificates

echo "==> LongMynd (BATC)"
SRC=/opt/longmynd-src
rm -rf "$SRC"; mkdir -p "$SRC"
curl -fsSL https://github.com/BritishAmateurTelevisionClub/longmynd/archive/refs/heads/master.tar.gz | tar xz -C "$SRC" --strip-components=1
make -C "$SRC" -s longmynd
install -m 0755 "$SRC/longmynd" /usr/local/bin/longmynd

echo "==> Accès au MiniTiouner (udev)"
# The node is a machine of its own: every FT2232H (the MiniTiouner's USB chip,
# whatever its product name — the Pro V2's differs from the first one) open to it.
mkdir -p /etc/udev/rules.d
cat > /etc/udev/rules.d/60-satme-minitiouner.rules <<'RULES'
SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", MODE:="0666"
RULES
udevadm control --reload-rules 2>/dev/null || true
udevadm trigger 2>/dev/null || true

echo "==> SatMe dans /opt/satme"
id satme >/dev/null 2>&1 || useradd --system --home-dir /var/lib/satme-noeud --shell /usr/sbin/nologin satme
mkdir -p /opt/satme /var/lib/satme-noeud
if [ "$SATME" != /opt/satme ]; then rm -rf /opt/satme/lib; cp -a "$SATME/." /opt/satme/; fi
chown -R satme:satme /var/lib/satme-noeud

if [ "$SERVICE" -eq 1 ]; then
  echo "==> Service satme-noeud"
  cat > /etc/systemd/system/satme-noeud.service <<UNIT
[Unit]
Description=SatMe-node (MiniTiouner piloté par le réseau)
After=network-online.target
Wants=network-online.target

[Service]
User=satme
Environment="JAVA_OPTS=-Xmx256m -XX:+UseSerialGC -Dstdout.encoding=UTF-8"
Environment=LC_ALL=C.UTF-8
ExecStart=/opt/satme/bin/satme --noeud --port $PORT --dossier /var/lib/satme-noeud
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable satme-noeud >/dev/null
  systemctl restart satme-noeud
  sleep 8
  echo
  journalctl -u satme-noeud -n 20 --no-pager | grep -E "SatMe-node|LongMynd|Code" || true
fi

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  echo
  echo "Pare-feu actif : sudo ufw allow from 192.168.0.0/16 to any port $PORT proto tcp"
fi
echo
echo "SatMe-node installé. Dans SatMe PC, onglet DATV → MiniTiouner « Changer » :"
echo "  adresse $(hostname -I 2>/dev/null | awk '{print $1}'):$PORT et le code ci-dessus"
echo "  (le revoir : journalctl -u satme-noeud | grep Code)."
