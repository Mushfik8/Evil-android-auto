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
 * Unified search screen — searches across media files, web, and places.
 *
 * Uses SearchTemplate which provides:
 * - On-screen keyboard when car is parked
 * - Voice input when driving
 *
 * Search results are categorized and actionable:
 * - Media files → tap to play
 * - Web queries → tap to open in browser
 * - URLs → tap to open directly
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
                    .addText("Open YouTube in browser")
                    .setOnClickListener {
                        screenManager.push(BrowserScreen(carContext, "https://m.youtube.com"))
                    }
                    .build()
            )
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🗺️ Google Maps")
                    .addText("Open Maps in browser")
                    .setOnClickListener {
                        screenManager.push(BrowserScreen(carContext, "https://maps.google.com"))
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

            // Web search option
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🔍 Search web: $searchText")
                    .addText("Open Google search in browser")
                    .setOnClickListener {
                        val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                        screenManager.push(
                            BrowserScreen(carContext, "https://www.google.com/search?q=$query")
                        )
                    }
                    .build()
            )

            // YouTube search option
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("📺 YouTube: $searchText")
                    .addText("Search on YouTube")
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

            // Media file results
            val maxMediaResults = 6.coerceAtMost(mediaResults.size)
            for (i in 0 until maxMediaResults) {
                val item = mediaResults[i]
                val icon = if (item.isVideo) "🎬" else "🎵"
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("$icon ${item.title}")
                        .addText("${item.artist} • ${scanner.formatDuration(item.duration)}")
                        .setOnClickListener {
                            com.autofreedom.app.media.MediaService.instance
                                ?.playbackEngine?.play(item)
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
                    // Open web search in browser
                    val query = java.net.URLEncoder.encode(searchText, "UTF-8")
                    screenManager.push(
                        BrowserScreen(carContext, "https://www.google.com/search?q=$query")
                    )
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Search web, YouTube, files…")
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
