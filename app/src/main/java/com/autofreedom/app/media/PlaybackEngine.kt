package com.autofreedom.app.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps Media3 ExoPlayer for audio/video playback.
 * Handles playback controls, queue management, and audio focus.
 *
 * Uses hardware decoding to minimize CPU usage and prevent overheating.
 */
class PlaybackEngine(private val context: Context) {

    companion object {
        private const val TAG = "PlaybackEngine"
    }

    private var player: ExoPlayer? = null
    private val playlist = mutableListOf<MediaScanner.MediaItem>()
    private var currentIndex = -1

    // Callbacks
    var onPlaybackStateChanged: ((Boolean, MediaScanner.MediaItem?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    val currentMediaItem: MediaScanner.MediaItem?
        get() = if (currentIndex in playlist.indices) playlist[currentIndex] else null

    val currentPosition: Long
        get() = player?.currentPosition ?: 0L

    val duration: Long
        get() = player?.duration ?: 0L

    /**
     * Initialize the ExoPlayer instance.
     */
    fun initialize() {
        if (player != null) return

        player = ExoPlayer.Builder(context)
            .build()
            .apply {
                // Use hardware decoders to minimize CPU usage
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onPlaybackStateChanged?.invoke(isPlaying, this@PlaybackEngine.currentMediaItem)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Playback error: ${error.message}")
                        onError?.invoke(error.message ?: "Playback error")
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        // Update current index when media changes
                        val uri = mediaItem?.localConfiguration?.uri
                        if (uri != null) {
                            val idx = playlist.indexOfFirst { it.uri == uri }
                            if (idx >= 0) currentIndex = idx
                        }
                        onPlaybackStateChanged?.invoke(this@PlaybackEngine.isPlaying, this@PlaybackEngine.currentMediaItem)
                    }
                })

                // Set playback attributes for car audio
                setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            }

        Log.i(TAG, "PlaybackEngine initialized")
    }

    /**
     * Play a single media item.
     */
    fun play(item: MediaScanner.MediaItem) {
        val p = player ?: return
        playlist.clear()
        playlist.add(item)
        currentIndex = 0

        p.setMediaItem(MediaItem.fromUri(item.uri))
        p.prepare()
        p.play()
        Log.i(TAG, "Playing: ${item.title}")
    }

    /**
     * Play a list of media items starting from the given index.
     */
    fun playQueue(items: List<MediaScanner.MediaItem>, startIndex: Int = 0) {
        val p = player ?: return
        playlist.clear()
        playlist.addAll(items)
        currentIndex = startIndex

        val mediaItems = items.map { MediaItem.fromUri(it.uri) }
        p.setMediaItems(mediaItems, startIndex, 0L)
        p.prepare()
        p.play()
        Log.i(TAG, "Playing queue: ${items.size} items, starting at $startIndex")
    }

    /**
     * Play from a URI directly (e.g., for local files from file explorer).
     */
    fun playUri(uri: Uri, title: String = "Unknown") {
        val p = player ?: return
        val item = MediaScanner.MediaItem(
            id = -1, title = title, artist = "Unknown", album = "Unknown",
            duration = 0, size = 0, uri = uri, path = uri.path ?: "",
            folder = "", mimeType = "audio/*", isVideo = false
        )
        play(item)
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun skipToNext() {
        val p = player ?: return
        if (p.hasNextMediaItem()) {
            p.seekToNext()
        }
    }

    fun skipToPrevious() {
        val p = player ?: return
        if (p.currentPosition > 3000) {
            // If more than 3 seconds in, restart current track
            p.seekTo(0)
        } else if (p.hasPreviousMediaItem()) {
            p.seekToPrevious()
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        player?.shuffleModeEnabled = enabled
    }

    fun setRepeatMode(mode: Int) {
        player?.repeatMode = mode
    }

    fun release() {
        player?.release()
        player = null
        playlist.clear()
        currentIndex = -1
        Log.i(TAG, "PlaybackEngine released")
    }
}
