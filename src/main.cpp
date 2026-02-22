#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <esp_task_wdt.h>
#include "config.h"

// Hardware pins
#define RELAY_PIN 16
#define MOISTURE_PIN 32

// Relay logic
constexpr uint8_t RELAY_ON  = HIGH;
constexpr uint8_t RELAY_OFF = LOW;

// Moisture calibration (raw ADC values)
const int WET = 1100;   // sensor in water
const int DRY = 2580;   // sensor in air

// MQTT topics
const char* TOPIC_MOISTURE = "plant/moisture";
const char* TOPIC_RELAY_CMD = "plant/relay/command";
const char* TOPIC_RELAY_STATE = "plant/relay/state";
const char* TOPIC_STATUS = "plant/status";

// Timing
unsigned long lastPublish = 0;
const unsigned long PUBLISH_INTERVAL = 10000; // 10 seconds
unsigned long lastMqttAttempt = 0;
const unsigned long MQTT_RETRY_INTERVAL = 5000;
unsigned long lastWifiCheck = 0;
const unsigned long WIFI_CHECK_INTERVAL = 10000;
int mqttFailCount = 0;
const int MAX_MQTT_FAILS = 60; // reboot after ~5 minutes of failures

// WiFi and MQTT clients
WiFiClientSecure espClient;
PubSubClient mqtt(espClient);

// State tracking
bool relayState = false;
int lastMoisture = 0;

// Function prototypes
void ensureWiFi();
bool tryConnectMQTT();
void mqttCallback(char* topic, byte* payload, unsigned int length);
int readMoistureAvg(int samples = 20);
int moisturePercent(int raw);
void setRelay(bool state);
void publishStatus();

void setup() {
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, RELAY_OFF);
  
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n\n=== Plant Watering System with MQTT ===");
  
  // Watchdog: auto-reboot if loop() hangs for 30s
  esp_task_wdt_init(30, true);
  esp_task_wdt_add(NULL);
  
  analogSetAttenuation(ADC_11db);
  
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connecting to WiFi: ");
  Serial.println(WIFI_SSID);

  // Wait up to 15s for initial WiFi (non-fatal if it fails)
  unsigned long wifiStart = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiStart < 15000) {
    delay(500);
    Serial.print(".");
    esp_task_wdt_reset();
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\n✅ WiFi connected!");
    Serial.print("   IP address: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("\n⚠️  WiFi not ready yet, will keep retrying...");
  }
  
  espClient.setInsecure();
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  mqtt.setKeepAlive(60);
  
  Serial.println("Setup complete. System ready.");
  Serial.println("================\n");
}

void loop() {
  esp_task_wdt_reset();
  unsigned long now = millis();

  ensureWiFi();

  if (!mqtt.connected()) {
    if (now - lastMqttAttempt >= MQTT_RETRY_INTERVAL) {
      lastMqttAttempt = now;
      if (!tryConnectMQTT()) {
        mqttFailCount++;
        if (mqttFailCount >= MAX_MQTT_FAILS) {
          Serial.println("Too many MQTT failures, rebooting...");
          delay(1000);
          ESP.restart();
        }
      }
    }
  } else {
    mqttFailCount = 0;
  }

  mqtt.loop();

  if (now - lastPublish >= PUBLISH_INTERVAL) {
    lastPublish = now;
    
    int raw = readMoistureAvg(30);
    int moisture = moisturePercent(raw);
    lastMoisture = moisture;
    
    if (mqtt.connected()) {
      char msg[50];
      snprintf(msg, 50, "{\"moisture\":%d,\"raw\":%d}", moisture, raw);
      mqtt.publish(TOPIC_MOISTURE, msg, true);
      publishStatus();
      Serial.printf("📊 Published: Moisture=%d%% (raw=%d)\n", moisture, raw);
    } else {
      Serial.printf("📊 Read: Moisture=%d%% (raw=%d) [offline]\n", moisture, raw);
    }
  }
  
  delay(100);
}

void ensureWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;

  unsigned long now = millis();
  if (now - lastWifiCheck < WIFI_CHECK_INTERVAL) return;
  lastWifiCheck = now;

  Serial.println("WiFi disconnected, reconnecting...");
  WiFi.disconnect();
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

bool tryConnectMQTT() {
  if (WiFi.status() != WL_CONNECTED) return false;

  Serial.print("Connecting to MQTT broker...");

  String clientId = "ESP32Plant-";
  clientId += String((uint32_t)(ESP.getEfuseMac() & 0xFFFF), HEX);

  if (mqtt.connect(clientId.c_str(), MQTT_USER, MQTT_PASSWORD)) {
    Serial.println(" connected!");
    mqttFailCount = 0;
    mqtt.subscribe(TOPIC_RELAY_CMD);
    Serial.printf("   Subscribed to: %s\n", TOPIC_RELAY_CMD);
    mqtt.publish(TOPIC_STATUS, "{\"status\":\"online\"}", true);
    return true;
  }

  Serial.printf(" failed, rc=%d\n", mqtt.state());
  return false;
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  // Convert payload to string
  String message;
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  message.toLowerCase();
  message.trim();
  
  Serial.printf("📥 Received [%s]: %s\n", topic, message.c_str());
  
  // Handle relay commands
  if (strcmp(topic, TOPIC_RELAY_CMD) == 0) {
    if (message == "on" || message == "1" || message == "true") {
      setRelay(true);
      Serial.println("💧 Relay turned ON (watering)");
    } 
    else if (message == "off" || message == "0" || message == "false") {
      setRelay(false);
      Serial.println("🛑 Relay turned OFF");
    }
    else if (message == "toggle") {
      setRelay(!relayState);
      Serial.printf("🔄 Relay toggled to %s\n", relayState ? "ON" : "OFF");
    }
    else {
      Serial.println("⚠️  Unknown command");
    }
  }
}

void setRelay(bool state) {
  relayState = state;
  digitalWrite(RELAY_PIN, state ? RELAY_ON : RELAY_OFF);
  
  // Publish relay state
  const char* stateStr = state ? "ON" : "OFF";
  mqtt.publish(TOPIC_RELAY_STATE, stateStr, true); // retained
}

int readMoistureAvg(int samples) {
  long sum = 0;
  for (int i = 0; i < samples; i++) {
    sum += analogRead(MOISTURE_PIN);
    delay(5);
  }
  return sum / samples;
}

int moisturePercent(int raw) {
  raw = constrain(raw, WET, DRY);
  return map(raw, WET, DRY, 100, 0);
}

void publishStatus() {
  char msg[100];
  snprintf(msg, 100, 
    "{\"moisture\":%d,\"relay\":\"%s\",\"uptime\":%lu}",
    lastMoisture,
    relayState ? "ON" : "OFF",
    millis() / 1000
  );
  mqtt.publish(TOPIC_STATUS, msg, true);
}
