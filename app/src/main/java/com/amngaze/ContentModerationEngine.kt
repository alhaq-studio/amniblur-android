package com.amngaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Multi-crop / whole-screen classification result returned by [ContentModerationEngine].
 */
data class VisionVerdict(
    val isViolation: Boolean,
    val confidence: Float,
    val threshold: Float,
    val categoryName: String,
    val modelType: ModerationModelType
)

/**
 * On-Device Multi-Crop Content Moderation Engine leveraging Google AI Edge LiteRT (TensorFlow Lite).
 *
 * Implements all 4 core vision accuracy safeguards:
 * 1. Dynamic Tensor Inspection (`inputTensor.shape()`, `inputTensor.dataType()`, `quantizationParams()`)
 *    and MobileNetV2 RGB `[0.0f, 1.0f]` / `UINT8 [0..255]` preprocessing.
 * 2. 5-Class MobileNetV2 NSFW scoring (`drawings [0]`, `hentai [1]`, `neutral [2]`, `porn [3]`, `sexy [4]`)
 *    combining `porn + hentai + (sexy * 0.85f)` with a `neutral + drawings > 0.78f` safe-UI guard.
 * 3. Aspect-ratio-preserving `360px` intermediate `HardwareBuffer` copy followed by 3 square `224x224`
 *    active-content crops (Center, Upper-Middle 10%-55%, Lower-Middle 45%-90%) with short-circuit evaluation.
 * 4. Calibrated sensitivity-to-threshold mapping (`25% -> 0.62f`, `50% -> 0.50f`, `75% -> 0.38f`, `95% -> 0.28f`).
 */
