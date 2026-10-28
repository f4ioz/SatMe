#!/usr/bin/env bash
# SatMe-PC for Windows, all in one: Java, SatMe-PC, ffmpeg/ffplay and rtl_tcp
# in one installer (.msi, per user, no administrator) and one folder to unzip
# (.zip). Run on Windows (GitHub Actions, Git Bash), after
# `./gradlew :pc:installDist`:
#
#   pc/windows/paquet-windows.sh 2026.10.13
#
# On Linux it makes the same folder (app-image), without the tools: to check
# the package before a release.
set -eo pipefail

V="${1:-dev}"
racine="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$racine"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) windows=1 ;; *) windows=0 ;; esac

B=build-windows
E="$B/entree"
rm -rf "$B"
mkdir -p "$E/outils" "$B/sortie"
cp pc/build/install/satme/lib/*.jar "$E/"
cp LICENSE THIRD-PARTY.md THIRD-PARTY.fr.md "$E/"

if [ "$windows" = 1 ]; then
    # The tools SatMe-PC calls, next to it (OutilsPc looks there first).
    T="$B/telechargements"; mkdir -p "$T"
    # FFmpeg (GPL build: the videos are H.264), the shared one (smaller): its .exe and .dll.
    # Its "latest" release is rebuilt every day (its files briefly missing), and GitHub's API
    # limits requests without a token: the runner's token when there is one, and a few tries.
    auth=(); [ -n "$GH_TOKEN" ] && auth=(-H "Authorization: Bearer $GH_TOKEN")
    nom=""
    for essai in 1 2 3 4 5 6; do
        nom=$(curl -fsSL "${auth[@]}" https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/tags/latest \
            | grep -o '"name": *"ffmpeg-n[0-9.]*-latest-win64-gpl-shared-[0-9.]*\.zip"' | sed 's/.*"\(ffmpeg[^"]*\)"/\1/' | sort -V | tail -1) || true
        [ -n "$nom" ] && curl -fsSL --retry 3 -o "$T/ffmpeg.zip" "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/$nom" && break
        echo "FFmpeg pour Windows pas encore là (essai $essai), nouvel essai dans une minute"; nom=""; sleep 60
    done
    [ -n "$nom" ] || { echo "FFmpeg pour Windows introuvable"; exit 1; }
    echo "FFmpeg : $nom"
    7z x -y -o"$T/ffmpeg" "$T/ffmpeg.zip" > /dev/null
    d=$(ls -d "$T"/ffmpeg/*/bin | head -1)
    cp "$d"/ffmpeg.exe "$d"/ffplay.exe "$d"/*.dll "$E/outils/"
    cp "$(dirname "$d")/LICENSE.txt" "$E/outils/FFMPEG-LICENSE.txt"
    # rtl_tcp (rtl-sdr, GPL-2), 64 bits.
    curl -fsSL --retry 5 --retry-all-errors -o "$T/rtl.zip" https://github.com/rtlsdrblog/rtl-sdr-blog/releases/download/V1.4.0/Release.zip
    7z x -y -o"$T/rtl" "$T/rtl.zip" > /dev/null
    cp "$T"/rtl/x64/rtl_tcp.exe "$T"/rtl/x64/rtl_test.exe "$T"/rtl/x64/*.dll "$E/outils/"
    printf 'rtl_tcp, rtl_test : rtl-sdr (GPL-2.0), https://github.com/rtlsdrblog/rtl-sdr-blog (V1.4.0)\nffmpeg, ffplay : FFmpeg (GPL), https://github.com/BtbN/FFmpeg-Builds — %s\n' "$nom" > "$E/outils/SOURCES.txt"
fi

# An installer's version is three numbers, the first two below 256:
# 2026.10.13 → 26.10.1300, 2026.10.13.1 → 26.10.1301; "dev" → 0.0.0.
if [[ "$V" =~ ^20([0-9]{2})\.([0-9]{1,2})\.([0-9]{1,2})(\.([0-9]{1,2}))?$ ]]; then
    VW="$((10#${BASH_REMATCH[1]})).$((10#${BASH_REMATCH[2]})).$((10#${BASH_REMATCH[3]} * 100 + 10#${BASH_REMATCH[5]:-0}))"
else
    VW="0.0.0"
fi

# The Java modules SatMe-PC uses (jdeps), and those found only at run time:
# TLS (crypto), the languages' dates and numbers, the charsets.
MODULES="java.base,java.desktop,java.instrument,java.sql,jdk.unsupported,java.logging,java.naming,java.management,java.net.http,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.charsets,jdk.zipfs"

commun=(--name SatMe-PC --app-version "$VW" --vendor "F4IOZ"
    --description "SatMe-PC : station satellite radioamateur"
    --input "$E" --main-jar pc.jar --main-class fr.f4ioz.satcombo.pc.MainKt
    --add-modules "$MODULES" --java-options "-Xmx512m --enable-native-access=ALL-UNNAMED")

if [ "$windows" = 1 ]; then
    commun+=(--icon pc/windows/satme.ico)
    # The folder to unzip.
    jpackage --type app-image "${commun[@]}" --dest "$B/image"
    (cd "$B/image" && 7z a -tzip "../sortie/satme-pc-windows.zip" SatMe-PC > /dev/null)
    # The installer's own mark beside the jars (not in the ZIP): SatMe-PC then never keeps
    # its data in the install folder (DonneesMsiPc).
    printf 'SatMe-PC installé par satme-pc-windows.msi : ses données ne sont jamais gardées dans ce dossier,\r\nqu'"'"'une mise à jour remplace (par défaut dans %%APPDATA%%\\SatMe).\r\n' > "$E/installe-par-msi.txt"
    # The installer: per user (no administrator), Start menu and desktop, updated in place.
    msi=(--type msi "${commun[@]}"
        --win-per-user-install --win-menu --win-menu-group SatMe --win-shortcut --win-shortcut-prompt
        --win-upgrade-uuid 8eb50f5b-d5a0-4ee2-b29e-b103f4129360
        --license-file LICENSE)
    # jpackage's WiX fragment empties the whole install folder on uninstall (RemoveFolderEx), an
    # update included: files the user put there with it. Made once to read it (--temp keeps it),
    # that component taken out, then the installer made again from it (--resource-dir bundle.wxf).
    jpackage "${msi[@]}" --dest "$B/msi-1" --temp "$B/wix-1"
    mkdir -p "$B/ressources"
    java pc/windows/SansNettoyage.java "$B/wix-1/config/bundle.wxf" "$B/ressources/bundle.wxf"
    jpackage "${msi[@]}" --dest "$B/msi" --temp "$B/wix-2" --resource-dir "$B/ressources"
    # Built from that fragment, not a new one: checked.
    cmp -s "$B/ressources/bundle.wxf" "$B/wix-2/config/bundle.wxf" || { echo "bundle.wxf corrigé non repris par jpackage"; exit 1; }
    ! grep -q RemoveFolderEx "$B/wix-2/config/bundle.wxf" || { echo "RemoveFolderEx encore dans l'installateur"; exit 1; }
    cp "$B"/msi/*.msi "$B/sortie/satme-pc-windows.msi"
else
    jpackage --type app-image "${commun[@]}" --dest "$B/image"
fi
ls -la "$B/sortie" "$B/image"
