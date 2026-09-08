# PixelTransfer

Transfer a file between two nearby Android devices with **no Bluetooth, no Wi-Fi, no mobile data,
no NFC and no internet**. The only channel is one phone's screen and the other phone's camera.

The app declares exactly one permission — `CAMERA`. There is no networking permission of any
kind, so the "no internet" property is verifiable from the manifest rather than merely claimed.

---

## How it works

The sender does not produce a single static image. It turns the file into a **continuous animated
stream of colour-cell frames** played back at 5–20 fps (12 by default). Each frame looks like
coloured pixel art or TV static — never like a QR code.

The receiver points its camera at the sending screen and decodes every frame it can resolve,
accumulating recovered data across frames until it has enough to reconstruct the file. Frames that
are missed, torn or misread are simply discarded; the stream keeps running.

**There is no back channel.** The receiver cannot ask for a retransmission, so the stream is
*rateless*: the sender can generate an unbounded number of distinct frames from one file, and the
receiver needs any sufficiently large subset of them, in any order, with any gaps.

### Coding pipeline

Layered, in this order:

| Layer | What it does |
|---|---|
| **Compress** | Deflate, auto-skipped when the content is already entropy-coded (JPEG, MP4, ZIP…) or when measuring shows it does not help. |
| **Metadata block** | Filename, MIME type, original and compressed size, compression type and the SHA-256 of the original, prepended to the payload so it travels *inside* the encoded stream. |
| **Split** | Into `K` fixed-size source blocks (1024 bytes by default). |
| **Fountain-encode** | LT codes over a robust soliton distribution, with a systematic prefix. Produces an unbounded sequence of symbols identified by an Encoded Symbol ID. |
| **Packetize** | One or more symbols per frame, each with its ESI and its own CRC32. |
| **Reed–Solomon** | Applied *within* each frame packet over GF(256), ~20% redundancy by default. |
| **Modulate** | Bytes → palette symbols → rendered colour cells. |

Fountain coding handles **erasures** (whole lost or torn frames). Reed–Solomon handles **errors**
(cells misread inside a frame that did arrive). Both are needed; neither replaces the other.

#### Why LT rather than RaptorQ

RaptorQ is the stronger code, but its value is largely in achieving low overhead with cheap
belief-propagation decoding at very large `K`. Here `K` is at most a couple of thousand blocks, and
measured reception overhead for this implementation is **0.8–1.5%** — already inside the 5% target —
because peeling is backed by Gauss-Jordan elimination over GF(2) on the residual system, guarded by
a cheap rank pre-check so the expensive pass runs at most once. Implementing RaptorQ's LDPC/HDPC
precode would add considerable complexity for a fraction of a percent.

### Colour modulation

Bytes are never mapped one-per-channel onto 8-bit R/G/B. No phone camera recovers 24 bits per pixel
through lens optics, sensor noise, display gamma, auto white balance and 4:2:0 chroma subsampling.
Each channel is quantised to a small number of levels instead, and levels are **Gray-coded** so a
channel misread by one level costs one bit rather than several.

| Mode | Bits/channel | Palette | Bits/cell | Use case |
|---|---|---|---|---|
| Robust | 1 | 8 colours | 3 | Poor lighting, cheap cameras, angled shots |
| Balanced *(default)* | 2 | 64 colours | 6 | Normal indoor use |
| Dense | 4 | 4096 colours | 12 | Two good phones, controlled lighting, short range |
| Grayscale 2 / 4 | 1–2 | 2–4 levels | 1–2 | Monochrome or very low light |

Every logical cell is drawn as an **N×N block of screen pixels** (8×8 by default, 4–16 configurable).
A 1:1 mapping onto physical screen pixels is not recoverable by any phone camera and is not offered.

**Reference geometry:** 96×96 cells at 8×8 px is a 768×768 display area, carrying 4096 bytes of
payload per frame in Balanced mode — about 48 KB/s at 12 fps.

### Frame format

A custom layout. It shares nothing with any QR standard beyond the idea of a concentric finder.

```
+---------------------------------------------------------------+
|  F F F F F F F s   t t t t t t t t t t t t t t   s F F F F F F |  finder blocks + timing
|  F F F F F F F s                                 s F F F F F F |  (row 3 = timing strip)
|  F F F F F F F s   A A A A A A A A A A A A A A   s F F F F F F |  calibration band A
|  s s s s s s s s   . . . . . . . . . . . . . . . . . . . . . . |  separators
|  t   B B B   I I I . . . . . . . . . . . . I I I . . . . N N N |  corner ids, band B, nonce
|  t   B B B   I I I . . . . data cells . . . . . . . . . N N N |
|  F F F F F F F s   . . . . . . . . . . . . . . . s F F F F F F |  bottom finder blocks
+---------------------------------------------------------------+
```

