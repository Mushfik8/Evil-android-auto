package com.autofreedom.app.renderer

import android.util.Log

/**
 * JavaScript code generator for extracting video URLs from web pages.
 *
 * This injects JavaScript into WebView pages that:
 * 1. Monitors the DOM for <video> elements using MutationObserver
 * 2. Extracts src URLs from <video> and <source> elements
 * 3. Intercepts XMLHttpRequest/fetch for .m3u8/.mpd manifest URLs
 * 4. Sends found URLs back to native code via JavascriptInterface
 *
 * Supports:
 * - YouTube (via ytInitialPlayerResponse parsing)
 * - Bioscope (standard HLS)
 * - Chorki (standard HLS/MP4)
 * - Hoichoi (standard HLS)
 * - Generic HTML5 video players
 */
object VideoExtractor {

    private const val TAG = "VideoExtractor"

    /**
     * JavaScript to inject into every page after it loads.
     * Monitors for video elements and streaming manifest URLs.
     */
    fun getExtractionScript(): String = """
        (function() {
            if (window._afVideoExtractorInstalled) return;
            window._afVideoExtractorInstalled = true;

            var foundUrls = {};

            function reportVideoUrl(url, source) {
                if (!url || url === '' || url.startsWith('blob:') || foundUrls[url]) return;
                foundUrls[url] = true;
                try {
                    AutoFreedomBridge.onVideoUrlFound(url, source || 'unknown');
                } catch(e) {}
            }

            // === 1. Scan existing <video> elements ===
            function scanVideoElements() {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    if (v.src && !v.src.startsWith('blob:')) {
                        reportVideoUrl(v.src, 'video-src');
                    }
                    if (v.currentSrc && !v.currentSrc.startsWith('blob:')) {
                        reportVideoUrl(v.currentSrc, 'video-currentSrc');
                    }
                    var sources = v.querySelectorAll('source');
                    for (var j = 0; j < sources.length; j++) {
                        if (sources[j].src) {
                            reportVideoUrl(sources[j].src, 'source-element');
                        }
                    }
                }
            }

            // === 2. MutationObserver for dynamically added videos ===
            var observer = new MutationObserver(function(mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    var nodes = mutations[i].addedNodes;
                    for (var j = 0; j < nodes.length; j++) {
                        var node = nodes[j];
                        if (node.tagName === 'VIDEO') {
                            setTimeout(function() { scanVideoElements(); }, 500);
                        }
                        if (node.querySelectorAll) {
                            var vids = node.querySelectorAll('video');
                            if (vids.length > 0) {
                                setTimeout(function() { scanVideoElements(); }, 500);
                            }
                        }
                    }
                }
            });
            observer.observe(document.body || document.documentElement, {
                childList: true,
                subtree: true
            });

            // === 3. Intercept XHR for .m3u8 / .mpd manifest URLs ===
            var origXhrOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                if (typeof url === 'string') {
                    if (url.includes('.m3u8') || url.includes('.mpd') ||
                        url.includes('manifest') || url.includes('/video/')) {
                        reportVideoUrl(url, 'xhr-' + method);
                    }
                }
                return origXhrOpen.apply(this, arguments);
            };

            // === 4. Intercept fetch for streaming URLs ===
            var origFetch = window.fetch;
            window.fetch = function(input) {
                var url = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';
                if (url.includes('.m3u8') || url.includes('.mpd') ||
                    url.includes('manifest') || url.includes('/video/')) {
                    reportVideoUrl(url, 'fetch');
                }
                return origFetch.apply(this, arguments);
            };

            // === 5. YouTube-specific: Parse ytInitialPlayerResponse ===
            function extractYouTubeUrl() {
                try {
                    if (window.ytInitialPlayerResponse) {
                        var sr = window.ytInitialPlayerResponse.streamingData;
                        if (sr) {
                            // Try adaptive formats first (better quality)
                            var formats = sr.adaptiveFormats || sr.formats || [];
                            for (var i = 0; i < formats.length; i++) {
                                var f = formats[i];
                                if (f.url && f.mimeType && f.mimeType.startsWith('video/')) {
                                    reportVideoUrl(f.url, 'youtube-adaptive');
                                    return;
                                }
                            }
                            // Try regular formats
                            formats = sr.formats || [];
                            for (var i = 0; i < formats.length; i++) {
                                if (formats[i].url) {
                                    reportVideoUrl(formats[i].url, 'youtube-format');
                                    return;
                                }
                            }
                            // Try HLS manifest
                            if (sr.hlsManifestUrl) {
                                reportVideoUrl(sr.hlsManifestUrl, 'youtube-hls');
                                return;
                            }
                        }
                    }
                } catch(e) {}
            }

            // === 6. Generic: Monitor video play events ===
            document.addEventListener('play', function(e) {
                if (e.target && e.target.tagName === 'VIDEO') {
                    var v = e.target;
                    if (v.src && !v.src.startsWith('blob:')) {
                        reportVideoUrl(v.src, 'play-event');
                    }
                    if (v.currentSrc && !v.currentSrc.startsWith('blob:')) {
                        reportVideoUrl(v.currentSrc, 'play-event-current');
                    }
                }
            }, true);

            // === 7. Monitor for video source changes ===
            document.addEventListener('loadeddata', function(e) {
                if (e.target && e.target.tagName === 'VIDEO') {
                    scanVideoElements();
                }
            }, true);

            // Run initial scan
            setTimeout(function() {
                scanVideoElements();
                extractYouTubeUrl();
            }, 1000);

            // Re-scan periodically (for SPAs that add videos dynamically)
            setInterval(function() {
                scanVideoElements();
            }, 3000);
        })();
    """.trimIndent()

    /**
     * JavaScript to force-pause all HTML5 videos on the page.
     * Used when switching to ExoPlayer direct rendering.
     */
    fun getPauseAllVideosScript(): String = """
        (function() {
            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
                videos[i].pause();
                videos[i].muted = true;
            }
        })();
    """.trimIndent()

    /**
     * JavaScript to mute all HTML5 videos (audio goes through ExoPlayer instead).
     */
    fun getMuteAllVideosScript(): String = """
        (function() {
            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
                videos[i].muted = true;
                videos[i].volume = 0;
            }
        })();
    """.trimIndent()

    /**
     * Check if a URL looks like a playable video URL.
     */
    fun isPlayableVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.startsWith("blob:")) return false

        val lower = url.lowercase()
        return lower.contains(".mp4") ||
                lower.contains(".webm") ||
                lower.contains(".m3u8") ||
                lower.contains(".mpd") ||
                lower.contains(".m4v") ||
                lower.contains(".mov") ||
                lower.contains("videoplayback") ||  // YouTube
                lower.contains("/video/") ||
                lower.contains("manifest") ||
                lower.contains("stream")
    }

    /**
     * Filter and prioritize video URLs.
     * Prefer HLS/DASH manifests over direct URLs, and higher quality.
     */
    fun prioritizeUrl(urls: List<String>): String? {
        if (urls.isEmpty()) return null

        // Prefer HLS manifests (most compatible)
        val hls = urls.find { it.contains(".m3u8") }
        if (hls != null) return hls

        // Then DASH manifests
        val dash = urls.find { it.contains(".mpd") }
        if (dash != null) return dash

        // Then direct MP4
        val mp4 = urls.find { it.contains(".mp4") }
        if (mp4 != null) return mp4

        // Then WebM
        val webm = urls.find { it.contains(".webm") }
        if (webm != null) return webm

        // Fallback: first URL
        return urls.first()
    }
}
