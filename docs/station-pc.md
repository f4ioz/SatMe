# SatMe-PC — the station in a browser

*October 2026. The Android app stays the reference; the PC does what it does, and what is made on the PC comes to the phone too. The PC pages are in French for now.*

SatMe runs on a PC (Linux, Windows, Raspberry Pi) and is driven from a browser. Its core is **the same code as on the phone**: Doppler loop, rotator tracking, pass journal, SSTV, METEOR, APRS, FT8 and the log all come from the shared `:moteur` module.

<p align="center"><img src="images/satme-ensemble-en.svg" width="100%" alt="The three SatMe: the phone, SatMe-PC and SatMe-Node at the mast; the phone joins the PC with a QR code; the same engine everywhere"></p>

<p align="center"><img src="images/pc-station.webp" width="100%" alt="SatMe-PC, Station tab: the next passes, the satellite followed on the sky, the AMSAT box, Doppler-corrected frequencies, the rig and the rotator with their green tick"></p>

## Installing it

- **Linux**: [Installing SatMe-PC](installer-satme-pc.md) — download `satme-pc.tar`, unpack, start, then open **http://localhost:8090/**.
- **Windows**: [Installing SatMe-PC on Windows](installer-satme-pc-windows.md) — all in one: `satme-pc-windows.msi`, double-click, the page opens.
- **The MiniTiouner by the antenna**: [Installing SatMe-Node on a Raspberry Pi](installer-satme-node.md).

| Option | Effect |
|---|---|
| `--port 8090` | the page's port |
| `--reseau` | page open to the other machines of the network (a station at the foot of the mast, driven from the house); without it, this PC only |
| `--dossier DIR` | where settings, log, journal and recordings are kept (`~/.satme`, `%APPDATA%\SatMe` on Windows) |
| `--noeud` | SatMe-Node: the MiniTiouner only, driven over the network |

