package com.autofreedom.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans device storage for media files using MediaStore API.
 * Categorizes files by type (audio/video), folder, artist, and album.
 * Caches results to avoid repeated scans.
 */
class MediaScanner(private val context: Context) {

    companion object {
        private const val TAG = "MediaScanner"
    }

    data class MediaItem(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val size: Long,
        val uri: Uri,
        val path: String,
        val folder: String,
        val mimeType: String,
        val isVideo: Boolean
    )

    private var cachedAudioFiles: List<MediaItem>? = null
    private var cachedVideoFiles: List<MediaItem>? = null

    /**
     * Get all audio files from device storage.
     */
    suspend fun getAudioFiles(forceRefresh: Boolean = false): List<MediaItem> {
        if (!forceRefresh && cachedAudioFiles != null) return cachedAudioFiles!!

        return withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            try {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, null, sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val path = cursor.getString(dataCol) ?: continue
                        val folder = path.substringBeforeLast("/").substringAfterLast("/")

                        items.add(
                            MediaItem(
                                id = id,
                                title = cursor.getString(titleCol) ?: "Unknown",
                                artist = cursor.getString(artistCol) ?: "Unknown Artist",
                                album = cursor.getString(albumCol) ?: "Unknown Album",
                                duration = cursor.getLong(durationCol),
                                size = cursor.getLong(sizeCol),
                                uri = ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                                ),
                                path = path,
                                folder = folder,
                                mimeType = cursor.getString(mimeCol) ?: "audio/*",
                                isVideo = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning audio files", e)
            }

            cachedAudioFiles = items
            Log.i(TAG, "Found ${items.size} audio files")
            items
        }
    }

    /**
     * Get all video files from device storage.
     */
    suspend fun getVideoFiles(forceRefresh: Boolean = false): List<MediaItem> {
        if (!forceRefresh && cachedVideoFiles != null) return cachedVideoFiles!!

        return withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.ARTIST,
                MediaStore.Video.Media.ALBUM,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.MIME_TYPE,
            )

            val sortOrder = "${MediaStore.Video.Media.TITLE} ASC"

            try {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null, sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val path = cursor.getString(dataCol) ?: continue
                        val folder = path.substringBeforeLast("/").substringAfterLast("/")

                        items.add(
                            MediaItem(
                                id = id,
                                title = cursor.getString(titleCol) ?: "Unknown",
                                artist = cursor.getString(artistCol) ?: "Unknown",
                                album = cursor.getString(albumCol) ?: "Unknown",
                                duration = cursor.getLong(durationCol),
                                size = cursor.getLong(sizeCol),
                                uri = ContentUris.withAppendedId(
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                                ),
                                path = path,
                                folder = folder,
                                mimeType = cursor.getString(mimeCol) ?: "video/*",
                                isVideo = true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning video files", e)
            }

            cachedVideoFiles = items
            Log.i(TAG, "Found ${items.size} video files")
            items
        }
    }

    /**
     * Get all media files (audio + video) combined.
     */
    suspend fun getAllMediaFiles(forceRefresh: Boolean = false): List<MediaItem> {
        return getAudioFiles(forceRefresh) + getVideoFiles(forceRefresh)
    }

    /**
     * Get unique folders containing media files.
     */
    suspend fun getMediaFolders(): List<String> {
        val all = getAllMediaFiles()
        return all.map { it.folder }.distinct().sorted()
    }

    /**
     * Get unique artists.
     */
    suspend fun getArtists(): List<String> {
        val audio = getAudioFiles()
        return audio.map { it.artist }.distinct().sorted()
    }

    /**
     * Get unique albums.
     */
    suspend fun getAlbums(): List<String> {
        val audio = getAudioFiles()
        return audio.map { it.album }.distinct().sorted()
    }

    /**
     * Get files in a specific folder.
     */
    suspend fun getFilesInFolder(folder: String): List<MediaItem> {
        val all = getAllMediaFiles()
        return all.filter { it.folder == folder }
    }

    /**
     * Get files by a specific artist.
     */
    suspend fun getFilesByArtist(artist: String): List<MediaItem> {
        val audio = getAudioFiles()
        return audio.filter { it.artist == artist }
    }

    /**
     * Format duration in milliseconds to "m:ss" or "h:mm:ss".
     */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /**
     * Clear cached results.
     */
    fun clearCache() {
        cachedAudioFiles = null
        cachedVideoFiles = null
    }
}
