#pragma once
// Effect engine. Everything is drawn into a logical width x height buffer;
// the Strip maps it to the physical wiring when the frame is shown.
#include <Arduino.h>
#include "config.h"
#include "font5x7.h"

struct RGB { uint8_t r, g, b; };

static inline uint8_t qadd8(uint8_t a, uint8_t b) { uint16_t s = a + b; return s > 255 ? 255 : s; }
static inline uint8_t qsub8(uint8_t a, uint8_t b) { return a > b ? a - b : 0; }
static inline uint8_t scale8(uint8_t v, uint8_t s) { return ((uint16_t)v * (s + 1)) >> 8; }

class Effects {
 public:
  void begin(uint16_t w, uint16_t h) {
    w_ = w ? w : 1;
    h_ = h ? h : 1;
    n_ = (uint32_t)w_ * h_;
    if (n_ > PB_MAX_LEDS) { n_ = PB_MAX_LEDS; h_ = n_ / w_; if (!h_) { h_ = 1; w_ = n_; } }
    frame_  = (RGB*)calloc(n_, sizeof(RGB));
    canvas_ = (RGB*)calloc(n_, sizeof(RGB));
    work_   = (uint8_t*)calloc(n_, 1);
    for (int i = 0; i < 256; i++) sin8_[i] = (uint8_t)(128 + 127 * sinf(i * 2 * PI / 256));
    strcpy(text_, "PixelBlue");
  }

  uint16_t width() const { return w_; }
  uint16_t height() const { return h_; }
  uint16_t count() const { return n_; }
  const RGB* frame() const { return frame_; }

  void setCanvasPixel(uint16_t i, uint8_t r, uint8_t g, uint8_t b) {
    if (i < n_) canvas_[i] = {r, g, b};
  }
  void fillCanvas(uint8_t r, uint8_t g, uint8_t b) {
    for (uint16_t i = 0; i < n_; i++) canvas_[i] = {r, g, b};
  }
  void setText(const char* s, size_t len) {
    size_t k = 0;
    for (size_t i = 0; i < len && k < PB_MAX_TEXT; i++) {
      uint8_t c = (uint8_t)s[i];
      if (c < 0x80) { text_[k++] = (char)c; continue; }
      // Romanian diacritics (UTF-8) -> base letter, other multi-byte chars -> '?'
      uint16_t cp = 0;
      if ((c & 0xE0) == 0xC0 && i + 1 < len) { cp = ((c & 0x1F) << 6) | (s[i + 1] & 0x3F); i += 1; }
      else { while (i + 1 < len && ((uint8_t)s[i + 1] & 0xC0) == 0x80) i++; }
      char base = '?';
      switch (cp) {
        case 0x103: case 0xE2: case 0x102: case 0xC2: base = 'A'; break;
        case 0xEE: case 0xCE: base = 'I'; break;
        case 0x219: case 0x15F: case 0x218: case 0x15E: base = 'S'; break;
        case 0x21B: case 0x163: case 0x21A: case 0x162: base = 'T'; break;
        case 0xE4: case 0xC4: base = 'A'; break;
        case 0xF6: case 0xD6: base = 'O'; break;
        case 0xFC: case 0xDC: base = 'U'; break;
        case 0xDF: base = 'S'; break;
      }
      text_[k++] = base;
    }
    text_[k] = 0;
    textLen_ = k;
    textStart_ = millis();
  }

  void restart() { memset(work_, 0, n_); start_ = millis(); textStart_ = millis(); }

