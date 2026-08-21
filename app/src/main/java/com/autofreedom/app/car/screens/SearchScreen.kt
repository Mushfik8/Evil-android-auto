package com.autofreedom.app.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.autofreedom.app.media.MediaScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Unified search screen — searches across media files, web, YouTube,
 * Bioscope, Chorki, Hoichoi, and local files.
 *
 * Uses SearchTemplate which provides:
 * - On-screen keyboard when car is parked
 * - Voice input when driving
 *
 * Search results are categorized and actionable:
 * - Media files → tap to play (video → VideoPlayerScreen, audio → PlaybackEngine)
 * - Web queries → tap to open in browser
 * - Streaming sites → tap to search on YouTube/Bioscope/Chorki
 */
class SearchScreen(carContext: CarContext) : Screen(carContext) {

    private val scanner = MediaScanner(carContext)
    private var searchText = ""
    private var mediaResults: List<MediaScanner.MediaItem> = emptyList()
    private var searchJob: Job? = null

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (searchText.isEmpty()) {
            // Show quick actions when no search text
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🌐 Open Browser")
                    .addText("Browse the web on car screen")
                    .setOnClickListener {
                        screenManager.push(BrowserScreen(carContext))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("📺 YouTube")
                    .addText("Watch videos on car screen")
                    .setOnClickListener {
                        screenManager.push(BrowserScreen(carContext, "https://m.youtube.com"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎬 Bioscope")
                    .addText("Bangladeshi movies & shows")
                    .setOnClickListener {
                        screenManager.push(BrowserScreen(carContext, "https://www.bioscopelive.com"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎭 Chorki")
                    .addText("Bangladeshi drama & entertainment")
                    .setOnClickListener {
                        screenManager.push(BrowserScreen(carContext, "https://chorki.com"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎵 Media Player")
                    .addText("Play local music and videos")
                    .setOnClickListener {
                        screenManager.push(MediaBrowseScreen(carContext))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("📂 File Explorer")
                    .addText("Browse device files")
                    .setOnClickListener {
                        screenManager.push(FileExplorerScreen(carContext))
                    }
                    .build()
            )
        } else {
            // Show search results

            // YouTube search option
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("📺 YouTube: $searchText")
                    .addText("Search & watch on YouTube")
                    .setOnClickListener {
                        val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                        screenManager.push(
                            BrowserScreen(
                                carContext,
                                "https://m.youtube.com/results?search_query=$query"
                            )
                        )
                    }
                    .build()
            )

            // Web search option
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🔍 Google: $searchText")
                    .addText("Search the web")
                    .setOnClickListener {
                        val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                        screenManager.push(
                            BrowserScreen(carContext, "https://www.google.com/search?q=$query")
                        )
                    }
                    .build()
            )

            // Bioscope search
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎬 Bioscope: $searchText")
                    .addText("Search Bioscope movies & shows")
                    .setOnClickListener {
                        val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                        screenManager.push(
                            BrowserScreen(carContext, "https://www.bioscopelive.com/bn/search?q=$query")
                        )
                    }
                    .build()
            )

            // Chorki search
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎭 Chorki: $searchText")
                    .addText("Search Chorki drama & entertainment")
                    .setOnClickListener {
                        val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                        screenManager.push(
                            BrowserScreen(carContext, "https://chorki.com/search?q=$query")
                        )
                    }
                    .build()
            )

            // Hoichoi search
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🎥 Hoichoi: $searchText")
                    .addText("Search Hoichoi Bengali content")
                    .setOnClickListener {
                        val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                        screenManager.push(
                            BrowserScreen(carContext, "https://www.hoichoi.tv/search?q=$query")
                        )
                    }
                    .build()
            )

            // Media file results (limit to avoid exceeding AA list limits)
            val maxMediaResults = 4.coerceAtMost(mediaResults.size)
            for (i in 0 until maxMediaResults) {
                val item = mediaResults[i]
                val icon = if (item.isVideo) "🎬" else "🎵"
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("$icon ${item.title}")
                        .addText("${item.artist} • ${scanner.formatDuration(item.duration)}")
                        .setOnClickListener {
                            if (item.isVideo) {
                                screenManager.push(
                                    VideoPlayerScreen(carContext, item.uri, item.title)
                                )
                            } else {
                                com.autofreedom.app.media.MediaService.instance
                                    ?.playbackEngine?.play(item)
                            }
                        }
                        .build()
                )
            }
        }

        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    this@SearchScreen.searchText = searchText
                    performSearch(searchText)
                }

                override fun onSearchSubmitted(searchText: String) {
                    // Default: search YouTube (most common use case for car video)
                    val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                    screenManager.push(
                        BrowserScreen(carContext, "https://m.youtube.com/results?search_query=$query")
                    )
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Search YouTube, Bioscope, Chorki…")
            .setItemList(listBuilder.build())
            .build()
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.isEmpty()) {
            mediaResults = emptyList()
            invalidate()
            return
        }

        searchJob = CoroutineScope(Dispatchers.Main).launch {
            delay(300) // Debounce

            // Search local media files
            val allMedia = scanner.getAllMediaFiles()
            mediaResults = allMedia.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.album.contains(query, ignoreCase = true)
            }

            invalidate()
        }
    }
}
