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
| `org.apache.pdfbox:pdfbox` (with `fontbox`, `pdfbox-io`; `commons-logging`) | Apache-2.0 | SatMe-PC only: the SSTV event report drawn as a PDF |
| Display fonts of the SSTV sheet: Orbitron Black, Audiowide, Exo 2 Black Italic, Russo One, Michroma, Bungee, Monoton (`moteur/src/main/resources/fr/f4ioz/satcombo/sstv/polices/`, each with its `*-OFL.txt`) | SIL Open Font License 1.1 | The styled titles and texts of the sheet, drawn on the phone and the PC, served to the sheet page (see below) |
| DejaVu Sans, regular and bold (`pc/src/main/resources/…/polices/`, licence beside them) | Bitstream Vera and Arev licences; DejaVu changes public domain | SatMe-PC only: the PDF report's letters and symbols, embedded in it |
| `com.github.mik3y:usb-serial-for-android` | MIT | USB serial bridges (CAT, rotator) |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | UI and lifecycle |
| AndroidX Media3 (ExoPlayer) | Apache-2.0 | DATV from a SatMe-Node, on the phone |
| Material Symbols (drawings, in SatMe-PC's page) | Apache-2.0 | SatMe-PC's tab icons, the phone's own |
| three.js r128 (`three.min.js`, bundled unmodified in `moteur/src/main/resources/…/demo/`, `LICENSE-three.txt` beside it) | MIT | SatMe-PC only: the 3D globes of the Orbites and Suivi tabs, loaded by the page when a globe is first shown |
| satellite.js 5.0.0 (`satellite.min.js`, bundled unmodified, `LICENSE-satellite.txt` beside it) | MIT | SatMe-PC only: the orbits worked out in the page for the globes, the map and the animated pass |
| `com.google.android.gms:play-services-code-scanner` | Google proprietary | Scanning SatMe-PC's QR code (no camera permission) |
| LongMynd (BATC), run as a separate program, not bundled | GPL-3.0 | Driving the MiniTiouner (DATV), SatMe-PC and SatMe-Node |
| FFmpeg (`ffmpeg`, `ffplay`), separate programs, in the Windows package only | GPL-3.0 | Videos, GIFs, DATV pictures and sound; BtbN/FFmpeg-Builds build, sources named in `outils/SOURCES.txt` |
| rtl-sdr (`rtl_tcp`, `rtl_test`), separate programs, in the Windows package only | GPL-2.0 | RTL-SDR dongle; github.com/rtlsdrblog/rtl-sdr-blog |
| Java (Eclipse Temurin), in the Windows package only | GPL-2.0 with Classpath exception | Runs SatMe-PC |
| `com.google.android.gms:play-services-location` | Google proprietary | GPS position |
| `com.google.android.play:app-update-ktx` | Google proprietary | Update detection |
| JUnit, Robolectric (tests only) | EPL-1.0, Apache-2.0 | Test harness |
| `ft8_lib` by Kārlis Goba (error-correcting code tables, copied) | MIT | FT8 and FT4 decoding |
| Twemoji Country Flags font (`drapeaux.woff2`, from country-flag-emoji-polyfill 0.1.10; flag art by Twemoji, Twitter, Inc. and contributors; `drapeaux-LICENCE.txt` beside it) and its flags drawn as images (`pc/src/main/resources/…/pc/drapeaux/drapeaux.png`, made by `tools/drapeaux/genere-atlas.mjs`, `LICENCE.txt` beside it) | CC BY 4.0 | The countries' flags in the pages; the same flags in SatMe-PC's videos and pictures (journal: FT4/FT8 panel, globe capitals) |

**On LAME and the LGPL.** The native library is bundled as a separate `.so`,
not merged into the rest of the app. That is what lets a user replace it with
a modified version, as the LGPL requires. This separation must be preserved.

**On the LDPC tables.** The file `domain/LdpcTables.kt` reproduces the (174,
91) error-correcting code tables from `ft8_lib`, under the MIT license. It
carries its own provenance notice and **is not covered by SatMe's copyright**:
the MIT license is GPL-compatible, but its author must remain credited.

**On the sheet's fonts.** They are bundled unmodified, each with its OFL
text: Orbitron Black from the League of Moveable Type's repository
(github.com/theleagueof/orbitron, © The Orbitron Project Authors, Matt
McInerney, Reserved Font Name "Orbitron"); Exo 2 Black Italic from
github.com/googlefonts/Exo-2.0 (© The Exo 2 Project Authors, Natanael Gama);
Audiowide (© Astigmatic, Brian J. Bonislawsky, Reserved Font Name
"Audiowide"), Russo One (© Jovanny Lemonad, Reserved Font Name "Russo"),
Michroma (© The Michroma Project Authors, Vernon Adams), Bungee (© The Bungee
Project Authors, David Jonathan Ross) and Monoton (© Vernon Adams, Reserved
Font Name "Monoton") from github.com/google/fonts. The OFL allows them to be
bundled with any software, GPL included, as long as they are not sold alone.

**On the Google components.** They are not free software. A distribution that
wanted to do without them — F-Droid, for example — would have to replace
location with the platform provider and remove update detection.

---

## Algorithms and data

**FT8 and FT4** are implemented from the *QEX* article by K9AN, G4WJS and
K1JT, which is in the public domain. **No WSJT-X code is used**: WSJT-X is GPL
and appears neither in the code nor in the app's credits.

**Orbital elements** come from Celestrak and the AMSAT bulletin, under their
respective publishers' terms. SatMe-PC's demonstration (`--demo`) carries a copy of
the AMSAT bulletin of 25 October 2026 (`pc/src/main/resources/…/demo/elements.json`), so that it works
offline; it is replaced by a fresh one as soon as the network answers.

**Satellite status** comes from AMSAT Live OSCAR Status and SatNOGS.

**The globes' relief** (`moteur/src/main/resources/…/demo/relief.png`, and the summits written in
`GlobeDonnees.kt`) is ETOPO1 (Amante, C. and B. W. Eakins, *ETOPO1 1 Arc-Minute Global Relief Model*,
NOAA Technical Memorandum NESDIS NGDC-24, 2009), taken at a 0.25° step from NOAA's ERDDAP server: NOAA
says it may be used and redistributed for free, without any warranty.

