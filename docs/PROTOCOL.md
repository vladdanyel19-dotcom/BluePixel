# PixelBlue BLE protocol (v1)

All multi-byte integers are little-endian.

## Service and characteristics

| Name   | UUID                                   | Properties            |
|--------|----------------------------------------|-----------------------|
| Service| `c0de0001-5ee1-4b1e-9e0d-50a3c1b2e000` |                       |
| CMD    | `c0de0002-5ee1-4b1e-9e0d-50a3c1b2e000` | write, write-no-resp  |
| STATE  | `c0de0003-5ee1-4b1e-9e0d-50a3c1b2e000` | read, notify          |
| PIXELS | `c0de0004-5ee1-4b1e-9e0d-50a3c1b2e000` | write-no-resp, write  |

The device advertises the service UUID, so apps can filter scans by it.

## CMD packets (first byte = opcode)

| Op   | Name       | Payload |
|------|------------|---------|
| 0x01 | POWER      | `on:u8` (0/1) |
| 0x02 | BRIGHTNESS | `b:u8` |
| 0x03 | COLOR      | `r:u8 g:u8 b:u8 w:u8` (primary color) |
| 0x04 | EFFECT     | `id:u8` |
| 0x05 | SPEED      | `s:u8` (0 slow … 255 fast) |
| 0x06 | TIME       | `epoch:u32 tzMinutes:i16` (UTC seconds + local offset) |
| 0x07 | SCHEDULE   | `n:u8` then n × `start:u16 end:u16 days:u8 enabled:u8` (minutes of day; days bit0=Mon … bit6=Sun; max 4) |
| 0x08 | CONFIG     | `type:u8 order:u8 dataPin:u8 clockPin:u8 width:u16 height:u16 layout:u8 maxmA:u16` → saved, device restarts |
| 0x09 | NAME       | UTF-8 name (max 20 bytes) → saved, device restarts |
| 0x0A | CLEAR      | `r:u8 g:u8 b:u8` fills the canvas and switches to the Canvas effect |
| 0x0B | TEXT       | UTF-8 text (max 120 bytes) → switches to the Text effect |
| 0x0C | COLOR2     | `r:u8 g:u8 b:u8` (secondary / background color) |

### LED types (`type`)
0 WS2812/WS2812B/WS2813/WS2815 (800 kHz) · 1 WS2811 (400 kHz) · 2 SK6812 RGBW · 3 APA102 · 4 SK9822

### Color order (`order`)
0 RGB · 1 RBG · 2 GRB · 3 GBR · 4 BRG · 5 BGR

### Layout flags (`layout`)
bit0 serpentine (zig-zag) · bit1 vertical (wired in columns) · bit2 start right · bit3 start bottom

`height = 1` means a plain strip.

## PIXELS packets

`offset:u16` followed by RGB triplets. `offset` is the logical index `x + y*width`.
Writing pixels switches the device to the Canvas effect.

## STATE (read / notify), 64 bytes

| Offset | Field |
|-------:|-------|
| 0  | protocol version (1) |
| 1  | power |
| 2  | brightness |
| 3  | effect |
| 4  | speed |
| 5  | r, g, b, w (4 bytes) |
| 9  | type |
| 10 | order |
| 11 | dataPin |
| 12 | clockPin |
| 13 | width u16 |
| 15 | height u16 |
| 17 | layout |
| 18 | maxmA u16 |
| 20 | timeValid |
| 21 | effect count |
| 22 | fw major, fw minor |
| 24 | schedule count n |
| 25 | n × 6 bytes schedule (start u16, end u16, days, enabled), 4 slots reserved (24 bytes) |
| 49 | color2 r, g, b |
| 52 | chip: 0 ESP32, 1 C3, 2 S3, 3 C6, 255 other |
| 53..63 | reserved |

## Effects

0 Solid · 1 Canvas · 2 Rainbow · 3 Rainbow wave · 4 Breathe · 5 Color wipe · 6 Theater chase ·
7 Twinkle · 8 Fire · 9 Comet · 10 Strobe · 11 Police · 12 Sparkle · 13 Gradient ·
14 Matrix rain · 15 Plasma · 16 Scrolling text
