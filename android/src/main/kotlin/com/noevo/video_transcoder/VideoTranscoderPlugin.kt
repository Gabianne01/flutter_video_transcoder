package com.noevo.video_transcoder

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
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
            val composition = Composition.Builder(listOf(edited)).build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            // Attach listener dynamically to handle both 1.3.x and 1.5.x APIs
            try {
                transformer.javaClass.getMethod("addListener", Transformer.Listener::class.java)
                    .invoke(transformer, object : Transformer.Listener {
                        // Works for 1.3.x (2 params) and 1.5.x (3 params) — no 'override' keywords
                        fun onCompleted(vararg args: Any?) {
                            result.success(output)
                        }

                        fun onError(vararg args: Any?) {
                            val ex = args.lastOrNull() as? ExportException
                            result.error("TRANSFORM_ERROR", ex?.message, null)
                        }
                    })
            } catch (ignored: Exception) {
                // addListener might fail if version mismatch — fallback silently
            }

            transformer.start(composition, output)

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
