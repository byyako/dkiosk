package com.byyako.dkiosk.screen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reports something coming close to the proximity sensor, like a hand reaching for the screen.
 * Only a change from far to near counts, so a sensor covered all the time doesn't keep waking it.
 */
class ProximityWake(context: Context, private val onNear: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var near: Boolean? = null

    val isAvailable: Boolean
        get() = sensor != null

    fun start() {
        val proximity = sensor ?: return
        near = null
        sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val proximity = sensor ?: return
        // Many sensors only report "near" (0) or their maximum range; a few report centimetres.
        val isNear = event.values[0] < minOf(proximity.maximumRange, NEAR_CM)
        val wasNear = near
        near = isNear
        if (isNear && wasNear == false) onNear()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val NEAR_CM = 5f
    }
}
