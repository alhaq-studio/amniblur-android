package com.amngaze

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class ViewportMediaItem(
    val id: String,
    val tagName: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val viewportWidth: Float,
    val viewportHeight: Float
)

data class MediaVerdict(
    val id: String,
    val shouldBlur: Boolean,
    val reason: String,
    val confidence: Float
)

/**
 * Tracks per-platform daily browsing time inside the Protected In-App WebView.
 */
object PlatformUsageTracker {
    fun isDailyLimitReached(
        platformId: String,
        dailyLimitMinutes: Int,
        usageSecondsMap: Map<String, Int>
    ): Boolean {
        if (dailyLimitMinutes <= 0) return false
        val usedSeconds = usageSecondsMap[platformId] ?: 0
        return usedSeconds >= (dailyLimitMinutes * 60)
    }

    fun remainingSeconds(
        platformId: String,
        dailyLimitMinutes: Int,
        usageSecondsMap: Map<String, Int>
    ): Int {
        if (dailyLimitMinutes <= 0) return Int.MAX_VALUE
        val usedSeconds = usageSecondsMap[platformId] ?: 0
        return ((dailyLimitMinutes * 60) - usedSeconds).coerceAtLeast(0)
    }
}

/**
 * Helper for creating and pinning Android launcher home-screen shortcuts that deep-link
 * directly into AmnGaze's protected WebView for a given [ConnectedPlatform].
 */
object ShortcutHelper {
    const val EXTRA_TARGET_PLATFORM_ID = "com.amngaze.extra.TARGET_PLATFORM_ID"
    const val EXTRA_TARGET_PLATFORM_URL = "com.amngaze.extra.TARGET_PLATFORM_URL"

    fun buildPlatformDeepLinkIntent(context: Context, platform: ConnectedPlatform): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(platform.url)
            putExtra(EXTRA_TARGET_PLATFORM_ID, platform.id)
            putExtra(EXTRA_TARGET_PLATFORM_URL, platform.url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    fun requestPinPlatformShortcut(context: Context, platform: ConnectedPlatform): Boolean {
        return try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                return false
            }
            val shortcutIntent = buildPlatformDeepLinkIntent(context, platform)
            val shortcutInfo = ShortcutInfoCompat.Builder(context, "amngaze_platform_${platform.id}")
                .setShortLabel(platform.name)
                .setLongLabel("Protected ${platform.name}")
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(shortcutIntent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        } catch (t: Throwable) {
            Log.w("ShortcutHelper", "Could not pin shortcut for ${platform.name}", t)
            false
        }
    }
}

/**
 * In-App Protected WebView Real-Time DOM-Locked Blurring & Feed Control Bridge.
 *
 * - Injects a lightweight `MutationObserver` + `IntersectionObserver` script that batches visible
 *   `<img>`, `<video>`, `<picture>`, and background-image elements every `220ms` (plus `900ms`
 *   re-evaluation for playing `<video>` elements).
 * - Captures the current WebView viewport into a reusable `480px`-wide software [Bitmap] on the
 *   main thread (`webView.draw(canvas)` — requires zero OS permissions and bypasses all CDN CORS
 *   restrictions on Instagram, X, YouTube, and Facebook).
 * - Crops each reported media bounding box (`mapCssRectToBitmapRect`), evaluates NSFW + Gender/Face
 *   via [ContentModerationEngine], and applies DOM-locked CSS filters (`window.__amngazeApplyVerdicts`)
 *   that scroll natively at 120Hz with zero drift and zero self-occlusion flicker.
 */
class WebViewModerationBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val moderationEngine: ContentModerationEngine,
    private val stateProvider: () -> AmnGazeState,
    private val onDetectionRecorded: () -> Unit = {}
) {
    companion object {
        private const val TAG = "WebViewModBridge"
        const val VIEWPORT_CAPTURE_WIDTH = 480
        const val MAX_BATCH_TILES = 16

        /**
         * Maps a CSS viewport bounding box ([ViewportMediaItem]) to clamped pixel coordinates
         * inside a [bitmapWidth] x [bitmapHeight] viewport bitmap.
         * Returns `null` if the element is off-screen or smaller than `8x8` pixels.
         */
        fun mapCssRectToBitmapRect(
            item: ViewportMediaItem,
            bitmapWidth: Int,
            bitmapHeight: Int
        ): Rect? {
            if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
            if (item.viewportWidth <= 0f || item.viewportHeight <= 0f) return null
            if (item.width <= 4f || item.height <= 4f) return null

            val scaleX = bitmapWidth.toFloat() / item.viewportWidth
            val scaleY = bitmapHeight.toFloat() / item.viewportHeight

            val rawLeft = (item.left * scaleX).roundToInt()
            val rawTop = (item.top * scaleY).roundToInt()
            val rawRight = ((item.left + item.width) * scaleX).roundToInt()
            val rawBottom = ((item.top + item.height) * scaleY).roundToInt()

            val clampedLeft = rawLeft.coerceIn(0, bitmapWidth - 1)
            val clampedTop = rawTop.coerceIn(0, bitmapHeight - 1)
            val clampedRight = rawRight.coerceIn(clampedLeft + 1, bitmapWidth)
            val clampedBottom = rawBottom.coerceIn(clampedTop + 1, bitmapHeight)

            val cropW = clampedRight - clampedLeft
            val cropH = clampedBottom - clampedTop
            if (cropW < 8 || cropH < 8) return null

            return Rect(clampedLeft, clampedTop, clampedRight, clampedBottom)
        }

        /**
         * Parses the JSON array sent from JavaScript `onVisibleMediaBatch(jsonBatch)`.
         */
        fun parseVisibleMediaBatchJson(jsonBatch: String): List<ViewportMediaItem> {
            if (jsonBatch.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonBatch)
                val items = ArrayList<ViewportMediaItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) continue
                    items.add(
                        ViewportMediaItem(
                            id = id,
                            tagName = obj.optString("tag", "IMG").uppercase(),
                            left = obj.optDouble("left", 0.0).toFloat(),
                            top = obj.optDouble("top", 0.0).toFloat(),
                            width = obj.optDouble("width", 0.0).toFloat(),
                            height = obj.optDouble("height", 0.0).toFloat(),
                            viewportWidth = obj.optDouble("vw", 360.0).toFloat().coerceAtLeast(1f),
                            viewportHeight = obj.optDouble("vh", 640.0).toFloat().coerceAtLeast(1f)
                        )
                    )
                }
                items
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse visible media JSON batch", e)
                emptyList()
            }
        }

        /**
         * Builds the JSON payload passed to `window.__amngazeApplyVerdicts(...)`.
         */
        fun buildVerdictsJson(
            verdicts: List<MediaVerdict>,
            style: HaramBlurOverlayStyle,
            strengthPx: Int
        ): String {
            val root = JSONObject()
            root.put("style", style.name)
            root.put("strengthPx", strengthPx.coerceIn(1, 20))
            val arr = JSONArray()
            for (v in verdicts) {
                val obj = JSONObject()
                obj.put("id", v.id)
                obj.put("blur", v.shouldBlur)
                obj.put("reason", v.reason)
                obj.put("confidence", v.confidence.toDouble())
                arr.put(obj)
            }
            root.put("verdicts", arr)
            return root.toString()
        }

        /**
         * Generates CSS selectors to hide distracting short-form or algorithmic feeds based on [FeedControlConfig].
         */
        fun generateFeedControlCss(config: FeedControlConfig): String {
            val rules = StringBuilder()
            if (config.hideYoutubeShorts) {
                rules.append(
                    "ytm-reel-shelf-renderer, ytd-reel-shelf-renderer, ytd-rich-shelf-renderer[is-shorts], " +
                        "a[href*=\"/shorts/\"], ytm-pivot-bar-item-renderer:nth-child(2) { display: none !important; }\n"
                )
            }
            if (config.hideInstagramReels) {
                rules.append(
                    "a[href*=\"/reels/\"], a[href*=\"/explore/\"], div[aria-label=\"Reels\"] { display: none !important; }\n"
                )
            }
            if (config.hideAlgorithmicFeeds) {
                rules.append(
                    "[aria-label=\"Timeline: Trending now\"], [data-testid=\"sidebarColumn\"], " +
                        "div[role=\"tablist\"] a[href*=\"/home\"]:first-child { display: none !important; }\n"
                )
            }
            if (config.hideComments) {
                rules.append(
                    "ytm-comment-section-renderer, ytd-comments, #comments, [data-testid=\"tweetTextarea_0\"] { display: none !important; }\n"
                )
            }
            return rules.toString()
        }

        /**
         * Generates the complete DOM Observer & CSS Filter JavaScript injected into the WebView.
         */
        fun generateDomObserverJavascript(
            whatToProtect: Set<WhatToProtectItem>,
            blurStyle: HaramBlurOverlayStyle,
            strengthPx: Int,
            feedControlConfig: FeedControlConfig,
            preBlurNewMediaUntilScanned: Boolean = false
        ): String {
            val protectImages = WhatToProtectItem.IMAGES in whatToProtect
            val protectVideos = WhatToProtectItem.VIDEOS in whatToProtect
            val feedCss = generateFeedControlCss(feedControlConfig)
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")

            return """
                (function() {
                    window.__amngazePreBlurEnabled = $preBlurNewMediaUntilScanned;
                    window.__amngazeDefaultStyle = '${blurStyle.name}';
                    window.__amngazeDefaultStrengthPx = $strengthPx;
                    if (window.__amngazeInitialized) {
                        if (window.__amngazeUpdateFeedCss) {
                            window.__amngazeUpdateFeedCss('$feedCss');
                        }
                        if (window.__amngazeRefreshActiveOverlays) {
                            window.__amngazeRefreshActiveOverlays('${blurStyle.name}', $strengthPx);
                        }
                        return;
                    }
                    window.__amngazeInitialized = true;
                    var nextId = 1;
                    var protectImages = $protectImages;
                    var protectVideos = $protectVideos;

                    var styleEl = document.createElement('style');
                    styleEl.id = '__amngaze_feed_style';
                    styleEl.innerHTML = '$feedCss [data-amngaze-verdict="BLURRED"] { position: relative !important; background-color: #1E293B !important; opacity: 1 !important; } [data-amngaze-verdict="BLURRED"]::before { content: ""; position: absolute; inset: 0; background-color: #1E293B; pointer-events: none; z-index: 9999; } html.__amngaze-clean-capture [data-amngaze-verdict="PREBLUR"] { filter: none !important; transition: none !important; }';
                    (document.head || document.documentElement).appendChild(styleEl);

                    window.__amngazeUpdateFeedCss = function(cssText) {
                        var el = document.getElementById('__amngaze_feed_style');
                        if (el) el.innerHTML = cssText + ' [data-amngaze-verdict="BLURRED"] { position: relative !important; background-color: #1E293B !important; opacity: 1 !important; } [data-amngaze-verdict="BLURRED"]::before { content: ""; position: absolute; inset: 0; background-color: #1E293B; pointer-events: none; z-index: 9999; } html.__amngaze-clean-capture [data-amngaze-verdict="PREBLUR"] { filter: none !important; transition: none !important; }';
                    };

                    window.__amngazePrepareCleanCapture = function() {
                        document.documentElement.classList.add('__amngaze-clean-capture');
                    };

                    window.__amngazeRestoreAfterCapture = function() {
                        document.documentElement.classList.remove('__amngaze-clean-capture');
                        if (window.__amngazePreBlurEnabled) {
                            var preNodes = document.querySelectorAll('[data-amngaze-verdict="PREBLUR"]');
                            for (var i = 0; i < preNodes.length; i++) {
                                applyOverlayStyle(preNodes[i], window.__amngazeDefaultStyle, window.__amngazeDefaultStrengthPx);
                            }
                        }
                    };

                    function applyOverlayStyle(el, styleName, px) {
                        el.style.opacity = '1';
                        if (styleName === 'BLACK') {
                            el.style.filter = 'brightness(0) contrast(0)';
                            el.style.backgroundColor = '#000000';
                        } else if (styleName === 'GRAY') {
                            el.style.filter = 'brightness(0) invert(0.45)';
                            el.style.backgroundColor = '#64748B';
                        } else if (styleName === 'SKIN') {
                            el.style.filter = 'brightness(0) invert(0.80) sepia(0.35)';
                            el.style.backgroundColor = '#D4A373';
                        } else if (styleName === 'PIXELATE') {
                            el.style.filter = 'blur(28px) brightness(0.22) contrast(0.4)';
                            el.style.backgroundColor = '#0F172A';
                        } else {
                            // BLUR style: 100% opaque blur filter paired with dark slate backdrop
                            el.style.filter = 'blur(28px) brightness(0.22) contrast(0.4)';
                            el.style.backgroundColor = '#1E293B';
                        }
                        el.style.transition = 'filter 0.10s ease-out';
                    }

                    window.__amngazeRefreshActiveOverlays = function(styleName, px) {
                        var blurredNodes = document.querySelectorAll('[data-amngaze-verdict="BLURRED"]');
                        for (var i = 0; i < blurredNodes.length; i++) {
                            applyOverlayStyle(blurredNodes[i], styleName, px);
                        }
                    };

                    window.__amngazeApplyVerdicts = function(payload) {
                        try {
                            var data = (typeof payload === 'string') ? JSON.parse(payload) : payload;
                            var styleName = data.style || window.__amngazeDefaultStyle || '${blurStyle.name}';
                            var px = data.strengthPx || window.__amngazeDefaultStrengthPx || $strengthPx;
                            var list = data.verdicts || [];
                            for (var i = 0; i < list.length; i++) {
                                var v = list[i];
                                var el = document.querySelector('[data-amngaze-id="' + v.id + '"]');
                                if (!el) continue;
                                if (v.blur) {
                                    el.dataset.amngazeVerdict = 'BLURRED';
                                    applyOverlayStyle(el, styleName, px);
                                } else {
                                    el.dataset.amngazeVerdict = 'SAFE';
                                    el.style.filter = '';
                                    el.style.backgroundColor = '';
                                }
                            }
                        } catch (e) {}
                    };

                    function getMediaSrcKey(el) {
                        return (el.currentSrc || el.src || el.getAttribute('srcset') || el.getAttribute('style') || '');
                    }

                    var intersectionObserver = null;
                    if (typeof IntersectionObserver !== 'undefined') {
                        intersectionObserver = new IntersectionObserver(function(entries) {
                            var anyIntersecting = false;
                            for (var i = 0; i < entries.length; i++) {
                                if (entries[i].isIntersecting) {
                                    anyIntersecting = true;
                                    break;
                                }
                            }
                            if (anyIntersecting) {
                                collectVisibleMedia();
                            }
                        }, { rootMargin: '80px', threshold: 0.05 });
                    }

                    function collectVisibleMedia() {
                        if (!window.AmnGazeBridge) return;
                        var vw = Math.max(window.innerWidth || 360, 1);
                        var vh = Math.max(window.innerHeight || 640, 1);
                        var selectors = [];
                        if (protectImages) {
                            selectors.push('img');
                            selectors.push('picture');
                            selectors.push('[style*="background-image"]');
                        }
                        if (protectVideos) {
                            selectors.push('video');
                        }
                        if (selectors.length === 0) return;

                        var nodes = document.querySelectorAll(selectors.join(','));
                        var batch = [];
                        var hasMorePending = false;
                        var now = Date.now();
                        for (var i = 0; i < nodes.length; i++) {
                            var el = nodes[i];
                            if (intersectionObserver && !el.dataset.amngazeObserved) {
                                el.dataset.amngazeObserved = '1';
                                try { intersectionObserver.observe(el); } catch (e) {}
                            }
                            var tag = (el.tagName || 'IMG').toUpperCase();
                            var srcKey = getMediaSrcKey(el);
                            if (srcKey && el.dataset.amngazeSrc && el.dataset.amngazeSrc !== srcKey) {
                                el.dataset.amngazeVerdict = '';
                                el.dataset.amngazeLastScan = '0';
                            }
                            if (srcKey) {
                                el.dataset.amngazeSrc = srcKey;
                            }

                            var verdict = el.dataset.amngazeVerdict || '';
                            var lastScan = parseInt(el.dataset.amngazeLastScan || '0', 10);

                            if (tag !== 'VIDEO' && (verdict === 'SAFE' || verdict === 'BLURRED')) {
                                continue;
                            }
                            if (tag === 'VIDEO' && (now - lastScan) < 750) {
                                continue;
                            }

                            var rect = el.getBoundingClientRect();
                            if (rect.width < 32 || rect.height < 32) continue;
                            if (rect.bottom < 0 || rect.top > vh || rect.right < 0 || rect.left > vw) continue;

                            if (batch.length >= $MAX_BATCH_TILES) {
                                hasMorePending = true;
                                if (window.__amngazePreBlurEnabled && !verdict) {
                                    el.dataset.amngazeVerdict = 'PREBLUR';
                                    applyOverlayStyle(el, window.__amngazeDefaultStyle, window.__amngazeDefaultStrengthPx);
                                }
                                continue;
                            }

                            if (!el.dataset.amngazeId) {
                                el.dataset.amngazeId = 'ag_' + (nextId++);
                            }
                            if (window.__amngazePreBlurEnabled && !verdict) {
                                el.dataset.amngazeVerdict = 'PREBLUR';
                            }
                            el.dataset.amngazeLastScan = String(now);
                            batch.push({
                                id: el.dataset.amngazeId,
                                tag: tag,
                                left: rect.left,
                                top: rect.top,
                                width: rect.width,
                                height: rect.height,
                                vw: vw,
                                vh: vh
                            });
                        }
                        if (batch.length > 0) {
                            if (window.__amngazePreBlurEnabled && window.__amngazePrepareCleanCapture) {
                                window.__amngazePrepareCleanCapture();
                            }
                            window.AmnGazeBridge.onVisibleMediaBatch(JSON.stringify(batch));
                        }
                        if (hasMorePending) {
                            setTimeout(collectVisibleMedia, 160);
                        }
                    }

                    if (typeof MutationObserver !== 'undefined') {
                        var domObserver = new MutationObserver(function(mutations) {
                            var shouldScan = false;
                            for (var m = 0; m < mutations.length; m++) {
                                var mut = mutations[m];
                                if (mut.type === 'attributes' && mut.target) {
                                    mut.target.dataset.amngazeVerdict = '';
                                    mut.target.dataset.amngazeLastScan = '0';
                                    shouldScan = true;
                                } else if (mut.addedNodes && mut.addedNodes.length > 0) {
                                    shouldScan = true;
                                }
                            }
                            if (shouldScan) {
                                collectVisibleMedia();
                            }
                        });
                        try {
                            domObserver.observe(document.documentElement || document.body, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['src', 'srcset', 'style']
                            });
                        } catch (e) {}
                    }

                    setInterval(collectVisibleMedia, 200);
                    window.addEventListener('scroll', function() {
                        setTimeout(collectVisibleMedia, 120);
                    }, { passive: true });
                })();
            """.trimIndent()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBatchProcessing = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var pendingBatchJson: String? = null

    @Volatile
    private var attachedWebView: WebView? = null

    fun attachWebView(webView: WebView) {
        attachedWebView = webView
    }

    fun detachWebView() {
        attachedWebView = null
        pendingBatchJson = null
    }

    @JavascriptInterface
    fun onVisibleMediaBatch(jsonBatch: String) {
        val items = parseVisibleMediaBatchJson(jsonBatch)
        if (items.isEmpty()) return
        if (!isBatchProcessing.compareAndSet(false, true)) {
            pendingBatchJson = jsonBatch
            return
        }

        mainHandler.post {
            val webView = attachedWebView
            if (webView == null) {
                isBatchProcessing.set(false)
                return@post
            }
            val viewW = webView.width
            val viewH = webView.height
            if (viewW <= 0 || viewH <= 0) {
                isBatchProcessing.set(false)
                return@post
            }

            val currentState = stateProvider()
            val targetW = VIEWPORT_CAPTURE_WIDTH
            val targetH = ((viewH.toFloat() / viewW.toFloat()) * targetW).roundToInt().coerceAtLeast(224)
            val snapshot = try {
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.scale(targetW.toFloat() / viewW.toFloat(), targetH.toFloat() / viewH.toFloat())
                webView.draw(canvas)
                bmp
            } catch (t: Throwable) {
                Log.w(TAG, "Could not capture WebView viewport bitmap", t)
                null
            } finally {
                if (currentState.preBlurNewMediaUntilScanned) {
                    webView.evaluateJavascript("if(window.__amngazeRestoreAfterCapture){window.__amngazeRestoreAfterCapture();}", null)
                }
            }
            if (snapshot == null) {
                isBatchProcessing.set(false)
                val nextPending = pendingBatchJson
                if (nextPending != null) {
                    pendingBatchJson = null
                    onVisibleMediaBatch(nextPending)
                }
                return@post
            }
            scope.launch(Dispatchers.Default) {
                val verdicts = ArrayList<MediaVerdict>(items.size)
                try {
                    for (item in items) {
                        val cropRect = mapCssRectToBitmapRect(item, snapshot.width, snapshot.height) ?: continue
                        var cropBmp: Bitmap? = null
                        try {
                            cropBmp = Bitmap.createBitmap(
                                snapshot,
                                cropRect.left,
                                cropRect.top,
                                cropRect.width(),
                                cropRect.height()
                            )
                            val (shouldBlur, candidate) = moderationEngine.evaluateMediaCropForProtection(
                                cropBitmap = cropBmp,
                                targetGender = currentState.haramBlurTargetGender,
                                sensitivity = currentState.haramBlurSensitivity
                            )
                            if (shouldBlur) {
                                onDetectionRecorded()
                            }
                            val bestConfidence = maxOf(
                                candidate.explicitPornNudityConfidence,
                                candidate.genderConfidence,
                                candidate.revealingSkinConfidence
                            )
                            verdicts.add(
                                MediaVerdict(
                                    id = item.id,
                                    shouldBlur = shouldBlur,
                                    reason = if (candidate.explicitPornNudityConfidence >= 0.55f) {
                                        "EXPLICIT_NSFW"
                                    } else {
                                        candidate.gender.name
                                    },
                                    confidence = bestConfidence
                                )
                            )
                        } catch (_: Throwable) {
                        } finally {
                            try {
                                if (cropBmp != null && cropBmp !== snapshot && !cropBmp.isRecycled) {
                                    cropBmp.recycle()
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } finally {
                    try {
                        if (!snapshot.isRecycled) snapshot.recycle()
                    } catch (_: Throwable) {
                    }
                    isBatchProcessing.set(false)
                    val nextPending = pendingBatchJson
                    if (nextPending != null) {
                        pendingBatchJson = null
                        onVisibleMediaBatch(nextPending)
                    }
                }

                if (verdicts.isNotEmpty()) {
                    val payloadJson = buildVerdictsJson(
                        verdicts = verdicts,
                        style = currentState.haramBlurOverlayStyle,
                        strengthPx = currentState.inAppBlurStrengthPx
                    )
                    val escaped = JSONObject.quote(payloadJson)
                    mainHandler.post {
                        attachedWebView?.evaluateJavascript(
                            "if(window.__amngazeApplyVerdicts){window.__amngazeApplyVerdicts($escaped);}",
                            null
                        )
                    }
                }
            }
        }
    }
}
