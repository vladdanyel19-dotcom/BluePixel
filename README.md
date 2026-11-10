# PixelBlue

Bluetooth LED controller for addressable strips and matrices, built on any ESP32 with Bluetooth,
with a native Android app. No Wi-Fi, no router, no web pages: open the app, tap the controller, done.

*Controler LED pe Bluetooth pentru benzi și matrice adresabile, pe orice ESP32 cu Bluetooth, cu aplicație Android nativă. Fără Wi-Fi.*

## Features

- **Fast Bluetooth LE connection** with auto-reconnect to the last controller
- **Every pixel**: draw on the matrix from the phone, changes show instantly
- **16 effects**: rainbow, fire, comet, twinkle, matrix rain, plasma, police, gradient…
- **Scrolling text** with a built-in 5×7 font
- **Schedule kept by the controller**: up to 4 on/off windows per day of the week.
  The light turns on and off by itself, even when your phone is far away.
- **Remembers everything** after a power cut (color, effect, brightness, schedule)
- **Current limiter** so a small power supply is never overloaded
- App in English and Romanian

## Supported hardware

| | |
|---|---|
| Boards | ESP32, ESP32-C3, ESP32-S3 (ESP32-S2 has no Bluetooth) |
| LED chips | WS2812B, WS2813, WS2815, WS2811, SK6812 RGBW, APA102, SK9822 |
| Color order | RGB, RBG, GRB, GBR, BRG, BGR |
| Layouts | strip or matrix up to 2048 LEDs; zig-zag or parallel rows, rows or columns, any starting corner |

Chip type, pins, size and layout are all set **from the app** — one firmware for everyone.

## Wiring

```
5V power supply (+) ──┬── LED strip 5V
                      └── ESP32 5V / VIN  (or power the ESP32 from USB)
GND ──────────────────┬── LED strip GND
                      └── ESP32 GND       (grounds must be connected!)
ESP32 GPIO (default 16, C3: 4) ── LED strip DIN
APA102 / SK9822 only: second GPIO ── LED strip CLK
```

Most WS2812B strips work fine with the ESP32's 3.3 V data signal over a short wire (under ~1 m).
For long data wires add a 74AHCT125 level shifter.

## Install

1. **Firmware** — download `PixelBlue-<board>.bin` from the Releases page and flash it at address `0x0`
   with [ESP Web Flasher](https://espressif.github.io/esptool-js/) (Chrome on a computer, USB cable).
   Or with PlatformIO: `cd firmware && pio run -e esp32 -t upload`.
2. **App** — download `PixelBlue.apk` from Releases and open it on your phone
   (allow "install unknown apps" for your browser or file manager).
3. Open the app, tap the controller, then go to **Setup** and enter your strip type, pin and size.

## Building

- Android: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK 35)
- Firmware: `cd firmware && pio run`

Every push to `main` builds the APK and the firmware on GitHub Actions; pushing a tag like `v0.1.0`
publishes them as a release.

The BLE protocol is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md), so other apps can talk to
the controller too.

## License

MIT
