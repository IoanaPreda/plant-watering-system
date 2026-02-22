package dev.plantwatering

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class MqttManager(private val brokerUrl: String, private val clientId: String) {
    private var client: MqttClient? = null
    private var messageCallback: ((topic: String, message: String) -> Unit)? = null
    private var connectedCallback: (() -> Unit)? = null
    private var connectionLostCallback: (() -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var credentials: Pair<String, String>? = null
    private val subscribedTopics = mutableListOf<String>()

    fun connect(username: String, password: String) {
        credentials = Pair(username, password)
        performConnect()
    }

    private fun performConnect(): Unit = runInBackground {
        try {
            if (client?.isConnected == true) return@runInBackground

            val (username, password) = credentials ?: return@runInBackground

            client = MqttClient(brokerUrl, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                this.userName = username
                this.password = password.toCharArray()
                connectionTimeout = 10
                keepAliveInterval = 20

                if (brokerUrl.startsWith("ssl://")) {
                    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    tmf.init(null as KeyStore?)
                    val ctx = SSLContext.getInstance("TLSv1.2")
                    ctx.init(null, tmf.trustManagers, null)
                    socketFactory = ctx.socketFactory
                }
            }

            client?.setCallback(object : MqttCallback {
                override fun messageArrived(topic: String, message: MqttMessage) {
                    mainHandler.post { messageCallback?.invoke(topic, String(message.payload)) }
                }
                override fun connectionLost(cause: Throwable) {
                    cause.printStackTrace()
                    mainHandler.post { connectionLostCallback?.invoke() }
                    mainHandler.postDelayed({ performConnect() }, 5000)
                }
                override fun deliveryComplete(token: IMqttDeliveryToken) {}
            })

            client?.connect(options)

            for (topic in subscribedTopics) {
                client?.subscribe(topic)
            }
            mainHandler.post { connectedCallback?.invoke() }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post { connectionLostCallback?.invoke() }
            mainHandler.postDelayed({ performConnect() }, 5000)
        }
    }

    fun subscribe(topic: String) {
        if (!subscribedTopics.contains(topic)) subscribedTopics.add(topic)
        runInBackground { client?.subscribe(topic) }
    }

    fun publish(topic: String, message: String) = runInBackground {
        client?.publish(topic, message.toByteArray(), 1, false)
    }

    fun disconnect() = runInBackground { client?.disconnect() }

    fun setMessageCallback(handler: (topic: String, message: String) -> Unit) {
        messageCallback = handler
    }

    fun onConnected(handler: () -> Unit) {
        connectedCallback = handler
    }

    fun setConnectionLostCallback(handler: () -> Unit) {
        connectionLostCallback = handler
    }

    private fun runInBackground(block: () -> Unit) {
        executor.execute {
            try { block() } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
