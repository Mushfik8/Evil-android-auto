package com.autofreedom.app.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * REWRITTEN — Renders a WebView offscreen and draws frames to an Android Auto Surface.
 *
 * KEY FIX: Handles fullscreen video via WebChromeClient.onShowCustomView().
 * When a user taps fullscreen on YouTube/any video, the video View is captured
 * and rendered directly to the Surface instead of the WebView bitmap.
 *
 * Audio: Requests AudioFocus so sound comes through the car speakers.
 * User Agent: Spoofs Chrome Mobile so YouTube/streaming sites work.
 */
class WebViewRenderer(private val context: Context) {

    companion object {
        private const val TAG = "WebViewRenderer"
        private const val RENDER_INTERVAL_MS = 50L  // ~20fps for smoother video
        private const val DEFAULT_URL = "https://www.google.com"

        // Chrome Mobile user agent — makes YouTube/streaming sites work
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderBitmap: Bitmap? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isRendering = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    // Fullscreen video state
    private var fullscreenVideoView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val isFullscreenVideo = AtomicBoolean(false)

    // Audio
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

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
     */
    fun initialize(width: Int, height: Int) {
        mainHandler.post {
            try {
                surfaceWidth = width
                surfaceHeight = height

                // Init audio manager
                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                webView = WebView(context).apply {
                    // Layout to match car screen
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, width, height)

                    // Full web capabilities
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
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

                        // CRITICAL: Chrome Mobile UA — makes YouTube/Bioscope/Chorki WORK
                        userAgentString = CHROME_UA
                    }

                    // Enable cookies (needed for YouTube login, site preferences)
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    // WebView client
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean = false

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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
                            injectInputDetection()
                            // Inject helper JS for better video handling
                            injectVideoHelper()
                        }
                    }

                    // CRITICAL: WebChromeClient with fullscreen video support
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

                        /**
                         * CRITICAL: Called when a video enters fullscreen mode.
                         * YouTube, Bioscope, Chorki — when user taps the fullscreen
                         * button on a video player, this method is called with the
                         * video View. We render THIS view to the Surface instead of
                         * the WebView bitmap, which gives us actual video frames.
                         */
                        override fun onShowCustomView(
                            view: View?,
                            callback: CustomViewCallback?
                        ) {
                            Log.i(TAG, "🎬 FULLSCREEN VIDEO STARTED")
                            view ?: return

                            fullscreenVideoView = view
                            fullscreenCallback = callback
                            isFullscreenVideo.set(true)

                            // Layout the video view to match surface
                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(surfaceWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(surfaceHeight, View.MeasureSpec.EXACTLY)
                            )
                            view.layout(0, 0, surfaceWidth, surfaceHeight)

                            // Request audio focus for video sound
                            requestAudioFocus()
                        }

                        /**
                         * Called when video exits fullscreen.
                         * Switch back to WebView bitmap rendering.
                         */
                        override fun onHideCustomView() {
                            Log.i(TAG, "🎬 FULLSCREEN VIDEO ENDED")
                            isFullscreenVideo.set(false)
                            fullscreenVideoView = null
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCallback = null
                            abandonAudioFocus()
                        }
                    }

                    // JavaScript bridge
                    addJavascriptInterface(InputBridge(), "AutoFreedomBridge")

