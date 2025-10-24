package com.noevo.video_transcoder

import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Composition
import android.net.Uri
import java.io.File
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

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
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            transformer.start(
                MediaItem.fromUri(Uri.fromFile(File(input))),
                output,
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        result.success(output)
                    }
                    override fun onError(error: ExportException) {
                        result.error("TRANSFORM_ERROR", error.message, null)
                    }
                }
            )
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
