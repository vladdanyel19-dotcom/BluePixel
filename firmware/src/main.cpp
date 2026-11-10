// PixelBlue - Bluetooth LED controller firmware for ESP32 / ESP32-C3 / ESP32-S3.
// Protocol: docs/PROTOCOL.md
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_timer.h>
#include "config.h"
#include "strip.h"
#include "effects.h"

static HwConfig     hw;
static LightState   st;
static ScheduleSlot sched[PB_MAX_SCHEDULES];
static char         devName[PB_MAX_NAME + 1];

static Strip   strip;
static Effects fx;
static Preferences prefs;

static NimBLECharacteristic* stateChar = nullptr;
static volatile bool connected = false;

// Time: UTC epoch captured at the last sync + local offset.
static int64_t  syncEpoch = 0;       // seconds
static int64_t  syncMicros = 0;
static int16_t  tzMinutes = 0;
static bool     timeValid = false;
static bool     firstSyncDone = false;
static int32_t  lastMinuteChecked = -1;

static bool     stateDirty = false;      // needs notify
static uint32_t saveAt = 0;              // millis when to persist LightState (0 = nothing pending)
static uint32_t restartAt = 0;

// ---------------------------------------------------------------- packet queue
// BLE writes arrive on the NimBLE task; we copy them into a queue and handle
// them in loop(), so the LED buffers are only ever touched from one task.
struct Packet { uint8_t kind; uint16_t len; uint8_t data[256]; };
static QueueHandle_t rxQueue;
enum : uint8_t { PK_CMD = 0, PK_PIXELS = 1 };

static void enqueue(uint8_t kind, const uint8_t* d, size_t n) {
  if (n == 0) return;
  static Packet p;   // only used from the NimBLE task
  p.kind = kind;
  p.len = n > sizeof(p.data) ? sizeof(p.data) : n;
  memcpy(p.data, d, p.len);
  xQueueSend(rxQueue, &p, 0);   // drop when full rather than blocking BLE
}

// ---------------------------------------------------------------- storage
static void loadAll() {
  prefs.begin("pb", true);
  if (prefs.getBytesLength("hw") == sizeof(hw)) prefs.getBytes("hw", &hw, sizeof(hw));
  if (prefs.getBytesLength("st") == sizeof(st)) prefs.getBytes("st", &st, sizeof(st));
  if (prefs.getBytesLength("sch") == sizeof(sched)) prefs.getBytes("sch", sched, sizeof(sched));
  String n = prefs.getString("name", "");
  prefs.end();

  if (n.length() == 0) {
    uint64_t mac = ESP.getEfuseMac();
    snprintf(devName, sizeof(devName), "PixelBlue-%02X%02X",
             (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));
  } else {
    strlcpy(devName, n.c_str(), sizeof(devName));
  }
  if (st.effect >= FX_COUNT) st.effect = FX_SOLID;
}

static void saveHw()    { prefs.begin("pb", false); prefs.putBytes("hw", &hw, sizeof(hw)); prefs.end(); }
static void saveSched() { prefs.begin("pb", false); prefs.putBytes("sch", sched, sizeof(sched)); prefs.end(); }
static void saveState() { prefs.begin("pb", false); prefs.putBytes("st", &st, sizeof(st)); prefs.end(); }
static void saveName()  { prefs.begin("pb", false); prefs.putString("name", devName); prefs.end(); }

static void stateChanged(bool persist = true) {
  stateDirty = true;
  if (persist) saveAt = millis() + 3000;   // debounce flash writes
}

// ---------------------------------------------------------------- time & schedule
static int64_t localNow() {
  return syncEpoch + (esp_timer_get_time() - syncMicros) / 1000000 + (int64_t)tzMinutes * 60;
}

static bool dayMatches(uint8_t mask, int64_t localSecs) {
  int dow = (int)(((localSecs / 86400) + 3) % 7);   // 1970-01-01 was Thursday -> 0 = Monday
  if (dow < 0) dow += 7;
  return mask & (1 << dow);
}

