#!/bin/sh
# A stand-in for bladeRF-cli in the tests: never touches a radio.
# Its folder holds "mode" (what to play) and "rx.bin" (the samples it gives),
# and receives "args", "commandes" (every line read), "iq" (the first bytes
# of a TX stream, "limite" of them), "compte" (TX bytes read), "tx_actif",
# "fin_flux" (the TX stream's end seen) and "quit".
d=$(dirname "$0")
mode=$(cat "$d/mode" 2>/dev/null || echo normal)
echo "$@" >> "$d/args"
interactif=0; info=0; probe=0
for a in "$@"; do
  case "$a" in -i) interactif=1 ;; -p) probe=1 ;; info) info=1 ;; esac
done
if [ "$mode" = absent ]; then
  echo "Failed to open device (first available): No devices available" >&2
  exit 255
fi
if [ "$probe" = 1 ]; then
  echo "  Description:    Nuand bladeRF 2.0"
  echo "  Serial:         aac46df92fb64a47a150cc2ab9b40e1f"
  exit 0
fi
if [ "$info" = 1 ] && [ "$interactif" = 0 ]; then
  echo "[INFO @ host/libraries/libbladeRF/src/helpers/version.c:79] Firmware version (v2.6.0) is newer than entries in libbladeRF's compatibility table." >&2
  echo ""
  echo "  Board:                    Nuand bladeRF 2.0 (bladerf2)"
  echo "  Serial #:                 aac46df92fb64a47a150cc2ab9b40e1f"
  echo "  FPGA size:                49 KLE"
  echo "  FPGA loaded:              yes"
  echo "  USB speed:                SuperSpeed"
  echo ""
  echo "  bladeRF-cli version:        1.9.0-0.2023.02-4build1"
  echo "  libbladeRF version:         2.5.0-0.2023.02-4build1"
  echo ""
  echo "  Firmware version:           2.6.0-git-09c82087"
  echo "  FPGA version:               0.16.0 (configured by USB host)"
  exit 0
fi
echo "[INFO @ host/libraries/libbladeRF/src/helpers/version.c:103] FPGA version (v0.16.0) is newer than entries in libbladeRF's compatibility table." >&2
txpid=""; rxpid=""; txfile=""; rxfile=""; tx_on=0
arret() {
  [ -n "$txpid" ] && kill "$txpid" 2>/dev/null
  [ -n "$rxpid" ] && kill "$rxpid" 2>/dev/null
  txpid=""; rxpid=""
  [ "$tx_on" = 1 ] && echo off > "$d/tx_actif"
  tx_on=0
}
trap arret EXIT
if [ "$mode" = sourd ]; then trap '' TERM; fi
while IFS= read -r l; do
  echo "$l" >> "$d/commandes"
  set -- $l
  ch=$(echo "$3" | tr a-z A-Z)
  case "$l" in
    quit)
      echo fin > "$d/quit"
      [ "$mode" = sourd ] && continue
      arret; exit 0 ;;
    "print frequency rx1")
      echo "  RX1 Frequency:  435000000 Hz (Range: [70000000, 6000000000])" ;;
    "set frequency "*)
      echo ""; echo "  $ch Frequency: $4 Hz (Range: [47000000, 6000000000])" ;;
    "set samplerate "*)
      echo "  Setting $ch sample rate - req:   $4 0/1Hz, actual:   $4 0/1Hz" ;;
    "set bandwidth "*)
      echo "  $ch Bandwidth:   $4 Hz (Range: [200000, 56000000])" ;;
    "set agc "*)
      echo "  $ch AGC: $4" ;;
    "set gain "*)
      if [ "$ch" = TX1 ]; then
        max=66; [ "$mode" = gamme ] && max=60
        if [ "$tx_on" = 1 ]; then lu=$4; [ "$mode" = gainfort ] && lu=$(($4 + 5)); else lu=-24; fi
        echo "Note: This change will not be visible until the channel is enabled."
        echo "Setting TX1 overall gain to $4 dB"
        echo "  Gain TX1 overall: $lu dB (Range: [-23.75, $max])"
      else
        echo "Setting RX1 overall gain to $4 dB"
        echo "  Gain RX1 overall:   $4 dB (Range: [-15, 60])"
      fi ;;
    "tx config "*)
      for a in $l; do case "$a" in file=*) txfile=${a#file=} ;; esac; done ;;
    tx)
      echo "  State: Idle"; echo "  Channels: TX1"; echo "  File: $txfile" ;;
    "tx start")
      tx_on=1; echo on > "$d/tx_actif"
      case "$mode" in
        bouche)
          # Opens the stream and never reads it.
          ( exec 3< "$txfile"; while :; do sleep 0.1; done ) & txpid=$! ;;
        erreur)
          ( exec < "$txfile"; head -c 300000 > /dev/null; sleep 1; echo "[ERROR @ host/libraries/libbladeRF/src/streaming/async.c:42] Transfer timed out"; while :; do sleep 0.1; done ) & txpid=$! ;;
        *)
          ( exec < "$txfile"
            limite=$(cat "$d/limite" 2>/dev/null || echo 0)
            total=0
            if [ "$limite" -gt 0 ]; then
              head -c "$limite" > "$d/iq"; total=$(wc -c < "$d/iq"); echo "$total" > "$d/compte"
            fi
            while :; do
              n=$(head -c 65536 | wc -c)
              [ "$n" -eq 0 ] && break
              total=$((total + n)); echo "$total" > "$d/compte"
              sleep 0.02
            done
            echo fin > "$d/fin_flux" ) & txpid=$! ;;
      esac ;;
    "tx stop")
      [ -n "$txpid" ] && kill "$txpid" 2>/dev/null
      txpid=""; tx_on=0; echo off > "$d/tx_actif" ;;
    "rx config "*)
      for a in $l; do case "$a" in file=*) rxfile=${a#file=} ;; esac; done ;;
    "rx start")
      ( exec > "$rxfile"; while :; do cat "$d/rx.bin" || exit 0; sleep 0.05; done ) & rxpid=$! ;;
    "rx stop")
      [ -n "$rxpid" ] && kill "$rxpid" 2>/dev/null; rxpid="" ;;
    *)
      echo "Unrecognized command: $l" ;;
  esac
done
arret
exit 0
