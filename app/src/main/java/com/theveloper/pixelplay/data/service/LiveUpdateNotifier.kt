package com.theveloper.pixelplay.data.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
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

    /**
     * Notification id the chip is posted under. Media3 owns the service notification, so when the
     * chip replaces it we post updates under Media3's id rather than adding a second card.
     */
    private var notificationId = OWN_NOTIFICATION_ID

    /** Set from the playback notification style preference. */
    var isEnabled: Boolean = false
        private set

    /**
     * Turning the chip off must not cancel Media3's notification, which the chip borrows the id of
     * while it is the service notification - that would drop the service out of the foreground.
     * Only a chip posted under our own id is cancelled; Media3 reposts its own straight after.
     */
    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        if (enabled) {
            refresh()
            return
        }
        stopTicker()
        if (posted && notificationId == OWN_NOTIFICATION_ID) {
            runCatching { notificationManager.cancel(OWN_NOTIFICATION_ID) }
        }
        posted = false
        notificationId = OWN_NOTIFICATION_ID
    }

    /** Index into [EQUALIZER_FRAMES]; advanced on every tick so the chip icon dances. */
    private var equalizerFrame = 0

    /** Album art for [artworkKey], loaded off the main thread and reused across ticks. */
    private var artworkKey: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

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
        bind(player)
        refresh()
    }

    /** Starts listening to [player] without posting anything. */
    private fun bind(player: Player) {
        if (this.player === player) return
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
    }

    fun detach(clearNotification: Boolean = true) {
        player?.removeListener(listener)
        player = null
        artworkJob?.cancel()
        artworkJob = null
        artworkKey = null
        artwork = null
        stopTicker()
        if (clearNotification) cancel()
    }

    /** Re-posts the chip. Called on every track change, play/pause and seek. */
    fun refresh() {
        if (!isEnabled) return
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
        runCatching { notificationManager.cancel(notificationId) }
    }

    /** Drives the progress bar forward while the track plays. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                val current = this@LiveUpdateNotifier.player ?: break
                if (!current.isPlaying) break
                equalizerFrame = (equalizerFrame + 1) % EQUALIZER_FRAMES.size
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
        notificationManager.notify(notificationId, build(player))
        posted = true
    }

    /**
     * Builds the chip so Media3's notification provider can hand it to the platform as the
     * service's own notification, replacing the MediaStyle one. Binds [notificationId] to whatever
     * id Media3 posts under so the ticker keeps updating that same notification.
     */
    fun buildForMediaService(player: Player, mediaNotificationId: Int): Notification? {
        if (!isEnabled) return null
        notificationId = mediaNotificationId
        bind(player)
        return runCatching { build(player) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to build live update notification") }
            .getOrNull()
    }

    private fun build(player: Player): Notification {
        ensureChannel()

        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()?.trim().orEmpty()
            .ifEmpty { context.getString(R.string.app_name) }
        val artist = metadata.artist?.toString()?.trim()
            ?: metadata.albumArtist?.toString()?.trim()

        ensureArtwork(player)

        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        val positionMs = player.currentPosition.coerceAtLeast(0L)

        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(
                Icon.createWithResource(
                    context,
                    if (player.isPlaying) {
                        R.drawable.rounded_play_circle_24
                    } else {
                        R.drawable.rounded_pause_filled_24
                    }
                )
            )
        if (durationMs > 0L) {
            progressStyle
                .addProgressSegment(Notification.ProgressStyle.Segment(durationMs.toInt()))
                .setProgress(positionMs.coerceAtMost(durationMs).toInt())
        } else {
            // Live streams, and tracks whose duration isn't known yet.
            progressStyle
                .addProgressSegment(Notification.ProgressStyle.Segment(1))
                .setProgressIndeterminate(true)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (player.isPlaying) {
                    EQUALIZER_FRAMES[equalizerFrame]
                } else {
                    R.drawable.monochrome_player
                }
            )
            .setContentTitle(title)
            .setContentText(artist)
            .setStyle(progressStyle)
            .setColor(accentColor)
            .setOngoing(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            // Sit in Media3's notification group so the shade shows one bundled media block
            // instead of two unrelated cards.
            .setGroup(DefaultMediaNotificationProvider.GROUP_KEY)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
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
        artwork?.let { builder.setLargeIcon(it) }
        openAppIntent()?.let { builder.setContentIntent(it) }

        val notification = builder.build()
        if (!notification.hasPromotableCharacteristics()) {
            // Warn rather than bail out: the notification is still usable, it just won't be
            // rendered as a chip.
            Timber.tag(TAG).w("Live update notification is not promotable; posting anyway")
        }
        return notification
    }

    /**
     * Loads the current track's album art for the chip's large icon. Keyed by media id so the
     * per-second re-posts reuse one bitmap, and decoded small because the chip only shows a
     * thumbnail.
     */
    private fun ensureArtwork(player: Player) {
        val key = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        if (key == null) {
            artworkKey = null
            artwork = null
            return
        }
        if (key == artworkKey) return

        artworkKey = key
        artwork = null
        artworkJob?.cancel()

        val source = artworkSource(player.mediaMetadata) ?: return
        artworkJob = scope.launch {
            val request = ImageRequest.Builder(context)
                .data(source)
                .size(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
            val bitmap = runCatching {
                context.imageLoader.execute(request).drawable?.toBitmap()
            }.getOrNull()
            if (bitmap != null && artworkKey == key) {
                artwork = bitmap
                refresh()
            }
        }
    }

    private fun artworkSource(metadata: MediaMetadata): Any? =
        metadata.artworkData ?: metadata.artworkUri

    private val accentColor: Int
        get() = ContextCompat.getColor(context, R.color.my_primary)

    /**
     * Text the collapsed status bar chip shows next to the icon. SystemUI drops the text
     * entirely - falling back to icon only - once it no longer fits the status bar, which on a
     * Pixel 7 happens above about nine characters, so the cap here is deliberately tight and
     * measured on device rather than guessed. Uses the track title with any trailing
     * "(Prod. by ...)" / "[feat. ...]" decoration removed, trimmed at a word boundary.
     */
    private fun shortCriticalText(title: String, artist: String?): String {
        val source = stripDecoration(title).ifEmpty { artist?.let(::stripDecoration).orEmpty() }
        if (source.isEmpty()) return context.getString(R.string.app_name)
        if (source.length <= SHORT_CRITICAL_TEXT_MAX_LENGTH) return source
        val cut = source.take(SHORT_CRITICAL_TEXT_MAX_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace >= MIN_WORD_BOUNDARY) cut.take(lastSpace) else cut.trimEnd()
    }

    /** Drops bracketed suffixes such as "(Prod. by X)", "[feat. Y]" or "- Remix". */
    private fun stripDecoration(text: String): String =
        text.substringBefore('(')
            .substringBefore('[')
            .substringBefore(" - ")
            .trim()

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
        const val OWN_NOTIFICATION_ID = 0x9110
        private const val REQUEST_CODE_BASE = 0x9110
        private const val PROGRESS_TICK_MS = 500L
        private const val ARTWORK_SIZE_PX = 256
        private val EQUALIZER_FRAMES = intArrayOf(
            R.drawable.ic_equalizer_frame_1,
            R.drawable.ic_equalizer_frame_2,
            R.drawable.ic_equalizer_frame_3,
            R.drawable.ic_equalizer_frame_4,
        )
        private const val SHORT_CRITICAL_TEXT_MAX_LENGTH = 9
        private const val MIN_WORD_BOUNDARY = 4

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36
    }
}
