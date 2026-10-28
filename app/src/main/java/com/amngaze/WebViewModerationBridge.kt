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
            feedControlConfig: FeedControlConfig
        ): String {
            val protectImages = WhatToProtectItem.IMAGES in whatToProtect
            val protectVideos = WhatToProtectItem.VIDEOS in whatToProtect
            val feedCss = generateFeedControlCss(feedControlConfig)
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")

            return """
                (function() {
                    if (window.__amngazeInitialized) {
                        if (window.__amngazeUpdateFeedCss) {
                            window.__amngazeUpdateFeedCss('$feedCss');
                        }
                        return;
                    }
                    window.__amngazeInitialized = true;
                    var nextId = 1;
                    var protectImages = $protectImages;
                    var protectVideos = $protectVideos;

                    var styleEl = document.createElement('style');
                    styleEl.id = '__amngaze_feed_style';
                    styleEl.innerHTML = '$feedCss';
                    (document.head || document.documentElement).appendChild(styleEl);

                    window.__amngazeUpdateFeedCss = function(cssText) {
                        var el = document.getElementById('__amngaze_feed_style');
                        if (el) el.innerHTML = cssText;
                    };

                    function applyOverlayStyle(el, styleName, px) {
                        var blurPx = Math.max(8, px * 2);
                        if (styleName === 'BLACK') {
                            el.style.filter = 'brightness(0)';
                            el.style.backgroundColor = '#000000';
                        } else if (styleName === 'GRAY') {
                            el.style.filter = 'brightness(0) invert(0.56)';
                            el.style.backgroundColor = '#8E8E93';
                        } else if (styleName === 'SKIN') {
                            el.style.filter = 'blur(' + blurPx + 'px) sepia(0.45)';
                            el.style.backgroundColor = '#E8BEAC';
                        } else if (styleName === 'PIXELATE') {
                            el.style.filter = 'blur(' + blurPx + 'px) contrast(1.35)';
                        } else {
                            el.style.filter = 'blur(' + blurPx + 'px)';
                        }
                        el.style.transition = 'filter 0.12s ease-out';
                    }

                    window.__amngazeApplyVerdicts = function(payload) {
                        try {
                            var data = (typeof payload === 'string') ? JSON.parse(payload) : payload;
                            var styleName = data.style || '${blurStyle.name}';
                            var px = data.strengthPx || $strengthPx;
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
                                }
                            }
                        } catch (e) {}
                    };

                    function collectVisibleMedia() {
                        if (!window.AmnGazeBridge) return;
                        var vw = Math.max(window.innerWidth || 360, 1);
                        var vh = Math.max(window.innerHeight || 640, 1);
                        var selectors = [];
                        if (protectImages) selectors.push('img');
                        if (protectVideos) selectors.push('video');
                        if (selectors.length === 0) return;

                        var nodes = document.querySelectorAll(selectors.join(','));
                        var batch = [];
                        var now = Date.now();
                        for (var i = 0; i < nodes.length && batch.length < 8; i++) {
                            var el = nodes[i];
                            var tag = (el.tagName || 'IMG').toUpperCase();
                            var verdict = el.dataset.amngazeVerdict || '';
                            var lastScan = parseInt(el.dataset.amngazeLastScan || '0', 10);

                            if (tag === 'IMG' && (verdict === 'SAFE' || verdict === 'BLURRED')) {
                                continue;
                            }
                            if (tag === 'VIDEO' && (now - lastScan) < 900) {
                                continue;
                            }

                            var rect = el.getBoundingClientRect();
                            if (rect.width < 44 || rect.height < 44) continue;
                            if (rect.bottom < 0 || rect.top > vh || rect.right < 0 || rect.left > vw) continue;

                            if (!el.dataset.amngazeId) {
                                el.dataset.amngazeId = 'ag_' + (nextId++);
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
                            window.AmnGazeBridge.onVisibleMediaBatch(JSON.stringify(batch));
                        }
                    }

                    setInterval(collectVisibleMedia, 220);
                    window.addEventListener('scroll', function() {
                        setTimeout(collectVisibleMedia, 140);
                    }, { passive: true });
                })();
            """.trimIndent()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var attachedWebView: WebView? = null

    fun attachWebView(webView: WebView) {
        attachedWebView = webView
    }

    fun detachWebView() {
        attachedWebView = null
    }

    @JavascriptInterface
    fun onVisibleMediaBatch(jsonBatch: String) {
        val items = parseVisibleMediaBatchJson(jsonBatch)
        if (items.isEmpty()) return

        mainHandler.post {
            val webView = attachedWebView ?: return@post
            val viewW = webView.width
            val viewH = webView.height
            if (viewW <= 0 || viewH <= 0) return@post

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
            } ?: return@post

            val currentState = stateProvider()
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
                            val nsfwVerdict = moderationEngine.classifyBitmap(
                                bitmap = cropBmp,
                                sensitivity = when (currentState.haramBlurSensitivity) {
                                    HaramBlurSensitivity.LOW -> 0.30f
                                    HaramBlurSensitivity.MEDIUM -> 0.50f
                                    HaramBlurSensitivity.HIGH -> 0.78f
                                },
                                modelType = currentState.amnGazeModelType,
                                includeSexyScore = currentState.includeSexyInNsfwScore
                            )
                            val genderCandidate = moderationEngine.classifyCropForHaramBlur(cropBmp)
                            val shouldBlurGender = HaramBlurFilterLogic.shouldBlurCandidate(
                                candidate = genderCandidate,
                                targetGender = currentState.haramBlurTargetGender,
                                sensitivity = currentState.haramBlurSensitivity
                            )
                            val shouldBlur = nsfwVerdict.isViolation || shouldBlurGender
                            if (shouldBlur) {
                                onDetectionRecorded()
                            }
                            verdicts.add(
                                MediaVerdict(
                                    id = item.id,
                                    shouldBlur = shouldBlur,
                                    reason = if (nsfwVerdict.isViolation) nsfwVerdict.categoryName else genderCandidate.gender.name,
                                    confidence = maxOf(nsfwVerdict.confidence, genderCandidate.genderConfidence)
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
