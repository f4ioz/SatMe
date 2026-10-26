# ESP32 polarisation box · firmware

*[Français](README.fr.md)*

The firmware of the box that drives **WiMo** remote polarisation switches (UHF 18082 / 18083, VHF 18080 / 18081) from **SatMe-PC**, over USB. The box puts +12 V on **one single** pin of the WiMo's 6-pin socket (1 right-hand circular, 2 left-hand circular, 5 horizontal, 4 ground); with no voltage the antenna is **vertical**.

## The hardware

ESP32 DevKit (WROOM module) → TBD62083A (or ULN2803A) → a tree of three 12 V relays, KA → KB → KC: the +12 V can only reach one pin, whatever the software does.

| ESP32 pin | Role |
|---|---|
| GPIO23, 22, 21 | outputs KA, KB, KC (UHF) → IN1, IN2, IN3 |
| GPIO19, 18, 17 | outputs KA′, KB′, KC′ (VHF, later) → IN4, IN5, IN6 |
| GPIO32, 33, 25, 26, 27 | selector, circuit B: V, H, D, G, AUTO (grounded in its position, internal pull-ups) |
| GPIO13 | the rig's SEND through 1 kΩ (grounded while transmitting, internal pull-up) |

| Polarisation | WiMo pin at 12 V | KA | KB | KC |
|---|---|---|---|---|
| V (vertical) | none | 0 | – | – |
| H (horizontal) | 5 | 1 | 0 | – |
| D (right-hand circular) | 1 | 1 | 1 | 0 |
| G (left-hand circular) | 2 | 1 | 1 | 1 |

The **rotary selector** (2 circuits × 5 positions) has the last word: on V, H, D or G its circuit A feeds the right pin directly and the tree gets no power; on **AUTO** it powers the tree and SatMe chooses. VHF: a single H/V relay goes on KA′ (at rest = V).

GPIO16 and 17 are used by the PSRAM on a **WROVER** module: use a WROOM DevKit, or move KC′.

## The firmware's rules

- At start-up every relay at rest (no voltage: V).
- Off AUTO (or between two selector positions): every output released, every command refused (`ERR MANUEL`).
- SEND active: no switching at all (`ERR TX`).
- A change goes through "no voltage": KA released first, KB and KC set behind it, KA last.
- Debounce: 50 ms for the selector, 20 ms for SEND.

## The USB dialogue

115200 baud, 8N1, one line per command (`\n` or `\r\n`), upper or lower case.

| Sent | Answer |
|---|---|
| `POL U H` (or `V`, `D`, `G`) | `OK POL U H`, or `ERR MANUEL …`, `ERR TX …`, `ERR SYNTAXE …` |
| `POL V H` | the same for VHF |
| `ETAT?` | `ETAT SEL=AUTO U=D V=V TX=0 FW=1.0` |
| `ID?` | `ID SATME-POL ESP32 FW=1.0` |

`SEL` is `AUTO`, `V`, `H`, `D`, `G` or `?` (between two positions). `U` is the UHF antenna's **real** polarisation: the selector's off AUTO, the relays' in AUTO. `V` is what the VHF relays give. An `ETAT …` line is also sent by itself at start-up and on every change (selector, SEND, polarisation).

To try by hand: `screen /dev/ttyUSB0 115200` (or the Arduino IDE's serial monitor), then `ETAT?`.

## Build and upload

Arduino IDE 2 or `arduino-cli`, with Espressif's **esp32** package, board "ESP32 Dev Module":

```
arduino-cli core install esp32:esp32
arduino-cli compile --fqbn esp32:esp32:esp32 esp32-polarisation
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 esp32-polarisation
```

## In SatMe-PC

**Réglages › Poste et rotor › Polarisation d'antenne**: choose the box's port, **Tester la liaison** (test the link), say what is fitted (UHF, VHF). The Suivi tab's **Polarisation** panel shows the real state and commands V / H / D / G in AUTO. The **virtual ESP32** follows exactly these rules: everything can be tried before soldering.

On Linux the user must be in the `dialout` group. When the port opens, Linux briefly raises RTS/DTR, which may restart the DevKit: it comes back with every relay at rest, and SatMe asks its state again a second and a half later.
