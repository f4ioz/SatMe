# Third-party components

*[Version française](THIRD-PARTY.fr.md)*

SatMe is distributed under the **GNU General Public License version 2** (see
`LICENSE`). The project's own code is licensed **GPL-2.0-or-later**: this
changes nothing today, but leaves the door open to version 3 should the
prediction library ever change. The choice isn't entirely a choice: it follows
from the orbital prediction library, described below.

---

## Why the GPL

`predict4java`, which computes the passes, is a Java port of the core of
**PREDICT**, written by John A. Magliacane (KD2BD), itself based on the C
translation by Neoklis Kyriazis (5B4AZ) released under the GNU GPL in 2002.
The underlying SGP4/SDP4 models are by Dr T. S. Kelso, placed in the public
domain.

The artifact used — `com.github.davidmoten:predict4java:1.3.1` — declares
**GPL version 2** in its Maven descriptor. Since the GPL is reciprocal, SatMe
as a combined work is distributed under the same license.

**Something to know if the license were ever to change.** The upstream
repository `uk.me.g4dpz/predict4java` was relicensed under MIT in 2026.
Switching to that artifact would free the license choice. But this code
descends from a GPL lineage, and a downstream relicensing doesn't necessarily
extinguish the rights of earlier authors. Staying GPL is the defensible
position; it is also the tradition of amateur radio software.

---

## Libraries used

| Component | License | Use in SatMe |
|---|---|---|
| `com.github.davidmoten:predict4java` | GPL-2.0 | Pass prediction, SGP4/SDP4, Doppler |
| LAME (`libandroidlame.so`, bundled) | LGPL-2.1 | MP3 encoding of recordings |
| `com.naman14.androidlame` (Java wrapper, in `src/main/java`) | LGPL-2.1 | Java interface to LAME |
| `com.google.zxing:core` | Apache-2.0 | QR code generation |
| `com.squareup.okhttp3:okhttp` | Apache-2.0 | Network requests |
| `com.github.mik3y:usb-serial-for-android` | MIT | USB serial bridges (CAT, rotator) |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | UI and lifecycle |
| `com.google.android.gms:play-services-location` | Google proprietary | GPS position |
| `com.google.android.play:app-update-ktx` | Google proprietary | Update detection |
| JUnit, Robolectric (tests only) | EPL-1.0, Apache-2.0 | Test harness |
| `ft8_lib` by Kārlis Goba (error-correcting code tables, copied) | MIT | FT8 and FT4 decoding |

**On LAME and the LGPL.** The native library is bundled as a separate `.so`,
not merged into the rest of the app. That is what lets a user replace it with
a modified version, as the LGPL requires. This separation must be preserved.

**On the LDPC tables.** The file `domain/LdpcTables.kt` reproduces the (174,
91) error-correcting code tables from `ft8_lib`, under the MIT license. It
carries its own provenance notice and **is not covered by SatMe's copyright**:
the MIT license is GPL-compatible, but its author must remain credited.

**On the Google components.** They are not free software. A distribution that
wanted to do without them — F-Droid, for example — would have to replace
location with the platform provider and remove update detection.

---

## Algorithms and data

**FT8 and FT4** are implemented from the *QEX* article by K9AN, G4WJS and
K1JT, which is in the public domain. **No WSJT-X code is used**: WSJT-X is GPL
and appears neither in the code nor in the app's credits.

**Orbital elements** come from Celestrak and the AMSAT bulletin, under their
respective publishers' terms.

**Satellite status** comes from AMSAT Live OSCAR Status and SatNOGS.

**POTA parks** come from `pota.app` (park list) and `pota-map.fr` (area
boundaries). These are public endpoints, with no key or authentication. SatMe
queries them, doesn't redistribute them, and controls neither their
availability nor their terms of use: anyone reusing this code is bound by
those services' rules.

---

## What publishing the source exposes

Two mechanisms in the app relied on the source being private, and no longer
benefit from it:

The **extension keyring** (`data/Extensions.kt`) holds its keywords in plain
text. Any feature it protects becomes accessible to anyone who reads the file.

The **`estAuteur()`** function checks whether the callsign contains "F4IOZ".
It can be bypassed by changing your callsign.

These are speed bumps, not locks, and they always were. But better to know it
before publishing than to find out after.

---

*Licenses last checked: September 24, 2026.*
