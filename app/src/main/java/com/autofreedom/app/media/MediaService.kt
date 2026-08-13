package com.autofreedom.app.media

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MediaBrowserService that exposes the device's media library to Android Auto.
 * Android Auto automatically creates playback controls (play/pause/skip/seek)
 * when this service is running.
 *
 * Hierarchy:
 * ROOT
 *   ├── All Songs
 *   ├── All Videos
 *   ├── Folders
 *   │   ├── Music/
 *   │   ├── Download/
 *   │   └── ...
 *   ├── Artists
 *   │   ├── Artist 1
 *   │   └── ...
 *   └── Albums
 *       ├── Album 1
 *       └── ...
 */
class MediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "MediaService"
        private const val ROOT_ID = "root"
        private const val ALL_SONGS_ID = "all_songs"
        private const val ALL_VIDEOS_ID = "all_videos"
        private const val FOLDERS_ID = "folders"
        private const val ARTISTS_ID = "artists"
        private const val ALBUMS_ID = "albums"
        private const val FOLDER_PREFIX = "folder:"
        private const val ARTIST_PREFIX = "artist:"
        private const val ALBUM_PREFIX = "album:"

        var instance: MediaService? = null
            private set
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var scanner: MediaScanner
    lateinit var playbackEngine: PlaybackEngine
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this

        scanner = MediaScanner(this)
        playbackEngine = PlaybackEngine(this)
        playbackEngine.initialize()

        // Create media session
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(MediaSessionCallback())
            isActive = true
        }

        sessionToken = mediaSession.sessionToken

        // Update playback state when playback changes
        playbackEngine.onPlaybackStateChanged = { isPlaying, item ->
            updatePlaybackState(isPlaying)
            item?.let { updateMetadata(it) }
        }

        Log.i(TAG, "MediaService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        playbackEngine.release()
        mediaSession.release()
        Log.i(TAG, "MediaService destroyed")
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()

        serviceScope.launch {
            val items = when (parentId) {
                ROOT_ID -> getRootItems()
                ALL_SONGS_ID -> getAllSongItems()
                ALL_VIDEOS_ID -> getAllVideoItems()
                FOLDERS_ID -> getFolderItems()
                ARTISTS_ID -> getArtistItems()
                ALBUMS_ID -> getAlbumItems()
                else -> {
                    when {
                        parentId.startsWith(FOLDER_PREFIX) ->
                            getItemsInFolder(parentId.removePrefix(FOLDER_PREFIX))
                        parentId.startsWith(ARTIST_PREFIX) ->
                            getItemsByArtist(parentId.removePrefix(ARTIST_PREFIX))
                        else -> mutableListOf()
                    }
                }
            }
            result.sendResult(items)
        }
    }

    // ==================== Browse Tree ====================

    private fun getRootItems(): MutableList<MediaBrowserCompat.MediaItem> {
        return mutableListOf(
            createBrowsableItem(ALL_SONGS_ID, "🎵 All Songs", "All audio files on device"),
            createBrowsableItem(ALL_VIDEOS_ID, "🎬 All Videos", "All video files on device"),
            createBrowsableItem(FOLDERS_ID, "📂 Folders", "Browse by folder"),
            createBrowsableItem(ARTISTS_ID, "🎤 Artists", "Browse by artist"),
            createBrowsableItem(ALBUMS_ID, "💿 Albums", "Browse by album"),
        )
    }

    private suspend fun getAllSongItems(): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getAudioFiles().map { createPlayableItem(it) }.toMutableList()
    }

    private suspend fun getAllVideoItems(): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getVideoFiles().map { createPlayableItem(it) }.toMutableList()
    }

    private suspend fun getFolderItems(): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getMediaFolders().map { folder ->
            createBrowsableItem("$FOLDER_PREFIX$folder", "📁 $folder", "")
        }.toMutableList()
    }

    private suspend fun getArtistItems(): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getArtists().map { artist ->
            createBrowsableItem("$ARTIST_PREFIX$artist", "🎤 $artist", "")
        }.toMutableList()
    }

    private suspend fun getAlbumItems(): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getAlbums().map { album ->
            createBrowsableItem("$ALBUM_PREFIX$album", "💿 $album", "")
        }.toMutableList()
    }

    private suspend fun getItemsInFolder(folder: String): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getFilesInFolder(folder).map { createPlayableItem(it) }.toMutableList()
    }

    private suspend fun getItemsByArtist(artist: String): MutableList<MediaBrowserCompat.MediaItem> {
        return scanner.getFilesByArtist(artist).map { createPlayableItem(it) }.toMutableList()
    }

    // ==================== Item Builders ====================

    private fun createBrowsableItem(
        id: String,
        title: String,
        subtitle: String
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun createPlayableItem(item: MediaScanner.MediaItem): MediaBrowserCompat.MediaItem {
        val icon = if (item.isVideo) "🎬" else "🎵"
        val duration = scanner.formatDuration(item.duration)
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(item.id.toString())
            .setTitle("$icon ${item.title}")
            .setSubtitle("${item.artist} • $duration")
            .setMediaUri(item.uri)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    // ==================== Playback State ====================

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                playbackEngine.currentPosition,
                1.0f
            )
            .build()
        mediaSession.setPlaybackState(state)
    }

    private fun updateMetadata(item: MediaScanner.MediaItem) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, item.id.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, item.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, item.duration)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, item.uri.toString())
            .build()
        mediaSession.setMetadata(metadata)
    }

    // ==================== Session Callbacks ====================

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            playbackEngine.resume()
        }

        override fun onPause() {
            playbackEngine.pause()
        }

        override fun onSkipToNext() {
            playbackEngine.skipToNext()
        }

        override fun onSkipToPrevious() {
            playbackEngine.skipToPrevious()
        }

        override fun onSeekTo(pos: Long) {
            playbackEngine.seekTo(pos)
        }

        override fun onStop() {
            playbackEngine.pause()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return
            serviceScope.launch {
                val allMedia = scanner.getAllMediaFiles()
                val item = allMedia.find { it.id.toString() == mediaId }
                if (item != null) {
                    val index = allMedia.indexOf(item)
                    playbackEngine.playQueue(allMedia, index)
                }
            }
        }
    }
}
