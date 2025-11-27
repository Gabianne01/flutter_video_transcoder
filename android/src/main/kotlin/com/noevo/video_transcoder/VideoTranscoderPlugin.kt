package com.noevo.video_transcoder

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Minimal, safe, predictable H.264 transcoder:
 *  - No smears
 *  - No black bars
 *  - No upscaling
 *  - No aspect distortion
 *  - Rotation handled ONLY by Media3
 *  - Dimensions aligned to 16 IN DISPLAY SPACE
 */
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

        val inputPath = call.argument<String>("input")
        val outputPath = call.argument<String>("output")

        val maxHeight = call.argument<Int>("maxHeight") ?: 720
        val targetBitrate = call.argument<Int>("bitrate") ?: 1_600_000

        if (inputPath.isNullOrBlank() || outputPath.isNullOrBlank()) {
            result.error("BAD_ARGS", "Input/output missing", null)
            return
        }

        val inputFile = File(inputPath)
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        if (!inputFile.exists()) {
            result.error("NO_INPUT", "Input not found", null)
            return
        }

        try {
            // --- Read metadata ---
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

            // --- DISPLAY size (this is what we visually see) ---
            val displayW = if (rotation == 90 || rotation == 270) rawH else rawW
            val displayH = if (rotation == 90 || rotation == 270) rawW else rawH

            // --- Downscale (never upscale) ---
            val scale = if (displayH > maxHeight)
                maxHeight.toFloat() / displayH.toFloat() else 1f

            var targetW = (displayW * scale).toInt()
            var targetH = (displayH * scale).toInt()

            // --- Clamp: never produce tiny illegal sizes ---
            if (targetW < 16) targetW = 16
            if (targetH < 16) targetH = 16

            // --- Align to 16 (in DISPLAY space!) ---
            fun align16(x: Int): Int = ((x + 15) / 16) * 16

            targetW = align16(targetW)
            targetH = align16(targetH)

            Log.i("VideoSafeTranscoder",
                "raw=${rawW}x$rawH rot=$rotation° display=${displayW}x$displayH → target=${targetW}x$targetH")

            // --- Presentation effect: SCALE + CROP (NO BARS EVER) ---
            val presentation = Presentation.createForWidthAndHeight(
                targetW,
                targetH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )

            val effects = Effects(
                /* audioProcessors = */ emptyList(),
                /* videoEffects = */ listOf(presentation)
            )

            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .setRemoveAudio(false)
                .build()

            val composition = Composition.Builder(listOf(EditedMediaItemSequence(listOf(edited))))
                .build()

            // --- Request: simple, safe H.264 + AAC ---
            val request = TransformationRequest.Builder()
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .setEnableFallback(true) // fallback to software if needed
                .build()

            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setTransformationRequest(request)
                .addListener(object : Transformer.Listener {

                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        val orig = inputFile.length()
                        val comp = outputFile.length()

                        // Restore original if useless
                        if (orig > 0 && comp > 0) {
                            val ratio = comp.toFloat() / orig.toFloat()
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
                        Log.e("VideoSafeTranscoder", "ERROR", exception)
                        result.error("TRANSFORM_FAIL", exception.message, null)
                    }
                })
                .build()

            transformer.start(composition, outputPath)

        } catch (e: Exception) {
            result.error("SETUP_FAIL", e.message, null)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
