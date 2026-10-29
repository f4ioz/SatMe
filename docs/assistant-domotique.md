# Voice assistant, Home Assistant and remote commands

*[Version française](assistant-domotique.fr.md)*

SatMe-PC answers a few questions and does a few gestures **without any
transmission** through three paths, each an optional module, off by default
(*Settings › Modules*), set up in *Settings › Assistant and home automation*
(« Assistant et domotique »):

| Path | Module | What leaves the PC |
|---|---|---|
| **Voice assistant**: this PC's microphone, "SatMe, next ISS pass" | Assistant vocal | nothing: recognition and voice on this PC |
| **Home Assistant** over MQTT, then Alexa or Google Home through Home Assistant | Home Assistant | the station's state to **your** MQTT broker |
| **Remote commands** through **your site** (an Alexa skill of your own) | Publication web | the state to your site; SatMe-PC fetches the commands |

> ### Never any transmission
> Nothing that transmits can be asked by voice, Home Assistant, Alexa or a
> site: no PTT, no CAT transmit, no DATV, SSTV, FT8/FT4 or ADALM-PLUTO
> transmission, no rotator move (only the auto-pilot, set up on the screen,
> moves it as usual). Such a request gets the answer: "For safety,
> transmitting and moving the antenna can only be done from the station's
> screen." The list of actions is closed (eight, below) and checked by
> SatMe-PC itself, whatever the other side sends.

## What can be asked

Questions (a short answer, said and shown):

| Intent | Examples |
|---|---|
| `prochain_passage` | "next ISS pass", "when is SO-50", "next pass" (your ★ favourites) |
| `visibles` | "which satellites are in view" |
| `prochain_aos` | "next AOS" |
| `autopilote_etat` | "autopilot status" (next campaign, pass under way) |
| `derniere_sstv` | "last SSTV picture" (satellite, time, mode) |
| `datv_etat` | "DATV status" (lock, frequency, MER, callsign read) |
| `balise` | "beacon status" (level above the noise, offset; the wide band beacon through the DATV receiver) |
| `rotor` | "where is the antenna pointing" (azimuth, elevation) |
| `dernier_qso` | "last QSO" |
| `aide` | "help" |

Actions (all receive or sound):

