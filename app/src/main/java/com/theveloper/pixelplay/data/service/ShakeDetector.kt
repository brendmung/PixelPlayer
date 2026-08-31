package com.theveloper.pixelplay.data.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt
import timber.log.Timber

/**
 * Detects a deliberate device shake from the accelerometer and reports it via [onShake].
 *
 * A shake is only reported once [REQUIRED_JOLTS] separate jolts land inside
 * [JOLT_WINDOW_MS] of each other, which keeps ordinary pocket movement and single
 * bumps from triggering it. After firing, further detection is muted for
 * [COOLDOWN_MS] so one long shake cannot skip several songs at once.
 *
 * Registration is inert until [start] is called, so no sensor is held while the
 * feature is disabled.
 */
class ShakeDetector(
    private val context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    // Sensor callbacks are delivered on the main thread so [onShake] can touch the player directly.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListening = false
    private var joltCount = 0
    private var firstJoltAt = 0L
    private var lastShakeAt = 0L

    /** True when the device actually exposes an accelerometer. */
    val isSupported: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun start() {
        if (isListening) return
        val manager = sensorManager ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Timber.tag(TAG).w("No accelerometer available; shake to skip is unavailable")
            return
        }
        manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, mainHandler)
        isListening = true
        resetJolts()
        Timber.tag(TAG).d("Shake detection started")
    }

    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        resetJolts()
        Timber.tag(TAG).d("Shake detection stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Magnitude of the acceleration vector expressed in g, with gravity removed.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce < SHAKE_THRESHOLD_G) return

        val now = System.currentTimeMillis()
        if (now - lastShakeAt < COOLDOWN_MS) return

        if (joltCount == 0 || now - firstJoltAt > JOLT_WINDOW_MS) {
            joltCount = 1
            firstJoltAt = now
            return
        }

        joltCount++
        if (joltCount < REQUIRED_JOLTS) return

        lastShakeAt = now
        resetJolts()
        Timber.tag(TAG).d("Shake detected (%.2fg)", gForce)
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetJolts() {
        joltCount = 0
        firstJoltAt = 0L
    }

    private companion object {
        const val TAG = "ShakeDetector"
        /** Acceleration, in g, that a single jolt must exceed. */
        const val SHAKE_THRESHOLD_G = 2.3f
        /** Number of jolts required before a shake is reported. */
        const val REQUIRED_JOLTS = 3
        /** Jolts must all land within this window to count as one shake. */
        const val JOLT_WINDOW_MS = 900L
        /** Muted period after a successful shake. */
        const val COOLDOWN_MS = 1500L
    }
}
