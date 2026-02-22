package dev.plantwatering

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var mqttManager: MqttManager
    private lateinit var dataStore: PlantDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mqttManager = MqttManager(BuildConfig.MQTT_BROKER_URL, "AndroidClient")
        dataStore = PlantDataStore(this)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlantWateringApp(mqttManager, dataStore)
                }
            }
        }
    }
}

enum class DeviceState { CHECKING, ONLINE, OFFLINE }

data class Esp32Info(
    val uptime: Long = 0,
    val lastSeenMs: Long = 0
)

@Composable
fun PlantWateringApp(mqttManager: MqttManager, dataStore: PlantDataStore) {
    var moisture by remember { mutableIntStateOf(0) }
    var relayState by remember { mutableStateOf("OFF") }
    var previousRelayState by remember { mutableStateOf<String?>(null) }
    var appConnected by remember { mutableStateOf(false) }
    var esp32Info by remember { mutableStateOf(Esp32Info()) }
    var lastStatusReceived by remember { mutableLongStateOf(0L) }

    // Retained-message detection: the first status message after subscribing
    // is almost certainly retained (stale). Only trust the ESP32 is live after
    // receiving a second message, which can only come from a live device.
    var statusMsgCount by remember { mutableIntStateOf(0) }
    var esp32MarkedOffline by remember { mutableStateOf(false) }

    val moistureHistory = remember {
        mutableStateListOf<MoistureReading>().also { list ->
            list.addAll(dataStore.loadMoistureHistory())
        }
    }

    var pumpOnTimestamp by remember { mutableLongStateOf(dataStore.getPumpOnTimestamp()) }
    var lastPumpEvent by remember { mutableStateOf(dataStore.getLastPumpEvent()) }

    var tickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            tickMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) {
        mqttManager.setMessageCallback { topic, message ->
            when (topic) {
                "plant/moisture" -> {
                    val m = try {
                        val data = Gson().fromJson(message, Map::class.java)
                        (data["moisture"] as? Double)?.toInt() ?: 0
                    } catch (_: Exception) {
                        message.toIntOrNull() ?: 0
                    }
                    moisture = m
                    dataStore.addMoistureReading(m)
                    val fresh = dataStore.loadMoistureHistory()
                    moistureHistory.clear()
                    moistureHistory.addAll(fresh)
                }
                "plant/relay/state" -> {
                    val newState = message.uppercase()

                    if (previousRelayState != null && newState != previousRelayState) {
                        if (newState == "ON") {
                            dataStore.recordPumpOn()
                            pumpOnTimestamp = System.currentTimeMillis()
                        } else if (newState == "OFF") {
                            dataStore.recordPumpOff()
                            pumpOnTimestamp = 0
                            lastPumpEvent = dataStore.getLastPumpEvent()
                        }
                    } else if (previousRelayState == null) {
                        if (newState == "ON" && dataStore.getPumpOnTimestamp() == 0L) {
                            dataStore.recordPumpOn()
                            pumpOnTimestamp = System.currentTimeMillis()
                        }
                    }

                    previousRelayState = newState
                    relayState = newState
                }
                "plant/status" -> {
                    statusMsgCount++
                    lastStatusReceived = System.currentTimeMillis()
                    try {
                        val data = Gson().fromJson(message, Map::class.java)
                        val statusField = data["status"] as? String
                        if (statusField == "offline") {
                            esp32MarkedOffline = true
                        } else {
                            if (statusMsgCount >= 2) {
                                esp32MarkedOffline = false
                            }
                            esp32Info = esp32Info.copy(
                                uptime = (data["uptime"] as? Double)?.toLong() ?: esp32Info.uptime,
                                lastSeenMs = System.currentTimeMillis()
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        mqttManager.setConnectionLostCallback {
            appConnected = false
            statusMsgCount = 0
        }

        mqttManager.onConnected {
            statusMsgCount = 0
            esp32MarkedOffline = false
            mqttManager.subscribe("plant/moisture")
            mqttManager.subscribe("plant/relay/state")
            mqttManager.subscribe("plant/status")
            appConnected = true
        }

        mqttManager.connect(BuildConfig.MQTT_USERNAME, BuildConfig.MQTT_PASSWORD)
    }

    val esp32State: DeviceState = when {
        !appConnected -> DeviceState.OFFLINE
        esp32MarkedOffline -> DeviceState.OFFLINE
        statusMsgCount >= 2 && (tickMs - lastStatusReceived < 25_000) -> DeviceState.ONLINE
        // Got one message (likely retained), waited 20s with no second → offline
        statusMsgCount == 1 && (tickMs - lastStatusReceived > 20_000) -> DeviceState.OFFLINE
        // Got two+ messages but nothing recent → went offline mid-session
        statusMsgCount >= 2 && (tickMs - lastStatusReceived >= 25_000) -> DeviceState.OFFLINE
        // Still waiting for a second message to confirm
        else -> DeviceState.CHECKING
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Plant Watering System",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        DeviceStatusCard(appConnected, esp32State, esp32Info, tickMs)
        MoistureCard(moisture, moistureHistory)
        PumpCard(
            relayState = relayState,
            esp32Online = esp32State == DeviceState.ONLINE,
            pumpOnTimestamp = pumpOnTimestamp,
            lastPumpEvent = lastPumpEvent,
            tickMs = tickMs,
            onToggle = { turnOn ->
                relayState = if (turnOn) "ON" else "OFF"
                mqttManager.publish("plant/relay/command", if (turnOn) "on" else "off")
            }
        )
    }
}

@Composable
fun DeviceStatusCard(
    appConnected: Boolean,
    esp32State: DeviceState,
    info: Esp32Info,
    tickMs: Long
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Device Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            StatusRow("App \u2192 Broker", if (appConnected) DeviceState.ONLINE else DeviceState.OFFLINE)
            StatusRow("ESP32", esp32State)

            when (esp32State) {
                DeviceState.ONLINE -> {
                    Text(
                        "Uptime: ${formatDuration(info.uptime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DeviceState.OFFLINE -> if (appConnected) {
                    if (info.lastSeenMs > 0) {
                        val ago = (tickMs - info.lastSeenMs) / 1000
                        Text(
                            "Last seen: ${formatDuration(ago)} ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "Check: power supply, WiFi, network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                DeviceState.CHECKING -> {
                    Text(
                        "Verifying connection\u2026",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, state: DeviceState) {
    val (dotTarget, text) = when (state) {
        DeviceState.ONLINE -> Color(0xFF4CAF50) to "Connected"
        DeviceState.OFFLINE -> Color(0xFFF44336) to "Disconnected"
        DeviceState.CHECKING -> Color(0xFFFFC107) to "Checking\u2026"
    }
    val dotColor by animateColorAsState(dotTarget, label = "statusDot")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = dotColor)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = dotTarget)
    }
}

@Composable
fun MoistureCard(moisture: Int, history: List<MoistureReading>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Soil Moisture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "$moisture%",
                style = MaterialTheme.typography.displaySmall
            )
            LinearProgressIndicator(
                progress = { moisture / 100f },
                modifier = Modifier.fillMaxWidth()
            )

            if (history.size >= 2) {
                Spacer(Modifier.height(4.dp))
                val spanDesc = describeTimeSpan(history.first().timestamp, history.last().timestamp)
                Text(
                    "History ($spanDesc)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MoistureChart(
                    readings = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            }
        }
    }
}

@Composable
fun MoistureChart(readings: List<MoistureReading>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padLeft = 48f
        val padBottom = 4f
        val chartW = w - padLeft
        val chartH = h - padBottom

        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 28f
            isAntiAlias = true
        }

        for (pct in listOf(0, 25, 50, 75, 100)) {
            val y = chartH - (pct / 100f) * chartH
            drawLine(gridColor, Offset(padLeft, y), Offset(w, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                "${pct}%", 0f, y + 10f, paint
            )
        }

        if (readings.size < 2) return@Canvas

        val path = Path()
        val step = chartW / (readings.size - 1).coerceAtLeast(1)
        readings.forEachIndexed { i, reading ->
            val x = padLeft + i * step
            val y = chartH - (reading.value.coerceIn(0, 100) / 100f) * chartH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path, lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun PumpCard(
    relayState: String,
    esp32Online: Boolean,
    pumpOnTimestamp: Long,
    lastPumpEvent: PumpEvent?,
    tickMs: Long,
    onToggle: (Boolean) -> Unit
) {
    val isOn = relayState == "ON"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Water Pump",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isOn) "Running" else "Stopped",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isOn) Color(0xFF2196F3)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = isOn,
                    onCheckedChange = { checked -> onToggle(checked) },
                    enabled = esp32Online
                )
            }

            val hasPumpData = isOn || lastPumpEvent != null
            if (hasPumpData) {
                HorizontalDivider()
                if (isOn && pumpOnTimestamp > 0) {
                    val runningSecs = (tickMs - pumpOnTimestamp) / 1000
                    Text(
                        "Running for: ${formatDuration(runningSecs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2196F3)
                    )
                    Text(
                        "Started: ${formatTimestamp(pumpOnTimestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!isOn && lastPumpEvent != null) {
                    val ago = (tickMs - lastPumpEvent.stoppedAt) / 1000
                    Text(
                        "Last turned on: ${formatTimestamp(lastPumpEvent.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Ran for: ${formatDuration(lastPumpEvent.durationSecs)} (${formatDuration(ago)} ago)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "unknown"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0) append("${hours}h ")
        if (mins > 0) append("${mins}m ")
        if (days == 0L && hours == 0L) append("${secs}s")
    }.trim()
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

fun formatTimestamp(ms: Long): String {
    val now = System.currentTimeMillis()
    val dayMs = 24 * 60 * 60 * 1000L
    return if (now - ms < dayMs) timeFormat.format(Date(ms))
    else dateTimeFormat.format(Date(ms))
}

fun describeTimeSpan(startMs: Long, endMs: Long): String {
    val span = (endMs - startMs) / 1000
    return "last ${formatDuration(span)}"
}
