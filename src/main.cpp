#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include "config.h"

// Hardware pins
#define RELAY_PIN 16
#define MOISTURE_PIN 32

// Relay logic
constexpr uint8_t RELAY_ON  = HIGH;
constexpr uint8_t RELAY_OFF = LOW;

// Moisture calibration
const int DRY = 2600;
const int WET = 1100;

// MQTT topics
const char* TOPIC_MOISTURE = "plant/moisture";
const char* TOPIC_RELAY_CMD = "plant/relay/command";
const char* TOPIC_RELAY_STATE = "plant/relay/state";
const char* TOPIC_STATUS = "plant/status";

// Timing
unsigned long lastPublish = 0;
const unsigned long PUBLISH_INTERVAL = 10000; // 10 seconds

// WiFi and MQTT clients
WiFiClientSecure espClient;
PubSubClient mqtt(espClient);

// State tracking
bool relayState = false;
int lastMoisture = 0;

// Function prototypes
void setupWiFi();
void reconnectMQTT();
void mqttCallback(char* topic, byte* payload, unsigned int length);
int readMoistureAvg(int samples = 20);
int moisturePercent(int raw);
void setRelay(bool state);
void publishStatus();

void setup() {
  // 1) Safety first - turn off relay
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, RELAY_OFF);
  
  // 2) Initialize serial
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n\n=== Plant Watering System with MQTT ===");
  
  // 3) Configure analog reading
  analogSetAttenuation(ADC_11db);
  
  // 4) Connect to WiFi
  setupWiFi();
  
  // 5) Setup MQTT (HiveMQ Cloud requires TLS on port 8883)
  espClient.setInsecure();
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  
  Serial.println("Setup complete. System ready.");
  Serial.println("================\n");
}

void loop() {
  // Ensure MQTT connection
  if (!mqtt.connected()) {
    reconnectMQTT();
  }
  mqtt.loop();
  
  // Publish sensor data periodically
  unsigned long now = millis();
  if (now - lastPublish >= PUBLISH_INTERVAL) {
    lastPublish = now;
    
    // Read moisture sensor
    int raw = readMoistureAvg(30);
    int moisture = moisturePercent(raw);
    lastMoisture = moisture;
    
    // Publish moisture data
    char msg[50];
    snprintf(msg, 50, "{\"moisture\":%d,\"raw\":%d}", moisture, raw);
    mqtt.publish(TOPIC_MOISTURE, msg, true); // retained
    
    Serial.printf("📊 Published: Moisture=%d%% (raw=%d)\n", moisture, raw);
    
    // Publish system status
    publishStatus();
  }
  
  delay(100);
}

void setupWiFi() {
  Serial.print("Connecting to WiFi: ");
  Serial.println(WIFI_SSID);
  
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\n✅ WiFi connected!");
    Serial.print("   IP address: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("\n❌ WiFi connection failed!");
  }
}

void reconnectMQTT() {
  while (!mqtt.connected()) {
    Serial.print("Connecting to MQTT broker...");
    
    // Create a unique client ID
    String clientId = "ESP32Plant-";
    clientId += String(random(0xffff), HEX);
    
    // Attempt to connect
    if (mqtt.connect(clientId.c_str(), MQTT_USER, MQTT_PASSWORD)) {
      Serial.println(" connected!");
      
      // Subscribe to command topic
      mqtt.subscribe(TOPIC_RELAY_CMD);
      Serial.printf("   Subscribed to: %s\n", TOPIC_RELAY_CMD);
      
      // Publish online status
      mqtt.publish(TOPIC_STATUS, "{\"status\":\"online\"}", true);
      
    } else {
      Serial.print(" failed, rc=");
      Serial.print(mqtt.state());
      Serial.println(" retrying in 5 seconds...");
      delay(5000);
    }
  }
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
  return map(raw, DRY, WET, 0, 100);
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
