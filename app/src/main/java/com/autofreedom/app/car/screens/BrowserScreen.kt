package com.autofreedom.app.car.screens

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.car.app.AppManager
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

/**
 * BrowserScreen — Uses VirtualDisplay + Presentation to render WebView
 * directly on the Android Auto car display with full hardware acceleration.
 *
 * VIDEO PLAYBACK: Works natively because the WebView is attached to a real
 * window on the VirtualDisplay. Hardware-accelerated video, CSS animations,
 * and everything else renders exactly like on a phone screen.
 *
 * COOKIES/LOGIN: CookieManager is configured to persist cookies to disk.
 * YouTube, Bioscope, Chorki logins are remembered across app restarts.
 * Cookies are flushed on every page load and when the screen is destroyed.
 *
 * RESPONSIVE: The WebView uses Chrome Mobile UA and injects a proper viewport
 * meta tag, so sites render in mobile mode and fit the car screen.
 */
@SuppressLint("UnsafeOptInUsageError")
class BrowserScreen(
    carContext: CarContext,
    private val initialUrl: String = "https://www.google.com"
) : Screen(carContext), DefaultLifecycleObserver {

    companion object {
        private const val TAG = "BrowserScreen"
        // Chrome Mobile UA — makes YouTube/streaming sites serve mobile video player
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"
    }

    // VirtualDisplay → Surface pipeline
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var currentSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Reference to the WebView inside the Presentation
    private val webView: WebView?
        get() = presentation?.webView

    private val mainHandler = Handler(Looper.getMainLooper())

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val width = surfaceContainer.width
            val height = surfaceContainer.height
            val surface = surfaceContainer.surface

            if (width <= 0 || height <= 0 || surface == null) return

            Log.i(TAG, "Surface available: ${width}x${height}")
            currentSurface = surface
            surfaceWidth = width
            surfaceHeight = height

            createVirtualDisplay(surface, width, height)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "Surface destroyed")
            destroyVirtualDisplay()
            currentSurface = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}
        override fun onStableAreaChanged(stableArea: Rect) {}

        override fun onScroll(distanceX: Float, distanceY: Float) {
            mainHandler.post {
                webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
            }
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            mainHandler.post {
                webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
            }
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            mainHandler.post {
                val zoomLevel = (scaleFactor * 100).toInt()
                webView?.evaluateJavascript(
                    "document.body.style.zoom = '${zoomLevel}%';", null
                )
            }
        }

        override fun onClick(x: Float, y: Float) {
            mainHandler.post {
                dispatchTouchToWebView(x, y)
            }
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.i(TAG, "BrowserScreen created for URL: $initialUrl")

        // Enable cookie persistence BEFORE any WebView is created
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        carContext.getCarService(AppManager::class.java)
            .setSurfaceCallback(surfaceCallback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.i(TAG, "BrowserScreen destroyed — flushing cookies")
        // IMPORTANT: Flush cookies to disk so YouTube login persists
        CookieManager.getInstance().flush()
        destroyVirtualDisplay()
    }

    // ==================== VirtualDisplay + Presentation ====================

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int) {
        mainHandler.post {
            try {
                destroyVirtualDisplayInternal()

                val density = carContext.resources.configuration.densityDpi

                val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                virtualDisplay = displayManager.createVirtualDisplay(
                    "AutoFreedomBrowser",
                    width,
                    height,
                    density,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )

                val display = virtualDisplay?.display
                if (display != null) {
                    presentation = CarPresentation(carContext, display, initialUrl)
                    presentation?.show()
                    Log.i(TAG, "✅ VirtualDisplay + Presentation created: ${width}x${height}")
                } else {
                    Log.e(TAG, "❌ VirtualDisplay created but display is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create VirtualDisplay", e)
            }
        }
    }

    private fun destroyVirtualDisplay() {
        mainHandler.post { destroyVirtualDisplayInternal() }
    }

    private fun destroyVirtualDisplayInternal() {
        try {
            // Flush cookies before destroying
            CookieManager.getInstance().flush()
            presentation?.dismiss()
            presentation = null
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying VirtualDisplay", e)
        }
    }

    // ==================== Touch Dispatch ====================

    private fun dispatchTouchToWebView(x: Float, y: Float) {
        val wv = webView ?: return
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        wv.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(up)
        up.recycle()
    }

    // ==================== Template ====================

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()

        // 🔙 Back
        actionStrip.addAction(
            Action.Builder()
                .setIcon(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_media_previous)
                ).build())
                .setOnClickListener {
                    val wv = webView
                    if (wv != null && wv.canGoBack()) {
                        mainHandler.post { wv.goBack() }
                    } else {
                        screenManager.pop()
                    }
                }
                .build()
        )

        // ➡️ Forward
        actionStrip.addAction(
            Action.Builder()
                .setIcon(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_media_next)
                ).build())
                .setOnClickListener {
                    mainHandler.post { webView?.goForward() }
                }
                .build()
        )

        // 🔄 Refresh
        actionStrip.addAction(
            Action.Builder()
                .setIcon(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_rotate)
                ).build())
                .setOnClickListener {
                    mainHandler.post { webView?.reload() }
                }
                .build()
        )

        // 🔍 Search / URL bar
        actionStrip.addAction(
            Action.Builder()
                .setTitle("Search")
                .setOnClickListener {
                    screenManager.push(
                        BrowserKeyboardScreen(carContext) { typedText ->
                            mainHandler.post { loadUrlInWebView(typedText) }
                        }
                    )
                }
                .build()
        )

        // BOTTOM: Quick-launch buttons
        val mapActionStrip = ActionStrip.Builder()

        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("YT")
                .setOnClickListener { mainHandler.post { loadUrlInWebView("https://m.youtube.com") } }
                .build()
        )

        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Bio")
                .setOnClickListener { mainHandler.post { loadUrlInWebView("https://www.bioscopelive.com") } }
                .build()
        )

        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Chk")
                .setOnClickListener { mainHandler.post { loadUrlInWebView("https://chorki.com") } }
                .build()
        )

        mapActionStrip.addAction(
            Action.Builder()
                .setTitle("Home")
                .setOnClickListener { mainHandler.post { loadUrlInWebView("https://www.google.com") } }
                .build()
        )

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip.build())
            .setMapActionStrip(mapActionStrip.build())
            .build()
    }

    private fun loadUrlInWebView(url: String) {
        val finalUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(url, "UTF-8")}"
        }
        webView?.loadUrl(finalUrl)
    }

    // ==================== Presentation ====================

    /**
     * CarPresentation — renders a full WebView on the VirtualDisplay.
     *
     * The WebView is hardware-accelerated and attached to a real window,
     * so video playback works natively. Cookies persist across sessions
     * for YouTube/OTT login.
     *
     * Fullscreen video: When the user taps fullscreen on a YouTube video,
     * onShowCustomView() adds the video view on top of the WebView container,
     * giving us true fullscreen video on the car display.
     *
     * Responsive: Injects viewport meta tag and mobile CSS so sites
     * render properly on the car screen resolution.
     */
    @SuppressLint("SetJavaScriptEnabled")
    class CarPresentation(
        outerContext: Context,
        display: android.view.Display,
        private val initialUrl: String
    ) : Presentation(outerContext, display) {

        var webView: WebView? = null
            private set

        private var container: FrameLayout? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Make the presentation fill the entire virtual display
            window?.apply {
                setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Hardware acceleration is critical for video rendering
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            }

            val frameLayout = FrameLayout(context)
            container = frameLayout

            webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // HARDWARE layer — critical for video rendering
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    // Core web capabilities
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true

                    // VIDEO: Allow autoplay — critical for YouTube/OTT
                    mediaPlaybackRequiresUserGesture = false

                    // RESPONSIVE: Proper viewport handling
                    loadWithOverviewMode = true
                    useWideViewPort = true

                    // ZOOM: Allow pinch-to-zoom
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)

                    // FILE ACCESS: For local file server
                    allowFileAccess = true
                    allowContentAccess = true

                    // WINDOWS: Don't open popups
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)

                    // SECURITY: Allow mixed content for streaming
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    // CACHE: Use default caching (stores page data for faster loads)
                    cacheMode = WebSettings.LOAD_DEFAULT

                    // USER AGENT: Chrome Mobile — makes YouTube/Bioscope/Chorki work
                    userAgentString = CHROME_UA
                }

                // COOKIES: Enable and accept all cookies for YouTube login persistence
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean = false // Handle all URLs inside our WebView

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Flush cookies after each page load to persist login state
                        CookieManager.getInstance().flush()

                        // Inject responsive viewport for proper mobile rendering
                        view?.evaluateJavascript("""
                            (function() {
                                // Ensure viewport meta tag for responsive rendering
                                var vp = document.querySelector('meta[name="viewport"]');
                                if (!vp) {
                                    vp = document.createElement('meta');
                                    vp.name = 'viewport';
                                    vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                                    document.head.appendChild(vp);
                                }
                                // Make images/videos responsive
                                var s = document.createElement('style');
                                s.textContent = 'img,video{max-width:100%;height:auto}';
                                document.head.appendChild(s);
                            })();
                        """.trimIndent(), null)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    private var fullscreenView: View? = null
                    private var fullscreenCallback: CustomViewCallback? = null

                    /**
                     * FULLSCREEN VIDEO: Called when user taps the fullscreen button
                     * on YouTube/Bioscope/Chorki video player.
                     *
                     * We add the video view on top of the WebView in our container.
                     * Since the container renders on the VirtualDisplay → AA Surface,
                     * the fullscreen video appears on the car screen.
                     */
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        Log.i(TAG, "🎬 FULLSCREEN VIDEO — showing on car display")
                        view ?: return
                        fullscreenView = view
                        fullscreenCallback = callback

                        // Add fullscreen video view on top of everything
                        frameLayout.addView(view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))

                        // Hide the WebView underneath
                        this@apply.visibility = View.GONE
                    }

                    override fun onHideCustomView() {
                        Log.i(TAG, "🎬 FULLSCREEN VIDEO — exiting")
                        fullscreenView?.let { frameLayout.removeView(it) }
                        fullscreenView = null
                        fullscreenCallback?.onCustomViewHidden()
                        fullscreenCallback = null

                        // Show WebView again
                        this@apply.visibility = View.VISIBLE
                    }
                }

                loadUrl(initialUrl)
            }

            frameLayout.addView(webView)
            setContentView(frameLayout)

            Log.i(TAG, "✅ CarPresentation created — loading: $initialUrl")
        }

        override fun onStop() {
            super.onStop()
            // Flush cookies on stop to persist YouTube login
            CookieManager.getInstance().flush()
            // NOTE: Don't destroy WebView here — it kills cookies/state.
            // WebView is destroyed when the BrowserScreen is destroyed.
        }

        fun destroyWebView() {
            webView?.apply {
                stopLoading()
                clearHistory()
                destroy()
            }
            webView = null
            container = null
        }
    }
}
