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

            val outputFile = File(output)
            outputFile.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }

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

            // --- Step 2: compute DISPLAY size (orientation-only effect) ---
            val displayW = if (rotationDegrees == 90 || rotationDegrees == 270) rawH else rawW
            val displayH = if (rotationDegrees == 90 || rotationDegrees == 270) rawW else rawH

            // --- Step 3: natural downscale without upscaling ---
            val scale = if (displayH > maxHeight) {
                maxHeight.toFloat() / displayH.toFloat()
            } else 1f

            val scaledW = (displayW * scale).toInt()
            val scaledH = (displayH * scale).toInt()

            // --- Step 4: alignment function ---
            fun align16(x: Int): Int = if (x > 0) (x / 16) * 16 else x

            // --- Step 5: align based on CODEC (raw orientation) ---
            val outW: Int
            val outH: Int

            if (rotationDegrees == 90 || rotationDegrees == 270) {
                outW = align16(scaledH)  // display H → codec width
                outH = align16(scaledW)  // display W → codec height
            } else {
                outW = align16(scaledW)
                outH = align16(scaledH)
            }

            Log.i(
                "VideoTranscoder",
                "📏 raw=${rawW}x$rawH, rot=$rotationDegrees°, " +
                "scaled=${scaledW}x$scaledH, aligned=${outW}x$outH"
            )

            // --- Step 6: Presentation ONLY —
            //     Media3 handles rotation internally safely (OpenGL path).
            val presentation = Presentation.createForWidthAndHeight(
                outW,
                outH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )

            val effects = Effects(
                emptyList(),        // audio processors
                listOf(presentation)
            )

            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .build()

            val sequence = EditedMediaItemSequence(listOf(edited))
            val composition = Composition.Builder(listOf(sequence)).build()

            // --- Step 7: H.264 + AAC + bitrate control ---
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
                            val compressedSize = outputFile.length()

                            Log.i(
                                "VideoTranscoder",
                                "✅ Completed: original=${originalSize}B, compressed=${compressedSize}B"
                            )

                            // If compression useless (<5% savings) → restore original
                            if (originalSize > 0 && compressedSize > 0) {
                                val ratio =
                                    compressedSize.toFloat() / originalSize.toFloat()

                                if (ratio >= 0.95f) {
                                    Log.i("VideoTranscoder", "ℹ Restoring original file.")
                                    inputFile.copyTo(outputFile, overwrite = true)
                                }
                            }

                            result.success(output)
                        } catch (e: Exception) {
                            result.error("POST_PROCESS", e.message, null)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e("VideoTranscoder", "❌ Transform error", exception)
                        result.error("TRANSFORM_ERROR", exception.message, null)
                    }
                })
                .build()

            transformer.start(composition, output)

        } catch (e: Exception) {
            Log.e("VideoTranscoder", "SETUP EXCEPTION", e)
            result.error("SETUP_FAIL", e.message, null)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