**The page on the network's other machines** (a tablet, the living-room PC): start `satme --reseau`, then open `http://<the PC's address>:8090/` (`hostname -I` gives it). On Linux with ufw, open the port to the local network once, in a terminal: `sudo ufw allow from 192.168.0.0/24 to any port 8090 proto tcp` (adapt the range to your router). The page has no password: home network only, never opened to the internet.

## The tabs

- **Station**: passes over the next 24 hours (favourites, heard on AMSAT these days, agenda), the satellite followed on the sky or the map, the **AMSAT box** to the right of its name (what stations hear, "Heard" / "Not heard" in one click), Doppler-corrected frequencies with the RX/TX offsets, **● Record the sound**, and **the rig and the rotator by name, a green tick ✓ when connected** — one click leads to their settings.
- **Carnet** (log): a contact is typed with the callsign predicted from your log and QRZ (Tab goes through the predictions, Enter confirms). Next to it, the station live: the satellite, its frequencies, the polar plot, the rotator, whether you are in the passband, the recording — started from here too. The callsign's card: every past contact and its notes, the QRZ.com sheet (photo, distance, map) if you want it. A forgotten contact is added after the pass, at its time.
- **Agenda** (appointments, and the **automatic mode**, below), **Frise** (the passes laid on a timeline), **Carrés** (worked grid squares on a map).
- **Sked**: the windows where you and another station both see a satellite, replayed on the map with both skies, and made into a video.
- **QO-100**: the narrow band transponder's band plan (AMSAT-DL's, in colour on the ruler and atop the waterfall, each segment's rule on hover), the beacon calibration, the station chain (converters), and the transponder seen by the SDR dongle. **Emergency frequency** (10 489.858 – 10 489.865): red banner; SatMe never sends the uplink there to the rig by itself (a memory, a signal clicked, a DATV talkback) — only when tuned by hand. The FT8 free spot is offered only in the digital segments; a mode out of its segment (SSB in the CW segment…) gets a discreet hint.
- **DATV**: QO-100's wide band with a MiniTiouner, see below.
- **SDR**: an RTL-SDR dongle on this PC (rtl_tcp started for you) or on the network, or an **ADALM-PLUTO**, or a **bladeRF 2.0** (below).
- **Sondes** (radiosondes) and **METEOR** (LRPT pictures) from the dongle.
- **Son** (sound card, recording), **SSTV** (live decoding, the pictures in a viewer), **APRS**, **FT8 / FT4**.
- **Planche**: the SSTV sheet, as on the phone — the pictures on a template (a grid, around the title, or an imported ARISS template whose boxes are found), filled in order of reception or by dragging, the texts, then the sheet downloaded full size (PNG).
- **Journal**: the passes replayed with their sound, made into a picture, a video or a GIF.
- **Réglages** (settings): **the rig (CAT) and the rotator** first (model, port, connection, end stops, parking), then the station, orbital elements, QRZ.com, the online log, converters.

<p align="center">
<img src="images/pc-carnet.webp" width="49%" alt="Log tab: a new contact, and beside it the station live — FO-29, its frequencies, the polar plot, the rotator">
<img src="images/pc-carnet-contacts.webp" width="49%" alt="Log tab, the contacts: tick several to delete, the upload state of each">
</p>

## Automatic mode: miss no pass

**Agenda** tab. Choose your satellites (one by one, or every ★ at once) and a minimum elevation; SatMe-PC lists their passes over the next 24 hours. Any of them can be set aside with a tick.

- **5 minutes before AOS**, the pass's satellite becomes the one followed: **the rig (CAT, Doppler) and the rotator follow it** — the rotator goes and waits at the rise azimuth.
- **The sound is recorded** from 5 s before AOS to 30 s after LOS (the Son tab's sound card), and the pass lands in the **journal**, replayable with its sound.
- **Two passes at once**: the first goes on, the other is marked "during …"; setting the first aside keeps the second.
- Changing satellite by hand during a pass is still possible: the automatic mode does not take over again before the next pass.
- The card says what is missing: rig not connected, rotator without "Suivre" (follow), sound card stopped.

The same mode is on the phone (*Settings → Recording / SSTV & NOAA → Automatic mode*), screen off included; its choices and the PC's are set the same way.

<p align="center">
<img src="images/pc-agenda-auto.webp" width="66%" alt="Agenda tab: the automatic mode, the satellites chosen, the 24 h passes to keep or set aside, those lost during another one">
<img src="images/fr-veille.webp" width="24%" alt="The automatic mode on the phone: the satellites chosen, 16 passes armed">
</p>

## Recording the sound

- **● Record the sound** in Station, Carnet and Son: a WAV named after the satellite (`SatMe_RS-44_20261013_203051Z.wav`), as on the phone.
- **Stop at the end of the pass** (option): even started by hand, the recording stops 30 s after the LOS of the pass it caught.
- **Every pass of the satellite followed** (option in the Son tab), or those of the **automatic mode**.
- A recording stopped by hand does not start again by itself before the next pass.

## The log and the online log

- **Delete** a contact from its row (🗑), or tick several and delete them at once.
- **Wavelog's station locations**: "Read the profiles" shows where you are (your square, or both on a grid line) and the profile it goes to — chosen in the list, or **created** when Wavelog has none for it (DXCC, CQ and ITU zones asked). Each contact goes to the profile of where it was made from, as on the phone; a profile chosen by hand for another square is flagged, Wavelog would file the contacts under its square.
- **Each new contact sent to Wavelog / Cloudlog by itself**, after a delay to choose (30 s to 30 min): the time to fix it or hold it back (⏸, countdown in the log). A contact the server refuses is held back with the reason. "Essayer" lists the station profiles the key gives. The phone has the same delay choice.
- **ADIF** export, and the deposit by hand (⬆) as before.

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

<p align="center"><img src="images/pc-journal-carte.webp" width="100%" alt="Journal: RS-44 replayed on the map, the predicted track dashed, the footprint, F5RRO in its place on the track and at its square"></p>

<p align="center">
<img src="images/pc-journal.webp" width="49%" alt="Pass journal: an ISS pass replayed on the map">
<img src="images/pc-journal-cadre.webp" width="49%" alt="Making a 3:4 video: the file's frame over the map, the caption below">
</p>

## Orbital elements

As on the phone: the **SatMe GP server** first (`https://gp.f4ioz.fr` by default), or that server only, and the sources to choose — AMSAT's bulletin, CelesTrak's groups (through the server only: the PC never asks CelesTrak), SatNOGS DB, and **the picture satellites (SSTV, SSDV)**: LAPAN-A2, ASRTU-1, HADES-SA, Geoscan-2/4/5, 239Alferov, UmKA-1, UTMN2, SONATE-2… most of them missing from AMSAT's bulletin, asked one by one by their number.

