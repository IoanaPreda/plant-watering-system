# Plant watering system

ESP32 firmware and an Android companion app for remote soil-moisture monitoring and pump control over MQTT. A cloud broker (for example [HiveMQ Cloud](https://www.hivemq.com/mqtt-cloud-broker/)) lets the phone and device talk without opening your home network.

## Architecture

```
Phone (Android)  ←→  MQTT broker (internet)  ←→  ESP32 (home Wi‑Fi)
```

## Capabilities

- Remote pump control and soil moisture readouts over MQTT
- JSON telemetry on fixed topics (see [MQTT interface](#mqtt-interface))
- TLS to the broker on the ESP32 (configurable port)
- Watchdog and maximum pump-on duration in firmware

## Hardware

| Item | Suggested notes |
|------|------------------|
| **MCU** | ESP32 DevKit-class board (e.g. ESP-WROOM-32, `esp32doit-devkit-v1` in PlatformIO) |
| **Soil sensor** | Capacitive analog soil moisture module (this project uses one analog output on GPIO 32) |
| **Switching** | Relay module suitable for your pump; logic input on GPIO 16 |
| **Pump** | Small DC pump (commonly 5–12 V); current and voltage must match the relay and supply |
| **Power** | separate stable supply for the pump; do not run the motor from the ESP32 3.3 V/5 V pins |

**Connections (this repository)**

| ESP32 | Peripheral |
|-------|------------|
| GPIO 32 | Soil sensor analog out |
| GPIO 16 | Relay signal input (coil or opto input per your module) |
| GND | Common ground with sensor and relay, per your module’s wiring |
| 3.3 V | Sensor VCC, if the module is 3.3 V compatible (many are) |

**Wiring practice:** Keep low-voltage logic and high-current pump paths separate. Use a relay rated for the pump’s voltage and current. If the pump is powered from mains (via a pump controller), follow local electrical rules; this README assumes a low-voltage DC pump for hobby use.

**Moisture scaling:** Raw ADC and “percent wet” depend on the sensor, soil, and supply. The firmware uses two constants, `WET` and `DRY`, in `src/main.cpp` (see `readMoistureAvg` / `moisturePercent`). Calibrate for your setup: read the raw value in very wet soil and in dry/air, then set `WET` to the wet reading and `DRY` to the dry reading so the map matches your environment.

**Relay test sketch:** `test/test_relay.cpp` is a minimal serial-driven relay check. To use it, temporarily build that code as the only application in `src/` (for example back up `main.cpp` and copy the test file to `src/main.cpp`), then restore `main.cpp` for normal operation.

## Firmware (ESP32)

**Requirements:** [PlatformIO](https://docs.platformio.org/) (VS Code extension or CLI).

1. **Configuration**  
   Copy `include/config.h.example` to `include/config.h` and set Wi-Fi and broker variables. The example file lists the required symbols.

2. **Build and upload**
   ```bash
   pio run --target upload
   ```
3. **Serial log** (115200 baud; the serial port is auto-detected by PlatformIO when possible)
   ```bash
   pio device monitor
   ```

**Broker:** Create a cluster and credentials in your provider’s console. Set `MQTT_SERVER`, `MQTT_PORT` (e.g. `8883` for TLS), `MQTT_USER`, and `MQTT_PASSWORD` to match. The stock firmware uses TLS (`WiFiClientSecure`) and `setInsecure()` for typical public broker hostnames; align your broker policy with that.

**Wi-Fi:** The ESP32 works on 2.4 GHz networks; it does not use 5 GHz Wi-Fi.

## Android app

The app lives under `android/`. It is a Kotlin/Compose project using Eclipse Paho. Broker URL and credentials are injected at build time from `local.properties` in the `android/` directory (this file is not committed).

Create `android/local.properties` with at least:

```properties
sdk.dir=/path/to/Android/sdk
mqtt.broker.url=ssl://your-cluster.s1.eu.hivemq.cloud:8883
mqtt.username=your_mqtt_user
mqtt.password=your_mqtt_password
```

Use a broker URL and port that match the ESP32 configuration (`ssl://` and port `8883` for TLS, consistent with a typical HiveMQ setup). Open the `android` folder in Android Studio and run the app; minimum SDK in the project is 24.

## MQTT interface

**Subscribe (ESP32 publishes)**

| Topic | Content |
|-------|---------|
| `plant/moisture` | JSON, e.g. `{"moisture":65,"raw":1850}` (about every 10 s) |
| `plant/relay/state` | `ON` or `OFF` |
| `plant/status` | JSON with moisture, relay, uptime, and pump-related fields |

**Publish (control the ESP32)**

| Topic | Payload |
|-------|---------|
| `plant/relay/command` | `on`, `off`, or `toggle` (also accepted: `1`/`0`, `true`/`false` for on/off) |

## Troubleshooting

- **No Wi-Fi:** Check SSID/password, 2.4 GHz, and range.
- **No MQTT from the ESP32:** Confirm broker host, port, and credentials; verify the cluster allows your connection and that TLS settings match.
- **App shows no data:** Broker URL, user, and password in `local.properties` must match the device; topic names are case-sensitive.
- **Commands ignored:** Send to `plant/relay/command` with a supported payload; watch the serial log for received lines.

## Safety

- Bench-test relay behavior **without** the pump connected first.
- Protect electronics from moisture; strain-relief and isolate high-current wiring from the dev board.
- Supervise the first watering runs; unattended automation carries flooding and equipment risk.
