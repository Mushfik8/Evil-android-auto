package com.autofreedom.app.car.screens

import android.graphics.Rect
import android.net.Uri
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface

/**
 * Dedicated video player screen that renders video directly onto the
 * Android Auto car display using ExoPlayer + Surface rendering.
 *
 * This is for playing local video files from the phone's storage
 * with full video on the car screen (not just audio).
 *
 * Uses NavigationTemplate for Surface access (same as BrowserScreen).
 * ExoPlayer renders directly to the Surface for hardware-accelerated,
 * efficient, no-overheating video playback.
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

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.i(TAG, "VideoPlayer created for: $videoTitle")
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.i(TAG, "VideoPlayer destroyed")
        releasePlayer()
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
        // Tap to toggle play/pause
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                isPaused = true
            } else {
                p.play()
                isPaused = false
            }
            invalidate()
        }
    }

    // ==================== Player ====================

    private fun initializePlayer() {
        val s = surface ?: return

        player = ExoPlayer.Builder(carContext)
            .build()
            .apply {
                // Render video directly to the AA Surface (hardware accelerated)
                setVideoSurface(s)

                // Set media item
                setMediaItem(MediaItem.fromUri(videoUri))

                // Listener for state changes
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPaused = !isPlaying
                        invalidate()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            // Video finished — go back
                            screenManager.pop()
                        }
                    }
                })

                // Prepare and start
                prepare()
                play()
            }

        Log.i(TAG, "ExoPlayer initialized, playing: $videoUri")
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
                    player?.let { p ->
                        p.seekTo(maxOf(0, p.currentPosition - 10_000))
                    }
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
                    player?.let { p ->
                        p.seekTo(minOf(p.duration, p.currentPosition + 10_000))
                    }
                }
                .build()
        )

        // ❌ Close / Back
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_close_clear_cancel)
                    ).build()
                )
                .setOnClickListener {
                    releasePlayer()
                    screenManager.pop()
                }
                .build()
        )

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip.build())
            .build()
    }
}