| Intent | Examples |
|---|---|
| `autopilote_on` / `autopilote_off` | "turn on the autopilot", "turn off the autopilot" |
| `sstv_on` / `sstv_off` | "start SSTV reception", "stop SSTV" |
| `datv_on` / `datv_off` | "start DATV" (the DATV tab's last settings), "stop DATV" |
| `son_muet` / `son_remet` | "mute", "unmute" (the receivers' sound on this PC) |

Sentences are understood in French and English, ignoring accents and
punctuation, with numbers in words ("SO fifty", « AO quatre-vingt-onze »)
and a letter wrong here and there. A question ("is DATV stopped?") is never
taken for an order. A module switched off (SSTV, DATV) says so instead of
acting.

## Voice assistant

1. *Settings › Modules*: tick **Assistant vocal**.
2. *Settings › Assistant and home automation › Voice assistant*: choose the
   model (**tiny** 75 MB, **base** 145 MB — the default —, **small** 485 MB)
   then **Install**. SatMe-PC uses *Speech to text*'s Python environment
   (`stt/` in the data folder): it creates it when missing (Python 3.9 or
   newer needed), adds faster-whisper when absent (about 120 MB) and
   downloads the model. That is the only time the assistant goes online.
3. Tick **Listen to the microphone** (or use only the 🎙 button).

**The wake word.** Say "**SatMe**" ("Satmi", "Sat me" work too), then the
question in the same sentence, or right after (8 s). SatMe-PC cuts the
voice by its level (no continuous sound goes to the engine), has each piece
written by Whisper on this PC and keeps only those starting with the wake
word. openWakeWord's ready-made models ("hey jarvis", "alexa"…) are under
CC BY-NC-SA (no commercial use): they are not used.

**The 🎙 button** (bottom right of the page, and on the card) listens to the
next sentence without the wake word, even with "Listen to the microphone"
unticked: the microphone then opens for 8 s only. The card's **Ask** field
tries a sentence typed.

**The answer** is said by *Speech to text › Spoken translation*'s voice
(Piper, else eSpeak NG, else speech-dispatcher) on this PC, the receivers'
sound lowered meanwhile, and shown in a bubble at the bottom right. With no
voice on the PC, the page open on this PC says it with the browser's voice.
The microphone does not listen while SatMe speaks.

**Privacy.** The microphone's sound never leaves the PC and is not
recorded; only the text of the last 30 commands is kept in memory (until
restart). The demonstration never opens the microphone.

**Settings.** The microphone (the system's by default; don't pick the
decoders' sound card), the language (SatMe's, French or English), the
sensitivity (1: only a loud voice; 10: a whisper) and "Say the answer
aloud".

## Home Assistant

SatMe-PC connects to **your MQTT broker** (often Home Assistant's
*Mosquitto broker* add-on) and publishes its entities with **MQTT
discovery**: they appear by themselves in Home Assistant, under a "SatMe"
device. Nothing is opened on the PC.

1. *Settings › Modules*: tick **Home Assistant**.
2. *Settings › Assistant and home automation › Home Assistant*: the
   broker's address, the port (1883, 8883 with TLS), the MQTT user and
   password (kept on this PC, never shown again nor written to a log),
   **Save**, then tick **Publish to Home Assistant**.
3. For the switches, tick **Actions** (unticked by default: state only).

| Entity | Kind | Content |
|---|---|---|
| Prochain passage | sensor | the sentence |
| Prochain AOS | `timestamp` sensor | the next AOS time |
| Satellites visibles | sensor | how many (the list in the state's `visibles_liste`) |
| Auto-pilote SSTV | sensor | its state |
| Dernière image SSTV, its time | sensors | satellite, time, mode |
| DATV verrouillée | binary sensor | ON / OFF |
| DATV MER, DATV station | sensors | dB, callsign read or service |
| Balise QO-100 | sensor | dB above the noise |
| Rotor azimut, élévation | sensors | degrees |
| Dernier QSO | sensor | callsign, satellite, time |
| Réponse de SatMe | sensor | the answer to the last command |
| Auto-pilote SSTV, Réception SSTV, Réception DATV, Son coupé | switches (with **Actions**) | the safe actions |
| Commande SatMe | text (with **Actions**) | a sentence, understood as by voice |

Topics: `satme/etat` (the whole state as JSON, retained, every 30 s and on
each command), `satme/disponible` (`online` / `offline`, MQTT last will),
`satme/commande/<switch>` (`ON` / `OFF`), `satme/commande/texte` (a
sentence), `satme/commande/intention` (an id above). The root `satme` and
the prefix `homeassistant` can be changed on the card. Unticking
**Actions** removes the switches from Home Assistant.

### Alexa and Google Home through Home Assistant

Home Assistant offers its entities to the commercial assistants; SatMe has
nothing more to do:

- **Home Assistant Cloud (Nabu Casa)**: *Settings › Voice assistants ›
  Alexa* (or *Google Assistant*), enable, then **expose** the SatMe switches
  you want. "Alexa, turn on Réception SSTV".
- **Without a subscription**: the *Alexa Smart Home* integration (a skill
  you create yourself in Amazon's developer console plus an AWS Lambda
  function) or the manual *Google Assistant* integration, both described in
  Home Assistant's documentation; expose the same switches there.
- **For questions**: an Alexa *routine* or a Home Assistant *script* that
  reads a sensor ("Prochain passage") and has the speaker say it, or Home
  Assistant's *Assist* with the "Commande SatMe" field.

Only the four safe actions are switches: no entity can transmit.

## Remote commands through your site (Alexa skill)

For an Alexa skill **of your own**, hosted on your site (no Home
Assistant): your site receives Amazon's requests, and SatMe-PC sends it the
state and fetches the commands — the site never connects to the station.

1. Set up and **test** *Settings › Online services › Send to a web site*
   (module *Publication web*), with a web address and a token.
2. *Settings › Assistant and home automation › Remote commands*: tick
   **Questions** (the answers go every minute) and, if wanted, **Actions**
   (the eight safe actions).
3. On the site: receive the types `commandes-etat`, `commandes-attente` and
   `commandes-resultat` (described in
   [the API documentation](publication-web.md#remote-commands-optional)),
   and an HTTPS endpoint for the skill that **verifies Amazon's signature**
   (the `SignatureCertChainUrl` URL, the certificate chain up to a trusted
   root, `echo-api.amazon.com` in the certificate, `Signature-256`, the
   timestamp within 150 s, the skill's id), answers questions from the state
   received and queues actions for SatMe-PC.
