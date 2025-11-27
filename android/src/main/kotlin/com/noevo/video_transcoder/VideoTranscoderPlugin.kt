package com.noevo.video_transcoder

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import kotlin.math.roundToInt

class VideoTranscoderPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: android.content.Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "video_transcoder")
        context = binding.applicationContext
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "transcodeToSafeH264") {
            result.notImplemented()
            return
        }

        val input = call.argument<String>("input")
        val output = call.argument<String>("output")

        if (input.isNullOrBlank() || output.isNullOrBlank()) {
            result.error("BAD_ARGS", "input and output paths are required", null)
            return
        }

        val maxHeight = call.argument<Int>("maxHeight") ?: 720
        val targetBitrate = call.argument<Int>("bitrate") ?: 1_500_000

        try {
            val inputFile = File(input)
            if (!inputFile.exists()) {
                result.error("NO_INPUT", "Input file not found at $input", null)
                return
            }

            // Make sure output directory exists
            val outputFile = File(output)
            outputFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }

            Log.i("VideoTranscoder", "🎬 Starting transcode: $input → $output")

            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

           // --- Step 1: Read raw coded resolution + rotation ---
val retriever = MediaMetadataRetriever()
retriever.setDataSource(context, Uri.fromFile(inputFile))

val rawW = retriever.extractMetadata(
    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
)?.toIntOrNull() ?: 0

val rawH = retriever.extractMetadata(
    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
)?.toIntOrNull() ?: 0

val rotationDegrees = retriever.extractMetadata(
    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
)?.toIntOrNull() ?: 0

retriever.release()

if (rawW <= 0 || rawH <= 0) {
    Log.w("VideoTranscoder", "Could not read resolution; proceeding anyway")
}

// --- Step 2: Compute DISPLAY size (orientation only affects display, not alignment) ---
val displayW = if (rotationDegrees == 90 || rotationDegrees == 270) rawH else rawW
val displayH = if (rotationDegrees == 90 || rotationDegrees == 270) rawW else rawH

// --- Step 3: Natural downscale (no upscaling) ---
val scale = if (displayH > maxHeight) {
    maxHeight.toFloat() / displayH.toFloat()
} else 1f

val scaledW = (displayW * scale).toInt()
val scaledH = (displayH * scale).toInt()

// --- Step 4: Align based on RAW coded orientation (not display orientation) ---
val alignedForCodecW = align16(rawW)
val alignedForCodecH = align16(rawH)

// Now align scaled output based on codec direction
var outW: Int
var outH: Int

if (rotationDegrees == 90 || rotationDegrees == 270) {
    // Codec sees (rawW × rawH) but display uses swapped orientation
    outW = align16(scaledH) // scaleH maps to codecWidth
    outH = align16(scaledW) // scaleW maps to codecHeight
} else {
    outW = align16(scaledW)
    outH = align16(scaledH)
}

if (outW <= 0 || outH <= 0) {
    outW = align16(displayW)
    outH = align16(displayH)
}

Log.i(
    "VideoTranscoder",
    "📏 raw=${rawW}x$rawH, rot=$rotationDegrees°, final=${outW}x$outH"
)

// --- Step 5: Presentation ONLY (Media3 handles rotation internally) ---
val presentation = Presentation.createForWidthAndHeight(
    outW,
    outH,
    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
)

val effects = Effects(
    /* audioProcessors = */ emptyList(),
    /* videoEffects   = */ listOf(presentation)
)

val edited = EditedMediaItem.Builder(mediaItem)
    .setEffects(effects)
    .build()

            val sequence = EditedMediaItemSequence(listOf(edited))
            val composition = Composition.Builder(listOf(sequence)).build()

            // --- Step 5: H.264 + AAC + bitrate control ---
            val request = TransformationRequest.Builder()
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .setEnableFallback(true)
                .build()

            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setTransformationRequest(request)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        try {
                            val originalSize = inputFile.length()
                            val outFile = File(output)
                            val compressedSize = outFile.length()

                            Log.i(
                                "VideoTranscoder",
                                "✅ Completed: original=${originalSize}B, compressed=${compressedSize}B"
                            )

                            if (originalSize > 0 && compressedSize > 0) {
                                val ratio =
                                    compressedSize.toFloat() / originalSize.toFloat()
                                if (ratio >= 0.95f) {
                                    Log.i(
                                        "VideoTranscoder",
                                        "ℹ️ Compressed file is >=95% of original. Copying original over."
                                    )
                                    try {
                                        inputFile.copyTo(outFile, overwrite = true)
                                    } catch (e: Exception) {
                                        Log.w(
                                            "VideoTranscoder",
                                            "Failed to overwrite with original: ${e.message}"
                                        )
                                    }
                                }
                            }

                            result.success(output)
                        } catch (e: Exception) {
                            Log.e("VideoTranscoder", "Post-process error: ${e.message}")
                            result.error("POST_PROCESS", e.message, null)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e(
                            "VideoTranscoder",
                            "❌ Transform error: ${exception.message}",
                            exception
                        )
                        result.error("TRANSFORM_ERROR", exception.message, null)
                    }
                })
                .build()

            transformer.start(composition, output)

        } catch (e: Exception) {
            Log.e("VideoTranscoder", "Exception during setup: ${e.message}", e)
            result.error("SETUP_FAIL", e.message, null)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // no-op
    }
}