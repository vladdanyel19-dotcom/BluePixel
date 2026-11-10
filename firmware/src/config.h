#pragma once
#include <Arduino.h>

#define PB_FW_MAJOR 0
#define PB_FW_MINOR 1
#define PB_PROTOCOL 1

#define PB_SERVICE_UUID "c0de0001-5ee1-4b1e-9e0d-50a3c1b2e000"
#define PB_CMD_UUID     "c0de0002-5ee1-4b1e-9e0d-50a3c1b2e000"
#define PB_STATE_UUID   "c0de0003-5ee1-4b1e-9e0d-50a3c1b2e000"
#define PB_PIXELS_UUID  "c0de0004-5ee1-4b1e-9e0d-50a3c1b2e000"

#define PB_MAX_LEDS      2048
#define PB_MAX_SCHEDULES 4
#define PB_MAX_TEXT      120
#define PB_MAX_NAME      20

enum LedType : uint8_t { LED_WS2812 = 0, LED_WS2811 = 1, LED_SK6812_RGBW = 2, LED_APA102 = 3, LED_SK9822 = 4 };
enum ColorOrder : uint8_t { ORD_RGB = 0, ORD_RBG, ORD_GRB, ORD_GBR, ORD_BRG, ORD_BGR };

enum Layout : uint8_t {
  LAYOUT_SERPENTINE = 1 << 0,
  LAYOUT_VERTICAL   = 1 << 1,
  LAYOUT_START_RIGHT  = 1 << 2,
  LAYOUT_START_BOTTOM = 1 << 3,
};

enum Effect : uint8_t {
  FX_SOLID = 0, FX_CANVAS, FX_RAINBOW, FX_RAINBOW_WAVE, FX_BREATHE, FX_WIPE, FX_THEATER,
  FX_TWINKLE, FX_FIRE, FX_COMET, FX_STROBE, FX_POLICE, FX_SPARKLE, FX_GRADIENT,
  FX_MATRIX, FX_PLASMA, FX_TEXT,
  FX_COUNT
};

#if defined(CONFIG_IDF_TARGET_ESP32C3)
  #define PB_CHIP 1
  #define PB_DEFAULT_PIN 4
#elif defined(CONFIG_IDF_TARGET_ESP32S3)
  #define PB_CHIP 2
  #define PB_DEFAULT_PIN 16
#elif defined(CONFIG_IDF_TARGET_ESP32C6)
  #define PB_CHIP 3
  #define PB_DEFAULT_PIN 4
#elif defined(CONFIG_IDF_TARGET_ESP32)
  #define PB_CHIP 0
  #define PB_DEFAULT_PIN 16
#else
  #define PB_CHIP 255
  #define PB_DEFAULT_PIN 4
#endif

struct __attribute__((packed)) HwConfig {
  uint8_t  type      = LED_WS2812;
  uint8_t  order     = ORD_GRB;
  uint8_t  dataPin   = PB_DEFAULT_PIN;
  uint8_t  clockPin  = 255;      // only APA102 / SK9822
  uint16_t width     = 60;       // LEDs per row (or strip length)
  uint16_t height    = 1;        // 1 = plain strip
  uint8_t  layout    = LAYOUT_SERPENTINE;
  uint16_t maxmA     = 2000;     // 0 = no limit
};

struct __attribute__((packed)) LightState {
  uint8_t power      = 1;
  uint8_t brightness = 128;
  uint8_t effect     = FX_RAINBOW_WAVE;
  uint8_t speed      = 128;
  uint8_t r = 255, g = 80, b = 0, w = 0;
  uint8_t r2 = 0, g2 = 0, b2 = 60;
};

struct __attribute__((packed)) ScheduleSlot {
  uint16_t start   = 0;   // minute of day, power ON
  uint16_t end     = 0;   // minute of day, power OFF
  uint8_t  days    = 0x7F; // bit0 = Monday ... bit6 = Sunday
  uint8_t  enabled = 0;
};
