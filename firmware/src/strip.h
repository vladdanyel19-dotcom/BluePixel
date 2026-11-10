#pragma once
// Runtime-configurable LED output: chip type, pins and color order are chosen
// at boot from the saved configuration, so one firmware fits every strip.
#include <Adafruit_NeoPixel.h>
#include <Adafruit_DotStar.h>
#include "config.h"

class Strip {
 public:
  bool begin(const HwConfig& c) {
    cfg_ = c;
    count_ = (uint32_t)c.width * c.height;
    if (count_ == 0) count_ = 1;
    if (count_ > PB_MAX_LEDS) count_ = PB_MAX_LEDS;

    if (c.type == LED_APA102 || c.type == LED_SK9822) {
      if (c.clockPin == 255) return false;
      static const uint8_t dsOrder[6] = {DOTSTAR_RGB, DOTSTAR_RBG, DOTSTAR_GRB,
                                         DOTSTAR_GBR, DOTSTAR_BRG, DOTSTAR_BGR};
      ds_ = new Adafruit_DotStar(count_, c.dataPin, c.clockPin, dsOrder[c.order % 6]);
      ds_->begin();
      ds_->setBrightness(255);
      ds_->show();
      return true;
    }

    rgbw_ = (c.type == LED_SK6812_RGBW);
    static const neoPixelType rgbOrder[6]  = {NEO_RGB, NEO_RBG, NEO_GRB, NEO_GBR, NEO_BRG, NEO_BGR};
    static const neoPixelType rgbwOrder[6] = {NEO_RGBW, NEO_RBGW, NEO_GRBW, NEO_GBRW, NEO_BRGW, NEO_BGRW};
    neoPixelType t = rgbw_ ? rgbwOrder[c.order % 6] : rgbOrder[c.order % 6];
    t += (c.type == LED_WS2811) ? NEO_KHZ400 : NEO_KHZ800;
    np_ = new Adafruit_NeoPixel(count_, c.dataPin, t);
    np_->begin();
    np_->setBrightness(255);
    np_->show();
    return true;
  }

  uint16_t count() const { return count_; }
  bool isRgbw() const { return rgbw_; }

  // Physical index for a logical (x, y) position, honoring the wiring layout.
  uint16_t index(uint16_t x, uint16_t y) const {
    const uint16_t w = cfg_.width, h = cfg_.height;
    if (cfg_.layout & LAYOUT_START_RIGHT)  x = w - 1 - x;
    if (cfg_.layout & LAYOUT_START_BOTTOM) y = h - 1 - y;
    const bool serp = cfg_.layout & LAYOUT_SERPENTINE;
    uint32_t i;
    if (cfg_.layout & LAYOUT_VERTICAL) {
      i = (uint32_t)x * h + ((serp && (x & 1)) ? (h - 1 - y) : y);
    } else {
      i = (uint32_t)y * w + ((serp && (y & 1)) ? (w - 1 - x) : x);
    }
    return i < count_ ? i : count_ - 1;
  }

  void set(uint16_t i, uint8_t r, uint8_t g, uint8_t b, uint8_t w = 0) {
    if (np_) {
      if (rgbw_) np_->setPixelColor(i, r, g, b, w);
      else       np_->setPixelColor(i, r, g, b);
    } else if (ds_) {
      ds_->setPixelColor(i, r, g, b);
    }
  }

  void show() {
    if (np_) np_->show();
    else if (ds_) ds_->show();
  }

 private:
  HwConfig cfg_;
  uint16_t count_ = 0;
  bool rgbw_ = false;
  Adafruit_NeoPixel* np_ = nullptr;
  Adafruit_DotStar* ds_ = nullptr;
};
