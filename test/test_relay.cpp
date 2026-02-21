#include <Arduino.h>

#define RELAY_PIN 16
#define MOISTURE_PIN 32

// Relay logic (most modules are active LOW)
constexpr uint8_t RELAY_ON  = HIGH;
constexpr uint8_t RELAY_OFF = LOW;

void forceRelayOff() {
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, RELAY_OFF);
}

void setup() {
  // 1) Make relay safe FIRST
  forceRelayOff();

  // 2) Start Serial
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n=== RELAY SAFETY TEST ===");
  Serial.println("Relay is now FORCED OFF");
  Serial.println("\nPHYSICAL CHECK:");
  Serial.println("- Look at relay module LED (should be OFF)");
  Serial.println("- Listen for clicking sound (should be silent)");
  Serial.println("\n✅ If LED is OFF and no clicking = SAFE to insert batteries");
  Serial.println("\n--- Commands ---");
  Serial.println("Type 'off' - Force relay OFF");
  Serial.println("Type 'on'  - Turn relay ON (test only, 2 seconds)");
  Serial.println("Type 'test' - Quick ON/OFF pulse");
  Serial.println("Type 'status' - Check current state");
  Serial.println("================\n");
  
  delay(1000);
  Serial.println(">> Relay is OFF. Ready for commands...\n");
}

void loop() {
  // Check for serial commands
  if (Serial.available() > 0) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    command.toLowerCase();
    
    if (command == "off") {
      digitalWrite(RELAY_PIN, RELAY_OFF);
      Serial.println(">> Relay turned OFF");
      Serial.println("   LED should be OFF, no clicking sound");
      
    } else if (command == "on") {
      Serial.println(">> Turning relay ON for 2 seconds...");
      Serial.println("   You should hear a CLICK and LED turns ON");
      digitalWrite(RELAY_PIN, RELAY_ON);
      delay(1000);
      digitalWrite(RELAY_PIN, RELAY_OFF);
      Serial.println(">> Relay turned OFF again");
      Serial.println("   You should hear another CLICK and LED turns OFF");
      
    } else if (command == "test") {
      Serial.println(">> Quick pulse test (500ms)...");
      digitalWrite(RELAY_PIN, RELAY_ON);
      Serial.println("   ON - listen for click");
      delay(500);
      digitalWrite(RELAY_PIN, RELAY_OFF);
      Serial.println("   OFF - listen for click");
      
    } else if (command == "status") {
      int state = digitalRead(RELAY_PIN);
      Serial.print(">> Current pin state: ");
      Serial.println(state == RELAY_OFF ? "OFF (HIGH)" : "ON (LOW)");
      Serial.println(state == RELAY_OFF ? "   ✅ SAFE" : "   ⚠️  PUMP RUNNING!");
      
    } else if (command != "") {
      Serial.println(">> Unknown command. Use: off, on, test, or status");
    }
  }
  
  delay(100);
}