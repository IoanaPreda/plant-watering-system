package dev.plantwatering

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class MoistureReading(val timestamp: Long, val value: Int)

data class PumpEvent(
    val startedAt: Long = 0,
    val stoppedAt: Long = 0,
    val durationSecs: Long = 0
)

class PlantDataStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("plant_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_MOISTURE_HISTORY = "moisture_history"
        private const val KEY_PUMP_LAST_EVENT = "pump_last_event"
        private const val KEY_PUMP_ON_TIMESTAMP = "pump_on_timestamp"
        private const val MAX_MOISTURE_READINGS = 360 // ~1 hour at 10s intervals
    }

    fun loadMoistureHistory(): MutableList<MoistureReading> {
        val json = prefs.getString(KEY_MOISTURE_HISTORY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<MoistureReading>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun addMoistureReading(value: Int) {
        val history = loadMoistureHistory()
        history.add(MoistureReading(System.currentTimeMillis(), value))

        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        history.removeAll { it.timestamp < cutoff }
        while (history.size > MAX_MOISTURE_READINGS) history.removeAt(0)

        prefs.edit().putString(KEY_MOISTURE_HISTORY, gson.toJson(history)).apply()
    }

    fun recordPumpOn() {
        prefs.edit().putLong(KEY_PUMP_ON_TIMESTAMP, System.currentTimeMillis()).apply()
    }

    fun recordPumpOff() {
        val onTs = prefs.getLong(KEY_PUMP_ON_TIMESTAMP, 0)
        if (onTs > 0) {
            val duration = (System.currentTimeMillis() - onTs) / 1000
            val event = PumpEvent(
                startedAt = onTs,
                stoppedAt = System.currentTimeMillis(),
                durationSecs = duration
            )
            prefs.edit()
                .putString(KEY_PUMP_LAST_EVENT, gson.toJson(event))
                .putLong(KEY_PUMP_ON_TIMESTAMP, 0)
                .apply()
        }
    }

    fun getPumpOnTimestamp(): Long = prefs.getLong(KEY_PUMP_ON_TIMESTAMP, 0)

    fun getLastPumpEvent(): PumpEvent? {
        val json = prefs.getString(KEY_PUMP_LAST_EVENT, null) ?: return null
        return try {
            gson.fromJson(json, PumpEvent::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
