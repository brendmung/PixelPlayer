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
 * The force each jolt must exceed is set by [setSensitivityLevel].
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

    // Written from the main thread, read on the sensor callback, hence @Volatile.
    @Volatile
    private var thresholdG = sensitivityToThreshold(DEFAULT_SENSITIVITY_LEVEL)

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

    /**
     * Sets how hard the device must be shaken, where [level] runs from
     * [MIN_SENSITIVITY_LEVEL] (needs a firm shake) to [MAX_SENSITIVITY_LEVEL]
     * (triggers easily). Out-of-range values are clamped.
     */
    fun setSensitivityLevel(level: Int) {
        thresholdG = sensitivityToThreshold(level)
        // Drop any half-counted shake so the new threshold applies cleanly.
        resetJolts()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Magnitude of the acceleration vector expressed in g, with gravity removed.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce < thresholdG) return

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

    companion object {
        const val TAG = "ShakeDetector"
        const val MIN_SENSITIVITY_LEVEL = 1
        const val MAX_SENSITIVITY_LEVEL = 5
        const val DEFAULT_SENSITIVITY_LEVEL = 3

        /**
         * Maps a sensitivity level to the acceleration, in g, a jolt must exceed.
         * Higher sensitivity means a lower threshold: level 1 needs 3.2g, the
         * default level 3 needs 2.3g, and level 5 needs 1.4g.
         */
        fun sensitivityToThreshold(level: Int): Float {
            val clamped = level.coerceIn(MIN_SENSITIVITY_LEVEL, MAX_SENSITIVITY_LEVEL)
            return 3.65f - (0.45f * clamped)
        }
        /** Number of jolts required before a shake is reported. */
        const val REQUIRED_JOLTS = 3
        /** Jolts must all land within this window to count as one shake. */
        const val JOLT_WINDOW_MS = 900L
        /** Muted period after a successful shake. */
        const val COOLDOWN_MS = 1500L
    }
}
