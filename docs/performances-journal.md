# Pass journal — what an export costs

*Measured and estimated on the reference phone: **Xiaomi 11T Pro** (8 cores, 8 GB, Android 14), SatMe 20.82, 11 October 2026.*

A journal video is made on the phone: each frame (5 a second) is drawn by the processor, then encoded in H.264 by the phone's video chip; the pass's sound is encoded in AAC. A GIF has 76 frames at 480 × 640, no sound.

## Measured (10-minute pass with sound, accelerated export)

| Export | Making | Processor | Extra memory | Heat | File |
|---|---|---|---|---|---|
| GIF | **2 to 4 s** | ¾ of a core, briefly | ≈ 30 MB | none | ≈ 3 MB |
| Video 480 px | **≈ 37 s** | 1 core, 2 at the end | ≈ 95 MB | none | ≈ 7 MB |
| Video 720 px | **≈ 45 s** | 1 to 2 cores | ≈ 100 MB | +1 °C | ≈ 15 MB |
| Video 720 px, from 20.83 (sound streamed) | **≈ 30 s** | 1 to 2 cores | **≈ 70 MB** | — | ≈ 15 MB |

"Extra memory": above what the app already holds, given back at the end. Of 8 cores, more than half always stay free.

## Estimated (same phone)

Model drawn from the measurements: ≈ 1 s of preparation per minute of pass (the sound), then 34 ms per frame + 35 ms per megapixel. An accelerated video lasts ≈ 1/5 of the pass (depending on activity: contacts, pictures); in real time, as long as the pass.

| Pass | Export | Making | File |
|---|---|---|---|
| 5 min | video 480 / 720 / 1080 px accelerated | 18 s / 22 s / 31 s | 4 / 8 / 19 MB |
| 5 min | video 480 / 720 / 1080 px real time | 1.3 / 1.6 / 2.4 min | 19 / 41 / 97 MB |
| 10 min | video 1080 px accelerated | ≈ 1 min | ≈ 39 MB |
| 10 min | video 480 / 720 / 1080 px real time | 2.6 / 3.2 / 4.7 min | 37 / 82 / 195 MB |
| 15 min | video 480 / 720 / 1080 px accelerated | 55 s / 67 s / 94 s | 11 / 25 / 58 MB |
| 15 min | video 480 / 720 / 1080 px real time | 3.9 / 4.9 / 7.1 min | 56 / 123 / 292 MB |
| any | GIF | a few seconds | 2 to 4 MB |

**Memory**: up to 20.82 the pass's sound was loaded in one block, in several copies: ≈ +10 MB per minute of pass while a video is made (≈ 50 MB for 5 min, ≈ 150 MB for 15 min). **From 20.83** it is decoded into a temporary file and read back in blocks while encoding: memory no longer depends on the pass's length (≈ +70 MB at 720 px, taken by the pictures, not the sound), and making is ≈ ⅓ faster — the times estimated above, made before, are upper bounds. The GIF has no sound: ≈ +30 MB.

## What to take from it

- The **GIF** costs next to nothing.
- The **480 px video** is the best choice to share: half the size of 720 px, barely faster to make (the cost is mostly the number of frames and the sound, not the size).
- The **real-time video** is the heaviest: several minutes and tens of megabytes.
- On an older phone (2 to 3 GB), a long pass as a video could run short of memory because of the sound loaded in one block; from 20.83 the pass's length no longer matters.
