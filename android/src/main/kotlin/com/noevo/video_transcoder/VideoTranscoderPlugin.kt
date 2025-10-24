package com.noevo.video_transcoder

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.ScaleAndRotateTransformation
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

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "transcodeToSafeH264") {
            val input = call.argument<String>("input")!!
            val output = call.argument<String>("output")!!

            try {
                val inputFile = File(input)
                if (!inputFile.exists()) {
                    result.error("NO_INPUT", "Input file not found at $input", null)
                    return
                }

                Log.i("VideoTranscoder", "🎬 Starting transcode: $input → $output")

                val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

                // Downscale to 720p by scaling to 1/3 height roughly (for 2160p → 720p)
                val scaleTransform = ScaleAndRotateTransformation.Builder()
                    .setScale(0.33f, 0.33f)
                    .setRotationDegrees(0f)
                    .build()

                val effects = Effects(emptyList(), listOf(scaleTransform))

                val request = TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setVideoBitrate(2_500_000) // available in 1.5.1
                    .build()

                val transformer = Transformer.Builder(context)
                    .setTransformationRequest(request)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            Log.i("VideoTranscoder", "✅ Transcode completed: $output")
                            result.success(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exception: ExportException
                        ) {
                            Log.e("VideoTranscoder", "❌ Transcode failed: ${exception.message}")
                            result.error("TRANSFORM_ERROR", exception.message, null)
                        }
                    })
                    .build()

                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(effects)
                    .build()

                val sequence = EditedMediaItemSequence(listOf(edited))
                val composition = Composition.Builder(listOf(sequence)).build()

                transformer.start(composition, output)

            } catch (e: Exception) {
                Log.e("VideoTranscoder", "Exception during setup: ${e.message}")
                result.error("SETUP_FAIL", e.message, null)
            }
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}