class ContentModerationEngine(private val context: Context) {
    companion object {
        private const val TAG = "AmnGazeAI"
        const val DEFAULT_INPUT_SIZE = 224
        const val INTERMEDIATE_CAPTURE_WIDTH = 360
        const val SAFE_UI_NEUTRAL_DRAWINGS_THRESHOLD = 0.78f
        const val SEXY_CLASS_WEIGHT = 0.85f

        /**
         * Maps user sensitivity (`0.20f..0.95f`, where higher sensitivity = lower threshold required to trigger):
         * - At 25% Sensitivity (`0.25f`, Strict):     threshold = `0.62f`
         * - At 50% Sensitivity (`0.50f`, Balanced):   threshold = `0.50f`
         * - At 75% Sensitivity (`0.75f`, Aggressive): threshold = `0.38f`
         * - At 95% Sensitivity (`0.95f`, Maximum):    threshold = `0.28f`
         */
        fun confidenceThresholdForSensitivity(sensitivity: Float): Float {
            val s = sensitivity.coerceIn(0.20f, 0.95f)
            val threshold = if (s <= 0.75f) {
                0.74f - (0.48f * s)
            } else {
                0.38f - ((s - 0.75f) * 0.50f)
            }
            return threshold.coerceIn(0.28f, 0.66f)
        }

        /**
         * Computes the 5-class MobileNetV2 NSFW violation score (`[0]=drawings, [1]=hentai, [2]=neutral, [3]=porn, [4]=sexy`).
         * - Returns `0.0f` (SAFE) when `neutral + drawings > 0.78f` to prevent false triggers on plain text/UI chrome.
         * - Otherwise returns `maxOf(porn + hentai, porn + hentai + sexy * 0.85f)` when [includeSexyScore] is true.
         */
        fun computeFiveClassNsfwScore(
            probabilities: FloatArray,
            includeSexyScore: Boolean = true
        ): Float {
            if (probabilities.size < 5) {
                return if (probabilities.size >= 2) {
                    probabilities[1].coerceIn(0f, 1f)
                } else if (probabilities.isNotEmpty()) {
                    probabilities[0].coerceIn(0f, 1f)
                } else {
                    0f
                }
            }

            val drawingsProb = probabilities[0].coerceIn(0f, 1f)
            val hentaiProb = probabilities[1].coerceIn(0f, 1f)
            val neutralProb = probabilities[2].coerceIn(0f, 1f)
            val pornProb = probabilities[3].coerceIn(0f, 1f)
            val sexyProb = probabilities[4].coerceIn(0f, 1f)

            // Safe UI / text screen guard
            if ((neutralProb + drawingsProb) > SAFE_UI_NEUTRAL_DRAWINGS_THRESHOLD &&
                pornProb < 0.25f &&
                hentaiProb < 0.25f
            ) {
                return 0.0f
            }

            val explicitScore = pornProb + hentaiProb
            val combinedNsfwScore = if (includeSexyScore) {
                pornProb + hentaiProb + (sexyProb * SEXY_CLASS_WEIGHT)
            } else {
                explicitScore
            }
            return maxOf(explicitScore, combinedNsfwScore).coerceIn(0f, 1f)
        }

        private val FLOAT_ZERO_TO_ONE_LUT = FloatArray(256) { it / 255.0f }
        private val FLOAT_MINUS1_TO_1_LUT = FloatArray(256) { (it - 127.5f) / 127.5f }

        /**
         * Separates mandatory hardcore pornography/hentai confidence (`porn + hentai`) from
         * gender-gated revealing skin confidence (`sexy * 0.85f`) so opposite-gender skin
         * never triggers false-positive blurs when a specific gender (`FEMALE` or `MALE`) is selected.
         */
        fun computeFiveClassBreakdown(probabilities: FloatArray): Triple<Float, Float, Float> {
            if (probabilities.size < 5) {
                val twoClass = if (probabilities.size >= 2) {
                    probabilities[1].coerceIn(0f, 1f)
                } else if (probabilities.isNotEmpty()) {
                    probabilities[0].coerceIn(0f, 1f)
                } else {
                    0f
                }
                return Triple(twoClass, twoClass, twoClass)
            }

            val drawingsProb = probabilities[0].coerceIn(0f, 1f)
            val hentaiProb = probabilities[1].coerceIn(0f, 1f)
            val neutralProb = probabilities[2].coerceIn(0f, 1f)
            val pornProb = probabilities[3].coerceIn(0f, 1f)
            val sexyProb = probabilities[4].coerceIn(0f, 1f)

            if ((neutralProb + drawingsProb) > SAFE_UI_NEUTRAL_DRAWINGS_THRESHOLD &&
                pornProb < 0.25f &&
                hentaiProb < 0.25f
            ) {
                return Triple(0f, 0f, 0f)
            }

            val explicitPornHentai = (pornProb + hentaiProb).coerceIn(0f, 1f)
            val revealingSkin = (sexyProb * SEXY_CLASS_WEIGHT).coerceIn(0f, 1f)
            val combined = (explicitPornHentai + revealingSkin).coerceIn(0f, 1f)
            return Triple(explicitPornHentai, revealingSkin, combined)
        }

        /**
         * Fast YCbCr/RGB skin-tone pixel fraction estimator on a sparse grid (`16x16`) to verify
         * whether a region contains actual human skin/face vs. flat UI chrome/landscapes.
         */
        fun estimateSkinPixelRatio(bitmap: Bitmap): Float {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 4 || h <= 4) return 0f
            val steps = 16
            var skinCount = 0
            var total = 0
            for (gy in 0 until steps) {
                val y = ((gy + 0.5f) * h / steps).toInt().coerceIn(0, h - 1)
                for (gx in 0 until steps) {
                    val x = ((gx + 0.5f) * w / steps).toInt().coerceIn(0, w - 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    total++
                    // Standard RGB + YCbCr human skin locus across diverse skin tones
                    val rgbSkin = r > 60 && g > 35 && b > 18 &&
                        r > g && r > b && (r - minOf(g, b)) > 12 && kotlin.math.abs(r - g) > 10
                    if (rgbSkin) {
                        val cb = 128 - (0.168736f * r) - (0.331264f * g) + (0.5f * b)
                        val cr = 128 + (0.5f * r) - (0.418688f * g) - (0.081312f * b)
                        if (cb in 75f..138f && cr in 130f..178f) {
                            skinCount++
                        }
                    }
                }
            }
            return if (total > 0) skinCount.toFloat() / total.toFloat() else 0f
        }

        /**
         * Extracts 3 square `targetSize x targetSize` (default `224x224`) crops from a portrait OR landscape
         * screen bitmap after trimming the top/bottom or left/right 10% chrome bands:
         * - Portrait (`h > w * 1.15f`):
         *   1. Crop 1 (Center Square)
         *   2. Crop 2 (Upper-Middle Content Square `10%..55%` height)
         *   3. Crop 3 (Lower-Middle Content Square `45%..90%` height)
         * - Landscape (`w > h * 1.15f`):
         *   1. Crop 1 (Center Square)
         *   2. Crop 2 (Left-Middle Content Square `10%..55%` width)
         *   3. Crop 3 (Right-Middle Content Square `45%..90%` width)
         */
        fun extractMultiCrops(
            source: Bitmap,
            targetSize: Int = DEFAULT_INPUT_SIZE
        ): List<Bitmap> {
            val w = source.width
            val h = source.height
            if (w <= 0 || h <= 0) return emptyList()

            if (w > (h * 1.15f).roundToInt()) {
                val activeLeft = (w * 0.10f).roundToInt().coerceIn(0, w - 1)
                val activeRight = (w * 0.90f).roundToInt().coerceIn(activeLeft + 1, w)
                val leftRegionEnd = (w * 0.55f).roundToInt().coerceIn(activeLeft + 1, activeRight)
                val rightRegionStart = (w * 0.45f).roundToInt().coerceIn(activeLeft, activeRight - 1)

                val centerSide = minOf(w, h)
                val centerX = ((w - centerSide) / 2).coerceIn(0, w - centerSide)

                val leftBandWidth = (leftRegionEnd - activeLeft).coerceAtLeast(1)
                val leftSide = minOf(h, leftBandWidth, w)
                val leftX = (activeLeft + (leftBandWidth - leftSide) / 2).coerceIn(0, w - leftSide)
                val leftY = ((h - leftSide) / 2).coerceIn(0, h - leftSide)

                val rightBandWidth = (activeRight - rightRegionStart).coerceAtLeast(1)
                val rightSide = minOf(h, rightBandWidth, w)
                val rightX = (rightRegionStart + (rightBandWidth - rightSide) / 2).coerceIn(0, w - rightSide)
                val rightY = ((h - rightSide) / 2).coerceIn(0, h - rightSide)

                val landscapeSpecs = listOf(
                    Triple(centerX, 0, centerSide),
                    Triple(leftX, leftY, leftSide),
                    Triple(rightX, rightY, rightSide)
                )
                return landscapeSpecs.map { (cx, cy, side) ->
                    val safeX = cx.coerceIn(0, (w - 1).coerceAtLeast(0))
                    val safeY = cy.coerceIn(0, (h - 1).coerceAtLeast(0))
                    val safeSide = side.coerceIn(1, minOf(w - safeX, h - safeY))
                    val rawCrop = Bitmap.createBitmap(source, safeX, safeY, safeSide, safeSide)
                    if (rawCrop.width == targetSize && rawCrop.height == targetSize) {
                        rawCrop
                    } else {
                        val s = Bitmap.createScaledBitmap(rawCrop, targetSize, targetSize, true)
                        if (s !== rawCrop) rawCrop.recycle()
                        s
                    }
                }
            }

            if (h <= (w * 1.15f).roundToInt()) {
                val side = minOf(w, h)
                val x = ((w - side) / 2).coerceAtLeast(0)
                val y = ((h - side) / 2).coerceAtLeast(0)
                val crop = Bitmap.createBitmap(source, x, y, side, side)
                val scaled = if (crop.width == targetSize && crop.height == targetSize) {
                    crop
                } else {
                    val s = Bitmap.createScaledBitmap(crop, targetSize, targetSize, true)
                    if (s !== crop) crop.recycle()
                    s
                }
                return listOf(scaled)
            }

            val activeTop = (h * 0.10f).roundToInt().coerceIn(0, h - 1)
            val activeBottom = (h * 0.90f).roundToInt().coerceIn(activeTop + 1, h)
            val upperRegionEnd = (h * 0.55f).roundToInt().coerceIn(activeTop + 1, activeBottom)
            val lowerRegionStart = (h * 0.45f).roundToInt().coerceIn(activeTop, activeBottom - 1)

            // Crop 1: Center Square (width x width, centered vertically)
            val centerSide = minOf(w, h)
            val centerY = ((h - centerSide) / 2).coerceIn(0, h - centerSide)

            // Crop 2: Upper-Middle Content Square (within 10%..55% height band)
            val upperBandHeight = (upperRegionEnd - activeTop).coerceAtLeast(1)
            val upperSide = minOf(w, upperBandHeight, h)
            val upperX = ((w - upperSide) / 2).coerceIn(0, w - upperSide)
            val upperY = (activeTop + (upperBandHeight - upperSide) / 2).coerceIn(0, h - upperSide)

            // Crop 3: Lower-Middle Content Square (within 45%..90% height band)
            val lowerBandHeight = (activeBottom - lowerRegionStart).coerceAtLeast(1)
            val lowerSide = minOf(w, lowerBandHeight, h)
            val lowerX = ((w - lowerSide) / 2).coerceIn(0, w - lowerSide)
            val lowerY = (lowerRegionStart + (lowerBandHeight - lowerSide) / 2).coerceIn(0, h - lowerSide)

            val specs = listOf(
                Triple(0, centerY, centerSide),
                Triple(upperX, upperY, upperSide),
                Triple(lowerX, lowerY, lowerSide)
            )

            val crops = ArrayList<Bitmap>(3)
            for ((cx, cy, side) in specs) {
                val safeX = cx.coerceIn(0, (w - 1).coerceAtLeast(0))
                val safeY = cy.coerceIn(0, (h - 1).coerceAtLeast(0))
                val safeSide = side.coerceIn(1, minOf(w - safeX, h - safeY))
                val rawCrop = Bitmap.createBitmap(source, safeX, safeY, safeSide, safeSide)
                val scaledCrop = if (rawCrop.width == targetSize && rawCrop.height == targetSize) {
                    rawCrop
                } else {
                    val s = Bitmap.createScaledBitmap(rawCrop, targetSize, targetSize, true)
                    if (s !== rawCrop) rawCrop.recycle()
                    s
                }
                crops.add(scaledCrop)
            }
            return crops
        }

        /**
         * Returns `true` only on physical hardware devices that expose a readable DRM render node
         * (`/dev/dri/renderD128`) and are not running on a virtual/emulator Goldfish/Cuttlefish GPU.
         * Prevents `E/platform: Failed to open rendernode: No such file or directory`.
         */
        fun hasRenderNodeSupport(): Boolean {
            val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.lowercase().contains("emulator") ||
                Build.FINGERPRINT.lowercase().contains("sdk_gphone") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("cutf") ||
                Build.PRODUCT.contains("sdk_gphone") ||
                Build.PRODUCT.contains("emulator")
            if (isEmulator) return false
            return try {
                val renderNode = java.io.File("/dev/dri/renderD128")
                renderNode.exists() && renderNode.canRead()
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val interpreters = java.util.EnumMap<ModerationModelType, Interpreter>(ModerationModelType::class.java)
    private val gpuDelegates = java.util.EnumMap<ModerationModelType, GpuDelegate>(ModerationModelType::class.java)

    // Reusable pre-allocated buffers keyed by inputSize / capacity to eliminate per-crop GC churn
    private val reusableInputBuffers = HashMap<Int, ByteBuffer>()
    private val reusableOutputBuffers = HashMap<Int, ByteBuffer>()
    private val reusablePixelArrays = HashMap<Int, IntArray>()
    private val reusableScaledBitmaps = HashMap<Int, Bitmap>()
    private val reusableCanvas = android.graphics.Canvas()
    private val scalingPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val scalingDestRect = android.graphics.Rect()

    @Synchronized
    private fun getOrLoadInterpreter(modelType: ModerationModelType): Interpreter? {
        interpreters[modelType]?.let { return it }

        val modelFileName = when (modelType) {
            ModerationModelType.DEFAULT_NSFW -> "amn_gaze.tflite"
            ModerationModelType.QUANTIZED_NSFW -> "nsfw_mobilenet_v2_140_224_quantized.tflite"
            ModerationModelType.GENDER_FILTER -> "model_lite_gender_q.tflite"
        }

        val modelBuffer = loadModelFile(context, modelFileName) ?: return null

        // Only attempt GpuDelegate on float models when physical DRM renderD128 support is verified
        val allowGpuDelegate = (modelType == ModerationModelType.DEFAULT_NSFW) && hasRenderNodeSupport()
        if (allowGpuDelegate) {
            try {
                val delegate = GpuDelegate()
                val options = Interpreter.Options().apply {
                    addDelegate(delegate)
                }
                val interp = Interpreter(modelBuffer, options)
                gpuDelegates[modelType] = delegate
                interpreters[modelType] = interp
                logModelTensorDetails(interp, modelFileName, "GPU")
                return interp
            } catch (e: Throwable) {
                Log.w(TAG, "GPU delegate unavailable for $modelFileName; falling back to multi-thread CPU.", e)
                try {
                    gpuDelegates.remove(modelType)?.close()
                } catch (_: Throwable) {
                }
            }
        }

        return try {
            val cpuOptions = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, cpuOptions)
            interpreters[modelType] = interp
            logModelTensorDetails(interp, modelFileName, "CPU-4T")
            interp
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to initialize LiteRT Interpreter on CPU for $modelFileName", ex)
            null
        }
    }

    private fun logModelTensorDetails(interp: Interpreter, modelFileName: String, backend: String) {
        try {
            val inTensor = interp.getInputTensor(0)
            val outTensor = interp.getOutputTensor(0)
            val inQ = inTensor.quantizationParams()
            val outQ = outTensor.quantizationParams()
            Log.i(
                TAG,
                "Loaded $modelFileName ($backend) | Input: shape=${inTensor.shape().contentToString()}, " +
                    "type=${inTensor.dataType()}, q=(scale=${inQ.scale}, zp=${inQ.zeroPoint}) | " +
                    "Output: shape=${outTensor.shape().contentToString()}, type=${outTensor.dataType()}, " +
                    "q=(scale=${outQ.scale}, zp=${outQ.zeroPoint})"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not inspect tensor metadata for $modelFileName", t)
        }
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer? {
        return try {
            context.assets.openFd(modelName).use { fileDescriptor ->
                FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                    inputStream.channel.use { fileChannel ->
                        val startOffset = fileDescriptor.startOffset
                        val declaredLength = fileDescriptor.declaredLength
                        fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Model asset '$modelName' could not be mapped via openFd; trying stream copy.", e)
            try {
                val bytes = context.assets.open(modelName).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            } catch (ex: IOException) {
                Log.e(TAG, "Model asset '$modelName' not found under assets/.", ex)
                null
            }
        }
    }

    /**
     * Preprocesses an ARGB_8888 Bitmap according to the dynamic input tensor dataType and
     * MobileNetV2 RGB normalization (`[0.0f, 1.0f]` for FLOAT32, or `0..255` bytes for UINT8).
     */
    fun preprocessBitmap(
        bitmap: Bitmap,
        inputSize: Int,
        inputDataType: DataType,
        inputScale: Float = 0f,
        inputZeroPoint: Int = 0,
        useMinusOneToOneRange: Boolean = false
    ): ByteBuffer {
        return preprocessBitmapInternal(
            bitmap = bitmap,
            inputSize = inputSize,
            inputDataType = inputDataType,
            inputScale = inputScale,
            inputZeroPoint = inputZeroPoint,
            useMinusOneToOneRange = useMinusOneToOneRange,
            reuseBuffers = false
        )
    }

    @Synchronized
    private fun preprocessBitmapInternal(
        bitmap: Bitmap,
        inputSize: Int,
        inputDataType: DataType,
        inputScale: Float,
        inputZeroPoint: Int,
        useMinusOneToOneRange: Boolean,
        reuseBuffers: Boolean
    ): ByteBuffer {
        val targetBitmap: Bitmap
        var tempAllocatedBitmap: Bitmap? = null

        if (bitmap.width == inputSize && bitmap.height == inputSize && bitmap.config == Bitmap.Config.ARGB_8888) {
            targetBitmap = bitmap
        } else if (reuseBuffers) {
            val cachedBmp = reusableScaledBitmaps.getOrPut(inputSize) {
                Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            }
            reusableCanvas.setBitmap(cachedBmp)
            scalingDestRect.set(0, 0, inputSize, inputSize)
            reusableCanvas.drawBitmap(bitmap, null, scalingDestRect, scalingPaint)
            targetBitmap = cachedBmp
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            if (scaled !== bitmap) {
                tempAllocatedBitmap = scaled
            }
            targetBitmap = scaled
        }

        val isByteInput = (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8)
        val numBytesPerChannel = if (isByteInput) 1 else 4
        val requiredCapacity = numBytesPerChannel * inputSize * inputSize * 3

        val byteBuffer = if (reuseBuffers) {
            reusableInputBuffers.getOrPut(requiredCapacity) {
                ByteBuffer.allocateDirect(requiredCapacity).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
        } else {
            ByteBuffer.allocateDirect(requiredCapacity).apply {
                order(ByteOrder.nativeOrder())
            }
        }

        val pixelCount = inputSize * inputSize
        val intValues = if (reuseBuffers) {
            reusablePixelArrays.getOrPut(pixelCount) { IntArray(pixelCount) }
        } else {
            IntArray(pixelCount)
        }

        targetBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        byteBuffer.rewind()
        when (inputDataType) {
            DataType.UINT8 -> {
                for (i in 0 until pixelCount) {
                    val pixelValue = intValues[i]
                    byteBuffer.put(((pixelValue shr 16) and 0xFF).toByte())
                    byteBuffer.put(((pixelValue shr 8) and 0xFF).toByte())
                    byteBuffer.put((pixelValue and 0xFF).toByte())
                }
            }
            DataType.INT8 -> {
                if (inputScale > 0f) {
                    val invScale = (1f / 255.0f) / inputScale
                    for (i in 0 until pixelCount) {
                        val pixelValue = intValues[i]
                        val r = (pixelValue shr 16) and 0xFF
                        val g = (pixelValue shr 8) and 0xFF
                        val b = pixelValue and 0xFF
                        byteBuffer.put((r * invScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                        byteBuffer.put((g * invScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                        byteBuffer.put((b * invScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                    }
                } else {
                    for (i in 0 until pixelCount) {
                        val pixelValue = intValues[i]
                        byteBuffer.put((((pixelValue shr 16) and 0xFF) - 128).toByte())
                        byteBuffer.put((((pixelValue shr 8) and 0xFF) - 128).toByte())
                        byteBuffer.put(((pixelValue and 0xFF) - 128).toByte())
                    }
                }
            }
            else -> {
                val lut = if (useMinusOneToOneRange) FLOAT_MINUS1_TO_1_LUT else FLOAT_ZERO_TO_ONE_LUT
                for (i in 0 until pixelCount) {
                    val pixelValue = intValues[i]
                    byteBuffer.putFloat(lut[(pixelValue shr 16) and 0xFF])
                    byteBuffer.putFloat(lut[(pixelValue shr 8) and 0xFF])
                    byteBuffer.putFloat(lut[pixelValue and 0xFF])
                }
            }
        }

        tempAllocatedBitmap?.recycle()
        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * Copies [hardwareBuffer] to an aspect-ratio-preserving software `ARGB_8888` bitmap (width `360px`,
     * proportional height `~760px`), immediately closes [hardwareBuffer] in `finally`, and runs
     * multi-crop LiteRT inference across 3 active-content square crops.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun analyzeHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        colorSpace: ColorSpace?,
        sensitivity: Float = 0.5f,
        modelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW,
        includeSexyScore: Boolean = true
    ): VisionVerdict {
        var hwBitmap: Bitmap? = null
        var fullSoftBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null

        try {
            val targetColorSpace = colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, targetColorSpace)
            if (hwBitmap != null && hwBitmap.width > 0 && hwBitmap.height > 0) {
                val targetWidth = INTERMEDIATE_CAPTURE_WIDTH
                val targetHeight = ((hwBitmap.height.toFloat() / hwBitmap.width.toFloat()) * targetWidth)
                    .roundToInt()
                    .coerceAtLeast(DEFAULT_INPUT_SIZE)
                // Copy HARDWARE bitmap to software ARGB_8888 BEFORE scaling so background thread
                // never invokes HardwareRenderer / DRM rendernode for scaling
                fullSoftBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (fullSoftBitmap != null) {
                    softwareBitmap = if (fullSoftBitmap.width == targetWidth && fullSoftBitmap.height == targetHeight) {
                        fullSoftBitmap
                    } else {
                        Bitmap.createScaledBitmap(
                            fullSoftBitmap,
                            targetWidth,
                            targetHeight,
                            true
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy HardwareBuffer to proportional software ARGB_8888 bitmap.", t)
        } finally {
            try {
                if (fullSoftBitmap != null && fullSoftBitmap !== softwareBitmap && !fullSoftBitmap.isRecycled) {
                    fullSoftBitmap.recycle()
                }
            } catch (_: Throwable) {
            }
            try {
                hwBitmap?.recycle()
            } catch (_: Throwable) {
            }
            try {
                hardwareBuffer.close()
            } catch (_: Throwable) {
            }
        }

        val threshold = confidenceThresholdForSensitivity(sensitivity)
        val bitmapToAnalyze = softwareBitmap
            ?: return VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "SAFE",
                modelType = modelType
            )

        return try {
            classifyScreenMultiCrop(
                screenBitmap = bitmapToAnalyze,
                sensitivity = sensitivity,
                modelType = modelType,
                includeSexyScore = includeSexyScore
            )
        } finally {
            try {
                bitmapToAnalyze.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Extracts 3 square `224x224` crops (Center, Upper-Middle 10%..55%, Lower-Middle 45%..90%) from
     * [screenBitmap], evaluates them in order, short-circuits immediately if Crop 1 exceeds the
     * threshold, and recycles all crop bitmaps cleanly.
     */
    @Synchronized
    fun classifyScreenMultiCrop(
        screenBitmap: Bitmap,
        sensitivity: Float = 0.5f,
        modelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW,
        includeSexyScore: Boolean = true
    ): VisionVerdict {
        val threshold = confidenceThresholdForSensitivity(sensitivity)
        val interpreterInstance = getOrLoadInterpreter(modelType)
            ?: return VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "MODEL_UNAVAILABLE",
                modelType = modelType
            )

        val inputTensor = interpreterInstance.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val inputSize = if (inputShape.size >= 3 && inputShape[1] > 0) inputShape[1] else DEFAULT_INPUT_SIZE

        val crops = extractMultiCrops(screenBitmap, inputSize)
        if (crops.isEmpty()) {
            return classifyBitmap(screenBitmap, sensitivity, modelType, includeSexyScore)
        }

        var maxScore = 0f
        var winningVerdict: VisionVerdict? = null

        try {
            for ((index, crop) in crops.withIndex()) {
                val cropVerdict = classifySingleCropInternal(
                    interpreterInstance = interpreterInstance,
                    cropBitmap = crop,
                    threshold = threshold,
                    modelType = modelType,
                    includeSexyScore = includeSexyScore,
                    cropIndex = index
                )
                if (cropVerdict.confidence >= maxScore || winningVerdict == null) {
                    maxScore = cropVerdict.confidence
                    winningVerdict = cropVerdict
                }
                // Short-circuit immediately if Crop 1 (or any crop) exceeds the violation threshold
                if (cropVerdict.isViolation) {
                    return cropVerdict
                }
            }
            return winningVerdict ?: VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "SAFE",
                modelType = modelType
            )
        } finally {
            for (crop in crops) {
                try {
                    if (crop !== screenBitmap && !crop.isRecycled) {
                        crop.recycle()
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    @Synchronized
    fun classifyBitmap(
        bitmap: Bitmap,
        sensitivity: Float = 0.5f,
        modelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW,
        includeSexyScore: Boolean = true
    ): VisionVerdict {
        val threshold = confidenceThresholdForSensitivity(sensitivity)
        val interpreterInstance = getOrLoadInterpreter(modelType)
            ?: return VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "MODEL_UNAVAILABLE",
                modelType = modelType
            )

        return classifySingleCropInternal(
            interpreterInstance = interpreterInstance,
            cropBitmap = bitmap,
            threshold = threshold,
            modelType = modelType,
            includeSexyScore = includeSexyScore,
            cropIndex = 0
        )
    }

    private fun classifySingleCropInternal(
        interpreterInstance: Interpreter,
        cropBitmap: Bitmap,
        threshold: Float,
        modelType: ModerationModelType,
        includeSexyScore: Boolean,
        cropIndex: Int
    ): VisionVerdict {
        return try {
            val inputTensor = interpreterInstance.getInputTensor(0)
            val inputShape = inputTensor.shape()
            val inputSize = if (inputShape.size >= 3 && inputShape[1] > 0) inputShape[1] else DEFAULT_INPUT_SIZE
            val inputDataType = inputTensor.dataType()
            val inQParams = inputTensor.quantizationParams()

            val outputTensor = interpreterInstance.getOutputTensor(0)
            val outputDataType = outputTensor.dataType()
            val outputShape = outputTensor.shape()
            val numClasses = if (outputShape.size >= 2 && outputShape[1] > 0) outputShape[1] else 2

            val probabilities = runInferencePass(
                interpreterInstance = interpreterInstance,
                cropBitmap = cropBitmap,
                inputSize = inputSize,
                inputDataType = inputDataType,
                inputScale = inQParams.scale,
                inputZeroPoint = inQParams.zeroPoint,
                outputTensor = outputTensor,
                outputDataType = outputDataType,
                numClasses = numClasses,
                useMinusOneToOneRange = false
            )

            val score: Float = if (numClasses >= 5) {
                computeFiveClassNsfwScore(probabilities, includeSexyScore)
            } else if (numClasses == 2) {
                var bestTwoClassScore = probabilities[1].coerceIn(0f, 1f)
                // Only run fallback [-1, 1] normalization pass if primary score is in the ambiguous border zone
                if (inputDataType == DataType.FLOAT32 &&
                    bestTwoClassScore >= (threshold * 0.58f) &&
                    bestTwoClassScore < threshold
                ) {
                    val altProbs = runInferencePass(
                        interpreterInstance = interpreterInstance,
                        cropBitmap = cropBitmap,
                        inputSize = inputSize,
                        inputDataType = inputDataType,
                        inputScale = inQParams.scale,
                        inputZeroPoint = inQParams.zeroPoint,
                        outputTensor = outputTensor,
                        outputDataType = outputDataType,
                        numClasses = numClasses,
                        useMinusOneToOneRange = true
                    )
                    bestTwoClassScore = maxOf(bestTwoClassScore, altProbs[1].coerceIn(0f, 1f))
                }
                bestTwoClassScore
            } else {
                probabilities[0].coerceIn(0f, 1f)
            }

            val isViolation = score >= threshold
            val category = if (isViolation) {
                when (modelType) {
                    ModerationModelType.DEFAULT_NSFW -> "EXPLICIT_CONTENT_NSFW"
                    ModerationModelType.QUANTIZED_NSFW -> "EXPLICIT_CONTENT_QUANTIZED"
                    ModerationModelType.GENDER_FILTER -> "GENDER_REVEALING_FILTER"
                }
            } else {
                "SAFE"
            }

            VisionVerdict(
                isViolation = isViolation,
                confidence = score,
                threshold = threshold,
                categoryName = category,
                modelType = modelType
            )
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT inference exception on crop[$cropIndex]", e)
            VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "ERROR",
                modelType = modelType
            )
        }
    }

    private fun runInferencePass(
        interpreterInstance: Interpreter,
        cropBitmap: Bitmap,
        inputSize: Int,
        inputDataType: DataType,
        inputScale: Float,
        inputZeroPoint: Int,
        outputTensor: org.tensorflow.lite.Tensor,
        outputDataType: DataType,
        numClasses: Int,
        useMinusOneToOneRange: Boolean
    ): FloatArray {
        val inputBuffer = preprocessBitmapInternal(
            bitmap = cropBitmap,
            inputSize = inputSize,
            inputDataType = inputDataType,
            inputScale = inputScale,
            inputZeroPoint = inputZeroPoint,
            useMinusOneToOneRange = useMinusOneToOneRange,
            reuseBuffers = true
        )

        val probabilities = FloatArray(numClasses)
        if (outputDataType == DataType.FLOAT32) {
            val outputScores = Array(1) { FloatArray(numClasses) }
            interpreterInstance.run(inputBuffer, outputScores)
            for (i in 0 until numClasses) {
                probabilities[i] = outputScores[0][i]
            }
        } else {
            val outBytes = outputTensor.numBytes()
            val outputBuffer = reusableOutputBuffers.getOrPut(outBytes) {
                ByteBuffer.allocateDirect(outBytes).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
            outputBuffer.rewind()
            interpreterInstance.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val qParams = outputTensor.quantizationParams()
            val scale = if (qParams.scale > 0f) qParams.scale else (1f / 255f)
            val zeroPoint = qParams.zeroPoint

            for (i in 0 until numClasses) {
                if (!outputBuffer.hasRemaining()) break
                val rawVal = if (outputDataType == DataType.UINT8) {
                    outputBuffer.get().toInt() and 0xFF
                } else {
                    outputBuffer.get().toInt()
                }
                probabilities[i] = ((rawVal - zeroPoint) * scale).coerceIn(0f, 1f)
            }
        }
        return probabilities
    }

    /**
     * Evaluates the 5-class `QUANTIZED_NSFW` (or `DEFAULT_NSFW`) probabilities on [cropBitmap]
     * to separate mandatory hardcore pornography/nudity (`explicitPornHentai`) from
     * gender-gated revealing skin (`revealingSkin`).
     */
    @Synchronized
    private fun evaluateNsfwAndSkinBreakdown(cropBitmap: Bitmap): Triple<Float, Float, Float> {
        val quantizedInterp = getOrLoadInterpreter(ModerationModelType.QUANTIZED_NSFW)
        if (quantizedInterp != null) {
            try {
                val inTensor = quantizedInterp.getInputTensor(0)
                val inShape = inTensor.shape()
                val inSize = if (inShape.size >= 3 && inShape[1] > 0) inShape[1] else DEFAULT_INPUT_SIZE
                val inQ = inTensor.quantizationParams()
                val outTensor = quantizedInterp.getOutputTensor(0)
                val outShape = outTensor.shape()
                val numClasses = if (outShape.size >= 2 && outShape[1] > 0) outShape[1] else 5

                val probs = runInferencePass(
                    interpreterInstance = quantizedInterp,
                    cropBitmap = cropBitmap,
                    inputSize = inSize,
                    inputDataType = inTensor.dataType(),
                    inputScale = inQ.scale,
                    inputZeroPoint = inQ.zeroPoint,
                    outputTensor = outTensor,
                    outputDataType = outTensor.dataType(),
                    numClasses = numClasses,
                    useMinusOneToOneRange = false
                )
                return computeFiveClassBreakdown(probs)
            } catch (_: Throwable) {
            }
        }

        val defaultInterp = getOrLoadInterpreter(ModerationModelType.DEFAULT_NSFW) ?: return Triple(0f, 0f, 0f)
        return try {
            val inTensor = defaultInterp.getInputTensor(0)
            val inShape = inTensor.shape()
            val inSize = if (inShape.size >= 3 && inShape[1] > 0) inShape[1] else DEFAULT_INPUT_SIZE
            val inQ = inTensor.quantizationParams()
            val outTensor = defaultInterp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val numClasses = if (outShape.size >= 2 && outShape[1] > 0) outShape[1] else 2

            val probs = runInferencePass(
                interpreterInstance = defaultInterp,
                cropBitmap = cropBitmap,
                inputSize = inSize,
                inputDataType = inTensor.dataType(),
                inputScale = inQ.scale,
                inputZeroPoint = inQ.zeroPoint,
                outputTensor = outTensor,
                outputDataType = outTensor.dataType(),
                numClasses = numClasses,
                useMinusOneToOneRange = false
            )
            val score = if (probs.size >= 2) probs[1].coerceIn(0f, 1f) else probs.getOrElse(0) { 0f }
            val explicitScore = if (score >= 0.72f) score else 0f
            Triple(explicitScore, score, score)
        } catch (_: Throwable) {
            Triple(0f, 0f, 0f)
        }
    }

    /**
     * Detects candidate face, figure, and exposed-skin bounding boxes in a downscaled frame
     * and classifies each region with strict gender isolation:
     * - Localizes faces (`FaceDetector` + upper-head skin anchors) and classifies each face
     *   on a square `128x128` crop using `model_lite_gender_q.tflite` (`FEMALE` vs `MALE`).
     * - Links the body/exposed-skin region directly beneath each detected face to that person's
     *   verified gender so:
     *   - Selecting `Female` blurs female faces, female figures, and female exposed skin, while
     *     NEVER blurring male faces, male figures, or male exposed skin.
     *   - Selecting `Male` blurs male faces, male figures, and male exposed skin, while
     *     NEVER blurring female faces, female figures, or female exposed skin.
     *   - Mandatory hardcore pornography and explicit nudity are blurred for any gender.
     */
    @Synchronized
    fun detectRegionalBlurCandidates(
        frameBitmap: Bitmap
    ): List<CandidateBlurBox> {
        val w = frameBitmap.width
        val h = frameBitmap.height
        if (w <= 16 || h <= 16) return emptyList()

        val faceRects = mutableListOf<android.graphics.RectF>()

        // 1. Fast Android FaceDetector pass on RGB_565 copy for accurate face localization
        var rgb565: Bitmap? = null
        try {
            rgb565 = frameBitmap.copy(Bitmap.Config.RGB_565, false)
            if (rgb565 != null) {
                val maxFaces = 5
                val faces = arrayOfNulls<android.media.FaceDetector.Face>(maxFaces)
                val detector = android.media.FaceDetector(rgb565.width, rgb565.height, maxFaces)
                val found = detector.findFaces(rgb565, faces)
                val midPoint = android.graphics.PointF()
                for (i in 0 until found) {
                    val face = faces[i] ?: continue
                    if (face.confidence() >= 0.28f) {
                        face.getMidPoint(midPoint)
                        val eyeDist = face.eyesDistance().coerceAtLeast(14f)
                        val halfW = eyeDist * 1.45f
                        val halfH = eyeDist * 1.85f
                        val left = ((midPoint.x - halfW) / w.toFloat()).coerceIn(0f, 1f)
                        val top = ((midPoint.y - halfH) / h.toFloat()).coerceIn(0f, 1f)
                        val right = ((midPoint.x + halfW) / w.toFloat()).coerceIn(0f, 1f)
                        val bottom = ((midPoint.y + halfH) / h.toFloat()).coerceIn(0f, 1f)
                        if (right - left > 0.04f && bottom - top > 0.04f) {
                            faceRects.add(android.graphics.RectF(left, top, right, bottom))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                rgb565?.recycle()
            } catch (_: Throwable) {
            }
        }

        val results = mutableListOf<CandidateBlurBox>()

        if (faceRects.isNotEmpty()) {
            // Evaluate each localized face AND its linked body/skin region directly below the face
            for (faceRect in faceRects) {
                val faceCandidate = cropAndClassifyRegion(
                    frameBitmap = frameBitmap,
                    normRect = faceRect,
                    isFaceAnchor = true,
                    inheritedGender = null,
                    inheritedGenderConf = 0f
                )
                if (faceCandidate != null) {
                    results.add(faceCandidate)

                    // Check the subject-linked torso / exposed-skin region below this face
                    val faceW = faceRect.width()
                    val faceH = faceRect.height()
                    val bodyTop = (faceRect.bottom - faceH * 0.10f).coerceIn(0f, 0.90f)
                    val bodyBottom = (faceRect.bottom + faceH * 2.15f).coerceIn(bodyTop + 0.06f, 0.96f)
                    val bodyLeft = (faceRect.left - faceW * 0.40f).coerceIn(0f, 0.92f)
                    val bodyRight = (faceRect.right + faceW * 0.40f).coerceIn(bodyLeft + 0.06f, 1f)

                    if (bodyBottom - bodyTop >= 0.08f && bodyRight - bodyLeft >= 0.08f) {
                        val bodyRect = android.graphics.RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
                        val bodyCandidate = cropAndClassifyRegion(
                            frameBitmap = frameBitmap,
                            normRect = bodyRect,
                            isFaceAnchor = false,
                            inheritedGender = faceCandidate.gender,
                            inheritedGenderConf = faceCandidate.genderConfidence
                        )
                        if (bodyCandidate != null &&
                            (bodyCandidate.revealingSkinConfidence >= 0.28f ||
                                bodyCandidate.explicitPornNudityConfidence >= 0.35f)
                        ) {
                            results.add(bodyCandidate)
                        }
                    }
                }
            }
            return results
        }

        // 2. Fallback when FaceDetector finds no frontal eyes (e.g., angled head, portrait, or body shot):
        //    Inspect the 3 active content bands using a head-anchor sub-crop for gender verification
        //    combined with skin/NSFW scoring on the band.
        val fallbackBands = listOf(
            android.graphics.RectF(0.14f, 0.12f, 0.86f, 0.50f),
            android.graphics.RectF(0.14f, 0.30f, 0.86f, 0.68f),
            android.graphics.RectF(0.14f, 0.48f, 0.86f, 0.88f)
        )
        for (bandRect in fallbackBands) {
            val candidate = cropAndClassifyRegion(
                frameBitmap = frameBitmap,
                normRect = bandRect,
                isFaceAnchor = false,
                inheritedGender = null,
                inheritedGenderConf = 0f
            )
            if (candidate != null) {
                results.add(candidate)
            }
        }
        return results
    }

    private fun cropAndClassifyRegion(
        frameBitmap: Bitmap,
        normRect: android.graphics.RectF,
        isFaceAnchor: Boolean,
        inheritedGender: DetectedGender?,
        inheritedGenderConf: Float
    ): CandidateBlurBox? {
        val w = frameBitmap.width
        val h = frameBitmap.height
        val cropLeft = (normRect.left * w).toInt().coerceIn(0, w - 2)
        val cropTop = (normRect.top * h).toInt().coerceIn(0, h - 2)
        val cropWidth = ((normRect.right - normRect.left) * w).toInt().coerceIn(1, w - cropLeft)
        val cropHeight = ((normRect.bottom - normRect.top) * h).toInt().coerceIn(1, h - cropTop)

        var subCrop: Bitmap? = null
        return try {
            subCrop = Bitmap.createBitmap(frameBitmap, cropLeft, cropTop, cropWidth, cropHeight)
            if (inheritedGender != null && inheritedGender != DetectedGender.UNKNOWN && !isFaceAnchor) {
                // Body/skin region linked to an already-verified face above it:
                // Inherit the face's verified gender so male skin is NEVER misclassified as female!
                val skinRatio = estimateSkinPixelRatio(subCrop)
                val (explicitPorn, revealingSkin, _) = evaluateNsfwAndSkinBreakdown(subCrop)
                val boostedSkin = if (skinRatio >= 0.22f) {
                    maxOf(revealingSkin, (skinRatio * 1.15f).coerceIn(0f, 0.88f))
                } else {
                    revealingSkin
                }
                CandidateBlurBox(
                    normalizedRect = normRect,
                    gender = inheritedGender,
                    genderConfidence = if (boostedSkin >= 0.30f) inheritedGenderConf else 0f,
                    nsfwConfidence = explicitPorn,
                    explicitPornNudityConfidence = explicitPorn,
                    revealingSkinConfidence = boostedSkin
                )
            } else {
                classifyCropForHaramBlur(subCrop, normRect)
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                if (subCrop != null && subCrop !== frameBitmap && !subCrop.isRecycled) {
                    subCrop.recycle()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Classifies a single face/figure crop using `model_lite_gender_q.tflite` (`GENDER_FILTER`)
     * on an aspect-preserved head crop plus `QUANTIZED_NSFW` breakdown, returning a [CandidateBlurBox]
     * with strictly isolated gender (`FEMALE` / `MALE` / `UNKNOWN`), `explicitPornNudityConfidence`,
     * and `revealingSkinConfidence`.
     */
    @Synchronized
    fun classifyCropForHaramBlur(
        cropBitmap: Bitmap,
        normalizedRect: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f)
    ): CandidateBlurBox {
        val interp = getOrLoadInterpreter(ModerationModelType.GENDER_FILTER)
            ?: return CandidateBlurBox(
                normalizedRect = normalizedRect,
                gender = DetectedGender.UNKNOWN,
                genderConfidence = 0f,
                nsfwConfidence = 0f,
                explicitPornNudityConfidence = 0f,
                revealingSkinConfidence = 0f
            )

        return try {
            val inTensor = interp.getInputTensor(0)
            val inShape = inTensor.shape()
            val inputSize = if (inShape.size >= 3 && inShape[1] > 0) inShape[1] else 128
            val inType = inTensor.dataType()
            val inQ = inTensor.quantizationParams()
            val outTensor = interp.getOutputTensor(0)
            val outType = outTensor.dataType()
            val outShape = outTensor.shape()
            val numClasses = if (outShape.size >= 2 && outShape[1] > 0) outShape[1] else 2

            // Extract upper-center square head sub-crop when given a tall/wide body crop
            // so the 128x128 face gender model sees an unstretched head/face region
            var headCrop: Bitmap = cropBitmap
            val cw = cropBitmap.width
            val ch = cropBitmap.height
            if (cw >= 32 && ch >= 32 && (cw > (ch * 1.35f) || ch > (cw * 1.35f))) {
                val side = minOf(cw, (ch * 0.65f).toInt().coerceAtLeast(16)).coerceIn(1, minOf(cw, ch))
                val hx = ((cw - side) / 2).coerceIn(0, cw - side)
                val hy = (ch * 0.05f).toInt().coerceIn(0, ch - side)
                headCrop = Bitmap.createBitmap(cropBitmap, hx, hy, side, side)
            }

            val probs = try {
                runInferencePass(
                    interpreterInstance = interp,
                    cropBitmap = headCrop,
                    inputSize = inputSize,
                    inputDataType = inType,
                    inputScale = inQ.scale,
                    inputZeroPoint = inQ.zeroPoint,
                    outputTensor = outTensor,
                    outputDataType = outType,
                    numClasses = numClasses,
                    useMinusOneToOneRange = false
                )
            } finally {
                if (headCrop !== cropBitmap && !headCrop.isRecycled) {
                    headCrop.recycle()
                }
            }

            // 2-class gender model: index 0 = Male, index 1 = Female
            val femaleScore = if (probs.size >= 2) probs[1].coerceIn(0f, 1f) else probs.getOrElse(0) { 0f }
            val maleScore = if (probs.size >= 2) probs[0].coerceIn(0f, 1f) else (1f - femaleScore).coerceIn(0f, 1f)
            val margin = kotlin.math.abs(femaleScore - maleScore)

            val (explicitPorn, revealingSkin, _) = evaluateNsfwAndSkinBreakdown(cropBitmap)

            // Require a meaningful margin between Female and Male probabilities so flat backgrounds
            // are not arbitrarily assigned a gender
            val gender = when {
                margin < 0.08f -> DetectedGender.UNKNOWN
                femaleScore > maleScore -> DetectedGender.FEMALE
                else -> DetectedGender.MALE
            }
            val genderConf = if (gender == DetectedGender.UNKNOWN) 0f else maxOf(femaleScore, maleScore)

            CandidateBlurBox(
                normalizedRect = normalizedRect,
                gender = gender,
                genderConfidence = genderConf,
                // Keep nsfwConfidence as explicitPorn so opposite-gender candidates never bypass gender filtering
                nsfwConfidence = explicitPorn,
                explicitPornNudityConfidence = explicitPorn,
                revealingSkinConfidence = revealingSkin
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to classify HaramBlur crop", t)
            CandidateBlurBox(
                normalizedRect = normalizedRect,
                gender = DetectedGender.UNKNOWN,
                genderConfidence = 0f,
                nsfwConfidence = 0f,
                explicitPornNudityConfidence = 0f,
                revealingSkinConfidence = 0f
            )
        }
    }

    /**
     * Evaluates an In-App WebView media element (`<img>` or `<video>`) with strict gender isolation
     * and mandatory pornography/nudity enforcement.
     */
    @Synchronized
    fun evaluateMediaCropForProtection(
        cropBitmap: Bitmap,
        targetGender: HaramBlurTargetGender,
        sensitivity: HaramBlurSensitivity
    ): Pair<Boolean, CandidateBlurBox> {
        val candidate = classifyCropForHaramBlur(cropBitmap)
        val shouldBlur = HaramBlurFilterLogic.shouldBlurCandidate(
            candidate = candidate,
            targetGender = targetGender,
            sensitivity = sensitivity
        )
        return Pair(shouldBlur, candidate)
    }

    @Synchronized
    fun close() {
        try {
            for (interp in interpreters.values) {
                try {
                    interp.close()
                } catch (_: Throwable) {
                }
            }
            for (delegate in gpuDelegates.values) {
                try {
                    delegate.close()
                } catch (_: Throwable) {
                }
            }
            for (bmp in reusableScaledBitmaps.values) {
                try {
                    if (!bmp.isRecycled) bmp.recycle()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LiteRT resources.", e)
        } finally {
            interpreters.clear()
            gpuDelegates.clear()
            reusableScaledBitmaps.clear()
            reusableInputBuffers.clear()
            reusableOutputBuffers.clear()
            reusablePixelArrays.clear()
        }
    }
}