                    // SOFTWARE layer forces bitmap capture to include all content
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }

                // Reusable bitmap
                renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                isInitialized.set(true)
                Log.i(TAG, "WebView initialized: ${width}x${height} with Chrome UA")

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
        if (surface != null && (width != surfaceWidth || height != surfaceHeight)) {
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
                renderBitmap?.recycle()
                renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    fun startRendering() {
        if (isRendering.getAndSet(true)) return
        Log.i(TAG, "Starting render loop")
        requestAudioFocus()
        renderLoop()
    }

    fun stopRendering() {
        isRendering.set(false)
        Log.i(TAG, "Stopping render loop")
    }

    /**
     * CORE RENDER LOOP — switches between WebView bitmap and fullscreen video
     */
    private fun renderLoop() {
        if (!isRendering.get()) return

        mainHandler.post {
            try {
                val srf = surface ?: return@post
                if (!srf.isValid) {
                    scheduleNextFrame()
                    return@post
                }

                val bmp = renderBitmap ?: return@post
                val bitmapCanvas = Canvas(bmp)

                if (isFullscreenVideo.get() && fullscreenVideoView != null) {
                    // ===== FULLSCREEN VIDEO MODE =====
                    // Render the video View directly — gives us actual video frames
                    val videoView = fullscreenVideoView!!

                    // Re-layout in case size changed
                    videoView.measure(
                        View.MeasureSpec.makeMeasureSpec(surfaceWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(surfaceHeight, View.MeasureSpec.EXACTLY)
                    )
                    videoView.layout(0, 0, surfaceWidth, surfaceHeight)

                    // Clear with black then draw video
                    bitmapCanvas.drawColor(Color.BLACK)
                    videoView.draw(bitmapCanvas)
                } else {
                    // ===== NORMAL BROWSING MODE =====
                    val wv = webView ?: return@post
                    wv.draw(bitmapCanvas)
                }

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

    // ==================== Audio Focus ====================

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            val result = am.requestAudioFocus(req)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "Audio focus request: ${if (hasAudioFocus) "GRANTED" else "DENIED"}")
        } else {
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                {}, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus {}
        }
        hasAudioFocus = false
    }

    // ==================== Navigation ====================

    fun loadUrl(url: String) {
        mainHandler.post {
            // Exit fullscreen if active
            if (isFullscreenVideo.get()) {
                exitFullscreen()
            }

            val finalUrl = when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.contains(".") && !url.contains(" ") -> "https://$url"
                else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(url, "UTF-8")}"
            }
            webView?.loadUrl(finalUrl)
            requestAudioFocus()
        }
    }

    fun goBack(): Boolean {
        if (isFullscreenVideo.get()) {
            exitFullscreen()
            return true
        }
        val wv = webView ?: return false
        return if (wv.canGoBack()) {
            mainHandler.post { wv.goBack() }
            true
        } else false
    }

    fun goForward(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoForward()) {
            mainHandler.post { wv.goForward() }
            true
        } else false
    }

    fun reload() {
        mainHandler.post { webView?.reload() }
    }

    fun exitFullscreen() {
        mainHandler.post {
            fullscreenCallback?.onCustomViewHidden()
            isFullscreenVideo.set(false)
            fullscreenVideoView = null
            fullscreenCallback = null
        }
    }

    fun getCurrentUrl(): String = currentUrl.get()
    fun getCurrentTitle(): String = currentTitle.get()
    fun canGoBack(): Boolean = webView?.canGoBack() == true || isFullscreenVideo.get()
    fun canGoForward(): Boolean = webView?.canGoForward() == true

    // ==================== Touch Events ====================

    fun handleClick(x: Float, y: Float) {
        mainHandler.post {
            val targetView = if (isFullscreenVideo.get()) fullscreenVideoView else webView
            targetView?.let { v ->
                val downTime = SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
                )
                v.dispatchTouchEvent(downEvent)
                downEvent.recycle()

                val upEvent = MotionEvent.obtain(
                    downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0
                )
                v.dispatchTouchEvent(upEvent)
                upEvent.recycle()
            }
        }
    }

    fun handleScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            if (isFullscreenVideo.get()) return@post
            webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
        }
    }

    fun handleFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            if (isFullscreenVideo.get()) return@post
            webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
        }
    }

    fun handleScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        mainHandler.post {
            if (isFullscreenVideo.get()) return@post
            webView?.let { wv ->
                val zoomLevel = (scaleFactor * 100).toInt()
                wv.evaluateJavascript(
                    "document.body.style.zoom = '${zoomLevel}%';", null
                )
            }
        }
    }

    // ==================== Text Input ====================

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
                """.trimIndent(), null
            )
        }
    }

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
                            el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',code:'Enter',keyCode:13,bubbles:true}));
                            el.dispatchEvent(new KeyboardEvent('keypress', {key:'Enter',code:'Enter',keyCode:13,bubbles:true}));
                            el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter',code:'Enter',keyCode:13,bubbles:true}));
                        }
                    }
                })();
                """.trimIndent(), null
            )
        }
    }

    // ==================== JavaScript Injection ====================

    private fun injectInputDetection() {
        mainHandler.post {
            webView?.evaluateJavascript(
                """
                (function() {
                    if (window._afInputDetected) return;
                    window._afInputDetected = true;
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
                """.trimIndent(), null
            )
        }
    }

    /**
     * Inject JavaScript that auto-clicks the fullscreen button on video players.
     * Makes YouTube videos automatically go fullscreen for better car screen viewing.
     */
    private fun injectVideoHelper() {
        mainHandler.post {
            webView?.evaluateJavascript(
                """
                (function() {
                    if (window._afVideoHelper) return;
                    window._afVideoHelper = true;

                    // Auto-request fullscreen when a video starts playing
                    document.addEventListener('play', function(e) {
                        if (e.target.tagName === 'VIDEO') {
                            try {
                                var v = e.target;
                                if (v.requestFullscreen) v.requestFullscreen();
                                else if (v.webkitRequestFullscreen) v.webkitRequestFullscreen();
                                else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
                            } catch(err) { console.log('AF: fullscreen failed', err); }
                        }
                    }, true);
                })();
                """.trimIndent(), null
            )
        }
    }

    inner class InputBridge {
        @JavascriptInterface
        fun onInputFocused(focused: Boolean) {
            onInputFocused?.invoke(focused)
        }
    }

    // ==================== Cleanup ====================

    fun destroy() {
        stopRendering()
        exitFullscreen()
        abandonAudioFocus()
        mainHandler.post {
            webView?.destroy()
            webView = null
            renderBitmap?.recycle()
            renderBitmap = null
        }
        surface = null
    }
}
