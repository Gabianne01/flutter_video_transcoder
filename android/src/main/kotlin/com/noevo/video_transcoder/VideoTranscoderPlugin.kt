package com.noevo.video_transcoder

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleToFit
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
                val mediaItem = MediaItem.fromUri(Uri.fromFile(File(input)))

                // --- Build transformation request (formats only) ---
                val transformationRequest = TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()

                // --- Presentation effect to scale down video to max 720p ---
                val presentation = Presentation.createForHeight(720, ScaleToFit.FIT)

                // --- Build transformer with listener ---
                val transformer = Transformer.Builder(context)
                    .setTransformationRequest(transformationRequest)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            result.success(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exception: ExportException
                        ) {
                            result.error("TRANSFORM_ERROR", exception.message, null)
                        }
                    })
                    .build()

                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(VideoGraphInput.of(presentation))
                    .build()

                val sequence = EditedMediaItemSequence(listOf(edited))
                val composition = Composition.Builder(listOf(sequence)).build()

                transformer.start(composition, output)

            } catch (e: Exception) {
                result.error("REFLECT_FAIL", e.message, null)
            }

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
