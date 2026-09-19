package com.theveloper.pixelplay.data.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.theveloper.pixelplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Posts the "Live Update" notification that Android 16+ promotes into the status bar and
 * lock screen chip (FLAG_PROMOTED_ONGOING) - the AOSP equivalent of a dynamic island.
 *
 * The playback notification Media3 posts cannot be used for this: the platform only promotes
 * notifications whose style is one of null / BigPicture / BigText / Call / Progress
 * (Notification.hasPromotableStyle), and Media3 uses MediaStyle. So this posts a second,
 * separate ProgressStyle notification carrying the track, a progress bar for the playback
 * position, and previous / play-pause / next actions, which is what the chip expands to.
 *
 * The other characteristics the platform requires are read straight off
 * Notification.hasPromotableCharacteristics, which on Android 17 is:
 *
 *     isRequestPromotedOngoing() && isOngoingEvent() && hasTitle() && hasPromotableStyle()
 *         && !isGroupSummary() && !containsCustomViews() && !isColorizedRequested() && !isBridged()
 *
 * Note the two traps. setRequestPromotedOngoing(true) is mandatory, not a hint; and the
 * notification must NOT request colorization - that condition is negated, the opposite of what
 * Android 16 documented - so setColor() is fine but colorization must never be requested here.
 * The channel importance must also stay above IMPORTANCE_MIN.
 */
@RequiresApi(36)
class LiveUpdateNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    private var player: Player? = null
    private var tickerJob: Job? = null
    private var channelCreated = false
    private var posted = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                refresh()
            }
        }
    }

    fun attach(player: Player) {
        if (this.player === player) {
            refresh()
            return
        }
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
        refresh()
    }

    fun detach(clearNotification: Boolean = true) {
        player?.removeListener(listener)
        player = null
        stopTicker()
        if (clearNotification) cancel()
    }

    /** Re-posts the chip. Called on every track change, play/pause and seek. */
    fun refresh() {
        val current = player
        if (current == null) {
            cancel()
            return
        }
        val hasTrack = current.currentMediaItem != null &&
            current.playbackState != Player.STATE_IDLE &&
            current.playbackState != Player.STATE_ENDED
        if (!hasTrack) {
            cancel()
            return
        }

        runCatching { post(current) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to post live update notification") }

        if (current.isPlaying) startTicker() else stopTicker()
    }

    fun cancel() {
        stopTicker()
        if (!posted) return
        posted = false
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
    }

    /** Drives the progress bar forward while the track plays. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                val current = this@LiveUpdateNotifier.player ?: break
                if (!current.isPlaying) break
                runCatching { post(current) }
                    .onFailure { Timber.tag(TAG).w(it, "Live update progress tick failed") }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    @SuppressLint("MissingPermission")
    private fun post(player: Player) {
        ensureChannel()

        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()?.trim().orEmpty()
            .ifEmpty { context.getString(R.string.app_name) }
        val artist = metadata.artist?.toString()?.trim()
            ?: metadata.albumArtist?.toString()?.trim()

        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        val positionMs = player.currentPosition.coerceAtLeast(0L)

        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgressTrackerIcon(
                Icon.createWithResource(context, R.drawable.rounded_play_circle_24)
            )
        if (durationMs > 0L) {
            progressStyle
                .addProgressSegment(
                    Notification.ProgressStyle.Segment(durationMs.toInt()).setColor(accentColor)
                )
                .setProgress(positionMs.coerceAtMost(durationMs).toInt())
        } else {
            // Live streams, and tracks whose duration isn't known yet.
            progressStyle
                .addProgressSegment(Notification.ProgressStyle.Segment(1).setColor(accentColor))
                .setProgressIndeterminate(true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(title)
            .setContentText(artist)
            .setStyle(progressStyle)
            .setColor(accentColor)
            .setOngoing(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShortCriticalText(shortCriticalText(title, artist))
            .setRequestPromotedOngoing(true)
            .addAction(
                action(
                    R.drawable.rounded_skip_previous_24,
                    R.string.live_update_action_previous,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                )
            )
            .addAction(
                if (player.isPlaying) {
                    action(
                        R.drawable.rounded_pause_24,
                        R.string.live_update_action_pause,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                    )
                } else {
                    action(
                        R.drawable.rounded_play_arrow_24,
                        R.string.live_update_action_play,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                    )
                }
            )
            .addAction(
                action(
                    R.drawable.rounded_skip_next_24,
                    R.string.live_update_action_next,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                )
            )
        openAppIntent()?.let { builder.setContentIntent(it) }

        val notification = builder.build()
        if (!notification.hasPromotableCharacteristics()) {
            // Warn rather than bail out: the notification is still usable, it just won't be
            // rendered as a chip.
            Timber.tag(TAG).w("Live update notification is not promotable; posting anyway")
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
        posted = true
    }

    private val accentColor: Int
        get() = ContextCompat.getColor(context, R.color.my_primary)

    /** Text the collapsed status bar chip shows next to the icon; only a few characters fit. */
    private fun shortCriticalText(title: String, artist: String?): String {
        val source = artist?.takeIf { it.isNotEmpty() } ?: title
        if (source.length <= SHORT_CRITICAL_TEXT_MAX_LENGTH) return source
        val cut = source.take(SHORT_CRITICAL_TEXT_MAX_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace >= 4) cut.take(lastSpace) else cut.trimEnd()
    }

    private fun action(iconRes: Int, labelRes: Int, keyCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, iconRes),
            context.getString(labelRes),
            mediaButtonIntent(keyCode),
        ).build()

    /**
     * Routes through [PixelPlayMediaButtonReceiver] rather than starting MusicService directly:
     * that receiver already handles the foreground-start restrictions that apply when a button
     * is tapped while the service is not in the foreground.
     */
    private fun mediaButtonIntent(keyCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(context, PixelPlayMediaButtonReceiver::class.java)
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + keyCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_BASE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The channel has to stay above IMPORTANCE_MIN to be promotable, so it is DEFAULT importance
     * with sound and vibration stripped - otherwise every track change would make a noise.
     */
    private fun ensureChannel() {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.live_update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.live_update_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        channelCreated = true
    }

    companion object {
        private const val TAG = "LiveUpdateNotifier"
        const val CHANNEL_ID = "pixelplay_live_update"
        const val NOTIFICATION_ID = 0x9110
        private const val REQUEST_CODE_BASE = 0x9110
        private const val PROGRESS_TICK_MS = 1_000L
        private const val SHORT_CRITICAL_TEXT_MAX_LENGTH = 12

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36
    }
}