// True when the current local time falls inside an enabled ON window.
static bool insideWindow(int64_t now) {
  const int minute = (int)((now % 86400) / 60);
  for (auto& s : sched) {
    if (!s.enabled || s.start == s.end) continue;
    if (s.start < s.end) {
      if (minute >= s.start && minute < s.end && dayMatches(s.days, now)) return true;
    } else {   // window crosses midnight
      if (minute >= s.start && dayMatches(s.days, now)) return true;
      if (minute < s.end && dayMatches(s.days, now - 86400)) return true;
    }
  }
  return false;
}

static bool anyScheduleEnabled() {
  for (auto& s : sched) if (s.enabled && s.start != s.end) return true;
  return false;
}

static void setPower(uint8_t on) {
  if (st.power == on) return;
  st.power = on;
  stateChanged();
}

static void checkSchedule() {
  if (!timeValid) return;
  const int64_t now = localNow();
  const int32_t minuteStamp = (int32_t)(now / 60);
  if (minuteStamp == lastMinuteChecked) return;
  lastMinuteChecked = minuteStamp;
  const int minute = (int)((now % 86400) / 60);

  for (auto& s : sched) {
    if (!s.enabled || s.start == s.end) continue;
    if (minute == s.start && dayMatches(s.days, now)) setPower(1);
    if (minute == s.end) {
      // The OFF event belongs to the day the window started.
      int64_t startDay = (s.end < s.start) ? now - 86400 : now;
      if (dayMatches(s.days, startDay)) setPower(0);
    }
  }
}

// ---------------------------------------------------------------- state packet
static void buildState(uint8_t* p) {
  memset(p, 0, 64);
  p[0] = PB_PROTOCOL;
  p[1] = st.power; p[2] = st.brightness; p[3] = st.effect; p[4] = st.speed;
  p[5] = st.r; p[6] = st.g; p[7] = st.b; p[8] = st.w;
  p[9] = hw.type; p[10] = hw.order; p[11] = hw.dataPin; p[12] = hw.clockPin;
  p[13] = hw.width & 0xFF; p[14] = hw.width >> 8;
  p[15] = hw.height & 0xFF; p[16] = hw.height >> 8;
  p[17] = hw.layout;
  p[18] = hw.maxmA & 0xFF; p[19] = hw.maxmA >> 8;
  p[20] = timeValid;
  p[21] = FX_COUNT;
  p[22] = PB_FW_MAJOR; p[23] = PB_FW_MINOR;
  p[24] = PB_MAX_SCHEDULES;
  for (int i = 0; i < PB_MAX_SCHEDULES; i++) {
    uint8_t* q = p + 25 + i * 6;
    q[0] = sched[i].start & 0xFF; q[1] = sched[i].start >> 8;
    q[2] = sched[i].end & 0xFF;   q[3] = sched[i].end >> 8;
    q[4] = sched[i].days; q[5] = sched[i].enabled;
  }
  p[49] = st.r2; p[50] = st.g2; p[51] = st.b2;
  p[52] = PB_CHIP;
}

static void notifyState() {
  if (!stateChar) return;
  uint8_t p[64];
  buildState(p);
  stateChar->setValue(p, sizeof(p));
  if (connected) stateChar->notify();
}

// ---------------------------------------------------------------- commands
static uint16_t rd16(const uint8_t* d) { return d[0] | (d[1] << 8); }

