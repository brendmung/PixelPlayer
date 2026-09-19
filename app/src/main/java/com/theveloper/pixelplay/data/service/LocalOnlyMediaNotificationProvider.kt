package com.theveloper.pixelplay.data.service

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Wraps Media3's default provider and marks playback notifications as local-only
 * so they don't get bridged to Wear OS as generic remote media controls.
 *
 * On Android 16+ the same notification is also requested as a "promoted ongoing"
 * notification, which is what the platform renders as the status bar / lock screen
 * live chip (the AOSP equivalent of a dynamic island). Tapping the chip expands it
 * into the media controls, so previous/play-pause/next stay reachable from there.
 */
@UnstableApi
class LocalOnlyMediaNotificationProvider(
    private val context: Context,
    private val delegate: DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider.Builder(context).build(),
) : MediaNotification.Provider {

    fun setSmallIcon(iconResId: Int) {
        delegate.setSmallIcon(iconResId)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val notification = delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            callback
        )
        val decorated = runCatching {
            val builder = Notification.Builder.recoverBuilder(context, notification.notification)
                .setLocalOnly(true)
            // Only a notification that is still ongoing can be promoted; when playback is
            // paused Media3 clears that flag so the user can swipe the notification away,
            // and we leave it cleared rather than making it sticky.
            val isOngoing =
                notification.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            if (Build.VERSION.SDK_INT >= 36 && isOngoing) {
                builder
                    .setColorized(true)
                    .setRequestPromotedOngoing(true)
                    .setShortCriticalText(shortCriticalTextFor(mediaSession))
            }
            builder.build()
        }.getOrElse {
            notification.notification
        }
        return MediaNotification(notification.notificationId, decorated)
    }

    /**
     * Text shown inside the collapsed status bar chip. The platform only has room for
     * a handful of characters there, so the track title is trimmed at a word boundary.
     */
    private fun shortCriticalTextFor(mediaSession: MediaSession): String? {
        val metadata = runCatching { mediaSession.player.mediaMetadata }.getOrNull() ?: return null
        val title = (metadata.title ?: metadata.artist)?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return null
        if (title.length <= SHORT_CRITICAL_TEXT_MAX_LENGTH) return title
        val cut = title.take(SHORT_CRITICAL_TEXT_MAX_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace >= 4) cut.take(lastSpace) else cut.trimEnd()
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.getNotificationChannelInfo()

    private companion object {
        const val SHORT_CRITICAL_TEXT_MAX_LENGTH = 12
    }
}