* **Finder / geometry** — four identical 7×7 concentric markers whose centre line reads
  1:1:3:1:1, each inside an 8×8 block whose inward row and column are a light **separator**.
  Without the separator the first timing cell merges with the marker's dark outer ring and destroys
  the run-length signature the detector depends on. Timing strips run along the lines through the
  finder centres, and a light quiet border surrounds everything.
* **Corner id patches** — 3×3 patches beside each finder carrying **1, 3, 5 and 7** light cells.
  Because the four markers are identical, a detected quad admits eight readings (four rotations ×
  a mirror); counting light cells resolves rotation *and* flip unambiguously.
* **Colour calibration** — four ramps (R, G, B, neutral) at five fixed code levels, repeated
  cyclically along a band under the top edge and a band down the left edge. The ramps are
  deliberately **independent of the palette mode**, because the receiver must normalise colour
  before it can read the header that tells it which mode the payload uses.
* **Frame header** — 32 bytes (magic, version, stream id, total size, `K`, block size, palette
  mode, cell size, ECC level, compression, frame sequence, nonce, symbol count, CRC32), protected
  by its own Reed–Solomon code and always modulated in the **Robust** palette.
* **Payload** — fountain symbols, each prefixed with its ESI and followed by its own CRC32, under
  the configured Reed–Solomon code, then an end marker and a whole-region CRC32.
* **Tear stripe** — four columns down the right-hand side carrying bits derived from the frame
  nonce and the row index.

**Cell interleaving.** Payload cells are not laid out in raster order. Damage from glare, a finger
or a tear is spatially contiguous; written in raster order it would land inside one or two
Reed–Solomon blocks and destroy them. Interleaved across the whole frame, the same damage becomes a
few correctable byte errors in every block.

**Tear detection.** The camera shutter is not synchronised to the display refresh, so a capture can
contain the top of frame *n* and the bottom of frame *n+1*. Three defences overlap:

1. Because the header is interleaved across the whole frame, any large tear destroys it and the
   frame is dropped before the payload is even read.
2. A small tear is *repaired* by Reed–Solomon rather than discarded — better than throwing away a
   usable frame.
3. In between, the tear stripe catches it. Detection is a **contiguity test**, not a count: a
   rolling-shutter tear disagrees over a contiguous band of rows, while misread cells disagree at
   random, so the decoder looks for the horizontal split that best separates the two. Uniform noise
   produces no such split and is correctly not called a tear.

Behind all of it, the per-symbol CRC32 is the backstop: in testing, no torn or random capture has
ever produced a corrupted symbol.

### Receiver pipeline

1. Adaptive local-mean binarisation over an integral image.
2. Concentric-marker detection (1:1:3:1:1 with vertical, horizontal and diagonal cross-checks),
   with a finer second pass when the coarse one finds fewer than four.
3. Several ranked quad hypotheses — the payload is a field of arbitrary colours, so accidental
   concentric patterns always exist; a wrong quad is rejected in about a millisecond by the timing
   score, and falling through to the next one is what stops a stray detection costing a frame.
4. Perspective correction by homography from the four finder centres.
5. Grid size, rotation and flip resolved **together**, by scoring every candidate against the
   timing strips and the corner-id patches.
6. Quadratic per-axis lens correction fitted to the observed timing transitions.
7. Per-frame colour normalisation: a 3×4 cross-talk fit, a per-channel response curve, and an
   optional spatial black-level and gain *plane* — kept only when it measurably reduces the
   residual, because the calibration bands cover two edges and an unhelpful plane extrapolates
   badly into the opposite corner.
8. Cell sampling by voting: a 3×3 grid inside the middle half of each cell, per-channel median.
9. Reed–Solomon, then CRC32 per symbol; failures are discarded.
10. Frames whose stream id does not match, or whose tear parity is inconsistent, are rejected.
11. Symbols feed the fountain decoder; on completion the object is decompressed and the SHA-256 is
    verified.

**SHA-256 is the only gate that reports success.** A reported success always means the bytes match
what the sender hashed. A mismatch is reported as a failure and the data is discarded.

---

## Measured performance

