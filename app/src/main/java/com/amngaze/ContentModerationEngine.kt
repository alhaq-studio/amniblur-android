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

/**
 * Whole-screen classification result returned by [ContentModerationEngine].
 */
data class VisionVerdict(
    val isViolation: Boolean,
    val confidence: Float,
    val threshold: Float,
    val categoryName: String,
    val modelType: ModerationModelType
)

/**
 * On-Device Whole-Screen Content Moderation Engine leveraging Google AI Edge LiteRT (TensorFlow Lite).
 *
 * Uses model-accurate preprocessing and output class mapping to eliminate false positives:
 * - [ModerationModelType.DEFAULT_NSFW] (`amn_gaze.tflite` — Yahoo OpenNSFW ResNet-50):
 *   224x224 FLOAT32 BGR with VGG mean subtraction (`B - 104f`, `G - 117f`, `R - 123f`),
 *   2-class output (`[0] = SFW`, `[1] = NSFW`).
 * - [ModerationModelType.QUANTIZED_NSFW] (`nsfw_mobilenet_v2_140_224_quantized.tflite` — GantMan 5-class MobileNetV2):
 *   224x224 RGB normalized `[0f..1f]` (FLOAT32) or UINT8 RGB,
 *   5-class output (`[0] = drawings`, `[1] = hentai`, `[2] = neutral`, `[3] = porn`, `[4] = sexy`),
 *   computing explicit score strictly from `porn` (`[3]`) + `hentai` (`[1]`).
 * - [ModerationModelType.GENDER_FILTER] (`model_lite_gender_q.tflite`):
 *   128x128 RGB normalized `[0f..1f]`, 2-class output.
 */
