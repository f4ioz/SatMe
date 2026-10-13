#!/usr/bin/env bash
# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Guided install of SatMe-PC (this computer) or SatMe-Node (a Raspberry Pi
# next to the antenna, for the MiniTiouner), on Debian, Ubuntu, Linux Mint
# or Raspberry Pi OS. It looks at what is plugged in and ticks what goes
# with it; you choose, it installs, it starts.
#
#   curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh
#   bash installe-satme.sh            # the menus
#   bash installe-satme.sh --oui      # no questions, what is detected
#   bash installe-satme.sh --pc | --noeud
#
# It always takes the latest SatMe-PC release (satme-pc.tar), checked
# against its SHA256SUMS. Nothing of the phone app (no APK) is involved.
set -euo pipefail

DEPOT="https://github.com/f4ioz/SatMe/releases/latest/download"
OUI=0; CHOIX=""
for a in "$@"; do
  case "$a" in
    --oui|--yes) OUI=1 ;;
    --pc) CHOIX=pc ;;
    --noeud|--node) CHOIX=noeud ;;
    -h|--help|--aide) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "option inconnue / unknown option: $a" >&2; exit 2 ;;
  esac
done

# ------------------------------------------------------------ words, FR or EN
FR=0; case "${LANG:-}${LC_ALL:-}" in *fr*) FR=1 ;; esac
m() { if [ "$FR" = 1 ]; then printf '%s' "$1"; else printf '%s' "$2"; fi; }

SUDO=""; [ "$(id -u)" -eq 0 ] || SUDO="sudo"
USER="${USER:-$(id -un)}"
# The answers from the terminal, even when the script comes through a pipe;
# without one (a container, a test), from standard input.
TTY=/dev/stdin; if (exec </dev/tty) 2>/dev/null; then TTY=/dev/tty; fi
UI=texte
if [ "$OUI" = 0 ] && command -v whiptail >/dev/null 2>&1 && [ -t 1 ]; then UI=whiptail; fi

titre() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
dit() { printf '%s\n' "$1"; }

# A yes/no question; $2 the default (1 yes, 0 no).
oui() {
  [ "$OUI" = 1 ] && return $((1 - $2))
  if [ "$UI" = whiptail ]; then
    if [ "$2" = 1 ]; then whiptail --title SatMe --yesno "$1" 12 70 <"$TTY"; else whiptail --title SatMe --defaultno --yesno "$1" 12 70 <"$TTY"; fi
    return $?
  fi
  local d r; d=$(m "O/n" "Y/n"); [ "$2" = 1 ] || d=$(m "o/N" "y/N")
  read -r -p "$1 [$d] " r <"$TTY" || r=""
  case "${r,,}" in o|oui|y|yes) return 0 ;; n|non|no) return 1 ;; *) return $((1 - $2)) ;; esac
}

