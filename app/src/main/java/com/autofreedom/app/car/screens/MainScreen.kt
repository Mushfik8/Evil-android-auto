package com.autofreedom.app.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.autofreedom.app.AutoFreedomApplication

/**
 * Main home screen — a grid of feature tiles:
 * Browser, YouTube, Bioscope, Chorki, Media, Files, Search, Maps, Hoichoi
 *
 * Quick access to all major features of AutoFreedom.
 * Streaming sites open in the built-in browser for video playback on car screen.
 */
class MainScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val gridItems = ItemList.Builder()

        // 🌐 Web Browser
        gridItems.addItem(
            GridItem.Builder()
                .setTitle("Browser")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_compass)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(BrowserScreen(carContext))
                }
                .build()
        )

        // 📺 YouTube (quick launch — opens browser at m.youtube.com)
        gridItems.addItem(
            GridItem.Builder()
                .setTitle("YouTube")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_play)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(BrowserScreen(carContext, "https://m.youtube.com"))
                }
                .build()
        )

        // 🎬 Bioscope (Bangladeshi streaming)
        gridItems.addItem(
            GridItem.Builder()
                .setTitle("Bioscope")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_slideshow)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(BrowserScreen(carContext, "https://www.bioscopelive.com"))
                }
                .build()
        )

        // 🎭 Chorki (Bangladeshi streaming)
        gridItems.addItem(
            GridItem.Builder()
                .setTitle("Chorki")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_gallery)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(BrowserScreen(carContext, "https://chorki.com"))
                }
                .build()
        )

        // 🎵 Media Player
        gridItems.addItem(
            GridItem.Builder()
                .setTitle("Media")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_btn_speak_now)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(MediaBrowseScreen(carContext))
                }
                .build()
        )

        // 📂 File Explorer
        gridItems.addItem(
            GridItem.Builder()
                .setTitle("Files")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_agenda)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(FileExplorerScreen(carContext))
                }
                .build()
        )

        return GridTemplate.Builder()
            .setTitle("AutoFreedom")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(gridItems.build())
            .build()
    }
}