  // Render one frame of `fx` into the logical buffer.
  void render(uint8_t fx, const LightState& s) {
    const uint32_t now = millis();
    const uint32_t t = now - start_;
    // speed 0..255 -> time multiplier ~0.1x .. ~4x
    const uint32_t sp = 26 + (uint32_t)s.speed * 4;      // 26..1046
    const uint32_t tt = (t * sp) >> 8;                   // scaled time in ms
    const RGB c1 = {s.r, s.g, s.b};
    const RGB c2 = {s.r2, s.g2, s.b2};

    switch (fx) {
      case FX_SOLID:  fill(c1); break;
      case FX_CANVAS: memcpy(frame_, canvas_, n_ * sizeof(RGB)); break;

      case FX_RAINBOW: fill(hsv(tt / 16, 255, 255)); break;

      case FX_RAINBOW_WAVE:
        for (uint16_t y = 0; y < h_; y++)
          for (uint16_t x = 0; x < w_; x++) {
            uint16_t pos = (h_ > 1) ? (x + y) * 256 / (w_ + h_) : x * 256 / w_;
            at(x, y) = hsv(pos + tt / 10, 255, 255);
          }
        break;

      case FX_BREATHE: {
        uint8_t v = sin8_[(tt / 12) & 255];
        v = scale8(v, v) | 8;
        fill({scale8(c1.r, v), scale8(c1.g, v), scale8(c1.b, v)});
        break;
      }

      case FX_WIPE: {
        uint32_t step = tt / 25;
        uint32_t cycle = step / n_;
        uint16_t pos = step % n_;
        RGB a = (cycle & 1) ? c2 : c1, b = (cycle & 1) ? c1 : c2;
        for (uint16_t i = 0; i < n_; i++) frame_[i] = (i <= pos) ? a : b;
        break;
      }

      case FX_THEATER: {
        uint8_t ph = (tt / 120) % 3;
        for (uint16_t i = 0; i < n_; i++) frame_[i] = ((i + ph) % 3 == 0) ? c1 : c2;
        break;
      }

      case FX_TWINKLE: {
        if (tick(now, 30)) {
          for (uint16_t i = 0; i < n_; i++) work_[i] = qsub8(work_[i], 12);
          uint16_t births = 1 + n_ / 40;
          for (uint16_t k = 0; k < births; k++) if (random(8) < 1 + s.speed / 64) work_[random(n_)] = 255;
        }
        for (uint16_t i = 0; i < n_; i++) {
          uint8_t v = work_[i];
          frame_[i] = blend(c2, c1, v);
        }
        break;
      }

      case FX_FIRE: fire(now, s.speed); break;

      case FX_COMET: {
        if (tick(now, 20)) for (uint16_t i = 0; i < n_; i++) work_[i] = scale8(work_[i], 200);
        uint16_t span = (h_ > 1) ? w_ : n_;
        uint16_t head = (tt / 18) % (span * 2);
        if (head >= span) head = span * 2 - 1 - head;
        if (h_ > 1) { for (uint16_t y = 0; y < h_; y++) work_[y * w_ + head] = 255; }
        else work_[head] = 255;
        for (uint16_t i = 0; i < n_; i++) frame_[i] = blend(c2, c1, work_[i]);
        break;
      }

      case FX_STROBE: fill(((tt / 60) % 6 == 0) ? c1 : c2); break;

      case FX_POLICE: {
        uint8_t ph = (tt / 70) % 16;
        bool left = ph < 8;
        bool flash = (ph % 8) < 6 && (ph % 2 == 0);
        for (uint16_t y = 0; y < h_; y++)
          for (uint16_t x = 0; x < w_; x++) {
            bool isLeft = x < w_ / 2;
            RGB c = {0, 0, 0};
            if (flash && isLeft == left) c = left ? RGB{255, 0, 0} : RGB{0, 0, 255};
            at(x, y) = c;
          }
        break;
      }

      case FX_SPARKLE: {
        fill(c2);
        uint16_t k = 1 + n_ / 50;
        for (uint16_t i = 0; i < k; i++) if (random(4) == 0) frame_[random(n_)] = c1;
        break;
      }

      case FX_GRADIENT:
        for (uint16_t y = 0; y < h_; y++)
          for (uint16_t x = 0; x < w_; x++) {
            uint16_t p = ((h_ > 1 ? x + y : x) * 512 / (h_ > 1 ? w_ + h_ : w_) + tt / 8) & 511;
            uint8_t a = p < 256 ? p : 511 - p;
            at(x, y) = blend(c1, c2, a);
          }
        break;

      case FX_MATRIX: matrixRain(now, c1); break;

      case FX_PLASMA:
        for (uint16_t y = 0; y < h_; y++)
          for (uint16_t x = 0; x < w_; x++) {
            uint8_t v = (sin8_[(x * 16 + tt / 8) & 255] + sin8_[(y * 16 + tt / 11) & 255] +
                         sin8_[((x + y) * 8 + tt / 14) & 255]) / 3;
            at(x, y) = hsv(v + tt / 30, 255, 255);
          }
        break;

      case FX_TEXT: text(now, s, c1, c2); break;

      default: fill(c1); break;
    }
  }

 private:
  RGB& at(uint16_t x, uint16_t y) { return frame_[(uint32_t)y * w_ + x]; }
  void fill(RGB c) { for (uint16_t i = 0; i < n_; i++) frame_[i] = c; }

  bool tick(uint32_t now, uint32_t every) {
    if (now - last_ < every) return false;
    last_ = now;
    return true;
  }

  static RGB blend(RGB a, RGB b, uint8_t amt) {
    return {(uint8_t)(a.r + (((int)b.r - a.r) * amt) / 255),
            (uint8_t)(a.g + (((int)b.g - a.g) * amt) / 255),
            (uint8_t)(a.b + (((int)b.b - a.b) * amt) / 255)};
  }

