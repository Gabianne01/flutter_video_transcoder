package com.noevo.video_transcoder

import android.net.Uri
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

// Media3 imports (1.5.1)
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.VideoEncoderSettings

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
      val input = call.argument<String>("input") ?: run {
        result.error("ARG", "Missing 'input' path", null); return
      }
      val output = call.argument<String>("output") ?: run {
        result.error("ARG", "Missing 'output' path", null); return
      }

      try {
        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(input)))

        // Scale down to 720p height (keeps aspect ratio). Other layouts available. :contentReference[oaicite:6]{index=6}
        val videoEffects = listOf(Presentation.createForHeight(720))
        val effects = Effects(videoEffects, /*audioProcessors=*/ emptyList())

        val edited = EditedMediaItem.Builder(mediaItem)
          .setEffects(effects)
          // You can also trim/mute/flatten slow-mo here if needed.
          .build()

        // Ask hardware encoder for ~2.5 Mbps video bitrate. 
        val encoderFactory = DefaultEncoderFactory.Builder(context)
          .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder()
              .setBitrate(2_500_000) // ~2.5 Mbps target; device may clamp
              .build()
          )
          .build()

        val transformer = Transformer.Builder(context)
          .setEncoderFactory(encoderFactory)
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
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

        transformer.start(edited, output)
      } catch (e: Exception) {
        result.error("NATIVE_FAIL", e.message, null)
      }
    } else {
      result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}




