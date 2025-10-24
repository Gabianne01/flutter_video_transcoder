package com.noevo.video_transcoder

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.TransformationRequest
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
                    result.error("NO_INPUT", "Input file not found: $input", null)
                    return
                }

                Log.i("VideoTranscoder", "🎬 Starting transcode: $input → $output")

                val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

                // --- Configure your target resolution & bitrate ---
                val transformRequest = TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    // ↓ Target around 720p, hardware encoders will adapt aspect ratio
                    .setVideoWidth(1280)
                    .setVideoHeight(720)
                    // ↓ Bitrate in bits per second (e.g., 3 Mbps)
                    .setVideoBitrate(3_000_000)
                    .build()

                val transformer = Transformer.Builder(context)
                    .setTransformationRequest(transformRequest)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            Log.i("VideoTranscoder", "✅ Transcode done: $output")
                            result.success(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exception: ExportException
                        ) {
                            Log.e("VideoTranscoder", "❌ Transcode error: ${exception.message}")
                            result.error("TRANSFORM_ERROR", exception.message, null)
                        }
                    })
                    .build()

                transformer.start(mediaItem, output)

            } catch (e: Exception) {
                Log.e("VideoTranscoder", "❌ Setup failed: ${e.message}", e)
                result.error("SETUP_FAIL", e.message, null)
            }

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}