static void handleCmd(const uint8_t* d, size_t n) {
  const uint8_t op = d[0];
  d++; n--;
  switch (op) {
    case 0x01: if (n >= 1) setPower(d[0] ? 1 : 0); break;
    case 0x02: if (n >= 1) { st.brightness = d[0]; stateChanged(); } break;
    case 0x03: if (n >= 3) { st.r = d[0]; st.g = d[1]; st.b = d[2]; st.w = n >= 4 ? d[3] : 0; stateChanged(); } break;
    case 0x04: if (n >= 1 && d[0] < FX_COUNT) { st.effect = d[0]; fx.restart(); st.power = 1; stateChanged(); } break;
    case 0x05: if (n >= 1) { st.speed = d[0]; stateChanged(); } break;
    case 0x06:
      if (n >= 6) {
        syncEpoch = (int64_t)(d[0] | (d[1] << 8) | (d[2] << 16) | ((uint32_t)d[3] << 24));
        syncMicros = esp_timer_get_time();
        tzMinutes = (int16_t)rd16(d + 4);
        timeValid = true;
        lastMinuteChecked = -1;
        if (!firstSyncDone) {
          firstSyncDone = true;
          // After a power cut the clock was unknown: line the light up with the schedule.
          if (anyScheduleEnabled()) setPower(insideWindow(localNow()) ? 1 : 0);
        }
        stateChanged(false);
      }
      break;
    case 0x07: {
      if (n < 1) break;
      uint8_t cnt = min<uint8_t>(d[0], PB_MAX_SCHEDULES);
      if (n < 1 + cnt * 6u) break;
      ScheduleSlot fresh[PB_MAX_SCHEDULES];
      for (int i = 0; i < cnt; i++) {
        const uint8_t* q = d + 1 + i * 6;
        fresh[i].start = min<uint16_t>(rd16(q), 1439);
        fresh[i].end = min<uint16_t>(rd16(q + 2), 1439);
        fresh[i].days = q[4] & 0x7F;
        fresh[i].enabled = q[5] ? 1 : 0;
      }
      memcpy(sched, fresh, sizeof(sched));
      saveSched();
      lastMinuteChecked = -1;
      stateChanged(false);
      break;
    }
    case 0x08:
      if (n >= 11) {
        HwConfig c;
        c.type = d[0] <= LED_SK9822 ? d[0] : LED_WS2812;
        c.order = d[1] % 6;
        c.dataPin = d[2];
        c.clockPin = d[3];
        c.width = max<uint16_t>(1, rd16(d + 4));
        c.height = max<uint16_t>(1, rd16(d + 6));
        if ((uint32_t)c.width * c.height > PB_MAX_LEDS) c.height = max<uint16_t>(1, PB_MAX_LEDS / c.width);
        c.layout = d[8] & 0x0F;
        c.maxmA = rd16(d + 9);
        hw = c;
        saveHw();
        saveState();
        restartAt = millis() + 400;
      }
      break;
    case 0x09:
      if (n >= 1) {
        size_t len = min<size_t>(n, PB_MAX_NAME);
        memcpy(devName, d, len);
        devName[len] = 0;
        saveName();
        saveState();
        restartAt = millis() + 400;
      }
      break;
    case 0x0A: if (n >= 3) { fx.fillCanvas(d[0], d[1], d[2]); st.effect = FX_CANVAS; st.power = 1; stateChanged(); } break;
    case 0x0B: fx.setText((const char*)d, n); st.effect = FX_TEXT; st.power = 1; stateChanged(); break;
    case 0x0C: if (n >= 3) { st.r2 = d[0]; st.g2 = d[1]; st.b2 = d[2]; stateChanged(); } break;
  }
}

static void handlePixels(const uint8_t* d, size_t n) {
  if (n < 5) return;
  uint16_t off = rd16(d);
  d += 2; n -= 2;
  for (size_t i = 0; i + 2 < n; i += 3) fx.setCanvasPixel(off++, d[i], d[i + 1], d[i + 2]);
  if (st.effect != FX_CANVAS || !st.power) { st.effect = FX_CANVAS; st.power = 1; stateChanged(); }
}

// ---------------------------------------------------------------- BLE
class ServerCB : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    connected = true;
    // Short connection interval -> snappy controls and fast pixel uploads.
    s->updateConnParams(info.getConnHandle(), 6, 12, 0, 300);
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    connected = false;
    NimBLEDevice::startAdvertising();
  }
};

