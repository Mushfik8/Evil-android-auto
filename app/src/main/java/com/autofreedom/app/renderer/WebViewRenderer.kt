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
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
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
 * VIDEO RENDERING STRATEGY (3-tier approach):
 *
 * 1. VIDEO URL EXTRACTION (BEST — ExoPlayer direct to Surface):
 *    JavaScript extracts video URLs from the page (via VideoExtractor).
 *    When found, SurfaceVideoPlayer renders the video directly to the AA Surface
 *    using hardware-accelerated ExoPlayer. Audio goes through car speakers.
 *    This bypasses WebView entirely for video and gives full framerate.
 *
 * 2. FULLSCREEN VIDEO CAPTURE (GOOD — TextureView.getBitmap):
 *    When onShowCustomView() is called (user taps fullscreen), we get the video View.
 *    If it's a TextureView, we use getBitmap() to capture frames.
 *    If it contains a TextureView child, we find and capture that.
 *    This gives us actual video frames at ~20fps.
 *
 * 3. WEBVIEW BITMAP CAPTURE (FALLBACK — for browsing/non-video):
 *    Standard View.draw(Canvas) for normal web browsing.
 *    Video elements will appear black but everything else renders fine.
 *
 * Audio: Requests AudioFocus so sound comes through the car speakers.
 * User Agent: Spoofs Chrome Mobile so YouTube/streaming sites work.
 */
class WebViewRenderer(private val context: Context) {

