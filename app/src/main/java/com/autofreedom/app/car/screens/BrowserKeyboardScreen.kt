package com.autofreedom.app.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/**
 * Keyboard input screen for the browser.
 *
 * Uses SearchTemplate which provides an on-screen keyboard when the car
 * is parked. The typed text is passed back to the browser via the callback.
 *
 * This screen appears when:
 * 1. User taps the URL/keyboard button in the browser toolbar
 * 2. User taps an input field on a web page (detected via JavaScript bridge)
 *
 * Common quick-access URLs are shown as suggestions.
 */
class BrowserKeyboardScreen(
    carContext: CarContext,
    private val onTextSubmitted: (String) -> Unit
) : Screen(carContext) {

    private var searchText = ""
    private var showSuggestions = true

    // Quick access URLs
    private val quickUrls = listOf(
        "YouTube" to "https://m.youtube.com",
        "Google" to "https://www.google.com",
        "Google Maps" to "https://maps.google.com",
        "Twitter / X" to "https://x.com",
        "Reddit" to "https://www.reddit.com",
        "Wikipedia" to "https://en.m.wikipedia.org",
        "Facebook" to "https://m.facebook.com",
        "Instagram" to "https://www.instagram.com",
        "DuckDuckGo" to "https://duckduckgo.com",
        "Gmail" to "https://mail.google.com",
    )

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (searchText.isEmpty() && showSuggestions) {
            // Show quick-access bookmarks when no text is typed
            for ((name, url) in quickUrls) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(name)
                        .addText(url)
                        .setOnClickListener {
                            onTextSubmitted(url)
                            screenManager.pop()
                        }
                        .build()
                )
            }
        } else if (searchText.isNotEmpty()) {
            // Show "Go to URL" option
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Go to: $searchText")
                    .addText(if (searchText.contains(".")) "Open as URL" else "Search Google")
                    .setOnClickListener {
                        onTextSubmitted(searchText)
                        screenManager.pop()
                    }
                    .build()
            )

            // Show search suggestion
            if (!searchText.startsWith("http")) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Search: $searchText")
                        .addText("Search on Google")
                        .setOnClickListener {
                            onTextSubmitted("https://www.google.com/search?q=${java.net.URLEncoder.encode(searchText, "UTF-8")}")
                            screenManager.pop()
                        }
                        .build()
                )

                // YouTube search suggestion
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("YouTube: $searchText")
                        .addText("Search on YouTube")
                        .setOnClickListener {
                            onTextSubmitted("https://m.youtube.com/results?search_query=${java.net.URLEncoder.encode(searchText, "UTF-8")}")
                            screenManager.pop()
                        }
                        .build()
                )
            }

            // Filter matching quick URLs
            val filtered = quickUrls.filter {
                it.first.contains(searchText, ignoreCase = true) ||
                    it.second.contains(searchText, ignoreCase = true)
            }
            for ((name, url) in filtered.take(3)) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(name)
                        .addText(url)
                        .setOnClickListener {
                            onTextSubmitted(url)
                            screenManager.pop()
                        }
                        .build()
                )
            }
        }

        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    this@BrowserKeyboardScreen.searchText = searchText
                    this@BrowserKeyboardScreen.showSuggestions = searchText.isEmpty()
                    invalidate()
                }

                override fun onSearchSubmitted(searchText: String) {
                    onTextSubmitted(searchText)
                    screenManager.pop()
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Enter URL or search…")
            .setItemList(listBuilder.build())
            .build()
    }
}
