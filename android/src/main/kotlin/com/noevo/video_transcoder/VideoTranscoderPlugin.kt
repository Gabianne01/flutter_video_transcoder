package com.noevo.video_transcoder

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.EditedMediaItem
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

            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(input)))
            val edited = EditedMediaItem.Builder(mediaItem).build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(exportResult: ExportResult) {
                        result.success(output)
                    }

                    override fun onError(
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        result.error("TRANSFORM_ERROR", exception.message, null)
                    }
                })
                .build()

            transformer.start(edited, output)

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}