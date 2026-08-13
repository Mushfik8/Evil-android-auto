package com.autofreedom.app.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders a WebView offscreen and draws frames to an Android Auto Surface.
 *
 * Architecture:
 * 1. WebView is created on the main (UI) thread
 * 2. A render loop (15fps) captures the WebView as a Bitmap on the UI thread
 * 3. The bitmap is drawn to the AA Surface on a background thread
 * 4. Touch/scroll events from SurfaceCallback are forwarded to the WebView
 *
 * This approach avoids overheating by capping at 15fps and reusing bitmaps.
 */
class WebViewRenderer(private val context: Context) {

    companion object {
        private const val TAG = "WebViewRenderer"
        private const val RENDER_INTERVAL_MS = 66L  // ~15fps — battery friendly
        private const val DEFAULT_URL = "https://www.google.com"
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderBitmap: Bitmap? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isRendering = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    // Callbacks
    var onPageStarted: ((String) -> Unit)? = null
    var onPageFinished: ((String) -> Unit)? = null
    var onProgressChanged: ((Int) -> Unit)? = null
    var onTitleChanged: ((String) -> Unit)? = null
    var onInputFocused: ((Boolean) -> Unit)? = null

    // Current page state
    private val currentUrl = AtomicReference(DEFAULT_URL)
    private val currentTitle = AtomicReference("AutoFreedom Browser")

    /**
     * Initialize the WebView on the main thread.
     * Must be called before any other methods.
     */
    fun initialize(width: Int, height: Int) {
        mainHandler.post {
            try {
                surfaceWidth = width
                surfaceHeight = height

                webView = WebView(context).apply {
                    // Layout the WebView to match the car screen dimensions
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, width, height)

                    // Enable full web capabilities
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                        allowFileAccess = true
                        allowContentAccess = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        setSupportMultipleWindows(false)

                        // Set a mobile user agent for better YouTube compatibility
                        userAgentString = userAgentString.replace(
                            "wv",
                            "Chrome/120.0.0.0 Mobile"
                        )
                    }

                    // WebView client for page lifecycle
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            return false // Handle all URLs in our WebView
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            url?.let {
                                currentUrl.set(it)
                                onPageStarted?.invoke(it)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let {
                                currentUrl.set(it)
                                onPageFinished?.invoke(it)
                            }
                            // Inject JavaScript to detect input focus
                            injectInputDetection()
                        }
                    }

                    // Chrome client for progress and title
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            onProgressChanged?.invoke(newProgress)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.let {
                                currentTitle.set(it)
                                onTitleChanged?.invoke(it)
                            }
                        }
                    }

                    // JavaScript bridge for input detection
                    addJavascriptInterface(InputBridge(), "AutoFreedomBridge")

