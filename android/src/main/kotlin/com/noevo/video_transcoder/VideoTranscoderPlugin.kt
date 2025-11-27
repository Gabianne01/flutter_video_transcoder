package com.noevo.video_transcoder

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

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
        if (!inputFile.exists()) {
            result.error("NO_INPUT", "File not found: $inputPath", null)
            return
        }

        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        try {
            // ===== 1. Extract metadata =====
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.fromFile(inputFile))

            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            retriever.release()

            if (rawW <= 0 || rawH <= 0) {
                result.error("BAD_METADATA", "Invalid video dimensions", null)
                return
            }

            // ===== 2. Compute display-space size =====
            val displayW = if (rotation == 90 || rotation == 270) rawH else rawW
            val displayH = if (rotation == 90 || rotation == 270) rawW else rawH

            // ===== 3. Downscale (never upscale) =====
            val scale = if (displayH > maxHeight) {
                maxHeight.toFloat() / displayH.toFloat()
            } else 1f

            var targetW = (displayW * scale).toInt()
            var targetH = (displayH * scale).toInt()

            // Avoid invalid small sizes
            if (targetW < 16) targetW = 16
            if (targetH < 16) targetH = 16

            // ===== 4. Align to mod-16 (floor), just like your working patch =====
            fun align16(x: Int): Int {
                val v = (x / 16) * 16
                return if (v < 16) 16 else v
            }

            targetW = align16(targetW)
            targetH = align16(targetH)

            Log.i("VideoTranscoder", "raw=${rawW}x$rawH rot=$rotation° -> target=${targetW}x$targetH")

            // ===== 5. Presentation (no black bars) =====
            val presentation = Presentation.createForWidthAndHeight(
                targetW,
                targetH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )

            val effects = Effects(
                emptyList(),                 // no audio effects
                listOf(presentation)         // crop-to-fit presentation
            )

            // ===== 6. Build EditedMediaItem =====
            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .setRemoveAudio(false)
                .build()

            val composition = Composition.Builder(
                listOf(EditedMediaItemSequence(listOf(edited)))
            ).build()

            // ===== 7. Encoder settings (bitrate control) =====
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .setEnableFallback(true)    // allow HW->SW fallback
                .build()

            // ===== 8. Transformer (Media3 1.8.0 compliant) =====
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        val originalSize = inputFile.length()
                        val compressedSize = outputFile.length()

                        // If compression useless, restore original
                        if (originalSize > 0 && compressedSize > 0) {
                            val ratio = compressedSize.toFloat() / originalSize.toFloat()
                            if (ratio >= 0.95f) {
                                inputFile.copyTo(outputFile, overwrite = true)
                            }
                        }

                        result.success(outputPath)
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

            // ===== 9. Start transformation (correct for 1.8.0) =====
            transformer.start(composition, outputPath)

        } catch (e: Exception) {
            result.error("SETUP_FAIL", e.message, null)
        }
    }
}
