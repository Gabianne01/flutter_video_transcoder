package com.noevo.video_transcoder

import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
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

            Log.i("VideoTranscoder", "🎬 Transcoding: $input → $output")

            try {
                // Build a MediaItem
                val mediaItem = MediaItem.fromUri(Uri.fromFile(File(input)))
                val editedItem = EditedMediaItem.Builder(mediaItem).build()

                // Wrap inside an EditedMediaItemSequence
                val sequence = EditedMediaItemSequence(listOf(editedItem))

                // Then a Composition with that sequence
                val composition = Composition.Builder(listOf(sequence)).build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            Log.i("VideoTranscoder", "✅ Done: ${exportResult.fileSizeBytes} bytes")
                            result.success(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exception: ExportException
                        ) {
                            Log.e("VideoTranscoder", "❌ Error: ${exception.message}")
                            result.error("TRANSFORM_ERROR", exception.message, null)
                        }
                    })
                    .build()

                transformer.start(composition, output)

            } catch (e: Exception) {
                Log.e("VideoTranscoder", "Exception: ${e.message}")
                result.error("REFLECT_FAIL", e.message, null)
            }

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}