  static RGB hsv(uint8_t h, uint8_t s, uint8_t v) {
    uint8_t region = h / 43, rem = (h - region * 43) * 6;
    uint8_t p = (v * (255 - s)) >> 8;
    uint8_t q = (v * (255 - ((s * rem) >> 8))) >> 8;
    uint8_t t = (v * (255 - ((s * (255 - rem)) >> 8))) >> 8;
    switch (region) {
      case 0: return {v, t, p};
      case 1: return {q, v, p};
      case 2: return {p, v, t};
      case 3: return {p, q, v};
      case 4: return {t, p, v};
      default: return {v, p, q};
    }
  }

  static RGB heatColor(uint8_t temp) {
    uint8_t t192 = scale8(temp, 191);
    uint8_t ramp = (t192 & 0x3F) << 2;
    if (t192 & 0x80) return {255, 255, ramp};
    if (t192 & 0x40) return {255, ramp, 0};
    return {ramp, 0, 0};
  }

  // Fire2012 per column (or along the whole strip when height == 1).
  void fire(uint32_t now, uint8_t speed) {
    const bool strip = (h_ == 1);
    const uint16_t cols = strip ? 1 : w_;
    const uint16_t len = strip ? w_ : h_;
    if (tick(now, 15 + (255 - speed) / 8)) {
      const uint8_t cooling = 55, sparking = 120;
      for (uint16_t c = 0; c < cols; c++) {
        auto H = [&](uint16_t i) -> uint8_t& { return strip ? work_[i] : work_[i * w_ + c]; };
        for (uint16_t i = 0; i < len; i++) H(i) = qsub8(H(i), random(0, ((cooling * 10) / len) + 2));
        for (int i = len - 1; i >= 2; i--) H(i) = (H(i - 1) + H(i - 2) + H(i - 2)) / 3;
        if (random(255) < sparking) { uint16_t y = random(min<uint16_t>(len, 7)); H(y) = qadd8(H(y), random(160, 255)); }
      }
    }
    for (uint16_t c = 0; c < cols; c++)
      for (uint16_t i = 0; i < len; i++) {
        if (strip) frame_[i] = heatColor(work_[i]);
        else at(c, h_ - 1 - i) = heatColor(work_[i * w_ + c]);   // flames rise upward
      }
  }

  void matrixRain(uint32_t now, RGB c) {
    if (tick(now, 70)) {
      if (h_ > 1) {
        for (int y = h_ - 1; y > 0; y--)
          for (uint16_t x = 0; x < w_; x++) {
            uint8_t above = work_[(y - 1) * w_ + x];
            work_[y * w_ + x] = (above == 255) ? 255 : scale8(work_[y * w_ + x], 180);
          }
        for (uint16_t x = 0; x < w_; x++) {
          uint8_t& top = work_[x];
          top = (top == 255) ? 200 : scale8(top, 180);
          if (random(100) < 6) top = 255;
        }
      } else {
        for (int i = n_ - 1; i > 0; i--) work_[i] = (work_[i - 1] == 255) ? 255 : scale8(work_[i], 160);
        work_[0] = (random(100) < 15) ? 255 : scale8(work_[0], 160);
      }
    }
    for (uint16_t i = 0; i < n_; i++) {
      uint8_t v = work_[i];
      frame_[i] = (v == 255) ? RGB{180, 255, 180} : RGB{scale8(c.r, v), scale8(c.g, v), scale8(c.b, v)};
    }
  }

  void text(uint32_t now, const LightState& s, RGB fg, RGB bg) {
    fill(bg);
    if (!textLen_) return;
    const int textW = textLen_ * 6;
    const uint32_t stepMs = 40 + (255 - s.speed);          // ms per column
    const int pos = (int)(((now - textStart_) / stepMs) % (uint32_t)(textW + w_));
    const int x0 = w_ - pos;                               // enters from the right
    const int y0 = (h_ > 7) ? (h_ - 7) / 2 : 0;
    for (int x = 0; x < w_; x++) {
      int tx = x - x0;
      if (tx < 0 || tx >= textW) continue;
      int ci = tx / 6, col = tx % 6;
      if (col == 5) continue;
      uint8_t ch = (uint8_t)text_[ci];
      if (ch < 32 || ch > 127) ch = '?';
      uint8_t bits = FONT5X7[ch - 32][col];
      for (int y = 0; y < 7; y++) {
        int py = y0 + y;
        if (py >= h_) break;
        if (bits & (1 << y)) at(x, py) = fg;
      }
    }
  }

  uint16_t w_ = 1, h_ = 1, n_ = 1;
  RGB* frame_ = nullptr;
  RGB* canvas_ = nullptr;
  uint8_t* work_ = nullptr;
  uint8_t sin8_[256];
  uint32_t start_ = 0, last_ = 0, textStart_ = 0;
  char text_[PB_MAX_TEXT + 1];
  uint16_t textLen_ = 9;
};