class CmdCB : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    NimBLEAttValue v = c->getValue();
    enqueue(PK_CMD, v.data(), v.length());
  }
};

class PixelsCB : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    NimBLEAttValue v = c->getValue();
    enqueue(PK_PIXELS, v.data(), v.length());
  }
};

static void startBle() {
  NimBLEDevice::init(devName);
  NimBLEDevice::setMTU(247);
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCB());

  NimBLEService* svc = server->createService(PB_SERVICE_UUID);
  NimBLECharacteristic* cmd = svc->createCharacteristic(
      PB_CMD_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  cmd->setCallbacks(new CmdCB());
  stateChar = svc->createCharacteristic(PB_STATE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* px = svc->createCharacteristic(
      PB_PIXELS_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  px->setCallbacks(new PixelsCB());
  svc->start();
  notifyState();

  NimBLEAdvertisementData advData;
  advData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  advData.addServiceUUID(NimBLEUUID(PB_SERVICE_UUID));
  NimBLEAdvertisementData scanData;
  scanData.setName(devName);

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->setAdvertisementData(advData);
  adv->setScanResponseData(scanData);
  adv->start();
}

// ---------------------------------------------------------------- output
static void showFrame() {
  const RGB* f = fx.frame();
  const uint16_t w = fx.width(), h = fx.height();

  // Estimate the current draw and scale brightness down to stay inside maxmA.
  uint8_t bri = st.power ? st.brightness : 0;
  if (hw.maxmA && bri) {
    uint32_t sum = 0;
    for (uint32_t i = 0; i < (uint32_t)w * h; i++) sum += f[i].r + f[i].g + f[i].b;
    if (st.effect == FX_SOLID && strip.isRgbw()) sum += (uint32_t)st.w * w * h;
    // ~20 mA per channel at full level, ~1 mA idle per LED
    uint32_t mA = (sum * bri / 255) * 20 / 255 + (uint32_t)w * h;
    if (mA > hw.maxmA) bri = (uint32_t)bri * hw.maxmA / mA;
  }

  for (uint16_t y = 0; y < h; y++)
    for (uint16_t x = 0; x < w; x++) {
      const RGB& c = f[(uint32_t)y * w + x];
      uint8_t wv = (st.effect == FX_SOLID) ? scale8(st.w, bri) : 0;
      strip.set(strip.index(x, y), scale8(c.r, bri), scale8(c.g, bri), scale8(c.b, bri), wv);
    }
  strip.show();
}

// ---------------------------------------------------------------- main
void setup() {
  Serial.begin(115200);
  rxQueue = xQueueCreate(24, sizeof(Packet));
  loadAll();
  if (!strip.begin(hw)) {
    // Clock pin missing for a DotStar strip: fall back to WS2812 so the device stays usable.
    hw.type = LED_WS2812;
    strip.begin(hw);
  }
  fx.begin(hw.width, hw.height);
  fx.restart();
  startBle();
  Serial.printf("PixelBlue %d.%d ready as '%s'\n", PB_FW_MAJOR, PB_FW_MINOR, devName);
}

void loop() {
  static Packet p;
  int handled = 0;
  while (handled < 24 && xQueueReceive(rxQueue, &p, 0) == pdTRUE) {
    if (p.kind == PK_CMD) handleCmd(p.data, p.len);
    else handlePixels(p.data, p.len);
    handled++;
  }

  checkSchedule();

  static uint32_t lastFrame = 0;
  const uint32_t now = millis();
  if (now - lastFrame >= 20) {   // ~50 fps
    lastFrame = now;
    fx.render(st.effect, st);
    showFrame();
  }

  if (stateDirty) { stateDirty = false; notifyState(); }
  if (saveAt && (int32_t)(now - saveAt) >= 0) { saveAt = 0; saveState(); }
  if (restartAt && (int32_t)(now - restartAt) >= 0) { ESP.restart(); }
  delay(1);
}