# ------------------------------------------------------------ what is here
[ "$(uname -s)" = Linux ] || { dit "$(m "SatMe-PC s'installe ainsi sous Linux. Ailleurs : https://github.com/f4ioz/SatMe/releases" "This script is for Linux. Elsewhere: https://github.com/f4ioz/SatMe/releases")"; exit 1; }
command -v apt-get >/dev/null 2>&1 || { dit "$(m "Il faut une Debian, Ubuntu, Mint ou Raspberry Pi OS (apt)." "Needs Debian, Ubuntu, Mint or Raspberry Pi OS (apt).")"; exit 1; }

PI=0; MODELE=""
if [ -r /proc/device-tree/model ] && grep -qa "Raspberry Pi" /proc/device-tree/model; then PI=1; MODELE=$(tr -d '\0' </proc/device-tree/model); fi
BUREAU=0; [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}${XDG_CURRENT_DESKTOP:-}" ] && BUREAU=1

# USB devices from /sys (no lsusb needed): vendor, product id, name.
usb() { for d in /sys/bus/usb/devices/*; do [ -f "$d/idVendor" ] && echo "$(cat "$d/idVendor"):$(cat "$d/idProduct") $(cat "$d/product" 2>/dev/null || true)"; done; }
USB=$(usb 2>/dev/null || true)
MINI=0; echo "$USB" | grep -qi "0403:6010 MiniTiouner" && MINI=1
SDR=0; echo "$USB" | grep -qiE "^0bda:(2838|2832)" && SDR=1
SERIE=0
for t in /dev/ttyUSB* /dev/ttyACM*; do [ -e "$t" ] || continue
  # The MiniTiouner's own two ports are not a rig.
  p=$(udevadm info -q property -n "$t" 2>/dev/null | sed -n 's/^ID_MODEL=//p' || true)
  case "$p" in *MiniTiouner*) ;; *) SERIE=1 ;; esac
done
# The firewall, seen without sudo: enabled in its settings and running.
UFW=0; grep -qs "^ENABLED=yes" /etc/ufw/ufw.conf && systemctl is-active -q ufw 2>/dev/null && UFW=1

titre "SatMe"
dit "$(m "Ce que je vois ici :" "What I see here:")"
[ "$PI" = 1 ] && dit "  • $MODELE"
[ "$BUREAU" = 1 ] && dit "  • $(m "un bureau graphique" "a desktop")" || dit "  • $(m "pas de bureau graphique" "no desktop")"
[ "$MINI" = 1 ] && dit "  • MiniTiouner (DATV)"
[ "$SDR" = 1 ] && dit "  • $(m "clé RTL-SDR" "RTL-SDR dongle")"
[ "$SERIE" = 1 ] && dit "  • $(m "adaptateur série (poste ou rotor)" "serial adapter (rig or rotator)")"

# ------------------------------------------------------------ PC or node
if [ -z "$CHOIX" ]; then
  DEF=pc; [ "$PI" = 1 ] && [ "$BUREAU" = 0 ] && DEF=noeud
  if [ "$OUI" = 1 ]; then CHOIX=$DEF
  elif [ "$UI" = whiptail ]; then
    a=OFF; b=OFF; [ "$DEF" = pc ] && a=ON || b=ON
    CHOIX=$(whiptail --title "SatMe" --radiolist "$(m "Quoi installer ?" "What to install?")" 14 78 2 \
      pc "$(m "SatMe-PC : toute la station dans le navigateur" "SatMe-PC: the whole station in the browser")" $a \
      noeud "$(m "SatMe-Node : le MiniTiouner près de l'antenne (Raspberry Pi)" "SatMe-Node: the MiniTiouner by the antenna (Raspberry Pi)")" $b \
      3>&1 1>&2 2>&3 <"$TTY") || exit 1
  else
    dit ""; dit "  1) $(m "SatMe-PC : toute la station dans le navigateur" "SatMe-PC: the whole station in the browser")"
    dit "  2) $(m "SatMe-Node : le MiniTiouner près de l'antenne (Raspberry Pi)" "SatMe-Node: the MiniTiouner by the antenna (Raspberry Pi)")"
    d=1; [ "$DEF" = noeud ] && d=2
    read -r -p "$(m "Choix" "Choice") [$d] " r <"$TTY" || r=""
    case "${r:-$d}" in 2) CHOIX=noeud ;; *) CHOIX=pc ;; esac
  fi
fi

# ------------------------------------------------------------ the package
telecharge() {
  titre "$(m "Téléchargement de SatMe-PC" "Downloading SatMe-PC")"
  TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
  curl -fsSL "$DEPOT/satme-pc.tar" -o "$TMP/satme-pc.tar"
  curl -fsSL "$DEPOT/SHA256SUMS" -o "$TMP/SHA256SUMS"
  (cd "$TMP" && sha256sum --ignore-missing -c SHA256SUMS >/dev/null) || { dit "$(m "Somme de contrôle fausse : téléchargement abîmé, recommencer." "Checksum mismatch: damaged download, try again.")"; exit 1; }
  tar xf "$TMP/satme-pc.tar" -C "$TMP"
  dit "$(m "Vérifié" "Checked"): $(sed -n 's/  satme-pc.tar$//p' "$TMP/SHA256SUMS" | cut -c1-16)…"
}

apt_installe() { $SUDO apt-get update -q >/dev/null; $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -q "$@" >/dev/null; }

# ============================================================ SatMe-Node
if [ "$CHOIX" = noeud ]; then
  [ "$PI" = 1 ] || oui "$(m "Ce n'est pas un Raspberry Pi. Installer quand même SatMe-Node ici ?" "This is not a Raspberry Pi. Install SatMe-Node here anyway?")" 0 || exit 0
  [ "$MINI" = 1 ] || dit "$(m "MiniTiouner pas encore branché : le brancher après l'installation." "MiniTiouner not plugged in yet: plug it in after the install.")"
  oui "$(m "Installer SatMe-Node (Java, LongMynd, règle udev, service satme-noeud) ? Une dizaine de minutes sur un Pi 3." "Install SatMe-Node (Java, LongMynd, udev rule, satme-noeud service)? About ten minutes on a Pi 3.")" 1 || exit 0
  apt_installe curl ca-certificates
  telecharge
  $SUDO "$TMP/satme/noeud/installe-noeud.sh"
  exit 0
fi

# ============================================================ SatMe-PC
CIBLE="$HOME/satme"
if [ "$OUI" = 1 ]; then
  CHOIX_PC="CAT FFMPEG"; [ "$SDR" = 1 ] && CHOIX_PC="$CHOIX_PC SDR"; [ "$MINI" = 1 ] && CHOIX_PC="$CHOIX_PC DATV"
  [ "$UFW" = 1 ] && CHOIX_PC="$CHOIX_PC QR"; [ "$BUREAU" = 1 ] && CHOIX_PC="$CHOIX_PC RACC"
else
  on() { [ "$1" = 1 ] && echo ON || echo OFF; }
  ITEMS=(
    CAT    "$(m "Poste ou rotor sur port série/USB (groupe dialout)" "Rig or rotator on a serial/USB port (dialout group)")" "$(on 1)"
    FFMPEG "$(m "Vidéos, GIF, sons du téléphone, images DATV (ffmpeg)" "Videos, GIFs, the phone's sounds, DATV pictures (ffmpeg)")" ON
    SDR    "$(m "Clé RTL-SDR : SDR, radiosondes, METEOR (rtl-sdr)" "RTL-SDR dongle: SDR, radiosondes, METEOR (rtl-sdr)")" "$(on $SDR)"
    DATV   "$(m "MiniTiouner branché sur ce PC (LongMynd, udev)" "MiniTiouner plugged into this PC (LongMynd, udev)")" "$(on $MINI)"
    QR     "$(m "Téléphone par QR code (pare-feu, port 8091)" "Phone by QR code (firewall, port 8091)")" "$(on $UFW)"
    AUTO   "$(m "Lancer SatMe-PC avec la session" "Start SatMe-PC with the session")" OFF
    RACC   "$(m "Raccourci dans le menu des applications" "Shortcut in the applications menu")" "$(on $BUREAU)"
    RESEAU "$(m "Page ouverte aux autres machines du réseau" "Page open to the other machines of the network")" OFF
  )
  if [ "$UI" = whiptail ]; then
    CHOIX_PC=$(whiptail --title "SatMe-PC" --separate-output --checklist \
      "$(m "Cocher ce qui servira (ce qui est branché est déjà coché) :" "Tick what you will use (what is plugged in is ticked):")" 20 82 8 \
      "${ITEMS[@]}" 3>&1 1>&2 2>&3 <"$TTY") || exit 1
  else
    CHOIX_PC=""
    for ((i = 0; i < ${#ITEMS[@]}; i += 3)); do
      d=0; [ "${ITEMS[i+2]}" = ON ] && d=1
      oui "${ITEMS[i+1]} ?" $d && CHOIX_PC="$CHOIX_PC ${ITEMS[i]}"
    done
  fi
fi
# Was [$1] ticked? (whiptail gives one per line, the text mode separated by spaces)
a() { printf '%s\n' $CHOIX_PC | grep -qx "$1"; }

titre "$(m "Paquets" "Packages")"
PAQ=(curl ca-certificates)
command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])' || PAQ+=(default-jre)
a FFMPEG && PAQ+=(ffmpeg)
a SDR && PAQ+=(rtl-sdr)
a DATV && PAQ+=(build-essential libusb-1.0-0-dev libasound2-dev)
[ "$BUREAU" = 1 ] && PAQ+=(xdg-utils)
dit "${PAQ[*]}"
apt_installe "${PAQ[@]}"

telecharge
titre "$(m "SatMe-PC dans" "SatMe-PC into") $CIBLE"
[ -x "$CIBLE/bin/satme" ] && pkill -f "$CIBLE/lib/" 2>/dev/null || true
rm -rf "$CIBLE/lib" "$CIBLE/bin"; mkdir -p "$CIBLE"; cp -a "$TMP/satme/." "$CIBLE/"
dit "$("$CIBLE/bin/satme" --version)"

if a CAT; then
  titre "$(m "Poste et rotor" "Rig and rotator")"
  if id -nG "$USER" | grep -qw dialout; then dit "$(m "déjà dans le groupe dialout" "already in the dialout group")"
  else $SUDO usermod -aG dialout "$USER"; dit "$(m "ajouté au groupe dialout : se déconnecter puis se reconnecter" "added to the dialout group: log out and back in")"; fi
fi

if a DATV; then
  titre "MiniTiouner (LongMynd)"
  S=$(mktemp -d)
  curl -fsSL https://github.com/BritishAmateurTelevisionClub/longmynd/archive/refs/heads/master.tar.gz | tar xz -C "$S" --strip-components=1
  make -C "$S" -s longmynd >/dev/null
  mkdir -p "$HOME/longmynd"; cp "$S/longmynd" "$HOME/longmynd/"; rm -rf "$S"
  $SUDO mkdir -p /etc/udev/rules.d
  echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | $SUDO tee /etc/udev/rules.d/60-satme-minitiouner.rules >/dev/null
  $SUDO udevadm control --reload-rules 2>/dev/null || true; $SUDO udevadm trigger 2>/dev/null || true
  dit "$(m "LongMynd dans ~/longmynd ; débrancher et rebrancher le MiniTiouner. Ses deux ports série ne sont jamais ceux du poste." "LongMynd in ~/longmynd; unplug and plug the MiniTiouner in again. Its two serial ports are never the rig's.")"
fi

if a QR && [ "$UFW" = 1 ]; then
  titre "$(m "Pare-feu" "Firewall")"
  $SUDO ufw allow from 192.168.0.0/16 to any port 8091 proto tcp >/dev/null && dit "$(m "port 8091 ouvert au réseau local" "port 8091 open to the local network")"
fi

OPTS=""; a RESEAU && OPTS="--reseau"
# A launcher: starts SatMe-PC unless it runs, then opens the page.
cat > "$CIBLE/lance-satme.sh" <<EOF
#!/usr/bin/env bash
curl -fs -o /dev/null http://localhost:8090/api/etat || { nohup "$CIBLE/bin/satme" $OPTS >"\$HOME/.satme-pc.log" 2>&1 & for i in \$(seq 1 30); do sleep 1; curl -fs -o /dev/null http://localhost:8090/api/etat && break; done; }
xdg-open http://localhost:8090/ >/dev/null 2>&1 || echo "http://localhost:8090/"
EOF
chmod +x "$CIBLE/lance-satme.sh"

if a AUTO; then
  titre "$(m "Lancement avec la session" "Start with the session")"
  mkdir -p "$HOME/.config/systemd/user"
  cat > "$HOME/.config/systemd/user/satme-pc.service" <<EOF
[Unit]
Description=SatMe-PC

[Service]
ExecStart=$CIBLE/bin/satme $OPTS
Restart=on-failure

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload 2>/dev/null && systemctl --user enable --now satme-pc 2>/dev/null && dit "satme-pc.service" || dit "$(m "pas de systemd utilisateur ici : utiliser ~/satme/lance-satme.sh" "no user systemd here: use ~/satme/lance-satme.sh")"
fi

if a RACC; then
  titre "$(m "Raccourci" "Shortcut")"
  ICONE=applications-science
  if command -v unzip >/dev/null 2>&1 && unzip -p "$CIBLE/lib/pc.jar" marque/icon.png >"$CIBLE/satme.png" 2>/dev/null && [ -s "$CIBLE/satme.png" ]; then ICONE="$CIBLE/satme.png"; fi
  mkdir -p "$HOME/.local/share/applications"
  cat > "$HOME/.local/share/applications/satme-pc.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=SatMe-PC
Comment=$(m "Satellites radioamateurs : passages, Doppler, poste, rotor, carnet" "Amateur radio satellites: passes, Doppler, rig, rotator, log")
Exec=$CIBLE/lance-satme.sh
Icon=$ICONE
Terminal=false
Categories=HamRadio;Network;Science;
EOF
  dit "$(m "SatMe-PC dans le menu des applications" "SatMe-PC in the applications menu")"
fi

titre "$(m "C'est prêt" "Ready")"
dit "$(m "Lancer SatMe-PC" "Start SatMe-PC"): $CIBLE/lance-satme.sh   ($(m "puis" "then") http://localhost:8090/)"
dit "$(m "Mettre à jour : relancer ce script. Vos réglages restent dans ~/.satme." "Update: run this script again. Your settings stay in ~/.satme.")"
if [ "$OUI" = 0 ] && ! a AUTO && oui "$(m "Lancer SatMe-PC maintenant ?" "Start SatMe-PC now?")" 1; then "$CIBLE/lance-satme.sh"; fi
