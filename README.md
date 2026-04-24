# 🌱 Plant Watering System

ESP32-based automated plant watering system with remote control via Android app using MQTT.

## 📋 Features

- ✅ **Remote Control**: Control water pump from anywhere via Android app
- ✅ **Real-time Monitoring**: View soil moisture levels remotely
- ✅ **MQTT Protocol**: Internet-based communication (works outside home WiFi)
- ✅ **Safety First**: Relay starts in OFF state for battery safety
- ✅ **Free Cloud**: Uses HiveMQ Cloud (free tier)

## 🔧 Hardware Components

- **ESP32 DevKit V1** (ESP-WROOM-32)
- **Capacitive Soil Moisture Sensor** (GPIO 32)
- **Relay Module** (GPIO 16) - Controls water pump
- **Water Pump** (5-12V DC)
- **Power Supply** (for pump)

### Circuit Connections
```
ESP32 GPIO 32  →  Moisture Sensor (Analog Out)
ESP32 GPIO 16  →  Relay Module (IN)
Relay Module   →  Water Pump (NO & COM terminals)
```

## 🚀 Quick Start

### 1. Setup PlatformIO Project
```bash
cd plant-watering-system
# PlatformIO will auto-install dependencies from platformio.ini
```

### 2. Configure WiFi & MQTT
Edit `include/config.h`:
```cpp
#define WIFI_SSID "YourWiFi"
#define WIFI_PASSWORD "YourPassword"
#define MQTT_SERVER "your-broker.hivemq.cloud"
#define MQTT_USER "your_username"
#define MQTT_PASSWORD "your_password"
```

See [ANDROID_APP_SETUP.md](ANDROID_APP_SETUP.md) for HiveMQ Cloud setup.

### 3. Upload to ESP32
```bash
pio run --target upload
pio device monitor  # View serial output
```

### 4. Setup Android App
Install **MQTT Dash** from Google Play Store and configure connection.  
Full instructions: [ANDROID_APP_SETUP.md](ANDROID_APP_SETUP.md)

## 📱 Android App Control

### MQTT Topics

**Subscribe to these** (ESP32 publishes):
- `plant/moisture` - Soil moisture percentage (every 10s)
- `plant/relay/state` - Pump ON/OFF state
- `plant/status` - Full system status

**Publish to these** (control ESP32):
- `plant/relay/command` - Send `on`, `off`, or `toggle`

## 📂 Project Structure

```
plant-watering-system/
├── platformio.ini           # PlatformIO config & libraries
├── include/
│   └── config.h            # WiFi & MQTT credentials (EDIT THIS)
├── src/
│   ├── main.cpp            # Main MQTT-enabled code
│   ├── test_relay.cpp      # Relay testing utility
│   └── test_sensor.cpp.bak # Sensor calibration
├── lib/                    # Custom libraries (if any)
├── test/                   # Unit tests
├── ANDROID_APP_SETUP.md    # Android app guide
└── README.md              # This file
```

## 🧪 Testing Components

### Test Relay (Safety First!)
```bash
# Edit platformio.ini: change main.cpp to test_relay.cpp in build
pio run --target upload
pio device monitor
# Commands: on, off, test, status
```

### Test Moisture Sensor
Use `test_sensor.cpp.bak` to calibrate your sensor (DRY/WET values).

## 🔐 Security Notes

- **Keep `config.h` private** - Don't commit credentials to public repos
- Add `include/config.h` to `.gitignore` if sharing code
- Use SSL/TLS (port 8883) for production
- Create strong MQTT passwords

## 🌐 Remote Access

This system works from **anywhere** because:
1. ESP32 connects to your home WiFi
2. ESP32 connects to HiveMQ Cloud (internet MQTT broker)
3. Your Android phone connects to same broker (via mobile data or any WiFi)
4. Broker relays messages between ESP32 and phone

No port forwarding or complex networking required! ✨

## 📊 Monitoring

View real-time logs via serial monitor:
```bash
pio device monitor -b 115200
```

Expected output:
```
=== Plant Watering System with MQTT ===
✅ WiFi connected!
   IP address: 192.168.1.100
Connecting to MQTT broker... connected!
📊 Published: Moisture=45% (raw=1980)
📥 Received [plant/relay/command]: on
💧 Relay turned ON (watering)
```

## 🛠️ Dependencies

Auto-installed via PlatformIO:
- `PubSubClient` (MQTT client)
- `ArduinoJson` (JSON parsing)
- `WiFi` (built-in ESP32)

## 📚 Documentation

- [Android App Setup Guide](ANDROID_APP_SETUP.md)
- [HiveMQ Cloud Docs](https://docs.hivemq.com/)
- [PlatformIO Docs](https://docs.platformio.org/)

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit changes (`git commit -m 'Add AmazingFeature'`)
4. Push to branch (`git push origin feature/AmazingFeature`)
5. Open Pull Request

## 📄 License

This project is open source and available under the [MIT License](LICENSE).

## ⚠️ Safety Warning

- Test relay control WITHOUT water pump connected first
- Ensure proper waterproofing for electronics
- Use appropriate power supply for your pump
- Monitor first watering cycles closely
- Don't leave system unattended until fully tested

## 💡 Future Enhancements

- [ ] Auto-watering based on moisture threshold
- [ ] Multiple plant support
- [ ] Battery level monitoring
- [ ] Historical data logging
- [ ] Push notifications
- [ ] Weather API integration
- [ ] Home Assistant integration

---

Made with ❤️ for plants
