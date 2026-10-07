package com.example.sonyremote

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Does nothing by itself. Android only lets an app read other apps' media sessions
 * (the song title and album art) if the app has an enabled notification listener,
 * so this empty one exists to be switched on in Settings > Notification access.
 */
class NowPlayingService : NotificationListenerService()

/** Watches whatever music app is playing on the phone and reports title, artist, art and progress. */
class NowPlaying(private val ctx: Context, private val onTrack: (Track?) -> Unit) {

    /**
     * [positionMs] was true at [updatedAt] (a SystemClock.elapsedRealtime() time); while
     * [playing] the real position keeps moving at [speed] from there.
     */
    class Track(
        val title: String?,
        val artist: String?,
        val art: Bitmap?,
        val artUri: String?,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val updatedAt: Long = 0L,
        val speed: Float = 1f,
        val playing: Boolean = false,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(ctx, NowPlayingService::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            publish()
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list ?: emptyList())
    }

    fun start() {
        stop()
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            started = true
            bind(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // Notification access has not been granted (yet).
            onTrack(null)
        }
    }

    fun stop() {
        if (started) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsChanged)
            } catch (e: Exception) {
            }
            started = false
        }
        for (c in controllers) {
            try {
                c.unregisterCallback(callback)
            } catch (e: Exception) {
            }
        }
        controllers = emptyList()
    }

    /** Plays if paused, pauses if playing, in the music app on the phone. Returns false if there is none. */
    fun togglePlayPause(): Boolean {
        val c = pick() ?: return false
        return try {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun bind(list: List<MediaController>) {
        for (c in controllers) {
            try {
                c.unregisterCallback(callback)
            } catch (e: Exception) {
            }
        }
        controllers = list
        for (c in list) {
            try {
                c.registerCallback(callback, handler)
            } catch (e: Exception) {
            }
        }
        publish()
    }

    // The app that is playing right now, or else the first one that has a song loaded.
    private fun pick(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }

    private fun publish() {
        val c = pick()
        if (c == null) {
            onTrack(null)
            return
        }
        val md = c.metadata
        if (md == null) {
            onTrack(null)
            return
        }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val ps = c.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val position = ps?.position ?: 0L
        val updatedAt = ps?.lastPositionUpdateTime ?: 0L
        val speed = ps?.playbackSpeed ?: 1f
        onTrack(Track(title, artist, art, uri, duration, position, updatedAt, speed, playing))
    }
}
