package com.noevo.video_transcoder

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

@OptIn(UnstableApi::class)
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

            val rawW = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0

            val rawH = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            retriever.release()

            if (rawW <= 0 || rawH <= 0) {
                result.error("BAD_METADATA", "Invalid video dimensions", null)
                return
            }

            // ===== 2. Compute display-space size (what user actually sees) =====
            val displayW = if (rotation == 90 || rotation == 270) rawH else rawW
            val displayH = if (rotation == 90 || rotation == 270) rawW else rawH

            // ===== 3. Downscale (never upscale) =====
            val scale = if (displayH > maxHeight) {
                maxHeight.toFloat() / displayH.toFloat()
            } else {
                1f
            }

            var targetW = (displayW * scale).toInt().coerceAtLeast(1)
            var targetH = (displayH * scale).toInt().coerceAtLeast(1)

            // No alignment for safety anymore; only used for choosing encoder path.
            val isMod16Aligned = (targetW % 16 == 0) && (targetH % 16 == 0)

            Log.i(
                "VideoTranscoder",
                "raw=${rawW}x$rawH rot=$rotation° → display=${displayW}x$displayH → target=${targetW}x$targetH (mod16=$isMod16Aligned)"
            )

            // ===== 4. Presentation: crop-to-fit, no black bars =====
            val presentation = Presentation.createForWidthAndHeight(
                targetW,
                targetH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )

            val effects = Effects(
                emptyList(),                 // no audio processors
                listOf(presentation)         // single video effect
            )

            // ===== 5. EditedMediaItem / Composition =====
            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .setRemoveAudio(false)
                .build()

            val sequence = EditedMediaItemSequence(listOf(edited))
            val composition = Composition.Builder(listOf(sequence)).build()

            // ===== 6. Encoder selection logic =====

            // Strict Google software encoder: only OMX.google.h264.encoder, no fallback.
            val googleSwOnlySelector = EncoderSelector { mimeType ->
                val encoders = EncoderUtil.getSupportedEncoders(mimeType)
                val googleSw = encoders.filter {
                    it.name.equals("OMX.google.h264.encoder", ignoreCase = true)
                }

                if (googleSw.isNotEmpty()) {
                    Log.i(
                        "VideoTranscoder",
                        "Using strict Google SW encoder for mimeType=$mimeType " +
                            "→ ${googleSw.joinToString { it.name }}"
                    )
                    ImmutableList.copyOf(googleSw)
                } else {
                    // Option A: no fallback to other encoders.
                    Log.e(
                        "VideoTranscoder",
                        "No OMX.google.h264.encoder found for mimeType=$mimeType. " +
                            "Returning empty encoder list → expect failure."
                    )
                    ImmutableList.of()
                }
            }

            val chosenSelector: EncoderSelector =
                if (isMod16Aligned) {
                    Log.i("VideoTranscoder", "Using DEFAULT encoder selector (HW preferred).")
                    EncoderSelector.DEFAULT
                } else {
                    Log.i("VideoTranscoder", "Unaligned → forcing OMX.google.h264.encoder only.")
                    googleSwOnlySelector
                }

            // ===== 7. Encoder settings (bitrate, nothing else) =====
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .setVideoEncoderSelector(chosenSelector)
                // We keep fallback enabled, but it only applies to *format*, not encoder list.
                // Since we can return an empty encoder list, this won't silently switch back to HW.
                .setEnableFallback(true)
                .build()

            // ===== 8. Transformer =====
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

                            Log.i(
                                "VideoTranscoder",
                                "✅ Completed: original=${originalSize}B, compressed=${compressedSize}B, " +
                                    "videoEncoder=${exportResult.videoEncoderName}"
                            )

                            // If compression is useless (<5% savings) → restore original.
                            if (originalSize > 0 && compressedSize > 0) {
                                val ratio = compressedSize.toFloat() / originalSize.toFloat()
                                if (ratio >= 0.95f) {
                                    Log.i(
                                        "VideoTranscoder",
                                        "ℹ Restoring original (compression not worth it)."
                                    )
                                    inputFile.copyTo(outputFile, overwrite = true)
                                }
                            }

                            result.success(outputPath)
                        } catch (e: Exception) {
                            Log.e("VideoTranscoder", "POST_PROCESS EXCEPTION", e)
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
                            "❌ Transform error, videoEncoder=${exportResult.videoEncoderName}",
                            exception
                        )
                        result.error("TRANSFORM_ERROR", exception.message, null)
                    }
                })
                .build()

            // ===== 9. Start transformation (Media3 1.8.0) =====
            transformer.start(composition, outputPath)

        } catch (e: Exception) {
            Log.e("VideoTranscoder", "SETUP EXCEPTION", e)
            result.error("SETUP_FAIL", e.message, null)
        }
    }
}
