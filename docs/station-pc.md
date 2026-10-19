# SatMe-PC — the station in a browser

*October 2026. The Android app stays the reference; the PC does what it does, and what is made on the PC comes to the phone too. The PC pages are in French for now.*

SatMe runs on a PC (Linux, Windows, Raspberry Pi) and is driven from a browser. Its core is **the same code as on the phone**: Doppler loop, rotator tracking, pass journal, SSTV, METEOR, APRS, FT8, QO-100, DATV and the log all come from the shared `:moteur` module.

<p align="center"><img src="images/satme-ensemble-en.svg" width="100%" alt="The three SatMe: the phone, SatMe-PC and SatMe-Node at the mast; the phone joins the PC with a QR code; the same engine everywhere"></p>

<p align="center"><img src="images/pc-station.webp" width="100%" alt="SatMe-PC, Station tab: the next passes, the satellite followed on the sky, the AMSAT box, Doppler-corrected frequencies, the rig and the rotator with their green tick"></p>

## Contents

1. [Installing it](#installing-it) — options, the page on other machines and its password
2. [The tabs at a glance](#the-tabs-at-a-glance) — workspaces, built-in manual (F1)
3. [Station and passes](#station-and-passes) — automatic mode, recording, calendar, horizon
4. [The log (Carnet)](#the-log-carnet)
5. [SatMe on the phone](#satme-on-the-phone)
6. [Pass journal](#pass-journal)
7. [QO-100: the cockpit](#qo-100-the-cockpit) — multiple decoders
8. [DATV: reception](#datv-reception) — MiniTiouner, SatMe-Node, Pluto / bladeRF, wide band, quality, subtitles
9. [DATV: transmission](#datv-transmission) — the station, the rules, the indoor test, card and chat
10. [SSTV](#sstv) — reception and transmission
11. [FT8 / FT4](#ft8--ft4)
12. [Voice to text (Phonie → texte)](#voice-to-text-phonie--texte)
13. [The SDR receiver](#the-sdr-receiver) — memories, band plan, display, sound cleaning, CTCSS, I/Q, test generator, measurements
14. [SDR hardware](#sdr-hardware) — RTL-SDR, ADALM-PLUTO, bladeRF 2.0, HackRF One
15. [Aircraft (ADS-B) and the map](#aircraft-ads-b-and-the-map) — 1090 MHz, aircraft scatter, the shared map
16. [Montage: the station chain](#montage-the-station-chain) — diagram, functions, profiles
17. [Réglages (settings)](#réglages-settings) — rig and rotator, orbital elements, Doppler log, satellite link, rigctld, USB knob, remote access
18. [More of the PC](#more-of-the-pc)
19. [Hardware safety](#hardware-safety)
20. [Before the first try](#before-the-first-try)

## Installing it

- **Linux**: [Installing SatMe-PC](installer-satme-pc.md) — download `satme-pc.tar`, unpack, start, then open **http://localhost:8090/**.
- **Windows**: [Installing SatMe-PC on Windows](installer-satme-pc-windows.md) — all in one: `satme-pc-windows.msi`, double-click, the page opens.
- **The MiniTiouner by the antenna**: [Installing SatMe-Node on a Raspberry Pi](installer-satme-node.md).

| Option | Effect |
|---|---|
| `--port 8090` | the page's port |
| `--reseau` | page open to the other machines of the network (a station at the foot of the mast, driven from the house); without it, this PC only |
| `--dossier DIR` | where settings, log, journal and recordings are kept (`~/.satme`, `%APPDATA%\SatMe` on Windows) |
| `--noeud` | SatMe-Node: the DATV receiver only, driven over the network |
| `--bureau` · `--console` | open the page in the browser, an icon by the clock (the default on Windows); or not |

**The page on the network's other machines** (a tablet, the living-room PC): start `satme --reseau`, then open `http://<the PC's address>:8090/` (`hostname -I` gives it). On Linux with ufw, open the port to the local network once, in a terminal: `sudo ufw allow from 192.168.0.0/24 to any port 8090 proto tcp` (adapt the range to your router). **A password** is then asked of the other machines (this PC itself gets in without one): drawn at the first start with `--reseau` (four words, written in SatMe-PC's log and shown in *Réglages › Accès à distance*), to be replaced with your own (see [Remote access](#remote-access)). Without HTTPS everything goes in clear: home network only, never opened to the internet. **🔈 Écouter ici**, at the top, plays SatMe-PC's sound (SDR or sound card) in that browser.

**Optional tools**, only for the features that use them (Linux):

| Package | For |
|---|---|
| `ffmpeg` | DATV pictures and sound, DATV transmission, subtitles (bundled on Windows) |
| `tesseract-ocr` | callsigns read in the DATV picture |
| `hackrf` | the HackRF One test transmitter |
| `bladerf` | the bladeRF 2.0 |
| `python3-venv` | voice to text (the engine is installed from Réglages) |

## The tabs at a glance

| Tab | What it does |
|---|---|
| **Suivi** | the next 24 h passes, the satellite followed on the sky or the map, the AMSAT box, Doppler-corrected frequencies, the rig and rotator by name with a green tick |
| **Carnet** | the log: entry with callsign prediction and QRZ, the station live beside it, the CW decoder window, voice to text |
| **Agenda** · **Frise** · **Carrés** | appointments and the **automatic mode**; the passes on a timeline; worked squares on a map |
| **Sked** | the windows when you and another station both see a satellite, replayed on the map, made into a video |
| **QO-100** | the narrow band cockpit: one-click reception, named signals, click-to-tune with the right decoder |
| **DATV** | QO-100's wide band: reception (MiniTiouner, node, Pluto or bladeRF), quality, subtitles, transmission |
| **Montage** | the station diagram, what each SatMe function holds, the receive profiles |
| **SDR** | the receiver: RTL-SDR, ADALM-PLUTO, bladeRF 2.0, I/Q file, test generator; waterfall, vernier, magnifier, memories, sound cleaning, measurements |
| **Carte** · **Avions** | the shared map (satellites, APRS, sondes, aircraft, DATV, squares); ADS-B on 1090 MHz and aircraft scatter |
| **Sondes** · **METEOR** | radiosondes; METEOR-M LRPT pictures |
| **Son** | the sound card, recording |
| **SSTV** | live decoding, the pictures, the test card, transmission |
| **Planche** | the SSTV sheet: pictures on a template, downloaded full size |
| **APRS** · **FT8** | APRS (KISS radio too) and its game; the FT8 / FT4 board |
| **Journal** | the passes replayed with their sound, made into a picture, video or GIF |
| **Réglages** | by category: Ma station, Poste et rotor, Satellites, Interfaces, Services en ligne, Phonie → texte, Téléphone, Accord fin, Émission, Accès à distance, Sauvegarde |

The QO-100, DATV and SSTV tabs fit one screen (1920×970 down to 1366×700): the main view stays in sight, the secondary cards are **sub-tabs** with icons, the last one opened kept.

- **Workspaces**, in the left rail (keys **1** to **6**, each reopened at its last tab): **Ciel** (Suivi, Agenda, Frise, Sked, Carrés, Carte, Avions, METEOR, Sondes), **QO-100**, **DATV**, **Modes** (SSTV, APRS, FT8), **Carnet** (Carnet, Journal, Planche), **Station** (Montage, SDR, Son, Réglages).
- **Ctrl+K**: the command palette — tabs and sub-tabs, settings categories, actions (« Arrêter toute émission »…), a satellite, or a typed frequency (« 145,8 ») for the SDR receiver to listen to.
- **The built-in manual**: **F1** opens it at the current tab's chapter; it can be searched; its chapters are this guide's. **Esc** closes it — a second **Esc** stops transmissions, as always.

## Station and passes

**Suivi** (workspace **Ciel**) lists the passes of the next 24 hours (favourites, heard on AMSAT these days, agenda), the satellite followed on the sky or the map, the **AMSAT box** to the right of its name (what stations hear, "Heard" / "Not heard" in one click), Doppler-corrected frequencies with the RX/TX offsets, **● Record the sound**, and **the rig and the rotator by name, a green tick ✓ when connected** — one click leads to their settings.

- **⤓ Agenda (.ics)**: the passes of the list (its filters kept), or those of the followed satellite, in a calendar file (RFC 5545, UTC; satellite, max elevation, AOS/LOS azimuths) for any calendar application.
- **Horizon mask** (Réglages › Ma station): the obstacles' height per azimuth sector (8 × 45° or 12 × 30°), drawn from above. Passes then start and end when the satellite clears them (the geometric AOS/LOS shown under ✱); passes hidden all along are left out. All at 0 changes nothing. Same setting on the phone.

### Automatic mode: miss no pass

**Agenda** tab. Choose your satellites (one by one, or every ★ at once) and a minimum elevation; SatMe-PC lists their passes over the next 24 hours. Any of them can be set aside with a tick.

- **5 minutes before AOS**, the pass's satellite becomes the one followed: **the rig (CAT, Doppler) and the rotator follow it** — the rotator goes and waits at the rise azimuth.
- **The sound is recorded** from 5 s before AOS to 30 s after LOS (the Son tab's sound card), and the pass lands in the **journal**, replayable with its sound.
- **Two passes at once**: the first goes on, the other is marked "during …"; setting the first aside keeps the second.
- Changing satellite by hand during a pass is still possible: the automatic mode does not take over again before the next pass.
- The card says what is missing: rig not connected, rotator without "Suivre" (follow), sound card stopped.

The same mode is on the phone (*Settings → Recording / SSTV & NOAA → Automatic mode*), screen off included.

<p align="center">
<img src="images/pc-agenda-auto.webp" width="66%" alt="Agenda tab: the automatic mode, the satellites chosen, the 24 h passes to keep or set aside, those lost during another one">
<img src="images/fr-veille.webp" width="24%" alt="The automatic mode on the phone: the satellites chosen, 16 passes armed">
</p>

### Recording the sound

- **● Record the sound** in Suivi, Carnet and Son: a WAV named after the satellite (`SatMe_RS-44_20261013_203051Z.wav`), as on the phone.
- **Stop at the end of the pass** (option): even started by hand, the recording stops 30 s after the LOS of the pass it caught.
- **Every pass of the satellite followed** (option in the Son tab), or those of the **automatic mode**.
- A recording stopped by hand does not start again by itself before the next pass.

## The log (Carnet)

A contact is typed with the callsign predicted from your log and QRZ (Tab goes through the predictions, Enter confirms). Next to it, the station live: the satellite, its frequencies, the polar plot, the rotator, whether you are in the passband, the recording. The callsign's card: every past contact and its notes, the QRZ.com sheet (photo, distance, map). A forgotten contact is added after the pass, at its time. **Records** by satellite: the farthest contact of all, of each satellite, mode, continent and country, WAC.

- **CW decoder** (off unless ticked): the tone found by itself, the speed followed, the Doppler followed; callsigns, squares and reports lit, a callsign into the entry with a click.
- **Delete** a contact from its row (🗑), or tick several and delete them at once.
- **Wavelog's station locations**: "Read the profiles" shows where you are (your square, or both on a grid line) and the profile it goes to — chosen in the list, or **created** when Wavelog has none for it (DXCC, CQ and ITU zones asked). Each contact goes to the profile of where it was made from; a profile chosen by hand for another square is flagged.
- **Each new contact sent to Wavelog / Cloudlog by itself**, after a delay to choose (30 s to 30 min): the time to fix it or hold it back (⏸, countdown in the log). A contact the server refuses is held back with the reason. The phone has the same delay choice.
- **ADIF** export, and the deposit by hand (⬆).

<p align="center">
<img src="images/pc-carnet.webp" width="49%" alt="Log tab: a new contact, and beside it the station live — FO-29, its frequencies, the polar plot, the rotator">
<img src="images/pc-carnet-contacts.webp" width="49%" alt="Log tab, the contacts: tick several to delete, the upload state of each">
</p>

## SatMe on the phone

The log's **Import → SatMe sur le téléphone**:

- **By QR code**: the PC shows one; on the phone, *Settings → Share and listen → Share → Scan the PC's QR code*. The phone opens its control desk and the PC joins it by itself (same Wi-Fi). On Linux, the PC's firewall must let the page's port + 1 through (`8091` by default), for example `sudo ufw allow from 192.168.0.0/16 to any port 8091 proto tcp`.
- **Or by hand**, with the address and code the phone shows.
- The phone's passes are listed; those ticked come over with their **SSTV pictures**, their **recordings** (made WAV, the spoken header cut) and their **contacts** (added to the log unless already there).
- A **pass file (.zip)** made on the phone can also be dropped on the page.

<p align="center"><img src="images/satme-qr-en.svg" width="100%" alt="From the phone to the PC in three moves: the PC shows a QR code, the phone scans it, the passes ticked arrive with their contacts, SSTV pictures and sound"></p>

<p align="center"><img src="images/pc-telephone.webp" width="70%" alt="The phone joins the PC by scanning a QR code"></p>

## Pass journal

The phone's journal page, under the PC's tabs: passes listed (♪ when there is sound), replayed on the sky or the map with the S-meter, the frequency, the contacts and the pictures in their place. "Make": sound, picture, video, GIF or pass file. **The file's frame is drawn over the replay** (phone 3:4 or screen 16:9): what is inside the frame is what the file shows.

- **The whole pass** on the map: the predicted track dashed, the part followed solid — even when SatMe-PC was started mid-pass.
- **Each contact in its place, as in the video**: a dot on the track at the QSO's moment, with the callsign, and the station at its square, linked to it.
- **The S-meter** read through CAT (IC-9700, IC-9100, IC-910H, TS-2000), receiving only.
- **Add** a contact during the replay: paused, the time is the sound's; callsign predicted and QRZ, the satellite's mode and frequencies, RST (59 / 59 by default) and a comment; Enter confirms.
- **+ Passage**: a pass made from nothing — the satellite and its times, contacts, a sound, pictures.
- The pass's **Doppler / CAT log** (when kept, see [Réglages](#réglages-settings)) is linked from it.

<p align="center"><img src="images/pc-journal-carte.webp" width="100%" alt="Journal: RS-44 replayed on the map, the predicted track dashed, the footprint, F5RRO in its place on the track and at its square"></p>

<p align="center">
<img src="images/pc-journal.webp" width="49%" alt="Pass journal: an ISS pass replayed on the map">
<img src="images/pc-journal-cadre.webp" width="49%" alt="Making a 3:4 video: the file's frame over the map, the caption below">
</p>

## QO-100: the cockpit

The **QO-100** tab is the narrow band transponder on one screen: the receiver, the decoder and the log entry side by side, the transponder's waterfall below.

1. **Démarrer la réception QO-100**: one click starts the receiver (SDR dongle, ADALM-PLUTO or bladeRF behind the LNB or a converter), puts it on QO-100 and sends its sound to the decoders and the log.
2. **Click a signal** in the waterfall: SatMe tunes it in its mode (SSB, CW, FT8, SSTV) and opens the right decoder in the dock — voice to text, CW, FT8 / FT4 or SSTV (*Auto*, or chosen by hand).
3. **Log the contact** on the right: satellite QO-100, frequencies and mode already filled in.
4. The **middle beacon** is the reference: with *Calage automatique* it is measured every 5 s and the LNB's drift followed by small steps, never on a weak measure.

- **Signal classifier**: each signal of the transponder named on the waterfall (SSB, CW, FT8, FT4, PSK31, RTTY, SSTV, carrier, beacons, wide digital, unknown), coloured by class with its confidence; one FFT of 16 Hz bins over the receiver's raw stream, combs of spurious lines and key clicks left out, signals forgotten once gone. It runs only while the page looks at it.
- **Band plan** (AMSAT-DL's) on the ruler and atop the waterfall, each segment's rule on hover. **Emergency frequency** (10 489.858 – 10 489.865): red outline and banner; SatMe never sends the uplink there to the rig by itself (a memory, a signal clicked, a DATV talkback, a restored session) — only when tuned by hand. The FT8 free spot is offered only in the digital segments; a mode out of its segment gets a discreet hint.
- **LEILA**: when the ground station's siren is heard (the listened station is stronger than the beacons and must lower its power), a red **⚠ LEILA** shows under the downlink for 10 s.
- **Multiple decoders** (**Multiples** button, off by default): up to **4 channels** taken from what the receiver sees, each with its decoder (FT8, FT4, CW, SSTV, voice, or plain listening; one SSTV and one voice at most). Presets « FT8 + CW + phonie » and « FT8 + FT4 »; right-click (or Shift + click) on a waterfall signal: « Ajouter un décodeur ici ». The sound stays the main tuning's, except the channel ticked 🔈; a click on a channel's frequency tunes the main receiver there. About 1 % of a core per channel.
- **Affichage** (the waterfall's palette, contrast and brightness, memory flags), **Mémoires**, **Mesures** and **sound cleaning**, as in the SDR tab (see [The SDR receiver](#the-sdr-receiver)); the memories made here carry the « QO-100 » tag.
- **Resume after a restart**: a banner offers to take QO-100 back where it was (receiver, frequency, mode); *Réglages › Satellites › QO-100* does it by itself at start. Reception only: nothing is ever transmitted.
- Sub-tabs: **Calage** (beacon calibration, by hand or automatic), **Qualité** (beacon over noise, offset, the LNB's error and drift over the hour, noise floor, a day's curve), **Mémoires**, **Poste & chaîne** (converters), **Pointage**.

<p align="center"><img src="images/pc-qo100-cockpit.webp" width="100%" alt="QO-100 cockpit, receiver stopped: the four steps, the receiver and its tuning, the decoder dock, the log entry, the band plan with its segments over the waterfall, the automatic beacon calibration"></p>

## DATV: reception

The **DATV** tab receives DVB-S / DVB-S2 on QO-100's wide band (or any frequency the receiver covers). The picture is sized to the window, with the lock, MER, MODCOD, the **margin** and its verdict, and the station's card beside it; **Réception**, **Émission**, **Qualité** and **Sous-titres** are sub-tabs.

<p align="center"><img src="images/pc-datv.webp" width="100%" alt="DATV tab: an indoor test at 23 cm — ADALM-PLUTO transmitting, bladeRF 2.0 receiving with SatMe's DVB-S2 demodulator; locked DVB-S2, MER 9.5 dB, QPSK 2/3, margin +5.5 dB, picture perfect; the callsign read in the picture with its QRZ record"></p>

**Three receivers**, chosen in the Réception card:

- **MiniTiouner** (tuner and demodulator on USB), driven by [LongMynd](https://github.com/BritishAmateurTelevisionClub/longmynd), BATC's free program. SatMe runs it apart, as it runs rtl_tcp: build it, then give its path on the page (or put it in the `PATH`). Its **LNB supply stays off** unless chosen (13 V or 18 V), with a warning. On Linux a udev rule lets LongMynd open it — LongMynd's own file misses the Pro TS2 (USB name `MiniTiouner_Pro_TS2`); this one covers them all, then unplug and plug it in again:

  ```
  echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | sudo tee /etc/udev/rules.d/60-satme-minitiouner.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger
  ```

  Plugged in, the MiniTiouner also shows as two serial ports (`/dev/ttyUSB…`): never choose them as the rig's or the rotator's port.
- **ADALM-PLUTO** or **bladeRF 2.0** with **SatMe's own DVB-S2 demodulator** (written from ETSI EN 302 307-1): carrier search on the spectrum, matched filter with clock recovery, PLHEADER search, carrier recovery on pilots or by decision, LDPC and BCH decoding, transport packets checked. QPSK and 8PSK, normal and short frames, with or without pilots. The SDR tab lends its board for the time of the reception and takes it back after; a board receiving DATV is refused to the transmitters. Lock is given up after 8 failed frames in a row.
- **A SatMe-Node** next to the antenna (below).

**What the tab shows**: the beacon (10491.5 MHz, 1.5 MS/s), the narrow channels, 125 to 2000 kS/s; the LNB's oscillator (9750 MHz by default) and **Caler sur le signal** to correct it on the beacon; lock (DVB-S / DVB-S2), MER, MODCOD, carrier and offset, service and provider, the constellation live. The picture in the page (H.264 or H.265, the players restarted when a station changes its coding), the sound on the PC, the same stream in **VLC**, and **recording** to a `.ts` file.

### SatMe-Node: the receiver next to the antenna

The MiniTiouner works best close to the LNB. A **SatMe-Node** is SatMe on a small computer there — a Raspberry Pi (a **Pi 3 is enough** for the MiniTiouner: the node only receives) — driven over the network by SatMe-PC and by the phone (**QO-100** screen → **DATV**: the same controls, the picture through the phone's own decoder). It runs LongMynd, or SatMe's DVB-S2 demodulator with a Pluto or a bladeRF; its status and the transport stream (0.3 to 2 Mbit/s) come to the PC, which shows, plays and records them as if the receiver were plugged into it.

Installing it on the Pi: [Installing SatMe-Node on a Raspberry Pi](installer-satme-node.md) — three commands, about ten minutes on a Pi 3. The script installs Java, builds LongMynd, lets it open the MiniTiouner (udev), and starts the **satme-noeud** service (`satme --noeud`, port 8090). At its first start the node draws a **six-digit code**, shown at the end of the installation (again: `journalctl -u satme-noeud | grep Code`). In SatMe-PC, DATV tab, **Changer**: the node's address and its code, **Associer**. The PC keeps the key the node gave: either can restart. An empty address goes back to the receiver on the PC. Without its key the node only says what it is; its LNB supply stays off unless chosen.

### The wide band at a glance

*Spectre de la bande large (station sol)*, off by default: the QO-100 ground station's public spectrum of the wide band (10490.5 – 10499.5 MHz, 4 frames a second), read while the option is on and the tab open.

- The **signals found**, each with its width rounded to the nearest symbol rate, its level against the beacon, and the station's **name** once read (service name, station card or callsign in the picture; kept 24 h for that frequency and rate).
- **Click a signal**: the MiniTiouner tunes to it (same oscillator, same input).
- **Veille**: the occupied channels in turn, held while locked, an optional recording named after the station; any tuning by hand stops it.
- **In the QO-100 cockpit too**: *Bande large DATV (station sol)*, off by default, shows the same band under the narrow transponder; a click on a signal tunes the DATV receiver. The connection stays open only while one of the two tabs shows it.

### Sound, chat and tuning by UDP

Each off until chosen in the Réception card.

- **Vumètre et volume**: the sound is decoded by SatMe (ffmpeg to 48 kHz stereo PCM) instead of ffplay, played on the PC's sound output at the chosen volume (0 to 200 %, applied at once), its left and right levels (« M » when both channels are the same) with the peak held 1.5 s. **Écouter ici** › *Son DATV* also sends it to the browser, with its own volume.
- **Salon de la bande large**: the ground station's public chat (BATC), read only until you write: nothing is sent without a click on **Envoyer**, the nick is the station's callsign (set just before the first message), one message every 3 s at most; without a callsign it stays read only. **Rapport** writes the usual reception report into the box (« SigReport: callsign - D margin (MER dB) - rate Ks - frequency »), to read over and send. No online DATV reception report service is reachable today: the page says so (« service indisponible ») and nothing else is sent.
- **Accord par UDP**: another program asks for a tuning by a text datagram, on the chosen port (9997 by default), from this PC only — or from the local network, when SatMe-PC runs with `--reseau` and the box is ticked. Receive only: nothing is ever answered nor transmitted, the LNB supply stays as it is, one request every 2 s at most (10 a minute), the same one asked again while receiving restarts nothing. Two formats:

  ```
  FREQ=10494750;SR=1500;OL=9750000;ENTREE=A
  [GlobalMsg],Freq=10491500,Offset=9750000,Srate=1500,FPlug=A
  ```

  The first: the sky frequency in kHz (or in MHz with a point: `FREQ=10494.75`), the symbol rate in kS/s, the LNB's oscillator in kHz and the input A or B optional (kept otherwise). The second, common among DATV tools: Freq in kHz, Offset the oscillator in kHz (0: Freq is already the intermediate frequency), Srate in kS/s; its other keys (supply, 22 kHz…) are ignored. For example: `echo -n 'FREQ=10494750;SR=333' | nc -u -w1 127.0.0.1 9997`.

### Reception quality

The **Qualité** sub-tab says whether the picture is good and why:

- **The margin**, big and coloured: the MER against the MODCOD's quasi-error-free threshold (EN 302 307-1), with a plain verdict ("Image parfaite"…).
- Tiles: packets received against expected (from the PCR), continuity errors and missing packets, transport errors and sync losses, error correction (LDPC, BCH), whole DVB-S2 frames lost, the video and audio decoders' errors, the tables seen (PAT, PMT, SDT, PCR) and the stream's null share.
- Four curves over 2 minutes (MER, margin, packets, continuity errors), the PIDs table, the constellation. When the receiver holds our own transmission, packets sent against received.

<p align="center">
<img src="images/pc-datv-qualite.webp" width="49%" alt="DATV, Qualité sub-tab: margin +6.6 dB, MODCOD threshold 3.1 dB, 856 of 858 packets a second, tables, curves over two minutes, the PIDs, the constellation">
<img src="images/pc-datv-emission.webp" width="49%" alt="DATV, Émission sub-tab during an indoor test: ADALM-PLUTO, QPSK 2/3, 1000 kS/s, a looped video in H.265, gain −40 dB, the orange indoor-test warning, the MiniTiouner settings for the test">
</p>

### Subtitles and callsigns in the picture

Both off unless chosen in the DATV tab.

- **Sous-titres**: the received sound (its first audio track, decoded by ffmpeg) goes to [voice to text](#voice-to-text-phonie--texte); the last two overs over the picture with the language's flag and the translation, the history in the sub-tab, callsign chips filling the log in mode DATV.
- **Callsigns read in the picture**: every 3 s while locked, the latest frame read by **tesseract** (`sudo apt install tesseract-ocr`); a callsign is shown once read twice, look-alike signs repaired (A/4, O/0…), checked against the service name, the station card and the log. Its **QRZ.com record** (name, town, country, square with distance and bearing, LoTW / eQSL, photo) is looked up by SatMe-PC, kept 7 days, at most one request every 5 s. Only what was read since the current lock is shown.
- **Noter le QSO**: QO-100 or *DATV terrestre* by the frequency received, mode DATV (ATV in ADIF), the MER as report, name and QTH from QRZ when blank.

## DATV: transmission

The DATV tab's **Émission** sub-tab sends a DVB-S2 signal to QO-100's wide band transponder with an **ADALM-PLUTO** or a **bladeRF 2.0** (a **HackRF One** only for indoor and dummy-load tests). SatMe makes the DVB-S2 itself (EN 302 307-1: QPSK at every rate, 8PSK 3/5 to 9/10, normal or short frames, pilots, roll-off 0.35 / 0.25 / 0.20); **ffmpeg** makes the picture into a transport stream at exactly the rate the MODCOD carries (`-muxrate`, null packets filling it), H.264 or H.265, the service's name and provider being the station's callsign.

- **The picture**: SatMe's test card with the callsign, an **animated test card** (a moving picture over the bars, a beep each second), a still image, a **video file looped**, or a camera (`/dev/video…`, listed only until a transmission starts).
- **Parcourir…**: the PC's folders (home, removable media, SatMe's `datv-sources`), read only, with thumbnails; files sent from the browser (500 MB at most) are kept in `datv-sources`. **Prévisualiser** makes one picture with the same command, nothing transmitted.
- **Overlay** (off by default): callsign, locator, a free line, the UTC time and the SatMe mark, in a corner or a strip, over the picture.
- **Adapter le MODCOD à la réception** (off by default): while our own signal is received back (service or station card in our callsign), the most efficient MODCOD of the allowed range whose threshold stays 2 dB under the MER is chosen — down quickly when the margin falls or packets are lost, up slowly after 20 s of spare margin, 8 s after each change for the receiver to relock, the most robust one if the loop is lost. Frequency, symbol rate and power are never touched; a lower power is suggested on QO-100, never applied.

**The station.** PC —USB— Pluto **TX** port → band-pass filter → power amplifier → feed (2.4 GHz uplink). Reception check: dish, LNB → MiniTiouner (*Réception*). A board transmitting does not receive (the SDR tab refuses it), and the other way round; DATV and SSTV transmissions exclude each other.

| kS/s | samples per symbol | Pluto rate |
|---|---|---|
| 125 | 17 | 2.125 MS/s |
| 250 | 9 | 2.25 MS/s |
| 333 | 7 | 2.331 MS/s |
| 500 | 5 | 2.5 MS/s |
| 1000 | 3 | 3 MS/s |
| 1500 / 2000 | 2 | 3 / 4 MS/s |

The bladeRF keeps at least 4 samples per symbol. A symbol rate or MODCOD not offered is refused, never silently replaced.

**First test: into a load, never into the antenna.** Pluto TX → 30 dB attenuator (or more) → MiniTiouner input (its tuner covers 2.4 GHz directly: *Ciel* = the uplink frequency, LNB oscillator 0, LNB supply **off**). Tick *Test sur charge* (any frequency, gain held at −40 dB or below, a red warning), choose 333 kS/s QPSK 2/3, **ÉMETTRE**, confirm: the MiniTiouner should lock on DVB-S2 with the callsign as service and the test card as picture. Only then the amplifier and the dish.

**The rules, built in.**
- Only on the operator's action: **ÉMETTRE** then a confirmation; never at startup, never taken back from the settings (only the parameters are kept).
- A **callsign** (Réglages › Ma station) before anything is sent.
- On the air, the whole occupied band (Rs × (1 + α)) between **2403.000 and 2410.000 MHz** uplink: clear of the beacon (uplink 2402.0 MHz, its band ending near 2402.9 MHz), up to the band plan's last channel (10499.25 MHz down, 2409.75 MHz up). The channel list is the band plan's; the one the MiniTiouner holds is marked busy.
- **Duration** 10 min by default, 30 min at most, then it stops by itself.
- It stops by itself when the board stops taking samples or is pulled out, when the samples come late too often, when ffmpeg stops, when the page has been silent for 15 s (closed tab, lost network), and when SatMe-PC closes.
- At every stop the Pluto's **TX oscillator is powered down first**; at the start it stays down until the first samples are in. Should the Pluto be unreachable at that moment, the page says so: unplug it.
- **TX gain** (an attenuation, 0 dB = full power): Pluto −30 dB by default, bladeRF −50 dB, never above the maximum set in Réglages (−10 dB by default).
- **Closed loop**: the receiver's MER on our own signal against the beacon's (measured with *Balise* beforehand): if we are not at least 1 dB under the beacon, a warning, and the gain is stepped down 1 dB every 5 s until we are. It never steps it up.

**Réglages › Poste et rotor › ADALM-PLUTO**: the maximum TX gain, and *Couper l'oscillateur d'émission du Pluto quand SatMe s'en sert en réception* (off by default). A Pluto switched on leaves its TX oscillator running (2450 MHz by default): ticked, SatMe powers it down each time it starts receiving with the Pluto.

### The indoor test

*Essai en intérieur* (Pluto, bladeRF or HackRF): two small antennas a few metres apart, indoors, to see the whole chain work before any dish.

- A real radio emission: for a **licensed radio amateur**, under the callsign (service name and test card), with an orange warning in the card.
- Only in amateur bands the MiniTiouner covers, the **whole occupied band inside**: 23 cm **1240–1300 MHz** or 13 cm **2300–2450 MHz**. 1255 MHz by default; 1260–1300 MHz is shared with radionavigation satellites and 2400–2450 MHz with Wi-Fi (the card says so).
- **Minimum power**: Pluto and bladeRF at −40 dB at most; the HackRF with its amplifier off and VGA ≤ 20 dB.
- **5 min** by default, **10 min** at most, then it stops by itself. The QO-100 closed loop is not used.

**The receiving side.** The card gives the MiniTiouner's settings for the test: *Ciel* = the transmit frequency, LNB oscillator **0**, the same symbol rate, LNB supply **off** — *Régler la réception* fills them in (without starting). With an antenna on the MiniTiouner's input the LNB supply **must be off**, or its 13/18 V go into the antenna. A Pluto or bladeRF can receive instead, with SatMe's demodulator.

**First indoor test, step by step.** Callsign set (Réglages › Ma station). HackRF (or Pluto) TX → small 23 cm antenna; receiver input → another small antenna, 2–3 m away. Card: *Émetteur* HackRF One, *Détecter*; tick *Essai en intérieur*, 1255 MHz, 333 kS/s, QPSK 2/3, test card, VGA 0 dB (Pluto: −40 dB or lower), 5 min. *Régler la réception*, then *Recevoir* in the Réception card (LNB supply off). **ÉMETTRE**, confirm: the receiver should lock on DVB-S2, the callsign as service. No lock: raise the VGA a few dB, or bring the antennas closer.

### Station card and chat in the stream

While transmitting, SatMe slips a **station card** into the transport stream, and the **chat** typed in the Émission card (a line of 140 characters at most, one every 2 s; typing never transmits: the chat only rides on a transmission already started). They take the place of null packets: the rate is unchanged, so are the picture and sound, and a receiver that does not know them ignores them. On reception the DATV tab shows the received station's card (flag, locator, distance and bearing, message, software; marked when it is already in the log), its chat lines as bubbles, and **Noter le QSO**. Receiving one's own signal ticks one's lines *✓✓ reçu*. The card is switched off under *Carte de visite dans le flux*.

**Talkback on the narrow band.** The Émission card also announces where the station listens for answers on the narrow band transponder (*Retour NB*, MHz, SSB or CW): by default the QO-100 cockpit's frequency when the rig is there. On reception the station's card shows « Retour en BLU : 10 489,xxx » and **Écouter le retour**: the QO-100 cockpit opens, the narrow band receiver tunes there and voice to text is switched on when installed. A talkback announced on the emergency frequency is flagged, never tuned by itself.

**The format**, for anyone who wants to read or send it:

- **PID 0x1F00**, MPEG private sections (ISO/IEC 13818-1, long form: `section_syntax_indicator` 1, `private_indicator` 1, MPEG-2 CRC-32 at the end); one section per group of packets, the first with `payload_unit_start` and `pointer_field` 0, the last padded with 0xFF. The PMT is left unchanged.
- **table_id 0xE5: card** (`table_id_extension` 0, `version_number` changes with the content), repeated every 2 s. **table_id 0xE6: chat line** (`table_id_extension` = 16-bit sequence number), sent 5 times 1 s apart; the receiver drops duplicates by callsign and sequence.
- Payload: `SATM`, a format byte (1), then TLV fields (1-byte tag, 1-byte length, UTF-8 value of 255 bytes at most). Tags: 0x01 callsign (required), 0x02 locator, 0x03 name, 0x04 QTH, 0x05 power, 0x06 antenna, 0x07 software, 0x08 transmit frequency (kHz, in digits), 0x09 MODCOD, 0x0A symbol rate (kS/s), 0x0B message, 0x0C talkback frequency on QO-100's narrow band transponder (downlink in Hz, in digits), 0x0D its mode (`USB` or `CW`), 0x20 chat text. An unknown tag is skipped; a format byte ≥ 0x80 announces an incompatible layout: the section is ignored.

## SSTV

The **SSTV** tab, as on the phone: the **live picture** (drawn at its true size from the first line, the lines to come dark, the arriving one marked), the decoder's state and a TX shortcut stay in view; **Réception**, **Émission**, **Mire**, **Images** and **Réglages** are sub-tabs.

<p align="center"><img src="images/pc-sstv.webp" width="100%" alt="SSTV, Images sub-tab: RS-44, UmKA-1 and ISS pictures, each with its satellite, mode and time"></p>

**Reception**

- **Source**: *auto* (the SDR while it plays, else the sound card), the sound card, or the SDR heard directly.
- **Mode**: *Auto (VIS)* or forced; **continuous decoding** starts a picture on its sync pulses even without a header (mode found from the line length); **Caler maintenant** hooks the picture on the next sync pulse.
- **Automatic tuning** (off by default): the tones' offset measured on the header (read over ±1 kHz) and the sync pulses, and the receiver nudged — the SDR, or the rig while the CAT loop drives it — by 20 Hz a second at most, up to ±2 kHz.
- A spectrum with the tones marked (1200 sync, 1500 black, 1900 VIS, 2300 white), the level, a hint when the source is silent.
- **Images**: every picture kept from an eighth of its lines, with its own sound; filtered by satellite, opened in a viewer, decoded again, made into a GIF, deleted.
- **Mire**: the SSTV test card (as on the phone), played on a chosen output or written to WAV.

**Transmission** (*Émission*), with the **ADALM-PLUTO** or the **bladeRF 2.0**:

- The picture: SatMe's test card with the callsign, the SSTV sheet, or an image file; any mode, sent 1 to 3 times, the **callsign written across the top** of the picture, and in CW at the end if asked.
- **Modulation**: narrow FM (±2.5 or ±5 kHz, pre-emphasis optional), USB or LSB.
- **Rules, built in**: **ÉMETTRE** then a confirmation; a callsign; the whole occupied band inside an amateur band (144–146, 430–440, 1240–1300, 2300–2450 MHz); the satellite sub-bands (145.8–146, 435–438, 1260–1270 MHz) only with *Vers un satellite* ticked; **never 145.800 MHz** (ISS); QO-100's narrow band in USB only, inside its digital (2.7 kHz) or mixed segment — never the emergency or the beacons, and never louder than the beacon (a closed loop on the beacon when SatMe hears its own downlink). 15 min at most; it stops by itself once the picture is sent, or when the page goes silent. *Test sur charge* and *Essai en intérieur* as for DATV (−40 dB, 5 min indoors).
- **Régler la réception** sets the SDR tab's receiver on our own transmission (frequency, NFM or USB / LSB) to watch it come back.

## FT8 / FT4

A tab made to see the band, not only to read it:

- **The slot's ring**: the 15 s as a circle, the countdown, cyan when the slot is even, orange when odd; beside it **the clock** read from the stations' DT.
- **A waterfall that speaks**: 46 s, the slots' edges and times. **Each callsign decoded is written with its flag, at its frequency, in its slot.** A **free spot** to send is found by itself (the quietest place between 500 and 2 500 Hz, away from the stations decoded; on QO-100, only in the digital segments).
- **The radar**: each station a dot at its bearing and distance from your QTH, fading with time, pulsing when just heard. Green for a CQ, ★ for a country new to your log, pink when it calls you.
- **Who to call now**: the stations of the last 5 minutes, ranked by what each would bring (new country, new square, distance, strength, CQ, rarity); a click prepares the contact in the log.
- **Marks** the same everywhere (list, messages, waterfall): calls me, watched, new country, new square, already worked. **Stations guettées**: the callsigns to watch for. One **banner** when the reception goes wrong (no sound, digital silence, nothing decoded for 40 slots).
- **💬 Messages**, as a messenger: the conversations between two stations (flags, the last message, ✓ when the QSO is closed), each one as bubbles, or everything as it comes. Each message says what it is: 📣 call, 📍 square, 📶 report, ✅ report received, 🤝 RR73 / 73.

**The decoder**, measured on a corpus of whole slots (weak stations down to −22 dB, overlaps, Doppler slopes, FT4): the satellite's known **Doppler drift removed** before decoding; each candidate read in its own narrow channel (start to 1/32 symbol, frequency to 1/16 tone); decoded signals **subtracted** and the rest searched again; residual drift searched; **hashed callsigns** resolved (`<CALL>` when known); during a QSO, **decoding by expectation** of the partner's possible answers (marked *ap*). SatMe-PC goes deep when the satellite is low or there is time left in the slot.

**PSK Reporter** (Réglages, off by default): the FT8 / FT4 stations heard sent every 5 minutes with their square, the frequency and the satellite — only when the frequency is sure (rig on CAT).

<p align="center">
<img src="images/pc-ft8.webp" width="49%" alt="FT8 on QO-100: the slot's ring, the waterfall with the callsigns at their frequency, the radar of the stations, who to call now">
<img src="images/pc-ft8-messages.webp" width="49%" alt="FT8, the messages: the conversations between two stations, and the stream as bubbles">
</p>

## Voice to text (Phonie → texte)

What the station hears in SSB or FM — the sound card, the SDR's sound, or a DATV stream's sound — written out over by over: QO-100's SSB is the first use. A window **Phonie → texte** in the **SDR**, **QO-100** and **Carnet** tabs shows each over as a bubble: time, frequency (QO-100's downlink when on QO-100), language, text (paler when the engine is less sure), and its translation when asked. Callsigns (written or spelled: "Sierra Papa five Alfa Bravo Charlie", "Fox quatre Italie Oscar Zoulou", in English, French, German, Spanish, Italian, Polish), squares ("Juliett November one eight") and reports ("five nine", "cinq neuf") are lit; a callsign's chip prepares the contact in the log, with the square and report heard.

- **Off by default.** Nothing listens and nothing starts until the window's box is ticked.
- **The engine** is faster-whisper, in Python, as a separate process on this PC: during listening nothing leaves the PC. **Réglages › Phonie → texte › Installer le moteur** creates a Python environment in SatMe's folder (`stt/`), installs faster-whisper from PyPI (about 150 MB; with an NVIDIA card, about 1.3 GB more for cuBLAS and cuDNN) and downloads the model chosen (large-v3 3.1 GB, turbo 1.6 GB, medium 1.5 GB, small 0.5 GB). Nothing is downloaded without that button. It needs Python 3.9 or newer (Debian/Ubuntu: `python3-venv`; Windows: python.org, "Add python.exe to PATH"). Another interpreter with faster-whisper can be given instead.
- An NVIDIA card is used when it answers, else the processor (choose *small* or *medium* there). On an RTX 3060 with large-v3, 20 s of speech take about 1.3 s.
- **Translation, offline** (*Traduire en*, French by default): each over translated on the PC with open OPUS-MT language pairs (CC BY 4.0), through English when there is no direct pair. **Installer la traduction** fetches the languages ticked (Polish, German, English, Spanish, Italian, Dutch, Portuguese, Russian, Ukrainian, Czech…) into `stt/traduction`.
- The voice is cut between silences (1 to 25 s, the squelch respected); noise, carriers and the sentences an engine invents on noise ("Thank you for watching"…) are dropped. When the engine lags, the oldest over waiting goes, and the window says how late it is.
- Each day's transcript is kept in `phonie/YYYY-MM-DD.jsonl`; ⤓ downloads it as text.

<p align="center"><img src="images/pc-reglages-phonie.webp" width="100%" alt="Réglages, Phonie → texte: the model (large-v3), NVIDIA card or processor, the engine installed, the offline translation into French with ten languages installed"></p>

## The SDR receiver

The **SDR** tab (workspace **Station**): listening (the followed satellite, Doppler corrected, or a frequency), demodulation (NFM, USB, LSB, AM; width, squelch, de-emphasis), the spectrum and waterfall, the vernier and magnifier. On the spectrum a click tunes, the wheel zooms, dragging moves the view within the 176 kHz the receiver sees, the passband's edges can be pulled; on the waterfall the wheel tunes; on the keyboard ← → one step, ↑ ↓ ten steps. **Reception only.**

- **CTCSS**: in NFM the sub-tone heard (the 50 tones from 67.0 to 254.1 Hz) is read under the voice in less than a second and shown beside the level (« CTCSS 67,0 Hz », its deviation on hover). **Sous-ton** (Démodulation card, *aucun* by default): the tone squelch — the sound (speaker, decoders, speech) passes only while that tone is heard. Receive only; the test generator can put one under its FM signal.

### Memories and band plan

**Mémoires** (and **＋** for the frequency listened to): named frequencies with their mode, width, a **tag** and a colour.

- A click on a memory tunes the receiver there: within the view, by the offset; beyond it, the receiver goes there and stops following the satellite.
- On the spectrum, **small flags**: click to tune, right-click to edit. Filter by tag.
- Kept by SatMe-PC (`memoires-sdr.json`); **Exporter** / **Importer** as JSON, without doubles, from one PC to another.
- **Band plan**: a coloured strip at the top of the spectrum — IARU Region 1 for 2 m, 70 cm, 23 cm, 13 cm and 3 cm, and QO-100's. It can be replaced band by band with a `plan-bandes.json` file in SatMe-PC's folder (the default plan downloads from **Affichage** as a model).

### Display (Affichage)

The **Affichage** button sets the spectrum and waterfall, kept in this browser (**défaut** goes back to the defaults):

- **Average** over 2 to 16 frames; **peak hold** (fixed or decaying 1, 3 or 10 dB/s) and **minimum hold**, cleared with ⟲;
- **Levels**: *auto* (the bottom on the noise, the top on the strongest signal), or a reference and a range (30 to 120 dB);
- **Waterfall**: seven palettes, contrast, brightness, black set on the noise, speed;
- **Tuning**: *inertia* wheel (a quick spin starts the tuning, which slows down), steps in SSB / CW (1 to 100 Hz) and FM / AM (100 Hz to 12.5 kHz);
- **Markers**: the band plan, the memory flags.

### Sound cleaning

Under the demodulation (SDR tab and QO-100 cockpit), set for each mode (USB, LSB, NFM, AM) and kept; **everything is off at first**.

- **NB** (noise blanker): very short clicks (switching supplies, the LNB supply) found on the raw I/Q samples and blanked before any filter; threshold and width in *Réglages fins du nettoyage*.
- **Notch auto**: up to three steady carriers whistling in the voice, notched out; in CW a note must hold 2 s before it is notched, so the keying gets through.
- **Notch**: a narrow notch at the audio frequency given; **Shift + click** on the spectrum or the magnifier puts it on a carrier.
- **Noise reduction** light, medium or strong: the hiss drops between words and under the voice (29 ms of delay, paused while the squelch is closed).
- Sound **AGC** in SSB and AM: slow, medium or fast.

### I/Q: back in time, recording, replay

The **I/Q** button keeps the receiver's raw samples (1.0584 MS/s: 127 MB a minute from an RTL dongle, 254 MB from the Pluto, the bladeRF or the generator), as **SigMF** (`.sigmf-data` and `.sigmf-meta`: frequency, date, satellite, mode).

- **Remonter le temps** (off by default): the last 1 to 10 minutes always kept in a fixed-size file on disk; **Garder les dernières minutes** turns them into a recording — a signal noticed too late is not lost.
- **● Enregistrer**: 1 to 60 minutes at most.
- **Replay**: the **Fichier I/Q** *Récepteur* takes the recording as the receiver; spectrum, demodulation and decoders (SSTV, CW, FT8, voice) work on it. Pause, position, speed ×1 / ×2 / ×4, loop, and *tranches FT8* (restart aligned on the 15 s slots). Raw files (`.cu8`, `.cs8`, `.cs16`, `.cf32`) dropped in the folder replay too.
- What is decoded from a recording is marked « relecture » (dated from the recording), never taken for a real reception: no journal, no sheet, no PSK Reporter.

### Test generator

The **Générateur d'essai** *Récepteur*: a fake receiver, with no antenna or hardware — noise (strong, medium, weak) and signals around the frequency listened to, each with its offset and SNR: carrier, keyed CW (text and speed to choose), SSB (synthetic voice), FM tone, an FT8 call in the even slots, SSTV pictures in narrow FM. **Écouter** sets the mode and offset to hear it. Nothing is transmitted; SSTV pictures show without being kept, nothing goes to PSK Reporter.

### Measurements (Mesures)

The **Mesures** button (SDR and QO-100 tabs), reception only:

- **Level over time**: the channel listened to, a **marker** set a few kHz from the listening frequency (it follows the Doppler), the noise floor, or the QO-100 beacon over the noise; over 10 min, 1 h or 24 h, as **CSV** (mean, minimum, maximum, UTC).
- **Sun noise** (Y factor): aim at **cold sky**, *Mesurer*, then at the **Sun**, *Mesurer*; the Sun / cold sky ratio against the expected one, worked out from the dish's diameter and efficiency, T<sub>sys</sub> and the day's solar flux (SFU at 10.7 cm, typed by hand: nothing is fetched from the internet). **Balayer**: a grid around the Sun by the rotator (rotctld or simulated), after confirmation, within its end stops; « Arrêter » stops the rotator.
- **QO-100 beacon · 24 h**: one point a minute while the beacon is followed — its level over the noise and the LNB's error; the drift from night to day.

## SDR hardware

The **SDR** tab's *Récepteur* chooses the receiver; everything works the same with each: Doppler, QO-100 (behind the LNB, IF around 739 MHz, or behind a converter), the cockpit, waterfall and panorama, the signal classifier, the sound for the decoders (FT8, SSTV, APRS, voice, LEILA), METEOR, radiosondes. **Fine tuning** as on the phone: the vernier (N Hz per cm, with momentum), the magnifier around the listened frequency, voice netting in SSB (Réglages › Accord fin). SSB and AM use a hang AGC.

| Hardware | Link | Receives | Transmits |
|---|---|---|---|
| **RTL-SDR** dongle | rtl_tcp, on this PC (started for you) or on the network | about 24 MHz – 1.7 GHz | — |
| **ADALM-PLUTO** | network (USB cable: 192.168.2.1, port 30431; or Wi-Fi / Ethernet) | 70 MHz – 6 GHz | DATV, SSTV |
| **bladeRF 2.0** micro | USB 3, through bladeRF-cli (Linux) | 70 MHz – 6 GHz | DATV, SSTV |
| **HackRF One** | USB, through hackrf_transfer | — | DATV tests only (indoor, dummy load) |
| **MiniTiouner** | USB, through LongMynd (or a SatMe-Node) | DVB-S / S2 | — |

### ADALM-PLUTO

Nothing to install: SatMe talks to its IIO server directly. On its USB cable it answers at **192.168.2.1** (port 30431); on Wi-Fi or Ethernet give its address.

- **The receiver only receives.** It sets the RX oscillator, the sample rate, the analog bandwidth and the gain, and nothing else: it never opens the transmitter, never writes its settings, never touches its USB drive or its `config.txt`. Only the transmitter's code (DATV, SSTV) writes on the transmit side.
- It runs at 2.1168 MS/s, brought down to the dongle's 1.0584 MS/s; its 12 bits are kept to the demodulator. About 8.5 MB/s on the cable.
- **Gain**: *automatique* is the AD936x's slow AGC; otherwise 0 to 71 dB. The status line shows the board and its firmware. Pulled out, or silent for three seconds: the reception stops and says why.

### bladeRF 2.0 (USB)

A **Nuand bladeRF 2.0** (micro xA4…) on a **USB 3** port, driven through the **bladeRF-cli** tool in interactive mode, the samples (SC16 Q11) going through a named pipe.

- **Installing (Linux)**: `sudo apt install bladerf` (bladeRF-cli, libbladeRF and the udev rule). The **FPGA** loads by itself when the board opens, from `~/.config/Nuand/bladeRF/hostedxA4.rbf` (or `/usr/share/Nuand/bladeRF/`): put your model's `.rbf` file there. The package's udev rule gives access to the **plugdev** group: `sudo usermod -aG plugdev $USER`, log in again, unplug and plug back. Check with `bladeRF-cli -p`.
- **Détecter** (SDR tab or Émission card) runs `bladeRF-cli -e info -e version`: read-only, it shows the model, serial number, FPGA and firmware; that serial is then used to open the board. bladeRF-cli's folder is set in the SDR tab (empty: the PATH).
- **Receiving**: as the Pluto, 2.1168 MS/s brought down to 1.0584 MS/s, 12 bits kept; gain *automatique* (the AD9361's AGC) or 0 to 60 dB. Only RX1 commands; silent too long or unplugged, the reception stops and says why.
- **Transmitting**: the Pluto's rules (the same AD9361), QO-100 included. The gain is an attenuation (bladeRF-cli gets 66 + gain), **−50 dB by default**, never above the maximum in Réglages, **held at −40 dB** into a load and indoors. Each setting is checked in bladeRF-cli's answer before `tx start`; once the stream flows the gain is read back. Stop: `tx stop`, pipe closed, `quit`, then the tool ended. A bladeRF receiving does not transmit, and the other way round.
- **Windows**: bladeRF-cli.exe cannot write into a named pipe: the bladeRF works only under Linux for now; the page says so.

### HackRF One (test transmitter)

The DATV Émission card's *Émetteur* also offers a **HackRF One**, to test a receiver — **never to QO-100**: its converter takes 8-bit samples, its harmonics and spurious outputs need a filter, and QO-100 needs a power amplifier. The HackRF sends only in the **indoor test** and the **dummy load** test.

SatMe drives it through `hackrf_transfer`, the samples on its standard input as signed 8-bit I/Q, at the Pluto's sample rates, with the narrowest baseband filter that holds the signal:

`hackrf_transfer [-d SERIAL] -t - -f FREQ_HZ -s RATE -b FILTER_HZ -x VGA -a 0 -p 0`

- **Installation.** Linux: `sudo apt install hackrf` (it brings `hackrf_transfer`, `hackrf_info` and the udev rules: unplug and replug the HackRF afterwards). Windows: the HackRF tools for Windows (`hackrf_transfer.exe`, `hackrf_info.exe`) and the HackRF's WinUSB driver; give their folder in *Outils HackRF* if they are not in the PATH.
- **Detection, read-only.** *Détecter* runs `hackrf_info` only (it lists the boards, sends nothing). Nothing else runs until **ÉMETTRE** is confirmed.
- **Low power only.** The RF amplifier is **always off** (`-a 0` on every command line; a line without it, or with any other option, is refused by the code), the antenna port's supply off (`-p 0`), the TX VGA gain 0 to **20 dB** at most (the HackRF goes to 47).
- **Oscillateur décalé** (on by default): the LO is tuned beside the signal and the baseband shifted back digitally, so the LO leakage falls outside the occupied band; indoors the LO and the image stay in the same amateur band.
- **Stop**: the tool's input closed first, then terminated, then killed. It stops when the tool dies, says the HackRF is missing or busy, stops taking samples for 3 s, at the end of the time allowed, when the page is silent for 15 s, and when SatMe-PC closes.

## Aircraft (ADS-B) and the map

The **Avions** tab receives **ADS-B** on 1090 MHz (2 MS/s), off until **Recevoir** is clicked. Reception only.

- **Receiver**: the SDR tab's dongle (taken only on **Prendre la clé**, the SDR tab then no longer starts on it), a **second dongle** (its own rtl_tcp, `-d` index or serial, on its port), or the **ADALM-PLUTO** (never taken from DATV or transmission). Gain, *réparer 1 bit faux* (single-bit repair).
- **The aircraft list**: ICAO, callsign, altitude, speed, track, position, distance and azimuth, last seen, messages, RSSI.
- **SBS-1 output** (« MSG,… » text lines) on 127.0.0.1:30003, off by default, for other programs on this PC.
- **Aircraft scatter** (a forecast, nothing is transmitted): a partner by callsign or locator, a band (10 GHz, 13 cm, 23 cm, 2 m), a radius, a horizon in minutes, both stations' heights and a minimum altitude. Each aircraft is extended in a straight line: the **window** is the time it spends in the common volume both stations see (radio horizon, 4/3 earth), with its duration, altitude, elevations and Doppler. A message when a window opens.

The **Carte** tab: the shared map, **without internet** (SatMe's own coastlines). Layers: satellites (footprint, ground track), APRS, the followed sonde, aircraft, DATV stations whose locator is known, worked squares, your station, day and night, aircraft scatter, range rings. *Fond OpenStreetMap* (unticked by default): tiles loaded by the browser, nothing goes through SatMe-PC.

## Montage: the station chain

The **Montage** tab (workspace **Station**) draws the station: the antenna on the left, the computer on the right, reception on top, transmission below. It lights up with what SatMe does: **cyan** what receives, **red** what transmits, the flow animated along the cables, the frequencies on them (sky, IF, uplink). It is a drawing: nothing there starts a transmission, opens a port or touches the LNB supply.

- **Composing**: a ready-made *montage*, **Proposer d'après SatMe** (a chain drawn from the settings: rig, rotator, SDR dongle, DATV receiver and transmitter, converters), or **✎ Modifier** (devices and cables, one by one). A click on a device shows its ports and its photo. *Complet* or *Simplifié* (what receives or transmits now).
- **The functions** of SatMe (SDR receiver, DATV, SSTV, FT8, CW, APRS, voice to text, METEOR, sondes, transmissions) to the right of the computer, with the sound hardware: **who holds which device** (« ADALM-PLUTO → DATV réception »), who receives, who transmits, who makes or takes sound. A **conflict** (a function wanting a device or a sound input already taken) shows in red with its reason and the gesture that frees it.
- **Fonction**: one function's chain alone, from the antenna to its screen, each link running, ready, missing or busy, and what is missing. The buttons only do reception (start or stop a receiver, choose a sound source).
- **Profils**: the receive settings of the moment kept under a name (« SSTV par IC-9700 ») — SDR receiver (board, mode, frequency, tracking, sound), the decoders' sound input, SSTV, FT8 / FT4, CW, APRS, voice to text, DATV receiver; never anything of transmission nor the LNB supply. **Aperçu** lists what will change; **Appliquer** does it in a safe order (stops, settings, receivers, sound card, decoders). **Refused during a transmission.** DATV reception is not started from here: the step is recalled.

## Réglages (settings)

By category, a list on the left: **Ma station** (callsign, square, minimum elevation, horizon), **Poste et rotor** (CAT, TH-D72, rotator, converters, Doppler log, ADALM-PLUTO), **Satellites** (orbital elements, AMSAT, QO-100 at start), **Interfaces** (satellite link, rigctld, USB knob), **Services en ligne** (QRZ.com, Wavelog / Cloudlog, hams.at skeds), **Phonie → texte**, **Téléphone**, **Accord fin**, **Émission** (the transmitters' I/Q correction), **Accès à distance** (password, app, screen kept on), **Sauvegarde** (the whole station in one file, restored at the next start).

### The rig and the rotator

The rig's model (IC-9700, IC-9100, IC-910H, TS-2000, two rigs FT-817 / IC-705, TH-D72, or simulated), its port and speed, Connect / Disconnect; the rotator (GS-232 over serial, rotctld over the network, or simulated), Follow the satellite, Park, Stop, its end stops and parking. The Suivi tab keeps only their name and a green tick.

<p align="center"><img src="images/pc-reglages-materiel.webp" width="100%" alt="Settings: the rig (CAT) and the rotator, their ports and connection"></p>

**Doppler / CAT log** (off by default): during a followed pass with the rig connected, every 5 s the time, azimuth, elevation, range, the Doppler predicted on both legs, the frequency the loop wrote and the one it read back — one CSV per pass in `doppler/`, linked from the pass in the Journal. Only what the CAT loop already does is noted.

### Orbital elements

As on the phone: the **SatMe GP server** first (`https://gp.f4ioz.fr` by default), or that server only, and the sources to choose — AMSAT's bulletin, CelesTrak's groups (through the server only: the PC never asks CelesTrak), SatNOGS DB, and **the picture satellites (SSTV, SSDV)**: LAPAN-A2, ASRTU-1, HADES-SA, Geoscan-2/4/5, 239Alferov, UmKA-1, UTMN2, SONATE-2… most of them missing from AMSAT's bulletin, asked one by one by their number.

<p align="center"><img src="images/pc-reglages.webp" width="80%" alt="Settings: orbital elements (server, sources), QRZ.com, the online log with the automatic upload"></p>

### The satellite link (WebSocket)

For other programs on the PC (a logger, a dashboard, a script): **Réglages › Interfaces › Lien satellite**, off by default. Once on, `ws://localhost:8095/` (the port can be changed) pushes JSON text frames; on this PC only, unless SatMe-PC was started with `--reseau`. Nothing a client sends is acted upon (ping is answered, close echoed).

| `type` | When | Fields |
|---|---|---|
| `hello` | on connecting | `app`, `version`, `callsign`, `locator` |
| `satellite` | every second while a satellite is followed | `time`, `name`, `catnum`, `az`, `el`, `rangeKm`, `rangeRateKmS`, `transmitter`, `downlinkHz`, `uplinkHz` (with Doppler), `dopplerDownHz`, `dopplerUpHz` |
| `passAlert` | the followed satellite and the ★ favourites | `kind` (`aosSoon` 5 min before, with `inS`; `aos`; `los`), `name`, `catnum`, `aos`, `los` (ms UTC), `maxEl`, `aosAz`, `losAz` |
| `qsoLogged` | a contact goes into the log | `time`, `call`, `grid`, `satellite`, `catnum`, `mode`, `rstSent`, `rstRcvd`, `downlinkMHz`, `uplinkMHz`, `az`, `el`, `myGrid` |

```
{"type":"satellite","time":1792089000000,"name":"ISS","catnum":25544,"az":212.4,"el":31.7,"rangeKm":812.3,"rangeRateKmS":-4.512,"transmitter":"FM voice repeater (V/U)","downlinkHz":437806580,"uplinkHz":145987810,"dopplerDownHz":6580,"dopplerUpHz":-2190}
```

### The frequency for logging programs (rigctld)

**Réglages › Interfaces › Fréquence pour les carnets**, off by default: a server speaking the rigctld network protocol, port 4532 (it can be changed; not the rotor's), on this PC only unless « Réseau local » is ticked and SatMe-PC was started with `--reseau`. A logging or digital-modes program set to « rigctld » at 127.0.0.1:4532 reads:

- `f` the sky frequency of the receive path in use — the rig when connected by CAT, else the SDR receiver; on QO-100 the nominal downlink (10 489 750 000), or the uplink when « Noter la montée » is ticked;
- `m` the mode and passband, `v` VFOA, `s` / `i` split and the transmit frequency when one goes with it (QO-100's uplink, the rig's TX), `t` whether SatMe transmits now (DATV, SSTV, I/Q calibration);
- `\dump_state`, `\chk_vfo`, `\get_powerstat`, `\dump_caps`, the long forms, and the extended answers (`+`, `;`).

`T` (PTT) and every transmit setting are always refused (`RPRT -11`). `F` and `M` are refused too unless « Autoriser le changement de fréquence » is ticked, and then retune the SDR receiver only — never the rig, nor while QO-100 is on the rig.

### The USB volume knob (VFO)

**Réglages › Interfaces › Molette USB**, off by default, **Linux only**: the USB volume knob tunes, as on the phone. It is read from `/dev/input`; when it cannot be read, the card gives the udev rule (**plugdev** group) to copy into a terminal.

- **Turning**: one step (10 Hz, 100 Hz or 1 kHz). **Pressing**: change the step, next target, or offset to zero. Keys A, B, C of a small keypad choose the target (« Apprendre », then press the key).
- **Accorde**: *Auto* (the SDR tab tunes the SDR receiver, the QO-100 cockpit the narrow band; elsewhere the rig when linked by CAT, else the running SDR), or always the *Poste* (VFO, RX or TX offset), the *SDR*, *QO-100*.
- **Capturer** (option): SatMe alone reads the knob, the desktop volume no longer moves while SatMe-PC runs; never a full keyboard.
- **Reception only**: the knob never keys a transmitter and starts nothing.

### Remote access

**Réglages › Accès à distance**: started with `--reseau`, SatMe-PC asks every other machine for a **password** (a login page); this PC itself gets in without one.

- **At the first start** with `--reseau`, a four-word password is drawn, written in SatMe-PC's log and shown here until replaced (8 characters at least). With no password, other machines get nothing.
- A session lasts **30 days** (a cookie). **Five wrong tries** from one address: a minute's wait, then twice as long after each series (an hour at most).
- The password is kept only **hashed** (PBKDF2) in `acces-distant.properties`, never in the backup; changing it closes the other sessions (**Fermer les autres sessions** too). A transmission started remotely asks the same confirmations as here.
- **Without HTTPS** the password and the page go in clear: a trusted network only (or an encrypted tunnel).
- **Sur cet appareil**: *Garder l'écran allumé* and **Installer l'application** (a window of its own, an icon), for this browser; browsers allow them only on http://localhost or over HTTPS.

### Remote mode

For the page opened from afar (a VPN, another house): the **Distant** button at the top, or **Réglages › Accès à distance › Mode distant**, for this browser only (suggested when the page is opened from another machine). The **Mode distant** badge and its « ? » list what is lightened.

- **🔈 Écouter ici** takes the **low-latency** path: a WebSocket (`/api/son/ws`, behind the same session), 20 ms frames at 8, 12 or 16 kHz (low-passed then resampled), as 16-bit PCM or **µ-law** (half the bandwidth: 64 kbit/s at 8 kHz). The player keeps a 60–120 ms buffer set from the measured jitter (95th percentile + 20 ms), replaces a lost frame by the previous one faded, follows the sound card's drift (±0.5 %), and shows the estimated latency, the jitter, the round trip, the frames concealed. On this PC, about 0.11 s instead of 0.6 s with the HTTP stream, which stays as the fallback (*Voie*: auto, low latency, HTTP stream).
- Over http from another machine, browsers refuse the AudioWorklet: the same buffer then runs in a ScriptProcessor (about 20 ms more). No Opus (it would need a native encoder on the PC).
- **Lightened**: SDR spectrum and waterfall at 5 frames a second (256 points, levels in one byte), the magnifier, the QO-100 panorama, the DATV wide band and level meter less often, DATV video as a thumbnail every 2 s (**▶ Vidéo en direct** on the picture to ask for it), map, planes and ships every 5 to 10 s, the live stream reduced to the shown tab, animations off.
- **Receive only**: the path keeps room for a microphone and a PTT later, but SatMe-PC refuses today any sound going up and any transmit command through it (the connection is closed).

## More of the PC

<p align="center">
<img src="images/pc-sked.webp" width="49%" alt="Sked: the common windows with another station, replayed on the map">
<img src="images/pc-carres.webp" width="49%" alt="Worked grid squares on the map">
</p>
<p align="center">
<img src="images/pc-meteor.webp" width="49%" alt="METEOR-M LRPT picture">
<img src="images/pc-sondes.webp" width="49%" alt="Radiosondes: launches near you, the flight on a map">
</p>

## Hardware safety

- **Nothing opens by itself.** A serial port opens only when it is chosen and "Connect" is clicked. No automatic search.
- **RTS and DTR low** by default. Icom CAT raises them (its USB bridges need it): **pick the right rig's port**. The TS-2000 keeps them low, and a port that echoes our own words (a RigExpert's PTT port) is closed at once.
- On Linux the system raises RTS/DTR for an instant on every port opening, before SatMe can lower them: one more reason to open only the port meant.
- **CAT never transmits** (`TX`, `KY` refused by the TS-2000 driver). APRS through a KISS TNC transmits only when you send a message, your position or a status yourself.
- **The MiniTiouner's LNB supply is off** unless chosen.
- **The ADALM-PLUTO and the bladeRF 2.0 transmit only from the DATV and SSTV tabs**, on a confirmed click, within the rules above; their receivers never write the transmit side. DATV and SSTV never transmit at the same time.
- **The interfaces never transmit**: rigctld refuses PTT, the USB knob tunes reception only, ADS-B, the measurements and the generator only receive, the Montage profiles are not applied during a transmission; the Sun scan moves the rotator only after confirmation.
- **The HackRF One transmits only from the DATV tab**, on a confirmed click, for the indoor test or into a load, its RF amplifier always off and its gain 20 dB at most; never to QO-100.

## Before the first try

- **Linux**: be in the `dialout` group (`sudo usermod -aG dialout $USER`, then log in again).
- **Windows**: install the adapter's driver (CP210x, FTDI, CH340); the rig shows as `COM3`, `COM4`…
- Start with the **simulated rig** and **simulated rotator**, then plug in the real hardware one piece at a time.