    companion object {
        private const val TAG = "WebViewRenderer"
        private const val RENDER_INTERVAL_BROWSE_MS = 50L   // ~20fps for browsing
        private const val RENDER_INTERVAL_VIDEO_MS = 33L    // ~30fps for video
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

    // Direct video player (ExoPlayer → Surface)
    private var surfaceVideoPlayer: SurfaceVideoPlayer? = null
    private val isDirectVideoPlaying = AtomicBoolean(false)
    private val extractedVideoUrls = mutableListOf<String>()

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
    var onVideoModeChanged: ((Boolean) -> Unit)? = null

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

                // Init direct video player
                surfaceVideoPlayer = SurfaceVideoPlayer(context).apply {
                    onVideoStarted = {
                        Log.i(TAG, "🎬 Direct video started playing on Surface")
                        isDirectVideoPlaying.set(true)
                        // Mute WebView audio to avoid double-playing
                        mainHandler.post { injectScript(VideoExtractor.getMuteAllVideosScript()) }
                        onVideoModeChanged?.invoke(true)
                    }
                    onVideoEnded = {
                        Log.i(TAG, "🎬 Direct video ended")
                        isDirectVideoPlaying.set(false)
                        onVideoModeChanged?.invoke(false)
                    }
                    onVideoError = { error ->
                        Log.w(TAG, "Direct video error: $error, falling back to WebView")
                        isDirectVideoPlaying.set(false)
                        onVideoModeChanged?.invoke(false)
                    }
                }

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
                                // Clear extracted URLs for new page
                                extractedVideoUrls.clear()
                                onPageStarted?.invoke(it)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let {
                                currentUrl.set(it)
                                onPageFinished?.invoke(it)
                            }
                            injectInputDetection()
                            // Inject video URL extractor
                            injectScript(VideoExtractor.getExtractionScript())
                            // Inject responsive CSS helper
                            injectResponsiveCSS()
                            // Inject auto-fullscreen helper
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
                         * video View.
                         *
                         * Strategy:
                         * 1. Check if we have an extracted video URL → use ExoPlayer direct
                         * 2. Check if view is/contains TextureView → capture via getBitmap()
                         * 3. Fallback: try View.draw() (may produce black for video)
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

                            // Strategy 1: Try direct ExoPlayer with extracted URL
                            val bestUrl = VideoExtractor.prioritizeUrl(extractedVideoUrls)
                            if (bestUrl != null) {
                                Log.i(TAG, "🎬 Using extracted URL for direct playback: $bestUrl")
                                val s = surface
                                if (s != null && s.isValid) {
                                    surfaceVideoPlayer?.play(bestUrl, s)
                                    // Pause the WebView video to avoid double audio
                                    injectScript(VideoExtractor.getPauseAllVideosScript())
                                    return
                                }
                            }

                            // Strategy 2/3: TextureView capture or View.draw fallback
                            // Request audio focus for video sound
                            requestAudioFocus()
                            Log.i(TAG, "🎬 Using fullscreen view capture (TextureView/View.draw)")
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

                            // Stop direct video player if active
                            if (isDirectVideoPlaying.get()) {
                                surfaceVideoPlayer?.stop()
                                isDirectVideoPlaying.set(false)
                                onVideoModeChanged?.invoke(false)
                            }

                            abandonAudioFocus()
                        }
                    }

                    // JavaScript bridge — includes video URL reporting
                    addJavascriptInterface(NativeBridge(), "AutoFreedomBridge")

                    // SOFTWARE layer forces bitmap capture to include all content
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }

                // Reusable bitmap
                renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                isInitialized.set(true)
                Log.i(TAG, "WebView initialized: ${width}x${height} with Chrome UA + VideoExtractor")

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
        // Update surface for direct video player
        surfaceVideoPlayer?.updateSurface(surface)
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
     * CORE RENDER LOOP — 3-tier rendering strategy:
     * 1. Direct ExoPlayer video → skip bitmap entirely (ExoPlayer renders to Surface)
     * 2. Fullscreen TextureView → getBitmap() for video frames
     * 3. WebView bitmap → View.draw() for normal browsing
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

                // TIER 1: Direct ExoPlayer rendering — no bitmap needed!
                // ExoPlayer renders directly to the Surface via hardware decoder.
                // We just need to schedule the next check.
                if (isDirectVideoPlaying.get()) {
                    scheduleNextFrame()
                    return@post
                }

                val bmp = renderBitmap ?: return@post
                val bitmapCanvas = Canvas(bmp)

                if (isFullscreenVideo.get() && fullscreenVideoView != null) {
                    // TIER 2: Fullscreen video capture
                    val videoView = fullscreenVideoView!!

                    // Re-layout in case size changed
                    videoView.measure(
                        View.MeasureSpec.makeMeasureSpec(surfaceWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(surfaceHeight, View.MeasureSpec.EXACTLY)
                    )
                    videoView.layout(0, 0, surfaceWidth, surfaceHeight)

                    // Clear with black
                    bitmapCanvas.drawColor(Color.BLACK)

                    // Try to find a TextureView inside the fullscreen view
                    val textureView = findTextureView(videoView)
                    if (textureView != null) {
                        // TextureView.getBitmap() captures actual video frames!
                        val videoBmp = textureView.bitmap
                        if (videoBmp != null) {
                            // Scale video to fit surface
                            val srcRatio = videoBmp.width.toFloat() / videoBmp.height
                            val dstRatio = surfaceWidth.toFloat() / surfaceHeight
                            val dstRect = if (srcRatio > dstRatio) {
                                // Video wider than surface — fit width, letterbox height
                                val h = (surfaceWidth / srcRatio).toInt()
                                val top = (surfaceHeight - h) / 2
                                android.graphics.Rect(0, top, surfaceWidth, top + h)
                            } else {
                                // Video taller — fit height, pillarbox width
                                val w = (surfaceHeight * srcRatio).toInt()
                                val left = (surfaceWidth - w) / 2
                                android.graphics.Rect(left, 0, left + w, surfaceHeight)
                            }
                            bitmapCanvas.drawBitmap(videoBmp, null, dstRect, null)
                            videoBmp.recycle()
                        } else {
                            // TextureView returned null bitmap — draw the view directly
                            videoView.draw(bitmapCanvas)
                        }
                    } else {
                        // No TextureView found — use View.draw() fallback
                        videoView.draw(bitmapCanvas)
                    }
                } else {
                    // TIER 3: Normal browsing — WebView bitmap capture
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

    /**
     * Recursively search for a TextureView inside a view hierarchy.
     * Fullscreen video views often wrap the actual TextureView in a FrameLayout.
     */
    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun scheduleNextFrame() {
        if (isRendering.get()) {
            val interval = if (isFullscreenVideo.get() || isDirectVideoPlaying.get()) {
                RENDER_INTERVAL_VIDEO_MS  // Faster for video
            } else {
                RENDER_INTERVAL_BROWSE_MS  // Normal for browsing
            }
            mainHandler.postDelayed({ renderLoop() }, interval)
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
            // Stop direct video if playing
            if (isDirectVideoPlaying.get()) {
                stopDirectVideo()
            }

            // Clear extracted URLs for new navigation
            extractedVideoUrls.clear()

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
        if (isDirectVideoPlaying.get()) {
            stopDirectVideo()
            return true
        }
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
    fun canGoBack(): Boolean = webView?.canGoBack() == true || isFullscreenVideo.get() || isDirectVideoPlaying.get()
    fun canGoForward(): Boolean = webView?.canGoForward() == true

    // ==================== Direct Video Controls ====================

    /**
     * Check if direct video (ExoPlayer) is currently playing.
     */
    fun isDirectVideoActive(): Boolean = isDirectVideoPlaying.get()

    /**
     * Toggle play/pause for direct video.
     */
    fun toggleDirectVideo() {
        surfaceVideoPlayer?.togglePlayPause()
    }

    /**
     * Seek forward in direct video.
     */
    fun seekDirectVideoForward(ms: Long = 10_000) {
        surfaceVideoPlayer?.seekForward(ms)
    }

    /**
     * Seek backward in direct video.
     */
    fun seekDirectVideoBack(ms: Long = 10_000) {
        surfaceVideoPlayer?.seekBack(ms)
    }

    /**
     * Stop direct video and return to WebView rendering.
     */
    fun stopDirectVideo() {
        surfaceVideoPlayer?.stop()
        isDirectVideoPlaying.set(false)
        onVideoModeChanged?.invoke(false)
    }

    /**
     * Try to play the best extracted video URL directly.
     * Called when user explicitly wants to switch to direct video mode.
     */
    fun tryDirectVideoPlayback(): Boolean {
        val bestUrl = VideoExtractor.prioritizeUrl(extractedVideoUrls)
        if (bestUrl != null) {
            val s = surface
            if (s != null && s.isValid) {
                Log.i(TAG, "🎬 Manual trigger: playing $bestUrl directly")
                surfaceVideoPlayer?.play(bestUrl, s)
                injectScript(VideoExtractor.getPauseAllVideosScript())
                return true
            }
        }
        return false
    }

    /**
     * Check if we have extracted video URLs available.
     */
    fun hasExtractedVideoUrls(): Boolean = extractedVideoUrls.isNotEmpty()

    // ==================== Touch Events ====================

    fun handleClick(x: Float, y: Float) {
        mainHandler.post {
            // If direct video is playing, toggle play/pause
            if (isDirectVideoPlaying.get()) {
                surfaceVideoPlayer?.togglePlayPause()
                return@post
            }

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
            if (isFullscreenVideo.get() || isDirectVideoPlaying.get()) return@post
            webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
        }
    }

    fun handleFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            if (isFullscreenVideo.get() || isDirectVideoPlaying.get()) return@post
            webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
        }
    }

    fun handleScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        mainHandler.post {
            if (isFullscreenVideo.get() || isDirectVideoPlaying.get()) return@post
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

    private fun injectScript(js: String) {
        mainHandler.post {
            webView?.evaluateJavascript(js, null)
        }
    }

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

    /**
     * Inject responsive CSS to make websites render properly on car screens.
     */
    private fun injectResponsiveCSS() {
        mainHandler.post {
            webView?.evaluateJavascript(
                """
                (function() {
                    if (window._afResponsive) return;
                    window._afResponsive = true;

                    // Ensure viewport meta tag exists for proper mobile rendering
                    var viewport = document.querySelector('meta[name="viewport"]');
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes';
                        document.head.appendChild(viewport);
                    }

                    // Add CSS for better car-screen readability
                    var style = document.createElement('style');
                    style.textContent = [
                        '/* AutoFreedom responsive enhancements */',
                        'body { -webkit-text-size-adjust: 100%; }',
                        'video { max-width: 100%; height: auto; }',
                        'img { max-width: 100%; height: auto; }'
                    ].join('\n');
                    document.head.appendChild(style);
                })();
                """.trimIndent(), null
            )
        }
    }

    /**
     * JavaScript bridge — receives callbacks from injected JavaScript.
     */
    inner class NativeBridge {
        @JavascriptInterface
        fun onInputFocused(focused: Boolean) {
            onInputFocused?.invoke(focused)
        }

        /**
         * Called by VideoExtractor JavaScript when a video URL is found.
         */
        @JavascriptInterface
        fun onVideoUrlFound(url: String, source: String) {
            Log.i(TAG, "🎬 Video URL found [$source]: $url")
            if (VideoExtractor.isPlayableVideoUrl(url)) {
                mainHandler.post {
                    if (!extractedVideoUrls.contains(url)) {
                        extractedVideoUrls.add(url)
                        Log.i(TAG, "🎬 Added playable URL (${extractedVideoUrls.size} total): $url")
                    }
                }
            }
        }
    }

    // ==================== Cleanup ====================

    fun destroy() {
        stopRendering()
        exitFullscreen()
        stopDirectVideo()
        abandonAudioFocus()
        mainHandler.post {
            webView?.destroy()
            webView = null
            renderBitmap?.recycle()
            renderBitmap = null
        }
        surfaceVideoPlayer?.stop()
        surfaceVideoPlayer = null
        surface = null
    }
}