<p align="center"><img src="images/pc-reglages.webp" width="80%" alt="Settings: orbital elements (server, sources), QRZ.com, the online log with the automatic upload"></p>

## The rig and the rotator

In **Réglages**, first: the rig's model (IC-9700, IC-9100, IC-910H, TS-2000, two rigs FT-817 / IC-705, TH-D72, or simulated), its port and speed, Connect / Disconnect; the rotator (GS-232 over serial, rotctld over the network, or simulated), Follow the satellite, Park, Stop, its end stops and parking. The Station tab keeps only their name and a green tick.

<p align="center"><img src="images/pc-reglages-materiel.webp" width="100%" alt="Settings: the rig (CAT) and the rotator, their ports and connection"></p>

## FT8 / FT4: the band at a glance

A tab made to see the band, not only to read it:

- **The slot's ring**: the 15 s as a circle, the countdown, cyan when the slot is even, orange when odd.
- **A waterfall that speaks**: 46 s, about 5.5 Hz a column, the slots' edges and times. **Each callsign decoded is written with its flag, at its frequency, in its slot.** A **free spot** to send is found by itself (the quietest place between 500 and 2 500 Hz, away from the stations decoded).
- **The radar**: each station a dot at its bearing and distance from your QTH. It fades with time and pulses when just heard; a sweep turns once a slot. Green for a CQ, ★ for a country new to your log, pink when it calls you.
- **Who to call now**: the stations of the last 5 minutes, ranked by what each would bring (new country, new square, distance, strength, CQ, rarity); a click prepares the contact in the log.
- **💬 Messages**, as a messenger: the conversations between two stations (flags, the last message, ✓ when the QSO is closed), each one as bubbles, or everything as it comes. Each message says what it is: 📣 call, 📍 square, 📶 report, ✅ report received, 🤝 RR73 / 73.

<p align="center">
<img src="images/pc-ft8.webp" width="49%" alt="FT8 on QO-100: the slot's ring, the waterfall with the callsigns at their frequency, the radar of the stations, who to call now">
<img src="images/pc-ft8-messages.webp" width="49%" alt="FT8, the messages: the conversations between two stations, and the stream as bubbles">
</p>

## The ADALM-PLUTO as a receiver

In the **SDR** tab, *Récepteur* chooses between the RTL-SDR dongle (rtl_tcp, the default) and an **ADALM-PLUTO** reached on the network: on its USB cable it answers at 192.168.2.1 (port 30431), on Wi-Fi or Ethernet give its address. Nothing to install: SatMe talks to its IIO server directly.

- **Receive only.** The receiver sets the receive side and nothing else: the RX oscillator, the sample rate, the analog bandwidth, the gain. It never opens the transmitter, never writes its settings, never touches its USB drive or its `config.txt`. Transmitting is the DATV tab's alone (below).
- Everything works as with the dongle: Doppler, QO-100 (behind the LNB, IF around 739 MHz, or behind a converter, set as for the dongle), the waterfall and panorama, the sound for the decoders, METEOR, radiosondes. From 70 MHz to 6 GHz.
- It runs at 2.1168 MS/s, brought down here to the dongle's 1.0584 MS/s; its 12 bits are kept to the demodulator. About 8.5 MB/s on the cable.
- **Gain**: *automatique* is the AD936x's slow AGC; otherwise 0 to 71 dB. The status line shows the board and its firmware.
- Pulled out, or silent for three seconds: the reception stops and says why.

## The bladeRF 2.0 (USB)

A **Nuand bladeRF 2.0** (micro xA4…) on a **USB 3** port is a third receiver (**SDR** tab, *Récepteur* « bladeRF 2.0 (USB) ») and a third DATV transmitter (**Émission** card, *Émetteur* « bladeRF 2.0 »). SatMe drives it through the **bladeRF-cli** tool in interactive mode, the samples (SC16 Q11) going through a named pipe; nothing else to install on the Java side.