**POTA parks** come from `pota.app` (park list) and `pota-map.fr` (area
boundaries). These are public endpoints, with no key or authentication. SatMe
queries them, doesn't redistribute them, and controls neither their
availability nor their terms of use: anyone reusing this code is bound by
those services' rules.

---

## Pictures

The SSTV sheet offers nine backgrounds made from **NASA** pictures, reduced to
the sheet's size (2400 × 1697, or 1600 × 1132 for the later ones) and recompressed, in
`moteur/src/main/resources/fr/f4ioz/satcombo/sstv/decors/`. NASA pictures are
generally not subject to copyright in the United States (NASA Images and
Media Usage Guidelines, nasa.gov/nasa-brand-center/images-and-media); they
are credited here, and do not imply any endorsement by NASA. Only pictures
released by NASA itself were chosen: none credited to a third party alone.

| File | Title | Source | Credit, licence |
|---|---|---|---|
| `aurore.jpg` | Aurora australis above the Indian Ocean, from the ISS (iss066e146457, 18 Feb 2022) | https://images.nasa.gov/details/iss066e146457 | NASA (ISS crew photograph, JSC) — public domain |
| `limbe.jpg` | The Earth's glow and the aurora australis over the Indian Ocean (iss062e148363, 13 Apr 2020) | https://images.nasa.gov/details/iss062e148363 | NASA (ISS crew photograph, JSC) — public domain |
| `lever.jpg` | An orbital sunrise over the Pacific, north of Auckland (iss071e439624, 6 Aug 2024) | https://images.nasa.gov/details/iss071e439624 | NASA (ISS crew photograph, JSC) — public domain |
| `lagune.jpg` | Hubble reveals heart of Lagoon Nebula (released 22 Sep 2010) | https://images.nasa.gov/details/GSFC_20171208_Archive_e001955 | NASA, ESA (NASA Goddard release) — public domain under NASA's guidelines |
| `iss_espace.jpg` | The ISS backdropped by Earth's horizon and the blackness of space, from Discovery after undocking (s131e011050, 17 Apr 2010) | https://images.nasa.gov/details/s131e011050 | NASA (STS-131 crew photograph, JSC) — public domain |
| `iss_terre.jpg` | Flyaround view of the ISS after undocking, from Atlantis (s132e012208, 23 May 2010) | https://images.nasa.gov/details/s132e012208 | NASA (STS-132 crew photograph, JSC) — public domain |
| `panneaux.jpg` | ISS solar array wing panels, backdropped by space and Earth's horizon (s118e07242, 14 Aug 2007), the frame number at the bottom cut off | https://images.nasa.gov/details/s118e07242 | NASA (STS-118 crew photograph, JSC) — public domain |
| `nuit.jpg` | Aurora borealis and city lights of the Midwestern United States from the ISS (iss029e012564, 29 Sep 2011) | https://images.nasa.gov/details/iss029e012564 | NASA (Expedition 29 crew photograph, JSC) — public domain |
| `papillon.jpg` | Space Butterfly: the W40 nebula in infrared, Spitzer Space Telescope (PIA23121, 27 Mar 2019) | https://images.nasa.gov/details/PIA23121 | NASA/JPL-Caltech — public domain under NASA's guidelines |

The other backgrounds (starry sky, dusk, abyss, slate, nebula, orbit) are drawn by SatMe.

---

## The F4IOZ web site (`:site`)

| Component or data | Licence / terms | Use |
|---|---|---|
| Leaflet 1.9.4 (`site/src/main/resources/fr/f4ioz/site/static/vendor/leaflet/`, unmodified, `LICENSE.txt` beside it; © Volodymyr Agafonkin, CloudMade) | BSD-2-Clause | The maps of the HF pages |
| OpenStreetMap tiles (fetched by the site, kept a month, at most 120 a minute, zoom ≤ 12, the site's User-Agent) | © OpenStreetMap contributors, ODbL; OSMF tile usage policy | Map background, attributed on every map |
| Natural Earth coastlines (`land.json`, the phone's) | Public domain | The beacons' world map |
| PSKReporter reception reports (retrieve.pskreporter.info) | Philip Gladstone's terms: at most one query every five minutes, `appcontact` given | DX cluster, bands map |
| HamQSL solar data (N0NBH, `solarxml.php`) | Free with credit to HamQSL.com; read hourly | Conditions, bands |
| KC2G ionospheric model (prop.kc2g.com) | CC BY 4.0, attributed on the map | MUF and foF2 contours |
| wspr.live (WSPRnet data, ClickHouse) | Free, non-commercial, results free for all; 20 requests/min (the site sends ≤ 10) | WSPR page, bands map |
| Reverse Beacon Network (telnet and daily archives) | Free; telnet login with a callsign | Bands map, WSPR's RBN tab, Ouvertures |

The site's own code (pages, scripts, styles) is GPL-2.0-or-later, as SatMe.

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
