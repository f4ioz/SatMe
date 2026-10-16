#!/bin/sh
# A stand-in for hackrf_transfer and hackrf_info in the tests: never touches a radio.
# Its folder holds "mode" (what to play), and receives "args", "iq" (the first
# bytes of its input, "limite" of them), "compte" (bytes read) and "fin".
d=$(dirname "$0")
mode=$(cat "$d/mode" 2>/dev/null || echo normal)
case "$(basename "$0")" in
  hackrf_info*)
    if [ "$mode" = absent ]; then
      echo "hackrf_info version: 2024.02.1"
      echo "No HackRF boards found."
      exit 0
    fi
    echo "hackrf_info version: 2024.02.1"
    echo "libhackrf version: 2024.02.1 (0.9)"
    echo "Found HackRF"
    echo "Index: 0"
    echo "Serial number: 0000000000000000a06063c8234e925f"
    echo "Board ID Number: 2 (HackRF One)"
    echo "Firmware Version: 2024.02.1 (API:1.08)"
    echo "Part ID Number: 0xa000cb3c 0x00614764"
    echo "Hardware Revision: r9"
    exit 0 ;;
esac
echo "$@" > "$d/args"
case "$mode" in
  absent)
    echo "No HackRF boards found." >&2
    echo "hackrf_open() failed: HACKRF_ERROR_NOT_FOUND (-5)" >&2
    exit 1 ;;
  occupe)
    echo "hackrf_open() failed: Resource busy (LIBUSB_ERROR_BUSY)" >&2
    exit 1 ;;
  meurt)
    echo "Stop with Ctrl-C" >&2
    sleep 1.2
    head -c 300000 > /dev/null
    echo "hackrf_is_streaming() result: HACKRF_ERROR_STREAMING_THREAD_ERR (-1003)" >&2
    exit 1 ;;
  bouche)
    # Reads nothing and does not hear SIGTERM: only killing ends it.
    trap '' TERM
    echo "Stop with Ctrl-C" >&2
    while :; do sleep 0.1; done ;;
esac
echo "call hackrf_set_sample_rate()" >&2
echo "Stop with Ctrl-C" >&2
limite=$(cat "$d/limite" 2>/dev/null || echo 0)
total=0
if [ "$limite" -gt 0 ]; then
  head -c "$limite" > "$d/iq"
  total=$(wc -c < "$d/iq")
  echo "$total" > "$d/compte"
fi
# About 3 MB/s, as a radio would take them.
while :; do
  n=$(head -c 65536 | wc -c)
  [ "$n" -eq 0 ] && break
  total=$((total + n))
  echo "$total" > "$d/compte"
  sleep 0.02
done
echo "Exiting... hackrf_is_streaming() result: HACKRF_ERROR_STREAMING_EXIT_CALLED (-1004)" >&2
echo "fin de l'entrée" > "$d/fin"
exit 0
