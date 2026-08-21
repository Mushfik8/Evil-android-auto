package com.autofreedom.app.car.screens

import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * Video player screen — renders local video files directly on the car display.
 *
 * ARCHITECTURE:
 * ExoPlayer renders directly to the Android Auto Surface (hardware accelerated).
 * This gives us full video frames on the car screen without any bitmap copying.
 *
 * KEY FIX: Race condition between Surface availability and Player readiness.
 * The Surface and Player are initialized independently. We only connect them
 * when BOTH are ready. This prevents the "gray box" issue where the player
 * tries to render before the surface exists.
 *
 * Also handles:
 * - Audio focus for car speakers
 * - Play/Pause, Seek ±10s/±30s, Close controls
 * - Tap screen to toggle play/pause
 * - Proper cleanup on lifecycle events
 */
class VideoPlayerScreen(
    carContext: CarContext,
    private val videoUri: Uri,
    private val videoTitle: String = "Video"
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "VideoPlayerScreen"
    }

    // Player state — initialized lazily
    private var player: ExoPlayer? = null
    private var isPrepared = false

    // Surface state — provided by Android Auto
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // UI state
    private var isPaused = false
    private var positionText = ""

    // Audio focus
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.i(TAG, "VideoPlayer created for: $videoTitle ($videoUri)")
        audioManager = carContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        // Register for Surface events
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(this)

        // Create player immediately (but don't attach surface yet)
        createPlayer()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.i(TAG, "VideoPlayer destroyed")
        releasePlayer()
        abandonAudioFocus()
    }

    // ==================== Player Creation ====================

    private fun createPlayer() {
        if (player != null) return

        // Request audio focus BEFORE creating player
        requestAudioFocus()

        player = ExoPlayer.Builder(carContext)
            .build()
            .apply {
                // Set audio attributes for car media playback
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false // We handle audio focus manually
                )

                // Video scaling — fit within bounds, maintain aspect ratio
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPaused = !isPlaying
                        invalidate()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_ENDED -> {
                                Log.i(TAG, "Video playback ended")
                                screenManager.pop()
                            }
                            Player.STATE_READY -> {
                                Log.i(TAG, "Video ready to play")
                                isPrepared = true
                            }
                            Player.STATE_BUFFERING -> {
                                Log.i(TAG, "Video buffering...")
                            }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                    }
                })

                // Set the media item
                setMediaItem(MediaItem.fromUri(videoUri))
            }

        Log.i(TAG, "ExoPlayer created (not yet attached to surface)")

        // If surface is already available, connect now
        connectPlayerToSurface()
    }

    /**
     * KEY FIX: Only connect player to surface when BOTH are ready.
     * This prevents the race condition where setVideoSurface is called
     * before the surface exists, causing a gray/black screen.
     */
    private fun connectPlayerToSurface() {
        val p = player ?: return
        val s = surface ?: return

        Log.i(TAG, "Connecting player to surface (${surfaceWidth}x${surfaceHeight})")

        // Attach surface to player
        p.setVideoSurface(s)

        // Now prepare and play
        if (!isPrepared) {
            p.prepare()
            p.play()
            Log.i(TAG, "Player prepared and started on surface")
        } else {
            // Already prepared, just re-attach surface
            if (!isPaused) p.play()
            Log.i(TAG, "Player re-attached to surface")
        }
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
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            player?.pause()
                            isPaused = true
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            player?.pause()
                            isPaused = true
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (isPaused) {
                                player?.play()
                                isPaused = false
                            }
                        }
                    }
                    invalidate()
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

    // ==================== SurfaceCallback ====================

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface available: ${surfaceContainer.width}x${surfaceContainer.height}")
        surface = surfaceContainer.surface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height

        // Player may already be created — connect now
        connectPlayerToSurface()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface destroyed")
        // Detach surface from player (but keep player alive for re-attachment)
        player?.setVideoSurface(null)
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}
    override fun onScroll(distanceX: Float, distanceY: Float) {}
    override fun onFling(velocityX: Float, velocityY: Float) {}
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}

    override fun onClick(x: Float, y: Float) {
        player?.let { p ->
            if (p.isPlaying) p.pause() else p.play()
            isPaused = !p.isPlaying
            invalidate()
        }
    }

    // ==================== Player Controls ====================

    private fun releasePlayer() {
        player?.apply {
            setVideoSurface(null)
            release()
        }
        player = null
        isPrepared = false
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // ==================== Template ====================

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()

        // ⏯ Play/Pause
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(
                            carContext,
                            if (isPaused) android.R.drawable.ic_media_play
                            else android.R.drawable.ic_media_pause
                        )
                    ).build()
                )
                .setOnClickListener {
                    player?.let { p ->
                        if (p.isPlaying) p.pause() else p.play()
                        isPaused = !p.isPlaying
                        invalidate()
                    }
                }
                .build()
        )

        // ⏪ Rewind 10s
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_rew)
                    ).build()
                )
                .setOnClickListener {
                    player?.let { p -> p.seekTo(maxOf(0, p.currentPosition - 10_000)) }
                }
                .build()
        )

        // ⏩ Forward 30s
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_ff)
                    ).build()
                )
                .setOnClickListener {
                    player?.let { p -> p.seekTo(minOf(p.duration, p.currentPosition + 30_000)) }
                }
                .build()
        )

        // ❌ Close
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_close_clear_cancel)
                    ).build()
                )
                .setOnClickListener {
                    releasePlayer()
                    abandonAudioFocus()
                    screenManager.pop()
                }
                .build()
        )

        // Bottom action strip with position info
        val mapActionStrip = ActionStrip.Builder()

        // Show current position / duration
        val p = player
        val posInfo = if (p != null && p.duration > 0) {
            "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}"
        } else {
            "Loading..."
        }

        mapActionStrip.addAction(
            Action.Builder()
                .setTitle(posInfo)
                .setOnClickListener {
                    // Refresh position display
                    invalidate()
                }
                .build()
        )

        // Title display
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle(videoTitle.take(20))
                .setOnClickListener { }
                .build()
        )

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip.build())
            .setMapActionStrip(mapActionStrip.build())
            .build()
    }
}
