package com.autofreedom.app.car.screens

import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
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
import androidx.media3.exoplayer.ExoPlayer
import android.view.Surface

/**
 * Video player screen — renders local video files directly on the car display.
 *
 * FIXED:
 * - ExoPlayer renders directly to Android Auto Surface (hardware accelerated)
 * - Audio focus requested so sound plays through car speakers
 * - Play/Pause, Seek ±10s, Close controls
 * - Tap screen to toggle play/pause
 */
class VideoPlayerScreen(
    carContext: CarContext,
    private val videoUri: Uri,
    private val videoTitle: String = "Video"
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "VideoPlayerScreen"
    }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var isPaused = false

    // Audio focus
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.i(TAG, "VideoPlayer created for: $videoTitle")
        audioManager = carContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.i(TAG, "VideoPlayer destroyed")
        releasePlayer()
        abandonAudioFocus()
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
                        AudioManager.AUDIOFOCUS_LOSS -> player?.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                        AudioManager.AUDIOFOCUS_GAIN -> player?.play()
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

    // ==================== SurfaceCallback ====================

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface available: ${surfaceContainer.width}x${surfaceContainer.height}")
        surface = surfaceContainer.surface
        initializePlayer()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface destroyed")
        releasePlayer()
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

    // ==================== Player ====================

    private fun initializePlayer() {
        val s = surface ?: return

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
                    false // Don't handle audio focus in ExoPlayer — we handle it manually
                )

                // Render to AA Surface
                setVideoSurface(s)
                setMediaItem(MediaItem.fromUri(videoUri))

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPaused = !isPlaying
                        invalidate()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            screenManager.pop()
                        }
                    }
                })

                prepare()
                play()
            }

        Log.i(TAG, "ExoPlayer initialized with audio focus, playing: $videoUri")
    }

    private fun releasePlayer() {
        player?.apply {
            setVideoSurface(null)
            release()
        }
        player = null
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

        // ⏩ Forward 10s
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_ff)
                    ).build()
                )
                .setOnClickListener {
                    player?.let { p -> p.seekTo(minOf(p.duration, p.currentPosition + 10_000)) }
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

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip.build())
            .build()
    }
}
