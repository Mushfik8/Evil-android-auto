package com.autofreedom.app.car.screens

import android.graphics.Rect
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
import com.autofreedom.app.renderer.WebViewRenderer

/**
 * Full-screen web browser on the Android Auto car display.
 *
 * FIXED:
 * - Fullscreen video works (YouTube, Bioscope, Chorki etc)
 * - Audio plays through car speakers (audio focus)
 * - Chrome UA so streaming sites accept the browser
 * - Prominent search/keyboard button
 * - Quick-launch buttons: YouTube, Maps, Search, Home
 */
class BrowserScreen(
    carContext: CarContext,
    private val initialUrl: String = "https://www.google.com"
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "BrowserScreen"
    }

    private val webViewRenderer = WebViewRenderer(carContext)
    private var surfaceWidth = 1280
    private var surfaceHeight = 720

    init {
        lifecycle.addObserver(this)

        webViewRenderer.onTitleChanged = { invalidate() }
        webViewRenderer.onPageStarted = { invalidate() }
        webViewRenderer.onPageFinished = { invalidate() }
        webViewRenderer.onInputFocused = { focused ->
            if (focused) {
                screenManager.push(
                    BrowserKeyboardScreen(carContext) { typedText ->
                        webViewRenderer.injectText(typedText)
                        webViewRenderer.submitForm()
                    }
                )
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.i(TAG, "BrowserScreen created")
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.i(TAG, "BrowserScreen destroyed")
        webViewRenderer.destroy()
    }

    // ==================== SurfaceCallback ====================

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface available: ${surfaceContainer.width}x${surfaceContainer.height}")
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height

        webViewRenderer.initialize(surfaceWidth, surfaceHeight)
        webViewRenderer.setSurface(surfaceContainer.surface, surfaceWidth, surfaceHeight)
        webViewRenderer.loadUrl(initialUrl)
        webViewRenderer.startRendering()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        webViewRenderer.stopRendering()
        webViewRenderer.setSurface(null, 0, 0)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onScroll(distanceX: Float, distanceY: Float) {
        webViewRenderer.handleScroll(distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        webViewRenderer.handleFling(velocityX, velocityY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        webViewRenderer.handleScale(focusX, focusY, scaleFactor)
    }

    override fun onClick(x: Float, y: Float) {
        webViewRenderer.handleClick(x, y)
    }

    // ==================== Template ====================

    override fun onGetTemplate(): Template {
        // TOP ACTION STRIP — browser controls
        val actionStrip = ActionStrip.Builder()

        // 🔙 Back
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_previous)
                    ).build()
                )
                .setOnClickListener {
                    if (!webViewRenderer.goBack()) {
                        screenManager.pop()
                    }
                }
                .build()
        )

        // ➡️ Forward
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_next)
                    ).build()
                )
                .setOnClickListener { webViewRenderer.goForward() }
                .build()
        )

        // 🔄 Refresh
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_rotate)
                    ).build()
                )
                .setOnClickListener { webViewRenderer.reload() }
                .build()
        )

        // 🔍 SEARCH / TYPE URL — most important button
        actionStrip.addAction(
            Action.Builder()
                .setTitle("Search")
                .setOnClickListener {
                    screenManager.push(
                        BrowserKeyboardScreen(carContext) { typedText ->
                            webViewRenderer.loadUrl(typedText)
                        }
                    )
                }
                .build()
        )

        // BOTTOM MAP ACTION STRIP — quick launch shortcuts
        val mapActionStrip = ActionStrip.Builder()

        // 📺 YouTube
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("YT")
                .setOnClickListener {
                    webViewRenderer.loadUrl("https://m.youtube.com")
                }
                .build()
        )

        // 🗺️ Maps
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Maps")
                .setOnClickListener {
                    webViewRenderer.loadUrl("https://maps.google.com")
                }
                .build()
        )

        // 🏠 Home / Google
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Home")
                .setOnClickListener {
                    webViewRenderer.loadUrl("https://www.google.com")
                }
                .build()
        )

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip.build())
            .setMapActionStrip(mapActionStrip.build())
            .build()
    }
}
