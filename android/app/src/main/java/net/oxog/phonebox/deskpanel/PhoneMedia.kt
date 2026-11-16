package net.oxog.phonebox.deskpanel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Android lists other apps' media sessions only to an enabled notification listener. This service does nothing else:
 * it reads no notifications, it exists so the user can grant "Notification access" to PhoneBox in the system settings.
 */
class PhoneMediaListener : NotificationListenerService()

/**
 * The phone's own media player for the "Phone now playing" widget: the active media session (Spotify, YouTube Music,
 * podcasts...) on the phone itself, its transport controls and the phone's music volume. Nothing leaves the phone.
 */
class PhoneMedia(private val ctx: Context) {
    private val listener = ComponentName(ctx, PhoneMediaListener::class.java)
    private val sessions by lazy { ctx.getSystemService(MediaSessionManager::class.java) }
    private val audio by lazy { ctx.getSystemService(AudioManager::class.java) }

    /** Whether the user granted Notification access (Settings › Apps › Special app access › Notification access). */
    fun granted(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == listener }
    }

    /** Opens the system page where Notification access is granted; the widget picks it up when the panel resumes. */
    fun openAccessSettings() {
        val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The session to show: one that is playing, else the most recently active one (the list is newest first). */
    private fun controller(): MediaController? {
        if (!granted()) return null
        val list = try { sessions?.getActiveSessions(listener) } catch (_: SecurityException) { null } ?: return null
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }

    private fun appName(pkg: String): String = try {
        ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
    } catch (_: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun MediaMetadata.text(key: String) = getString(key)?.takeIf { it.isNotBlank() }

    /** Changes when the track does; the panel asks for the cover art again only then. */
    private fun artId(c: MediaController, m: MediaMetadata?): Int =
        if (m?.art() == null) 0 else (listOf(c.packageName, m.text(MediaMetadata.METADATA_KEY_TITLE),
            m.text(MediaMetadata.METADATA_KEY_ARTIST), m.text(MediaMetadata.METADATA_KEY_ALBUM)).hashCode() or 1)

    private fun MediaMetadata.art(): Bitmap? =
        getBitmap(MediaMetadata.METADATA_KEY_ART) ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /**
     * {"access": bool, "media": null | {app, title, artist, album, playing, posMs, durMs, canSeek, artId},
     *  "audio": {volume 0..100, muted, canControl}}, the same shape as the computer's media and audio state.
     */
    fun stateJson(): String {
        val out = JSONObject().put("access", granted())
        val c = controller()
        if (c != null) {
            val m = c.metadata
            val s = c.playbackState
            val playing = s?.state == PlaybackState.STATE_PLAYING
            var pos = s?.position ?: 0L
            if (playing && s != null && s.lastPositionUpdateTime > 0)
                pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            out.put("media", JSONObject()
                .put("app", appName(c.packageName))
                .put("title", m?.text(MediaMetadata.METADATA_KEY_TITLE) ?: m?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "")
                .put("artist", m?.text(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
                .put("album", m?.text(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                .put("playing", playing)
                .put("posMs", if (dur > 0) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L))
                .put("durMs", dur.coerceAtLeast(0L))
                .put("canSeek", dur > 0 && ((s?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L)
                .put("artId", artId(c, m)))
        } else out.put("media", JSONObject.NULL)
        val am = audio
        if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            out.put("audio", JSONObject()
                .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
                .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
                .put("canControl", !am.isVolumeFixed))
        }
        return out.toString()
    }

    /** The current cover as a data: URL (at most 512 px), or "" when there is none. */
    fun artDataUrl(): String {
        val bmp = controller()?.metadata?.art() ?: return ""
        return try {
            val scale = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
            val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val bytes = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    /** "playpause", "play", "pause", "next", "prev"; false when nothing is playing or access is missing. */
    fun action(op: String): Boolean {
        val c = controller() ?: return false
        val t = c.transportControls
        when (op) {
            "playpause" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }

    fun seek(ms: Long): Boolean {
        val c = controller() ?: return false
        c.transportControls.seekTo(ms.coerceAtLeast(0L))
        return true
    }

    /** The phone's music volume, 0..100. */
    fun setVolume(level: Int) {
        val am = audio ?: return
        if (am.isVolumeFixed) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    fun toggleMute() {
        val am = audio ?: return
        if (!am.isVolumeFixed) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
    }
}
