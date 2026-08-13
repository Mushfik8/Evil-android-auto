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
 * Full-screen web browser rendered onto the Android Auto car display.
 *
 * Uses NavigationTemplate (the only template with a Surface) to render
 * a WebView captured as bitmaps. Touch events from the car screen are
 * forwarded to the WebView for full interactivity.
 *
 * Quick-launch URLs: YouTube, Google, Maps can be opened instantly.
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
    private var currentTitle = "AutoFreedom Browser"
    private var isLoading = false

    init {
        lifecycle.addObserver(this)

        // Set up WebView callbacks
        webViewRenderer.onTitleChanged = { title ->
            currentTitle = title
            invalidate()
        }
        webViewRenderer.onPageStarted = { _ ->
            isLoading = true
            invalidate()
        }
        webViewRenderer.onPageFinished = { _ ->
            isLoading = false
            invalidate()
        }
        webViewRenderer.onInputFocused = { focused ->
            if (focused) {
                // When a web input field is tapped, open keyboard screen
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
        Log.i(TAG, "BrowserScreen created, initializing WebView")
        // Register surface callback to receive the car display surface
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
        Log.i(TAG, "Surface destroyed")
        webViewRenderer.stopRendering()
        webViewRenderer.setSurface(null, 0, 0)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "Visible area: $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "Stable area: $stableArea")
    }

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
        val actionStrip = ActionStrip.Builder()

        // 🔙 Back button
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

        // ➡️ Forward button
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_media_next)
                    ).build()
                )
                .setOnClickListener {
                    webViewRenderer.goForward()
                }
                .build()
        )

        // 🔄 Refresh button
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_rotate)
                    ).build()
                )
                .setOnClickListener {
                    webViewRenderer.reload()
                }
                .build()
        )

        // ⌨️ URL / Keyboard button
        actionStrip.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_edit)
                    ).build()
                )
                .setOnClickListener {
                    screenManager.push(
                        BrowserKeyboardScreen(carContext) { typedText ->
                            webViewRenderer.loadUrl(typedText)
                        }
                    )
                }
                .build()
        )

        // Build the navigation template with our surface
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip.build())

        // Map action strip (bottom of screen) — quick launch bookmarks
        val mapActionStrip = ActionStrip.Builder()

        // 📺 YouTube quick launch
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("YT")
                .setOnClickListener {
                    webViewRenderer.loadUrl("https://m.youtube.com")
                }
                .build()
        )

        // 🗺️ Google Maps quick launch
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Maps")
                .setOnClickListener {
                    webViewRenderer.loadUrl("https://maps.google.com")
                }
                .build()
        )

        // 🏠 Home (Google)
        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Home")
                .setOnClickListener {
                    webViewRenderer.loadUrl("https://www.google.com")
                }
                .build()
        )

        builder.setMapActionStrip(mapActionStrip.build())

        return builder.build()
    }
}