                    // Enable hardware acceleration drawing
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }

                // Create reusable bitmap for rendering
                renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                isInitialized.set(true)
                Log.i(TAG, "WebView initialized: ${width}x${height}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebView", e)
            }
        }
    }

    /**
     * Set the AA Surface to render to.
     */
    fun setSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        if (width != surfaceWidth || height != surfaceHeight) {
            surfaceWidth = width
            surfaceHeight = height
            mainHandler.post {
                webView?.apply {
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, width, height)
                }
                // Recreate bitmap at new size
                renderBitmap?.recycle()
                renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    /**
     * Start the render loop. Captures WebView content and draws to Surface.
     */
    fun startRendering() {
        if (isRendering.getAndSet(true)) return
        Log.i(TAG, "Starting render loop")
        renderLoop()
    }

    /**
     * Stop the render loop.
     */
    fun stopRendering() {
        isRendering.set(false)
        Log.i(TAG, "Stopping render loop")
    }

    private fun renderLoop() {
        if (!isRendering.get()) return

        mainHandler.post {
            try {
                val wv = webView ?: return@post
                val bmp = renderBitmap ?: return@post
                val srf = surface ?: return@post

                if (!srf.isValid) {
                    scheduleNextFrame()
                    return@post
                }

                // Draw WebView to bitmap (must happen on UI thread)
                val bitmapCanvas = Canvas(bmp)
                wv.draw(bitmapCanvas)

                // Draw bitmap to Surface
                try {
                    val surfaceCanvas = srf.lockCanvas(null)
                    surfaceCanvas.drawBitmap(bmp, 0f, 0f, null)
                    srf.unlockCanvasAndPost(surfaceCanvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Surface draw failed: ${e.message}")
                }

                scheduleNextFrame()
            } catch (e: Exception) {
                Log.e(TAG, "Render error", e)
                scheduleNextFrame()
            }
        }
    }

    private fun scheduleNextFrame() {
        if (isRendering.get()) {
            mainHandler.postDelayed({ renderLoop() }, RENDER_INTERVAL_MS)
        }
    }

    // ==================== Navigation Methods ====================

    fun loadUrl(url: String) {
        mainHandler.post {
            val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (url.contains(".") && !url.contains(" ")) {
                    "https://$url"
                } else {
                    "https://www.google.com/search?q=${java.net.URLEncoder.encode(url, "UTF-8")}"
                }
            } else {
                url
            }
            webView?.loadUrl(finalUrl)
        }
    }

    fun goBack(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoBack()) {
            mainHandler.post { wv.goBack() }
            true
        } else {
            false
        }
    }

    fun goForward(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoForward()) {
            mainHandler.post { wv.goForward() }
            true
        } else {
            false
        }
    }

    fun reload() {
        mainHandler.post { webView?.reload() }
    }

    fun getCurrentUrl(): String = currentUrl.get()
    fun getCurrentTitle(): String = currentTitle.get()

    fun canGoBack(): Boolean = webView?.canGoBack() == true
    fun canGoForward(): Boolean = webView?.canGoForward() == true

    // ==================== Touch Event Handling ====================

    /**
     * Handle a tap/click from the car screen.
     * Coordinates are relative to the Surface.
     */
    fun handleClick(x: Float, y: Float) {
        mainHandler.post {
            webView?.let { wv ->
                val downTime = SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(
                    downTime, downTime,
                    MotionEvent.ACTION_DOWN, x, y, 0
                )
                wv.dispatchTouchEvent(downEvent)
                downEvent.recycle()

                val upEvent = MotionEvent.obtain(
                    downTime, downTime + 50,
                    MotionEvent.ACTION_UP, x, y, 0
                )
                wv.dispatchTouchEvent(upEvent)
                upEvent.recycle()
            }
        }
    }

    /**
     * Handle scroll gestures from the car screen.
     */
    fun handleScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
        }
    }

    /**
     * Handle fling gestures from the car screen.
     */
    fun handleFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
        }
    }

    /**
     * Handle pinch-to-zoom from the car screen.
     */
    fun handleScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        mainHandler.post {
            webView?.let { wv ->
                // Use JavaScript to zoom
                val zoomLevel = (scaleFactor * 100).toInt()
                wv.evaluateJavascript(
                    "document.body.style.zoom = '${zoomLevel}%';",
                    null
                )
            }
        }
    }

    /**
     * Inject text into the currently focused input field.
     * Used after keyboard input from SearchTemplate.
     */
    fun injectText(text: String) {
        mainHandler.post {
            val escaped = text.replace("'", "\\'").replace("\n", "\\n")
            webView?.evaluateJavascript(
                """
                (function() {
                    var el = document.activeElement;
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                        el.value = '$escaped';
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    /**
     * Submit the currently focused form (simulates Enter key).
     */
    fun submitForm() {
        mainHandler.post {
            webView?.evaluateJavascript(
                """
                (function() {
                    var el = document.activeElement;
                    if (el) {
                        var form = el.closest('form');
                        if (form) {
                            form.submit();
                        } else {
                            el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));
                            el.dispatchEvent(new KeyboardEvent('keypress', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));
                            el.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));
                        }
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    // ==================== Input Detection ====================

    private fun injectInputDetection() {
        mainHandler.post {
            webView?.evaluateJavascript(
                """
                (function() {
                    document.addEventListener('focusin', function(e) {
                        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
                            AutoFreedomBridge.onInputFocused(true);
                        }
                    });
                    document.addEventListener('focusout', function(e) {
                        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
                            AutoFreedomBridge.onInputFocused(false);
                        }
                    });
                })();
                """.trimIndent(),
                null
            )
        }
    }

    /**
     * JavaScript bridge for detecting input field focus.
     */
    inner class InputBridge {
        @JavascriptInterface
        fun onInputFocused(focused: Boolean) {
            onInputFocused?.invoke(focused)
        }
    }

    // ==================== Cleanup ====================

    fun destroy() {
        stopRendering()
        mainHandler.post {
            webView?.destroy()
            webView = null
            renderBitmap?.recycle()
            renderBitmap = null
        }
        surface = null
    }
}
