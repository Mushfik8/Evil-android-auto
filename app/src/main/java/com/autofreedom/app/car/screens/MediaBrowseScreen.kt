package com.autofreedom.app.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.autofreedom.app.media.MediaScanner
import com.autofreedom.app.media.MediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Media browse screen — displays local audio and video files organized
 * by categories (All Songs, Videos, Folders, Artists, Albums).
 *
 * Tapping a media file starts playback through the MediaService/PlaybackEngine.
 */
class MediaBrowseScreen(
    carContext: CarContext,
    private val category: String = "root",
    private val categoryTitle: String = "Media"
) : Screen(carContext) {

    private val scanner = MediaScanner(carContext)
    private var items: List<MediaScanner.MediaItem> = emptyList()
    private var folders: List<String> = emptyList()
    private var artists: List<String> = emptyList()
    private var isLoaded = false

    init {
        loadData()
    }

    private fun loadData() {
        CoroutineScope(Dispatchers.Main).launch {
            when (category) {
                "root" -> {
                    // Root shows categories, no data needed
                    isLoaded = true
                    invalidate()
                }
                "all_songs" -> {
                    items = scanner.getAudioFiles()
                    isLoaded = true
                    invalidate()
                }
                "all_videos" -> {
                    items = scanner.getVideoFiles()
                    isLoaded = true
                    invalidate()
                }
                "folders" -> {
                    folders = scanner.getMediaFolders()
                    isLoaded = true
                    invalidate()
                }
                "artists" -> {
                    artists = scanner.getArtists()
                    isLoaded = true
                    invalidate()
                }
                else -> {
                    items = when {
                        category.startsWith("folder:") ->
                            scanner.getFilesInFolder(category.removePrefix("folder:"))
                        category.startsWith("artist:") ->
                            scanner.getFilesByArtist(category.removePrefix("artist:"))
                        else -> emptyList()
                    }
                    isLoaded = true
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (!isLoaded) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Loading media files...")
                    .build()
            )
        } else if (category == "root") {
            // Show categories
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎵 All Songs")
                    .addText("Browse all audio files")
                    .setOnClickListener {
                        screenManager.push(MediaBrowseScreen(carContext, "all_songs", "All Songs"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎬 All Videos")
                    .addText("Browse all video files")
                    .setOnClickListener {
                        screenManager.push(MediaBrowseScreen(carContext, "all_videos", "All Videos"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("📂 Folders")
                    .addText("Browse by folder")
                    .setOnClickListener {
                        screenManager.push(MediaBrowseScreen(carContext, "folders", "Folders"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎤 Artists")
                    .addText("Browse by artist")
                    .setOnClickListener {
                        screenManager.push(MediaBrowseScreen(carContext, "artists", "Artists"))
                    }
                    .build()
            )
        } else if (folders.isNotEmpty()) {
            // Show folder list
            for (folder in folders.take(20)) { // AA limits list to ~20 items per page
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("📁 $folder")
                        .setOnClickListener {
                            screenManager.push(
                                MediaBrowseScreen(carContext, "folder:$folder", folder)
                            )
                        }
                        .build()
                )
            }
        } else if (artists.isNotEmpty()) {
            // Show artist list
            for (artist in artists.take(20)) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("🎤 $artist")
                        .setOnClickListener {
                            screenManager.push(
                                MediaBrowseScreen(carContext, "artist:$artist", artist)
                            )
                        }
                        .build()
                )
            }
        } else if (items.isNotEmpty()) {
            // Show media items
            for ((index, item) in items.take(20).withIndex()) {
                val icon = if (item.isVideo) "🎬" else "🎵"
                val duration = scanner.formatDuration(item.duration)
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("$icon ${item.title}")
                        .addText("${item.artist} • $duration")
                        .setOnClickListener {
                            // Play this item and queue remaining items
                            MediaService.instance?.playbackEngine?.playQueue(items, index)
                        }
                        .build()
                )
            }
        } else {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("No media files found")
                    .addText("Add music or video files to your device")
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(categoryTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
