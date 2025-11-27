package com.noevo.video_transcoder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
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

/**
 * Method channel: "video_transcoder"
 *
 * Method: "transcodeToSafeH264"
 * Args:
 *   - input: String (absolute path)
 *   - output: String (absolute path)
 *   - maxHeight: Int?  (optional, defaults to 720)
 *   - bitrate: Int?    (optional, defaults to 1_600_000)
 */
@OptIn(UnstableApi::class)
class VideoTranscoderPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "video_transcoder")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "transcodeToSafeH264") {
            result.notImplemented()
            return
        }

        val inputPath = call.argument<String>("input")
        val outputPath = call.argument<String>("output")
        val maxHeight = call.argument<Int>("maxHeight") ?: 720
        val targetBitrate = call.argument<Int>("bitrate") ?: 1_600_000

        if (inputPath.isNullOrBlank() || outputPath.isNullOrBlank()) {
            result.error("BAD_ARGS", "input and output paths are required", null)
            return
        }

        try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                result.error("NO_INPUT", "Input file not found at $inputPath", null)
                return
            }

            val outputFile = File(outputPath)
            outputFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    result.error("NO_OUTPUT_DIR", "Unable to create output directory", null)
                    return
                }
            }

            // 1) Probe video
            val meta = readVideoMetadata(context, inputFile)
            if (meta == null || meta.displayW <= 0 || meta.displayH <= 0) {
                Log.e(TAG, "Bad metadata: $meta")
                // Fallback: just copy original without compression
                inputFile.copyTo(outputFile, overwrite = true)
                result.success(outputPath)
                return
            }

            val displayW = meta.displayW
            val displayH = meta.displayH
            val rotation = meta.rotation

            Log.i(TAG, "raw=${meta.rawW}x${meta.rawH}, rot=$rotation°, display=${displayW}x$displayH")

            // 2) Compute scaled display size with maxHeight=720, no upscaling
            val scale = if (displayH > maxHeight) {
                maxHeight.toFloat() / displayH.toFloat()
            } else {
                1f
            }

            val scaledDisplayW = (displayW * scale).toInt().coerceAtLeast(2)
            val scaledDisplayH = (displayH * scale).toInt().coerceAtLeast(2)

            // 3) Align to mod16 in display space (like your old patch)
            fun align16(x: Int): Int = if (x > 0) (x / 16) * 16 else x

            var safeDisplayW = align16(scaledDisplayW)
            var safeDisplayH = align16(scaledDisplayH)

            // Don't let them collapse to 0; fall back to at least 16x16
            if (safeDisplayW < 16) safeDisplayW = 16
            if (safeDisplayH < 16) safeDisplayH = 16

            Log.i(
                TAG,
                "scaledDisplay=${scaledDisplayW}x$scaledDisplayH → safeDisplay=${safeDisplayW}x$safeDisplayH"
            )

            // NOTE: Presentation takes the display-space target size.
            // Media3 will handle rotation internally using GL, so we define
            // the size "as seen by the user" (no swapped width/height here).
            val presentation = Presentation.createForWidthAndHeight(
                safeDisplayW,
                safeDisplayH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP // crop-to-fill: no black bars
            )

            val effects = Effects(
                emptyList(),                 // no audio effects
                listOf(presentation)         // single video effect
            )

            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .setRemoveAudio(false)
                .build()

            val sequence = EditedMediaItemSequence(listOf(editedItem))
            val composition = Composition.Builder(listOf(sequence)).build()

            // 4) Encoder settings: H.264 + AAC, modest bitrate
            val request = TransformationRequest.Builder()
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .setEnableFallback(true) // allow Media3 to adjust settings if needed
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
                            val compressedSize = outputFile.length()

                            Log.i(
                                TAG,
                                "✅ Completed: original=${originalSize}B, compressed=${compressedSize}B, " +
                                        "videoEncoder=${exportResult.videoEncoderName}"
                            )

                            // Optional: if compression is useless (<5% savings), keep original
                            if (originalSize > 0 && compressedSize > 0) {
                                val ratio =
                                    compressedSize.toFloat() / originalSize.toFloat()
                                if (ratio >= 0.95f) {
                                    Log.i(TAG, "ℹ Restoring original (compression not worth it).")
                                    inputFile.copyTo(outputFile, overwrite = true)
                                }
                            }

                            result.success(outputPath)
                        } catch (e: Exception) {
                            Log.e(TAG, "POST_PROCESS EXCEPTION", e)
                            result.error("POST_PROCESS", e.message, null)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e(
                            TAG,
                            "❌ Transform error, videoEncoder=${exportResult.videoEncoderName}",
                            exception
                        )
                        result.error("TRANSFORM_ERROR", exception.message, null)
                    }
                })
                .build()

            // 5) Start transformation
            transformer.start(composition, outputPath)

        } catch (t: Throwable) {
            Log.e(TAG, "SETUP_FAIL", t)
            result.error("SETUP_FAIL", t.message, null)
        }
    }

    // ------------------------------------------------------------------------
    // Metadata helpers
    // ------------------------------------------------------------------------

    private data class VideoMeta(
        val rawW: Int,
        val rawH: Int,
        val rotation: Int,
        val displayW: Int,
        val displayH: Int
    )

    private fun readVideoMetadata(context: Context, file: File): VideoMeta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.fromFile(file))

            val rawW = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawH = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            val displayW = if (rotation == 90 || rotation == 270) rawH else rawW
            val displayH = if (rotation == 90 || rotation == 270) rawW else rawH

            if (rawW <= 0 || rawH <= 0 || displayW <= 0 || displayH <= 0) {
                null
            } else {
                VideoMeta(rawW, rawH, rotation, displayW, displayH)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Metadata read failed", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "VideoTranscoderPlugin"
    }
}