From `AcceptanceTest`, which runs complete transfers through a simulated camera modelling
perspective, optical blur, sensor noise, display gamma, camera white balance and channel cross-talk,
vignetting, glare hotspots, PWM banding, rolling-shutter tearing and 4:2:0 chroma subsampling:

| Criterion | Result |
|---|---|
| 256 KB, Balanced, indoor conditions | **7.7 s** of display time at 12 fps (spec: under 45 s) |
| 35° off-axis, Robust mode | Transfers; 13 of 13 captures decoded |
| Receiver starts at an arbitrary point in the loop | Transfers |
| Fountain reception overhead | 0.8–1.5% (spec: ≤ 5%) |
| Erasure tolerance | Completes with 95% of frames dropped |
| Torn captures | 30-position sweep: no corrupted symbol ever accepted |
| Random / unrelated images | Never produce a file |

Single-frame decode envelope, by palette mode, at 1080p capture:

| Mode | Smallest capture | Max blur (σ px) | Max off-axis | Glare |
|---|---|---|---|---|
| Robust | code fills 35% of frame | 3.0 | 40° | heavy |
| Balanced | 60% | 1.5 | 30° | moderate |
| Grayscale 4 | 60% | 1.5 | 40° | moderate |
| Dense | 85%, head-on | 0.8 | — | none |

Dense is exactly as fragile as the specification predicts: at 16 levels per channel, adjacent
levels are 17 code values apart, which does not survive half-resolution chroma. It is offered for
two good phones in controlled light at short range, and refused for video export.

---

## Hardware pitfalls, and what is done about them

| Pitfall | Response |
|---|---|
| **Rolling shutter** | Per-frame nonce stripe plus whole-frame interleaving; see above. |
| **Refresh vs capture rate** | Frame rate capped at 20 fps so a 30 fps camera sees each frame for a full exposure. The display mode is pinned to a fixed refresh rate where the API allows, and the UI warns when it cannot be. |
| **PWM backlight dimming** | Brightness is forced to maximum, which is where PWM banding is weakest. |
| **Display colour management** | The window colour mode is forced to sRGB, and the per-frame calibration patches absorb whatever profile remains. |
| **Auto-exposure hunting** | AE and AWB are locked as soon as a frame decodes — not before, so a bad exposure is never frozen in. |
| **Night light / blue-light filter** | *Cannot* be disabled by a normal app. It is detected where the platform exposes it and the user is told to switch it off. |
| **Moiré and glare** | Cell sampling votes over a sub-grid; glare is reported to the user as live guidance. |

---

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26).

```bash
# Point at your SDK, then:
./gradlew :core:test          # codec and vision tests, no device needed
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug      # warnings are errors
./gradlew :app:assembleDebug
```

The `:core` module is plain Kotlin with no Android dependencies, so the entire coding and vision
stack is testable on the JVM — which is how the numbers above were produced.

## Project layout

```
core/                                  pure-Kotlin codec and vision, JVM-testable
  codec/      Gf256, ReedSolomon, Fountain, Prng, Compression, Metadata, ByteIo
  frame/      Palette, FrameLayout, FrameHeader, FrameCodec, Bits, FrameRasterizer
  vision/     CameraImage, Binarizer, FinderDetector, Homography, GridFitter,
              ColorCalibrator, CellSampler, FrameReader, Guidance
  pipeline/   SenderConfig, StreamEncoder, StreamDecoder
app/                                   Android app (Compose, CameraX)
  sender/     bitmap rendering, frame production, playback, MP4 and image-sequence export
  receiver/   CameraX wiring, YUV conversion, receive loop
  ui/         theme, home screen, shared components
  util/       file IO, display control
```

## Limitations, stated plainly

* **Brotli is not implemented.** Neither the JDK nor Android ships a Brotli encoder, and adding a
  native one would mean an ABI-specific dependency for a few percent on payloads that are usually
  already compressed. Deflate is used, and auto-skipped by measurement when it does not help.
* **Night mode cannot be turned off programmatically.** The app detects and warns instead of
  pretending.
* **Video export is a compromise** and is treated as one: H.264 is pinned to an all-keyframe, very
  high bitrate configuration, and refused outright for Dense. Lossless PNG or WebP sequences are the
  safe export. A **single still PNG** is offered only when the file genuinely fits in one frame in
  Robust mode — symbols below `K` are systematic, so frame 0 alone carries every source block.
* **The performance figures come from a simulated optical channel**, not from two phones on a desk.
  The simulation models the impairments the specification names and is deliberately unkind, but it
  is a model. Real-device numbers will differ.
