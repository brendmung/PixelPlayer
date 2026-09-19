package com.theveloper.pixelplay.data.preferences

import android.os.Build

/**
 * Which notification the playback service posts.
 *
 * [MEDIA3] is Media3's own MediaStyle notification: it feeds the system media player in Quick
 * Settings and on the lock screen, but the platform never promotes MediaStyle to the Android 16+
 * status bar chip.
 *
 * [LIVE_CHIP] replaces it with the ProgressStyle live update, which the platform does promote to
 * the chip, at the cost of the system media player.
 */
object PlaybackNotificationStyle {
    const val MEDIA3 = "media3"
    const val LIVE_CHIP = "live_chip"

    /** The live chip needs promoted-ongoing notifications, which arrived in API 36. */
    fun isLiveChipSupported(): Boolean = Build.VERSION.SDK_INT >= 36

    fun sanitize(value: String?): String = when {
        value == LIVE_CHIP && isLiveChipSupported() -> LIVE_CHIP
        else -> MEDIA3
    }
}
