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
import androidx.media3.transformer.TransformationRequest // included but not used—safe for 1.8.0
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Stable, predictable Media3 1.8.0 transcoder:
 *
 * - No black bars (uses SCALE_TO_FIT_WITH_CROP)
 * - No upscaling
 * - Rotation handled internally by Media3
 * - Resizes only in display space (post-rotation)
 * - Aligns to mod-16 (floor), just like your original working patch
 * - H.264 + AAC output
 * - Bitrate control through DefaultEncoderFactory
 */
class VideoTranscoderPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: android.content.Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "video_transcoder")
        context = binding.applicationContext
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

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
            result.error("BAD_ARGS", "input/output required", null)
            return
        }

        val inputFile = File(inputPath)
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        if (!inputFile.exists()) {
            result.error("NO_INPUT", "Input not found: $inputPath", null)
            return
        }

        try {
            // ===== 1. Read metadata =====
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.fromFile(inputFile))

            val rawW = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            val rawH = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            retriever.release()

            if (rawW <= 0 || rawH <= 0) {
                result.error("BAD_METADATA", "Invalid video dimensions", null)
                return
            }

            // ===== 2. Compute display-space size (post-rotation) =====
            val displayW = if (rotation == 90 || rotation == 270) rawH else rawW
            val displayH = if (rotation == 90 || rotation == 270) rawW else rawH

            // ===== 3. Downscale (never upscale) =====
            val scale = if (displayH > maxHeight)
                maxHeight.toFloat() / displayH.toFloat()
            else
                1f

            var targetW = (displayW * scale).toInt()
            var targetH = (displayH * scale).toInt()

            // Avoid invalid sizes
            if (targetW < 16) targetW = 16
            if (targetH < 16) targetH = 16

            // ===== 4. Align to mod-16 (floor) =====
            fun align16(x: Int): Int {
                if (x <= 0) return 16
                val aligned = (x / 16) * 16
                return if (aligned < 16) 16 else aligned
            }

            targetW = align16(targetW)
            targetH = align16(targetH)

            Log.i(
                "VideoTranscoder",
                "raw=${rawW}x$rawH rot=$rotation° display=${displayW}x${displayH} → ${targetW}x${targetH}"
            )

            // ===== 5. Presentation (NO BLACK BARS) =====
            val presentation = Presentation.createForWidthAndHeight(
                targetW,
                targetH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )

            val effects = Effects(
                emptyList(),           // no audio processors
                listOf(presentation)   // single video effect
            )

            // ===== 6. Build EditedMediaItem (Media3 1.8.0 style) =====
            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .setRemoveAudio(false)
                .setVideoMimeType(MimeTypes.VIDEO_H264) // <-- 1.8.0 correct
                .setAudioMimeType(MimeTypes.AUDIO_AAC) // <-- 1.8.0 correct
                .build()

            val sequence = EditedMediaItemSequence(listOf(edited))
            val composition = Composition.Builder(listOf(sequence)).build()

            // ===== 7. Encoder factory & bitrate =====
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .setEnableFallback(true)  // allow HW→SW fallback
                .build()

            // ===== 8. Build Transformer (1.8.0 API) =====
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {

                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        try {
                            val originalSize = inputFile.length()
                            val compressedSize = outputFile.length()

                            // If compression useless → restore original
                            if (originalSize > 0 && compressedSize > 0) {
                                val ratio = compressedSize.toFloat() / originalSize.toFloat()
                                if (ratio >= 0.95f) {
                                    inputFile.copyTo(outputFile, overwrite = true)
                                }
                            }

                            result.success(outputPath)
                        } catch (e: Exception) {
                            result.error("POST_PROCESS", e.message, null)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        result.error("TRANSFORM_ERROR", exception.message, null)
                    }
                })
                .build()

            // ===== 9. Start transcoding =====
            transformer.start(composition, outputPath)

        } catch (e: Exception) {
            result.error("SETUP_FAIL", e.message, null)
        }
    }
}
