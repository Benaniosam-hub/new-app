package com.coverlock.app

import android.app.Notification
import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/**
 * Runs in the background (once Notification access is granted), watches every media session
 * on the phone, and swaps the lock screen wallpaper to the album cover while something plays.
 */
class MediaWatcherService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()   // HD cover downloads, kept off the wallpaper thread

    private var current: List<MediaController> = emptyList()
    private val tracked = ArrayList<Pair<MediaController, MediaController.Callback>>()

    @Volatile private var lastKey: String? = null

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    private val restoreRunnable = Runnable {
        worker.execute {
            try { LockArt.restore(this) } catch (e: Exception) { Log.w(TAG, "restore failed", e) }
            lastKey = null
        }
    }

    // When the user changes style / size / position in the app, redraw right away.
    private val reapplyRunnable = Runnable {
        lastKey = null
        evaluate()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in LockArt.STYLE_KEYS) {
            main.removeCallbacks(reapplyRunnable)
            main.postDelayed(reapplyRunnable, 700)
        }
    }

    override fun onListenerConnected() {
        try {
            LockArt.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
            val msm = getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(this, MediaWatcherService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionListener, me, main)
            attach(msm.getActiveSessions(me))
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access yet", e)
        }
    }

    override fun onListenerDisconnected() {
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Some apps deliver the cover through the notification a moment after the metadata.
        main.post { evaluate() }
    }

    private fun shutdown() {
        try {
            getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        try { LockArt.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        detachAll()
        main.removeCallbacks(restoreRunnable)
        main.removeCallbacks(reapplyRunnable)
        worker.execute { try { LockArt.restore(this) } catch (_: Exception) {} }
    }

    private fun detachAll() {
        tracked.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        tracked.clear()
    }

    private fun attach(list: List<MediaController>) {
        detachAll()
        current = list
        list.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
                override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
            }
            controller.registerCallback(cb, main)
            tracked.add(controller to cb)
        }
        evaluate()
    }

    private fun scheduleRestore(delayMs: Long) {
        main.removeCallbacks(restoreRunnable)
        if (delayMs <= 0) main.post(restoreRunnable) else main.postDelayed(restoreRunnable, delayMs)
    }

    private fun evaluate() {
        if (!LockArt.isEnabled(this)) {
            scheduleRestore(0)
            return
        }

        val playing = current.firstOrNull {
            it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (playing == null) {
            // Short delay so switching tracks doesn't flash the normal wallpaper
            scheduleRestore(1500)
            return
        }
        main.removeCallbacks(restoreRunnable)

        val md = playing.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val key = listOf(
            playing.packageName,
            title,
            md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artist
        ).joinToString("|")
        if (key == lastKey) return

        val art = artFromMetadata(md) ?: artFromNotification(playing.packageName) ?: return
        lastKey = key

        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val uris = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        ).mapNotNull { md?.getString(it) }.filter { it.startsWith("http") }
        val hdOn = LockArt.isHdEnabled(this)

        worker.execute {
            // If we already downloaded a sharp cover for this song, use it straight away.
            val cached = if (hdOn) HdArt.cached(this, title, artist, art.width) else null
            try {
                LockArt.applyCover(this, cached ?: art, title, artist)
            } catch (e: Exception) {
                Log.w(TAG, "apply failed", e)
            }
            if (hdOn && cached == null) {
                // Show the player's cover now; swap in the sharp one when it arrives.
                net.execute {
                    val hd = HdArt.fetch(this, title, artist, album, uris, art.width)
                    if (hd != null) {
                        worker.execute {
                            if (key == lastKey) {
                                try {
                                    LockArt.applyCover(this, hd, title, artist)
                                } catch (e: Exception) {
                                    Log.w(TAG, "HD apply failed", e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun artFromMetadata(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        val uris = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        )
        for (k in uris) {
            val s = md.getString(k) ?: continue
            loadUri(s)?.let { return it }
        }
        return null
    }

    private fun loadUri(s: String): Bitmap? = try {
        val uri = Uri.parse(s)
        if (uri.scheme == "http" || uri.scheme == "https") null
        else contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /** Fallback: use the big picture from the player's media notification. */
    private fun artFromNotification(pkg: String): Bitmap? = try {
        val sbn = activeNotifications.firstOrNull {
            it.packageName == pkg &&
                it.notification.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        }
        val drawable = sbn?.notification?.getLargeIcon()?.loadDrawable(this)
        when (drawable) {
            null -> null
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                drawable.setBounds(0, 0, bmp.width, bmp.height)
                drawable.draw(Canvas(bmp))
                bmp
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "CoverLock"
    }
}
