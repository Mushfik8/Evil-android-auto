package com.autofreedom.app.renderer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight ExoPlayer wrapper that renders video directly to the Android Auto Surface.
 *
 * Used by WebViewRenderer when a video URL is extracted from a web page.
 * Instead of trying to capture video frames from the WebView (which produces
 * black frames because of hardware acceleration), we extract the video URL
 * and play it directly through ExoPlayer → Surface pipeline.
 *
 * This gives us:
 * - Hardware accelerated video decoding
 * - Full frame rate (30-60fps instead of 15-20fps bitmap copying)
 * - Audio through car speakers
 * - Seek/pause support
 */
class SurfaceVideoPlayer(private val context: Context) {

    companion object {
        private const val TAG = "SurfaceVideoPlayer"
    }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private val isActive = AtomicBoolean(false)

    // Audio focus
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Callbacks
    var onVideoStarted: (() -> Unit)? = null
    var onVideoEnded: (() -> Unit)? = null
    var onVideoError: ((String) -> Unit)? = null
    var onPlaybackChanged: ((Boolean) -> Unit)? = null

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    val currentPosition: Long
        get() = player?.currentPosition ?: 0L

    val duration: Long
        get() = player?.duration ?: 0L

    /**
     * Start playing a video URL directly on the given Surface.
     * This bypasses the WebView entirely — ExoPlayer decodes and renders
     * video frames directly to the Android Auto Surface.
     */
    fun play(videoUrl: String, targetSurface: Surface) {
        Log.i(TAG, "▶️ Playing video URL: $videoUrl")

        // Stop any existing playback
        stop()

        surface = targetSurface
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        player = ExoPlayer.Builder(context)
            .build()
            .apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false
                )

                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                setVideoSurface(targetSurface)

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onPlaybackChanged?.invoke(isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.i(TAG, "Video ready")
                                isActive.set(true)
                                onVideoStarted?.invoke()
                            }
                            Player.STATE_ENDED -> {
                                Log.i(TAG, "Video ended")
                                isActive.set(false)
                                onVideoEnded?.invoke()
                            }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "Video error: ${error.message}")
                        isActive.set(false)
                        onVideoError?.invoke(error.message ?: "Playback error")
                    }
                })

                // Handle different URL formats
                val mediaItem = when {
                    videoUrl.contains(".m3u8") || videoUrl.contains("manifest") -> {
                        // HLS stream (common for Bioscope, Chorki, etc.)
                        MediaItem.Builder()
                            .setUri(videoUrl)
                            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                            .build()
                    }
                    videoUrl.contains(".mpd") -> {
                        // DASH stream
                        MediaItem.Builder()
                            .setUri(videoUrl)
                            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                            .build()
                    }
                    else -> {
                        // Direct URL (MP4, WebM, etc.)
                        MediaItem.fromUri(videoUrl)
                    }
                }

                setMediaItem(mediaItem)
                prepare()
                play()
            }

        Log.i(TAG, "ExoPlayer created and playing: $videoUrl")
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun seekForward(ms: Long = 10_000) {
        player?.let { p -> p.seekTo(minOf(p.duration, p.currentPosition + ms)) }
    }

    fun seekBack(ms: Long = 10_000) {
        player?.let { p -> p.seekTo(maxOf(0, p.currentPosition - ms)) }
    }

    fun stop() {
        isActive.set(false)
        player?.apply {
            setVideoSurface(null)
            release()
        }
        player = null
        abandonAudioFocus()
        Log.i(TAG, "Video stopped")
    }

    fun isVideoActive(): Boolean = isActive.get()

    /**
     * Update the surface (e.g., when it's recreated by Android Auto).
     */
    fun updateSurface(newSurface: Surface?) {
        surface = newSurface
        player?.setVideoSurface(newSurface)
    }

    // ==================== Audio Focus ====================

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener { focus ->
                    when (focus) {
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        AudioManager.AUDIOFOCUS_GAIN -> resume()
                    }
                }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        }
    }
}
