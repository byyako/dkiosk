package com.byyako.dkiosk.remote

import android.util.Log
import com.byyako.dkiosk.remote.mqtt.MqttClient
import com.byyako.dkiosk.remote.mqtt.MqttPackets
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Connects the kiosk to Home Assistant over MQTT: announces its entities, reports its state and
 * runs commands. Commands and state reports run on a worker thread, because [KioskControl] calls
 * wait for the main thread.
 */
class MqttBridge(
    settings: Settings,
    private val ha: HomeAssistant,
    private val control: KioskControl,
    private val screenshotsAllowed: () -> Boolean,
) : MqttClient.Listener {

    class Settings(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val username: String?,
        val password: String?,
        val clientId: String,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "mqtt-commands") }
    private val homeAssistantStatus = "homeassistant/status"
    private var pendingState: ScheduledFuture<*>? = null
    private val client = MqttClient(
        MqttClient.Config(
            host = settings.host,
            port = settings.port,
            tls = settings.tls,
            username = settings.username,
            password = settings.password,
            clientId = settings.clientId,
            will = MqttPackets.Will(ha.availabilityTopic, HomeAssistant.OFFLINE.toByteArray(), retain = true),
            subscriptions = listOf(ha.commandFilter, homeAssistantStatus),
        ),
        this,
    )

    fun start() {
        client.start()
        // Battery and the like change without any event; report them now and then anyway.
        worker.scheduleWithFixedDelay(::publishState, STATE_EVERY_S, STATE_EVERY_S, TimeUnit.SECONDS)
    }

    fun stop() {
        worker.shutdownNow()
        client.stop(ha.availabilityTopic to HomeAssistant.OFFLINE.toByteArray())
    }

    /** Something Home Assistant shows has changed; report it soon, once for a burst of changes. */
    @Synchronized
    fun stateChanged() {
        if (pendingState?.isDone == false) return
        pendingState = try {
            worker.schedule(::publishState, 500, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    override fun onConnected() {
        client.publish(ha.availabilityTopic, HomeAssistant.ONLINE.toByteArray(), retain = true)
        announce()
    }

    override fun onMessage(topic: String, payload: ByteArray) {
        if (topic == homeAssistantStatus) {
            // Home Assistant restarted and forgot non-retained state; tell it again.
            if (payload.toString(Charsets.UTF_8) == HomeAssistant.ONLINE) announce()
            return
        }
        run {
            when (val result = ha.handle(topic, payload, control)) {
                is HomeAssistant.Result.Screenshot -> client.publish(ha.screenshotTopic, result.jpeg, retain = false)
                is HomeAssistant.Result.Failed -> Log.w(TAG, "MQTT command on $topic failed: ${result.reason}")
                HomeAssistant.Result.Done, null -> Unit
            }
            publishState()
        }
    }

    override fun onStatus(status: String) {
        MqttStatus.update(status)
    }

    private fun announce() = run {
        ha.discovery(screenshotsAllowed()).forEach { (topic, config) ->
            client.publish(topic, config.toByteArray(), retain = true)
        }
        publishState()
    }

    private fun publishState() {
        try {
            client.publish(ha.stateTopic, control.status().toString().toByteArray(), retain = true)
        } catch (e: Exception) {
            // The dashboard is pausing or busy; the next report catches up.
            Log.d(TAG, "No state to report: $e")
        }
    }

    private fun run(task: () -> Unit) {
        try {
            worker.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "MQTT task failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Stopped.
        }
    }

    private companion object {
        const val TAG = "MqttBridge"
        const val STATE_EVERY_S = 60L
    }
}

/** The last MQTT connection state, kept for the settings screen (which pauses the connection). */
object MqttStatus {
    @Volatile var text: String? = null
        private set

    @Volatile var at: Long = 0
        private set

    fun update(status: String) {
        // "Stopped" just means the dashboard paused; the state before it is more useful to show.
        if (status == MqttClient.STATUS_STOPPED) return
        text = status
        at = System.currentTimeMillis()
    }
}