class ContentModerationEngine(private val context: Context) {
    companion object {
        private const val TAG = "ContentModerationEngine"
        const val DEFAULT_INPUT_SIZE = 224

        // Yahoo open_nsfw BGR channel means
        private const val VGG_MEAN_B = 104.0f
        private const val VGG_MEAN_G = 117.0f
        private const val VGG_MEAN_R = 123.0f

        /**
         * Maps user sensitivity (0.20..0.95, default 0.50) to a strict confidence threshold
         * (0.58..0.90, default 0.75) so normal UI screens do not false-trigger.
         */
        fun confidenceThresholdForSensitivity(sensitivity: Float): Float {
            val clamped = sensitivity.coerceIn(0.2f, 0.95f)
            return (1.0f - (clamped * 0.5f)).coerceIn(0.58f, 0.90f)
        }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var loadedModelType: ModerationModelType? = null

    init {
        reloadModel(ModerationModelType.DEFAULT_NSFW)
    }

    @Synchronized
    private fun reloadModel(modelType: ModerationModelType) {
        Log.i(TAG, "Switching/reloading LiteRT model to: $modelType")
        close()

        val modelFileName = when (modelType) {
            ModerationModelType.DEFAULT_NSFW -> "amn_gaze.tflite"
            ModerationModelType.QUANTIZED_NSFW -> "nsfw_mobilenet_v2_140_224_quantized.tflite"
            ModerationModelType.GENDER_FILTER -> "model_lite_gender_q.tflite"
        }

        val modelBuffer = loadModelFile(context, modelFileName)
        if (modelBuffer != null) {
            try {
                val options = Interpreter.Options()
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                interpreter = Interpreter(modelBuffer, options)
                loadedModelType = modelType
                Log.i(TAG, "LiteRT Interpreter initialized with GPU delegate: $modelFileName")
            } catch (e: Throwable) {
                Log.w(TAG, "GPU delegate unavailable for $modelFileName; falling back to multi-thread CPU.", e)
                try {
                    gpuDelegate?.close()
                } catch (_: Throwable) {
                }
                gpuDelegate = null
                try {
                    val cpuOptions = Interpreter.Options().apply {
                        setNumThreads(4)
                    }
                    interpreter = Interpreter(modelBuffer, cpuOptions)
                    loadedModelType = modelType
                    Log.i(TAG, "LiteRT Interpreter initialized on CPU (4 threads): $modelFileName")
                } catch (ex: Throwable) {
                    Log.e(TAG, "Failed to initialize LiteRT Interpreter on CPU for $modelFileName", ex)
                    interpreter = null
                    loadedModelType = null
                }
            }
        } else {
            interpreter = null
            loadedModelType = null
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
     * Preprocesses an ARGB_8888 Bitmap according to the exact channel order and normalization
     * expected by [modelType].
     */
    fun preprocessBitmap(
        bitmap: Bitmap,
        inputSize: Int,
        isQuantized: Boolean,
        modelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW
    ): ByteBuffer {
        val scaledBitmap = if (bitmap.width == inputSize && bitmap.height == inputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        }
        val numBytesPerChannel = if (isQuantized) 1 else 4
        val byteBuffer = ByteBuffer.allocateDirect(numBytesPerChannel * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

        byteBuffer.rewind()
        for (pixelValue in intValues) {
            val r = ((pixelValue shr 16) and 0xFF)
            val g = ((pixelValue shr 8) and 0xFF)
            val b = (pixelValue and 0xFF)

            if (isQuantized) {
                // Quantized MobileNetV2 / Gender models use RGB byte order
                byteBuffer.put(r.toByte())
                byteBuffer.put(g.toByte())
                byteBuffer.put(b.toByte())
            } else {
                when (modelType) {
                    ModerationModelType.DEFAULT_NSFW -> {
                        // Yahoo OpenNSFW expects BGR float32 with VGG mean subtraction
                        byteBuffer.putFloat(b.toFloat() - VGG_MEAN_B)
                        byteBuffer.putFloat(g.toFloat() - VGG_MEAN_G)
                        byteBuffer.putFloat(r.toFloat() - VGG_MEAN_R)
                    }
                    ModerationModelType.QUANTIZED_NSFW,
                    ModerationModelType.GENDER_FILTER -> {
                        // MobileNetV2 / Keras models expect RGB normalized to [0.0f, 1.0f]
                        byteBuffer.putFloat(r.toFloat() / 255.0f)
                        byteBuffer.putFloat(g.toFloat() / 255.0f)
                        byteBuffer.putFloat(b.toFloat() / 255.0f)
                    }
                }
            }
        }

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun analyzeHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        colorSpace: ColorSpace?,
        sensitivity: Float = 0.5f,
        modelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW
    ): VisionVerdict {
        var hwBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null

        try {
            val targetColorSpace = colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, targetColorSpace)
            if (hwBitmap != null) {
                scaledBitmap = Bitmap.createScaledBitmap(
                    hwBitmap,
                    DEFAULT_INPUT_SIZE,
                    DEFAULT_INPUT_SIZE,
                    true
                )
                softwareBitmap = scaledBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy HardwareBuffer to software ARGB_8888 bitmap.", t)
        } finally {
            try {
                if (scaledBitmap != null && scaledBitmap !== hwBitmap) {
                    scaledBitmap.recycle()
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
            classifyBitmap(
                bitmap = bitmapToAnalyze,
                sensitivity = sensitivity,
                modelType = modelType
            )
        } finally {
            try {
                bitmapToAnalyze.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun classifyBitmap(
        bitmap: Bitmap,
        sensitivity: Float = 0.5f,
        modelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW
    ): VisionVerdict {
        val threshold = confidenceThresholdForSensitivity(sensitivity)

        if (loadedModelType != modelType || interpreter == null) {
            reloadModel(modelType)
        }

        val interpreterInstance = interpreter
            ?: return VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "MODEL_UNAVAILABLE",
                modelType = modelType
            )

        return try {
            val inputTensor = interpreterInstance.getInputTensor(0)
            val inputShape = inputTensor.shape()
            val inputSize = if (inputShape.size >= 3 && inputShape[1] > 0) inputShape[1] else DEFAULT_INPUT_SIZE
            val inputDataType = inputTensor.dataType()
            val isQuantized = (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8)

            val inputBuffer = preprocessBitmap(bitmap, inputSize, isQuantized, modelType)

            val outputTensor = interpreterInstance.getOutputTensor(0)
            val outputDataType = outputTensor.dataType()
            val outputShape = outputTensor.shape()
            val numClasses = if (outputShape.size >= 2 && outputShape[1] > 0) outputShape[1] else 2

            val probabilities = FloatArray(numClasses)

            if (outputDataType == DataType.FLOAT32) {
                val outputScores = Array(1) { FloatArray(numClasses) }
                interpreterInstance.run(inputBuffer, outputScores)
                for (i in 0 until numClasses) {
                    probabilities[i] = outputScores[0][i]
                }
            } else {
                val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
                outputBuffer.order(ByteOrder.nativeOrder())
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

            // Compute explicit score accurately based on the model's output class schema:
            // - 5-class GantMan MobileNetV2: [0]=drawings, [1]=hentai, [2]=neutral, [3]=porn, [4]=sexy
            // - 2-class OpenNSFW / Gender: [0]=safe/male, [1]=explicit/female
            val score: Float = if (numClasses >= 5) {
                val hentaiProb = probabilities[1]
                val pornProb = probabilities[3]
                (pornProb + hentaiProb).coerceIn(0f, 1f)
            } else if (numClasses >= 2) {
                probabilities[1].coerceIn(0f, 1f)
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

            Log.d(TAG, "Whole-screen LiteRT inference ($modelType, classes=$numClasses): score=$score, threshold=$threshold, violation=$isViolation")
            VisionVerdict(
                isViolation = isViolation,
                confidence = score,
                threshold = threshold,
                categoryName = category,
                modelType = modelType
            )
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT inference exception", e)
            VisionVerdict(
                isViolation = false,
                confidence = 0f,
                threshold = threshold,
                categoryName = "ERROR",
                modelType = modelType
            )
        }
    }

    @Synchronized
    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LiteRT resources.", e)
        } finally {
            interpreter = null
            gpuDelegate = null
        }
    }
}