- **Installing (Linux)**: `sudo apt install bladerf` (bladeRF-cli, libbladeRF and the udev rule). The **FPGA** loads by itself when the board opens, from `~/.config/Nuand/bladeRF/hostedxA4.rbf` (or `/usr/share/Nuand/bladeRF/`): put your model's `.rbf` file there. The package's udev rule gives access to the **plugdev** group: `sudo usermod -aG plugdev $USER`, log in again, unplug and plug back. Check with `bladeRF-cli -p`.
- **Détecter** (SDR tab or Émission card) runs `bladeRF-cli -e info -e version`: read-only, it shows the model, serial number, FPGA and firmware. The serial found is then used to open that board.
- **Receiving**: as the Pluto, 2.1168 MS/s brought down to the dongle's 1.0584 MS/s, 12 bits kept; 70 MHz to 6 GHz; gain *automatique* (the AD9361's AGC) or 0 to 60 dB. Everything works as with the dongle: QO-100 behind the LNB or a converter, cockpit, panorama, signal classifier, FT8, SSTV, voice, LEILA, METEOR, radiosondes. SatMe only gives bladeRF-cli RX1 commands; silent too long or unplugged, the reception stops and says why. bladeRF-cli's folder is set in the SDR tab (empty: the PATH).
- **Transmitting**: the Pluto's rules (the same AD9361, a 12-bit converter), QO-100 included. The gain is an **attenuation** like the Pluto's (0 dB = full power; bladeRF-cli gets 66 + gain), **−50 dB by default**, never above the maximum in Réglages, **held at −40 dB** into a load and for the indoor test. Each setting (gain, frequency, rate, band, stream) is checked in bladeRF-cli's answer before `tx start`; once the stream flows the gain is read back (never more than asked). Stop: `tx stop`, pipe closed, `quit`, then the tool ended. A bladeRF receiving does not transmit, and the other way round.
- **Windows**: bladeRF-cli.exe (Nuand's Windows tools) cannot write into a named pipe: the bladeRF works only under Linux for now; the page says so.

## DATV — QO-100's wide band with a MiniTiouner

The **DATV** tab receives DVB-S/S2 with a **MiniTiouner** (tuner and demodulator on USB), driven by [LongMynd](https://github.com/BritishAmateurTelevisionClub/longmynd), BATC's free program. SatMe runs it apart, as it runs rtl_tcp: build it, then give its path on the page (or put it in the `PATH`).

- The beacon (10491.5 MHz, 1.5 MS/s), the narrow channels, 125 to 2000 kS/s; the LNB's oscillator (9750 MHz by default) and "Caler sur le signal" to correct it on the beacon.
- Lock (DVB-S / DVB-S2), MER, MODCOD, carrier and offset, service and provider, video and audio streams, the constellation live.
- The picture in the page, the sound on the PC, the same stream in **VLC**, and **recording** to a `.ts` file.
- The MiniTiouner's **LNB supply stays off** unless chosen (13 V or 18 V), with a warning.
- Linux: a udev rule lets LongMynd open the MiniTiouner. LongMynd's own file misses the Pro TS2 (its USB name is `MiniTiouner_Pro_TS2`); this one covers them all, then unplug and plug it in again:

  ```
  echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | sudo tee /etc/udev/rules.d/60-satme-minitiouner.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger
  ```
- Plugged in, the MiniTiouner also shows as two serial ports (`/dev/ttyUSB…`): never choose them as the rig's or the rotator's port.

<p align="center"><img src="images/pc-datv.webp" width="100%" alt="DATV tab: a DVB-S2 test signal locked, MER, MODCOD, the constellation"></p>

### SatMe-Node: the MiniTiouner next to the antenna

The MiniTiouner works best close to the LNB. A **SatMe-Node** is SatMe on a small computer there — a Raspberry Pi (a **Pi 3 is enough**: the node only receives, it decodes nothing) — driven over the network by SatMe-PC and by the phone (**QO-100** screen → **DATV**: the same controls, the picture through the phone's own decoder). It runs LongMynd; its status and the transport stream (0.3 to 2 Mbit/s) come to the PC, which shows, plays and records them as if the MiniTiouner were plugged into it.

Installing it on the Pi: [Installing SatMe-Node on a Raspberry Pi](installer-satme-node.md) — three commands, about ten minutes on a Pi 3.

The script installs Java, builds LongMynd, lets it open the MiniTiouner (udev), and starts the **satme-noeud** service (`satme --noeud`, port 8090). At its first start the node draws a **six-digit code**, shown at the end of the installation (again: `journalctl -u satme-noeud | grep Code`). In SatMe-PC, DATV tab, MiniTiouner **Changer**: the node's address and its code, **Associer**. The PC keeps the key the node gave: either can restart. An empty address goes back to the MiniTiouner on the PC.

Without its key the node only says what it is; what changes it is a POST; its LNB supply stays off unless chosen.

*Above, a test signal. The MiniTiouner Pro TS2 itself is recognised and searches (tuner, demodulator, LNAs), on the PC and through a node; the first signal on the air is still to come.*

## DATV transmission — the ADALM-PLUTO to QO-100

The DATV tab's **Émission** card sends a DVB-S2 signal to QO-100's wide band transponder with an **ADALM-PLUTO**. SatMe makes the DVB-S2 itself (EN 302 307-1: QPSK at every rate, 8PSK 3/5 to 9/10, normal or short frames, pilots, roll-off 0.35 / 0.25 / 0.20); **ffmpeg** makes the picture into a transport stream at exactly the rate the MODCOD carries (`-muxrate`, null packets filling it), the service's name and provider being the station's callsign. The picture: SatMe's test card with the callsign, a still image, or a camera (`/dev/video…`, listed only until a transmission starts).

**The station.** PC —USB— Pluto **TX** port → band-pass filter → power amplifier → feed (2.4 GHz uplink). Reception check: dish, LNB → **MiniTiouner** (DATV tab, *Réception*). While transmitting, the Pluto does not receive (the SDR tab refuses it; they share the sample rate).

| kS/s | samples per symbol | Pluto rate |
|---|---|---|
| 125 | 17 | 2.125 MS/s |
| 250 | 9 | 2.25 MS/s |
| 333 | 7 | 2.331 MS/s |
| 500 | 5 | 2.5 MS/s |
| 1000 | 3 | 3 MS/s |
| 1500 / 2000 | 2 | 3 / 4 MS/s |

**First test: into a load, never into the antenna.** Pluto TX → 30 dB attenuator (or more) → MiniTiouner input (its tuner covers 2.4 GHz directly: *Ciel* = the uplink frequency, LNB oscillator 0, LNB supply **off**). Tick *Test sur charge* (any frequency, gain held at −40 dB or below, a red warning), choose 333 kS/s QPSK 2/3, **ÉMETTRE**, confirm: the MiniTiouner should lock on DVB-S2 with the callsign as service and the test card as picture. Only then the amplifier and the dish.

**The rules, built in.**
- Only on the operator's action: **ÉMETTRE** then a confirmation; never at startup, never taken back from the settings (only the parameters are kept).
- A **callsign** (Réglages › Station) before anything is sent.
- On the air, the whole occupied band (Rs × (1 + α)) between **2403.000 and 2410.000 MHz** uplink: clear of the beacon (uplink 2402.0 MHz, its band ending near 2402.9 MHz), up to the band plan's last channel (10499.25 MHz down, 2409.75 MHz up). The channel list is the band plan's; the one the MiniTiouner holds is marked busy.
- **Duration** 10 min by default, 30 min at most, then it stops by itself.
- It stops by itself when the Pluto stops taking samples or is pulled out, when the samples come late too often, when ffmpeg stops, when the page has been silent for 15 s (closed tab, lost network), and when SatMe-PC closes.
- At every stop the Pluto's **TX oscillator is powered down first**; at the start it stays down until the first samples are in. Should the Pluto be unreachable at that moment, the page says so: unplug it.
- **TX gain**: −30 dB by default, never above the maximum set in Réglages (−10 dB by default).
- **Closed loop**: the MiniTiouner's MER on our own signal against the beacon's (measured with *Balise* beforehand): if we are not at least 1 dB under the beacon, a warning, and the gain is stepped down 1 dB every 5 s until we are. It never steps it up.

**Réglages › Poste et rotor › ADALM-PLUTO**: the maximum TX gain, and *Couper l'oscillateur d'émission du Pluto quand SatMe s'en sert en réception* (off by default). A Pluto switched on leaves its TX oscillator running (2450 MHz by default): ticked, SatMe powers it down each time it starts receiving with the Pluto. Only the transmitter's code writes anything on the Pluto's transmit side; the receiver still cannot.

### Station card and chat in the stream

While transmitting, SatMe slips a **station card** into the transport stream, and the **chat** typed in the Émission card (a line of 140 characters at most, one every 2 s; typing never transmits: the chat only rides on a transmission already started). They take the place of null packets: the rate is unchanged, so are the picture and sound, and a receiver that does not know them ignores them. On reception the DATV tab shows the received station's card (flag, locator, distance and bearing, message, software; marked when it is already in the log), its chat lines as bubbles, and **Noter le QSO** (QO-100, or *DATV terrestre* outside the wide band; mode DATV; the measured MER as the report). Receiving one's own signal ticks one's lines *✓✓ reçu*. The card is switched off under *Carte de visite dans le flux*.

**Talkback on the narrow band.** The Émission card also announces where the station listens for answers on the narrow band transponder (*Retour NB*, MHz, SSB or CW): by default the QO-100 cockpit's frequency when the rig is there, empty otherwise. On reception the station's card shows « Retour en BLU : 10 489,xxx » and **Écouter le retour**: the QO-100 cockpit opens, the narrow band receiver tunes there and *Phonie → texte* is switched on when installed. A talkback announced on the emergency frequency is flagged, never tuned by itself.

**The format**, for anyone who wants to read or send it:

- **PID 0x1F00**, MPEG private sections (ISO/IEC 13818-1, long form: `section_syntax_indicator` 1, `private_indicator` 1, MPEG-2 CRC-32 at the end); one section per group of packets, the first with `payload_unit_start` and `pointer_field` 0, the last padded with 0xFF. The PMT is left unchanged.
- **table_id 0xE5: card** (`table_id_extension` 0, `version_number` changes with the content), repeated every 2 s. **table_id 0xE6: chat line** (`table_id_extension` = 16-bit sequence number), sent 5 times 1 s apart; the receiver drops duplicates by callsign and sequence.
- Payload: `SATM`, a format byte (1), then TLV fields (1-byte tag, 1-byte length, UTF-8 value of 255 bytes at most). Tags: 0x01 callsign (required), 0x02 locator, 0x03 name, 0x04 QTH, 0x05 power, 0x06 antenna, 0x07 software, 0x08 transmit frequency (kHz, in digits), 0x09 MODCOD, 0x0A symbol rate (kS/s), 0x0B message, 0x0C talkback frequency on QO-100's narrow band transponder (downlink in Hz, in digits), 0x0D its mode (`USB` or `CW`), 0x20 chat text. An unknown tag is skipped; a format byte ≥ 0x80 announces an incompatible layout: the section is ignored.

## More of the PC

<p align="center">
<img src="images/pc-sked.webp" width="49%" alt="Sked: the common windows with another station, replayed on the map">
<img src="images/pc-qo100.webp" width="49%" alt="QO-100: the band plan, the beacon calibration, the station chain">
</p>
<p align="center">
<img src="images/pc-carres.webp" width="49%" alt="Worked grid squares on the map">
<img src="images/pc-meteor.webp" width="49%" alt="METEOR-M LRPT picture">
</p>
<p align="center">
<img src="images/pc-sondes.webp" width="49%" alt="Radiosondes: launches near you, the flight on a map">
<img src="images/pc-sstv.webp" width="49%" alt="SSTV: a picture opened in the viewer">
</p>

## Voice to text (Phonie → texte)

What the station hears in SSB or FM — the sound card, or the SDR dongle's sound — written out over by over: QO-100's SSB is the first use. A window **Phonie → texte** in the **SDR**, **QO-100** and **Carnet** tabs shows each over as a bubble: time, frequency (QO-100's downlink when on QO-100), language, text (paler when the engine is less sure), and an English translation under it when asked. Callsigns (written or spelled: "Sierra Papa five Alfa Bravo Charlie", "Fox quatre Italie Oscar Zoulou", in English, French, German, Spanish, Italian, Polish), squares ("Juliett November one eight") and reports ("five nine", "cinq neuf") are lit; a callsign's chip prepares the contact in the log, with the square and report heard.

- **Off by default.** Nothing listens and nothing starts until the window's box is ticked.
- **The engine** is faster-whisper, in Python, as a separate process on this PC: during listening nothing leaves the PC. **Réglages › Phonie → texte › Installer le moteur** creates a Python environment in SatMe's folder (`stt/`), installs faster-whisper from PyPI (about 150 MB; with an NVIDIA card, about 1.3 GB more for cuBLAS and cuDNN) and downloads the model chosen (large-v3 3.1 GB, turbo 1.6 GB, medium 1.5 GB, small 0.5 GB). Nothing is downloaded without that button. It needs Python 3.9 or newer (Debian/Ubuntu: `python3-venv`; Windows: python.org, "Add python.exe to PATH"). Another interpreter with faster-whisper can be given instead.
- An NVIDIA card is used when it answers, else the processor (choose *small* or *medium* there). On an RTX 3060 with large-v3, 20 s of speech take about 1.3 s.
- The voice is cut between silences (1 to 25 s, the dongle's squelch respected); noise, carriers and the sentences an engine invents on noise ("Thank you for watching"…) are dropped. When the engine lags, the oldest over waiting goes, and the window says how late it is.
- Each day's transcript is kept in `phonie/YYYY-MM-DD.jsonl`; ⤓ downloads it as text.

## The satellite link (WebSocket)

For other programs on the PC (a logger, a dashboard, a script): **Réglages › Satellites › Lien satellite**, off by default. Once on, `ws://localhost:8095/` (the port can be changed) pushes JSON text frames; on this PC only, unless SatMe-PC was started with `--reseau`. Nothing a client sends is acted upon (ping is answered, close echoed).

| `type` | When | Fields |
|---|---|---|
| `hello` | on connecting | `app`, `version`, `callsign`, `locator` |
| `satellite` | every second while a satellite is followed | `time`, `name`, `catnum`, `az`, `el`, `rangeKm`, `rangeRateKmS`, `transmitter`, `downlinkHz`, `uplinkHz` (with Doppler), `dopplerDownHz`, `dopplerUpHz` |
| `passAlert` | the followed satellite and the ★ favourites | `kind` (`aosSoon` 5 min before, with `inS`; `aos`; `los`), `name`, `catnum`, `aos`, `los` (ms UTC), `maxEl`, `aosAz`, `losAz` |
| `qsoLogged` | a contact goes into the log | `time`, `call`, `grid`, `satellite`, `catnum`, `mode`, `rstSent`, `rstRcvd`, `downlinkMHz`, `uplinkMHz`, `az`, `el`, `myGrid` |

```
{"type":"satellite","time":1792089000000,"name":"ISS","catnum":25544,"az":212.4,"el":31.7,"rangeKm":812.3,"rangeRateKmS":-4.512,"transmitter":"FM voice repeater (V/U)","downlinkHz":437806580,"uplinkHz":145987810,"dopplerDownHz":6580,"dopplerUpHz":-2190}
```

### The HackRF One and the indoor test

The **Émission** card's *Émetteur* also offers a **HackRF One**, to test the MiniTiouner receiver — never to QO-100: its converter takes 8-bit samples, its harmonics and spurious outputs need a filter, and QO-100 needs a power amplifier; QO-100 stays the Pluto's. The HackRF sends only in the **indoor test** and the **dummy load** test.

SatMe drives it through the `hackrf_transfer` tool, the samples on its standard input as signed 8-bit I/Q (the modulator's 16-bit samples, top byte rounded, about 10 dB under full scale), at the same sample rates as the Pluto (2.125 to 4 MS/s), with the narrowest baseband filter that holds the signal:

`hackrf_transfer [-d SERIAL] -t - -f FREQ_HZ -s RATE -b FILTER_HZ -x VGA -a 0 -p 0`

- **Installation.** Linux: `sudo apt install hackrf` (it brings `hackrf_transfer`, `hackrf_info` and the udev rules: unplug and replug the HackRF afterwards). Windows: the HackRF tools for Windows (`hackrf_transfer.exe`, `hackrf_info.exe`) and the HackRF's WinUSB driver; give their folder in *Outils HackRF* if they are not in the PATH.
- **Detection, read-only.** *Détecter* runs `hackrf_info` only (it lists the boards, sends nothing): « HackRF One · serial number · firmware ». Nothing else runs until **ÉMETTRE** is confirmed.
- **Low power only.** The RF amplifier is **always off** (`-a 0` on every command line; a line without it, or with any other option, is refused by the code), the antenna port's supply off (`-p 0`), the TX VGA gain 0 to **10 dB** at most (the HackRF goes to 47). The gain is taken at the start.
- **Stop**: the tool's input closed first (it ends at the end of its input), then terminated, then killed. It stops when the tool dies, says the HackRF is missing or busy, stops taking samples for 3 s, at the end of the time allowed, when the page is silent for 15 s, and when SatMe-PC closes (no `hackrf_transfer` left running).

**The indoor test** (*Essai en intérieur*, Pluto or HackRF): two small antennas a few metres apart, indoors, to see the whole chain work before any dish.
- A real radio emission: for a **licensed radio amateur**, under the callsign (service name and test card), with an orange warning in the card.
- Only in amateur bands the MiniTiouner covers, the **whole occupied band inside**: 23 cm **1240–1300 MHz** or 13 cm **2300–2450 MHz**. 1255 MHz by default; 1260–1300 MHz is shared with radionavigation satellites and 2400–2450 MHz with Wi-Fi (the card says so).
- **Minimum power**: the Pluto at −40 dB at most; the HackRF with its amplifier off and VGA ≤ 10 dB.
- **5 min** by default, **10 min** at most, then it stops by itself. The QO-100 closed loop is not used.

**The receiving side.** The card gives the MiniTiouner's settings for the test: *Ciel* = the transmit frequency, LNB oscillator **0**, the same symbol rate, LNB supply **off** — *Régler la réception* fills them in (without starting). With an antenna on the MiniTiouner's input the LNB supply **must be off**, or its 13/18 V go into the antenna.

**First indoor test, step by step.** Callsign set (Réglages › Station). HackRF (or Pluto) TX → small 23 cm antenna; MiniTiouner input → another small antenna, 2–3 m away. Card: *Émetteur* HackRF One, *Détecter*; tick *Essai en intérieur*, 1255 MHz, 333 kS/s, QPSK 2/3, test card, VGA 0 dB (Pluto: −40 dB or lower), 5 min. *Régler la réception*, then *Recevoir* in the Réception card (LNB supply off). **ÉMETTRE**, confirm: the MiniTiouner should lock on DVB-S2, the callsign as service. No lock: raise the VGA a few dB (10 at most), or bring the antennas closer.

## Hardware safety

- **Nothing opens by itself.** A serial port opens only when it is chosen and "Connect" is clicked. No automatic search.
- **RTS and DTR low** by default. Icom CAT raises them (its USB bridges need it): **pick the right rig's port**. The TS-2000 keeps them low, and a port that echoes our own words (a RigExpert's PTT port) is closed at once.
- On Linux the system raises RTS/DTR for an instant on every port opening, before SatMe can lower them: one more reason to open only the port meant.
- **CAT never transmits** (`TX`, `KY` refused by the TS-2000 driver). Only APRS through a KISS TNC transmits, and only when you send a message, your position or a status yourself.
- **The MiniTiouner's LNB supply is off** unless chosen.
- **The ADALM-PLUTO transmits only from the DATV tab**, on a confirmed click, within the rules above; the receiver never writes its transmit side.
- **The HackRF One transmits only from the DATV tab**, on a confirmed click, for the indoor test or into a load, its RF amplifier always off and its gain 10 dB at most; never to QO-100.
- **The bladeRF 2.0 transmits only from the DATV tab**, on a confirmed click, within the Pluto's rules (−40 dB at most into a load and indoors); its receiver never sends a transmit command.

## Before the first try

- **Linux**: be in the `dialout` group (`sudo usermod -aG dialout $USER`, then log in again).
- **Windows**: install the adapter's driver (CP210x, FTDI, CH340); the rig shows as `COM3`, `COM4`…
- Start with the **simulated rig** and **simulated rotator**, then plug in the real hardware one piece at a time.
