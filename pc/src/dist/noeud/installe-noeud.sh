#!/usr/bin/env bash
# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Installs a SatMe-Node on a Raspberry Pi (Raspberry Pi OS 64-bit, Debian):
# the MiniTiouner, a bladeRF or a Pluto next to the antenna, driven over the
# network by SatMe-PC or the phone. A Pi 3 is enough for the MiniTiouner
# alone; a bladeRF wants a Pi 4 or 5 and its USB 3.
#
#   sudo ./installe-noeud.sh                # from the unpacked satme.tar
#   sudo ./installe-noeud.sh --port 8090    # another port
#
# It installs Java, builds LongMynd (BATC), lets it open the MiniTiouner
# (udev), installs the bladeRF tool and FPGA image, copies SatMe to /opt/satme and starts the satme-noeud service,
# allowed to restart or power off the Pi (/etc/sudoers.d/satme-noeud, those two commands only).
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
# The first Java the system can really install (a name only referred to, as
# openjdk-17 on Debian 13, has no candidate).
JRE=
for j in openjdk-21-jre-headless openjdk-17-jre-headless openjdk-25-jre-headless default-jre-headless; do
  # Read whole first: grep -q leaving early would end apt-cache on SIGPIPE, a failure under pipefail.
  POL="$(LC_ALL=C apt-cache policy "$j" 2>/dev/null || true)"
  case "$POL" in *"Candidate: "[0-9]*) JRE=$j; break ;; esac
done
[ -n "$JRE" ] || { echo "Aucun Java 17 ou plus à installer sur ce système." >&2; exit 1; }
apt-get install -y -q "$JRE" build-essential libusb-1.0-0-dev libasound2-dev curl ca-certificates

echo "==> LongMynd (BATC)"
SRC=/opt/longmynd-src
rm -rf "$SRC"; mkdir -p "$SRC"
curl -fsSL https://github.com/BritishAmateurTelevisionClub/longmynd/archive/refs/heads/master.tar.gz | tar xz -C "$SRC" --strip-components=1
# Its Makefile adds -mfpu (32-bit ARM only) on a Pi 3 or 4, which a 64-bit
# compiler refuses: there, its flags without it.
case "$(uname -m)" in
  armv6l|armv7l) make -C "$SRC" -s longmynd ;;
  *) make -C "$SRC" -s longmynd COPT="-O3 -march=native -mtune=native -funsafe-math-optimizations" ;;
esac
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

echo "==> bladeRF (outil, accès USB, image FPGA)"
# bladeRF-cli drives the board; its udev rule opens it to the plugdev group.
# The FPGA image, loaded at each opening: the latest, as used with libbladeRF 2023.02 on the PC.
if apt-get install -y -q bladerf; then
  mkdir -p /usr/share/Nuand/bladeRF
  for f in hostedxA4 hostedxA9; do
    [ -s /usr/share/Nuand/bladeRF/$f.rbf ] || curl -fsSL "https://www.nuand.com/fpga/$f-latest.rbf" -o /usr/share/Nuand/bladeRF/$f.rbf \
      || { rm -f /usr/share/Nuand/bladeRF/$f.rbf; echo "  image FPGA $f non téléchargée (le bladeRF $f ne pourra pas recevoir)"; }
  done
else
  echo "  paquet bladerf indisponible : bladeRF non utilisable sur ce node"
fi
# The package's rule may only open the board to a desktop session (uaccess),
# never to the service: the node is a machine of its own, every bladeRF
# (Nuand 2cf0, and the first bladeRF 1d50:6066) open to it, as the MiniTiouner.
cat > /etc/udev/rules.d/60-satme-bladerf.rules <<'RULES'
SUBSYSTEMS=="usb", ATTRS{idVendor}=="2cf0", MODE:="0666"
SUBSYSTEMS=="usb", ATTRS{idVendor}=="1d50", ATTRS{idProduct}=="6066", MODE:="0666"
RULES
udevadm control --reload-rules 2>/dev/null || true
udevadm trigger 2>/dev/null || true
# The Pluto needs nothing here: plugged in USB it is a network card (192.168.2.1).

echo "==> SatMe dans /opt/satme"
id satme >/dev/null 2>&1 || useradd --system --home-dir /var/lib/satme-noeud --shell /usr/sbin/nologin satme
# plugdev: the bladeRF; video: vcgencmd (temperature, throttling) for the node's health
for g in plugdev video; do getent group $g >/dev/null && usermod -aG $g satme; done
mkdir -p /opt/satme /var/lib/satme-noeud
if [ "$SATME" != /opt/satme ]; then rm -rf /opt/satme/lib; cp -a "$SATME/." /opt/satme/; fi
chown -R satme:satme /var/lib/satme-noeud

if [ "$SERVICE" -eq 1 ]; then
  echo "==> Service satme-noeud"
  cat > /etc/systemd/system/satme-noeud.service <<UNIT
[Unit]
Description=SatMe-Node (MiniTiouner, SDR et émetteur pilotés par le réseau)
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
  echo "==> Redémarrer et éteindre le Pi depuis SatMe-PC (sudo, ces deux commandes seulement)"
  # Checked by visudo before it goes in: a broken file in sudoers.d would lock sudo out.
  if ! command -v sudo >/dev/null 2>&1; then apt-get install -y -q sudo; fi
  if [ -x /usr/bin/systemctl ] && command -v visudo >/dev/null 2>&1; then
    TMP_SUDO="$(mktemp)"
    cp "$ICI/satme-noeud.sudoers" "$TMP_SUDO"
    if visudo -cf "$TMP_SUDO" >/dev/null; then
      install -m 0440 -o root -g root "$TMP_SUDO" /etc/sudoers.d/satme-noeud
    else
      echo "  règle sudo refusée par visudo : redémarrer et éteindre le Pi depuis SatMe-PC impossibles" >&2
    fi
    rm -f "$TMP_SUDO"
  else
    echo "  systemctl ou visudo absent : redémarrer et éteindre le Pi depuis SatMe-PC impossibles"
  fi
  systemctl daemon-reload
  systemctl enable satme-noeud >/dev/null
  systemctl restart satme-noeud
  sleep 8
  echo
  journalctl -u satme-noeud -n 20 --no-pager | grep -E "SatMe-Node|LongMynd|Code" || true
fi

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  echo
  echo "Pare-feu actif : sudo ufw allow from 192.168.0.0/16 to any port $PORT proto tcp"
fi
echo
echo "SatMe-Node installé. Dans SatMe-PC, onglet DATV → MiniTiouner « Changer » :"
echo "  adresse $(hostname -I 2>/dev/null | awk '{print $1}'):$PORT et le code ci-dessus"
echo "  (le revoir : journalctl -u satme-noeud | grep Code)."
