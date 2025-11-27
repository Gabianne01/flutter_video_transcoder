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

        // Optional overrides from Dart, with sane defaults
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

            // --- Step 1: Read original resolution (no manual rotation) ---
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.fromFile(inputFile))

            val srcWidth =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            val srcHeight =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0

            // We DON'T use rotation metadata manually here.
            // Media3 will auto-apply camera rotation if we don't override it.
            retriever.release()

            if (srcWidth <= 0 || srcHeight <= 0) {
                Log.w("VideoTranscoder", "Could not read resolution, leaving as-is.")
            }

            // --- Step 2: Natural scale – no upscaling ---
            // If srcHeight > maxHeight → downscale, else keep original size.
            val scale =
                if (srcHeight > 0 && srcHeight > maxHeight) {
                    maxHeight.toFloat() / srcHeight.toFloat()
                } else {
                    1f
                }

            val scaledW =
                if (srcWidth > 0) (srcWidth * scale).roundToInt() else srcWidth
            val scaledH =
                if (srcHeight > 0) (srcHeight * scale).roundToInt() else srcHeight

            // --- Step 3: Align to 16px to avoid chroma issues ---
            fun align16(x: Int): Int = if (x > 0) (x / 16) * 16 else x

            var outW = align16(scaledW)
            var outH = align16(scaledH)

            // Safety: never drop to 0, fall back to original size
            if (outW <= 0 || outH <= 0) {
                outW = align16(srcWidth)
                outH = align16(srcHeight)
            }

            Log.i(
                "VideoTranscoder",
                "📏 Source=${srcWidth}x$srcHeight, " +
                    "scale=${"%.3f".format(scale)}, output=${outW}x$outH"
            )

            // --- Step 4: Presentation only – let Media3 handle orientation ---
            val presentation = Presentation.createForWidthAndHeight(
                outW,
                outH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )

            val videoEffects = listOf(presentation)
            val effects = Effects(
                /* audioProcessors = */ emptyList(),
                /* videoEffects   = */ videoEffects
            )

            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(effects)
                .build()

            val sequence = EditedMediaItemSequence(listOf(edited))
            val composition = Composition.Builder(listOf(sequence)).build()

            // --- Step 5: Request H.264 + AAC + controlled bitrate ---
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

                            // Guard: if compression is useless (>=95% of original), keep original
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
