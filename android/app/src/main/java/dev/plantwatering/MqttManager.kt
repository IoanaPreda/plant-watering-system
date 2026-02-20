package dev.plantwatering

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttMessage

class MqttManager(private val brokerUrl: String, private val clientId: String) {
    private var client: MqttClient? = null
    private var callback: ((topic: String, message: String) -> Unit)? = null

    fun connect(username: String, password: String) {
        try {
            client = MqttClient(brokerUrl, clientId, org.eclipse.paho.client.mqttv3.persist.MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                this.userName = username
                this.password = password.toCharArray()
            }

            client?.setCallback(object : MqttCallback {
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload)
                    callback?.invoke(topic, payload)
                }

                override fun connectionLost(cause: Throwable) {
                    // Handle reconnection
                }

                override fun deliveryComplete(token: IMqttDeliveryToken) {}
            })

            client?.connect(options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun subscribe(topic: String) {
        client?.subscribe(topic)
    }

    fun publish(topic: String, message: String) {
        client?.publish(topic, message.toByteArray(), 1, false)
    }

    fun setMessageCallback(handler: (topic: String, message: String) -> Unit) {
        callback = handler
    }

    fun disconnect() {
        client?.disconnect()
    }
}