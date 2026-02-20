package dev.plantwatering

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson

class MainActivity : ComponentActivity() {
    private lateinit var mqttManager: MqttManager
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val brokerUrl = "tcp://your-broker.hivemq.cloud:1883"
        mqttManager = MqttManager(brokerUrl, "AndroidClient")

        setContent {
            PlantWateringApp(mqttManager)
        }
    }
}

@Composable
fun PlantWateringApp(mqttManager: MqttManager) {
    var moisture by remember { mutableStateOf(0) }
    var relayState by remember { mutableStateOf("OFF") }
    var isConnected by remember { mutableStateOf(false) }

    // Setup MQTT listener
    LaunchedEffect(Unit) {
        mqttManager.setMessageCallback { topic, message ->
            when (topic) {
                "plant/moisture" -> {
                    try {
                        val data = Gson().fromJson(message, Map::class.java)
                        moisture = (data["moisture"] as? Double)?.toInt() ?: 0
                    } catch (e: Exception) {
                        moisture = message.toIntOrNull() ?: 0
                    }
                }
                "plant/relay/state" -> {
                    relayState = message.uppercase()
                }
            }
        }

        // Connect to MQTT
        mqttManager.connect("your_username", "your_password")
        mqttManager.subscribe("plant/moisture")
        mqttManager.subscribe("plant/relay/state")
        mqttManager.subscribe("plant/status")
        isConnected = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "🌱 Plant Watering System",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Connection Status
        Card {
            Text(
                text = "Status: ${if (isConnected) "🟢 Connected" else "🔴 Disconnected"}",
                modifier = Modifier.padding(16.dp)
            )
        }

        // Moisture Display
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💧 Soil Moisture")
                Text(
                    text = "$moisture%",
                    style = MaterialTheme.typography.displaySmall
                )
                LinearProgressIndicator(
                    progress = moisture / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }

        // Relay Control
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💦 Water Pump")
                Text("Status: $relayState", modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            mqttManager.publish("plant/relay/command", "on")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Turn ON")
                    }

                    Button(
                        onClick = {
                            mqttManager.publish("plant/relay/command", "off")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Turn OFF")
                    }
                }
            }
        }
    }
}