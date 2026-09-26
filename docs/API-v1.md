# SatMe control station API — version 1

*[Version française](API-v1.fr.md)*

SatMe runs a small HTTP server on the phone. The control station web page is
just one client among others: any program that can make an HTTP request can
do the same, on Windows, Linux, macOS or elsewhere.

This document freezes the **version 1** contract.

---

## 1. Principles

**Two doors, two tokens.** `/d/<jeton>/` is the public door: read-only, with a
token meant to be shown as a QR code. `/c/<jetonCommande>/` is the control
station: it writes to the log, so its token never leaves the phone, and a
six-digit code is required on top of it.

**Everything is in clear text, on the local network.** That is proportionate
for a demo hotspot or a home network. For access from the internet, use a
tunnel — WireGuard, for example — and certainly not port forwarding.

**The version only changes if a client breaks.** Adding a route or a field
doesn't change the number: a program written for version 1 keeps working.
Removing a field or changing its meaning does.

---

## 2. Connecting

The operator enables the control station under **Settings → Share and listen →
Share** (French UI: **Réglages → Partage et écoute → Partage**). The app then
shows an address of the form:

```
http://192.168.1.42:8080/c/nkq7wz3d5f/
```

and a six-digit code. This address is the **base** for all the requests that
follow.

### Finding out who you are talking to

```
GET <base>/api
→ {"api":1,"satme":"20.53"}
```

No authentication. Call it first: a client must be able to check the version
before presenting a code.

### Opening a session

```
GET <base>/entrer?code=123456
→ {"ok":true,"cle":"a1b2c3…","api":1}
→ {"ok":false}          (code refusé, réponse retardée d'une seconde)
```

A rejected code gets `{"ok":false}`, with the response delayed by one second.

The returned key is valid for **three hours**. It goes with every subsequent
request as the `cle` parameter. An expired or unknown key gets a **403** with
`{"ok":false,"raison":"session"}` — the client must then ask for the code
again.

If the bridge to the app isn't up yet — which happens during startup — routes
answer `{"ok":false,"raison":"pasPret"}`.

---

## 3. Reading

### Current state

```
GET <base>/etat?cle=…
```

```json
{
  "station": "F4IOZ", "grille": "JN18FS",
  "sat": "ISS",
  "az": 226.4, "el": -59.0,
  "rx": 437801100, "tx": 145989000,
  "antaz": 231.0, "antel": 2.0,
  "aos": 1790254285126, "los": 1790254885126,
  "elmax": 41.0, "now": 1790254200000,
  "ptt": false, "son": true,
  "heure": "09:21:31 UTC",
  "trace": [[118.0, 1.2], [126.0, 8.4]],
  "qso": [{"h": "09:18", "c": "F5RRS", "l": "JN36EB"}],
  "img": 3
}
```

Frequencies are in **hertz**, Doppler-corrected. `aos` and `los` are
milliseconds since 1970: compare them with `now`, the phone's clock, to get
an accurate countdown even if the PC's clock drifts. `antaz` and `antel` are
`null` when no compass is connected. `trace` is the pass track, as
azimuth/elevation pairs.

### Tracked satellites

```
GET <base>/sats?cle=…
→ ["ISS","SO-50","JO-97"]
```

### Callsign suggestions

```
GET <base>/propose?cle=…&q=F5R
→ [{"c":"F5RRS","l":"JN36EB","n":"Jean Dupont","q":4}]
```

Three at most, ranked by contact frequency, recency, and a bonus if the
callsign has already been worked on the current satellite. Nothing is
suggested below two characters. `q` is the number of contacts already made.

### QRZ lookup

```
GET <base>/qrz?cle=…&call=F5RRS
→ {"ok":true,"c":"F5RRS","l":"JN36EB","n":"Jean Dupont",
   "f":"Jean","v":"Quimper","p":"France","e":""}
```

`f` is the first name only, `v` the city, `p` the country, `e` the error, if
any. Requires a QRZ XML subscription configured in the app. **The quota is
the subscription's**: a client that queries on every keystroke will use it
up. SatMe caches responses, but the client should still space out its
requests.

---

## 4. Acting

### Logging a contact

```
GET <base>/qso?cle=…&call=F5RRS&loc=JN36EB&rse=59&rsr=59
→ {"ok":true}
```

`loc`, `rse` and `rsr` are optional; RST defaults to 59. The contact is filed
under the currently tracked satellite: if none is selected, the response is
`{"ok":false}`.

### Pass recording

```
GET <base>/rec?cle=…&on=1      (1 démarre, 0 arrête)
→ {"ok":true}
```

`on=1` starts recording, `on=0` stops it.

### Switching satellite

```
GET <base>/sat?cle=…&nom=SO-50
→ {"ok":true}
→ {"ok":false}    (nom inconnu des satellites suivis)
```

`{"ok":false}` means the name isn't among the tracked satellites. The name
must match one of those returned by `/sats`, case-insensitive.

---

## 5. What the API doesn't do

This is deliberate, and the list matters as much as the rest.

**It doesn't control the radio.** No frequency, no PTT, no mode.
Transmitting puts the operator's license on the line, and it must not be
triggered by an HTTP request on a shared local network.

**It doesn't change the app's settings**, nor the log beyond adding a
contact. Nothing can be deleted over the network.

**It doesn't expose an audio stream on the control station.** Audio goes
through the public page — `/d/<jeton>/son.pcm`, 16-bit mono PCM at
22,050 Hz — and only while a pass is being recorded.

The exposed surface fits in a single file, `demo/PontCommande.kt`: six
actions, readable in one sitting. A surface you can read is a surface you can
defend.

---

## 6. Writing a client

Nothing more than an HTTP client is needed. In Python:

```python
import requests

BASE = "http://192.168.1.42:8080/c/nkq7wz3d5f"

assert requests.get(f"{BASE}/api").json()["api"] == 1
cle = requests.get(f"{BASE}/entrer", params={"code": "123456"}).json()["cle"]

etat = requests.get(f"{BASE}/etat", params={"cle": cle}).json()
print(etat["sat"], etat["az"], etat["el"])

requests.get(f"{BASE}/qso", params={"cle": cle, "call": "F5RRS",
                                    "loc": "JN36EB"})
```

**Three tips learned from writing the web page.**

Polling the state **once per second** is enough: telemetry is only computed at
that rate in the app, and polling faster gets you nothing fresher.

Treat a **403** as a return to the code screen, not as a failure.

Never steal the operator's cursor while they're typing. During a pass, every
move back to the mouse is a lost contact.

---

*Contract version 1, frozen at SatMe 20.53. Compatible changes will be added
without changing this number.*
