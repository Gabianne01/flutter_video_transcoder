package com.noevo.video_transcoder

import android.net.Uri
import android.util.Log
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

            Log.i("VideoTranscoder", "🎬 Starting transcode: $input → $output")

            try {
                val mediaItemCls = Class.forName("androidx.media3.common.MediaItem")
                val mimeTypesCls = Class.forName("androidx.media3.common.MimeTypes")
                val editedMediaItemCls = Class.forName("androidx.media3.transformer.EditedMediaItem")
                val compositionCls = Class.forName("androidx.media3.transformer.Composition")
                val transformerCls = Class.forName("androidx.media3.transformer.Transformer")

                // MediaItem.fromUri(Uri)
                val fromUri = mediaItemCls.getMethod("fromUri", Uri::class.java)
                val mediaItem = fromUri.invoke(null, Uri.fromFile(File(input)))

                // EditedMediaItem.Builder(mediaItem).build()
                val builderCtor = editedMediaItemCls.declaredClasses
                    .firstOrNull { it.simpleName == "Builder" }?.getConstructor(mediaItemCls)
                val editedBuilder = builderCtor?.newInstance(mediaItem)
                val buildEdited = editedBuilder?.javaClass?.getMethod("build")
                val edited = buildEdited?.invoke(editedBuilder)

                // Composition.Builder(listOf(edited)).build()
                val builderCtor2 = compositionCls.declaredClasses
                    .firstOrNull { it.simpleName == "Builder" }?.getConstructor(List::class.java)
                val compBuilder = builderCtor2?.newInstance(listOf(edited))
                val buildComp = compBuilder?.javaClass?.getMethod("build")
                val composition = buildComp?.invoke(compBuilder)

                // Transformer.Builder(context)
                val builderCtor3 = transformerCls.declaredClasses
                    .firstOrNull { it.simpleName == "Builder" }?.getConstructor(android.content.Context::class.java)
                val transBuilder = builderCtor3?.newInstance(context)

                val setVideo = transBuilder?.javaClass?.getMethod("setVideoMimeType", String::class.java)
                val setAudio = transBuilder?.javaClass?.getMethod("setAudioMimeType", String::class.java)

                val videoH264 = mimeTypesCls.getField("VIDEO_H264").get(null) as String
                val audioAac = mimeTypesCls.getField("AUDIO_AAC").get(null) as String
                setVideo?.invoke(transBuilder, videoH264)
                setAudio?.invoke(transBuilder, audioAac)

                // Try to set resolution (only exists on some API levels)
                try {
                    val setResolution = transBuilder?.javaClass?.methods
                        ?.firstOrNull { it.name == "setVideoResolution" }
                    if (setResolution != null) {
                        setResolution.invoke(transBuilder, 960, 540)
                        Log.i("VideoTranscoder", "📏 Forced 960x540 resolution")
                    } else {
                        Log.i("VideoTranscoder", "⚠️ No setVideoResolution() found (skipping)")
                    }
                } catch (e: Exception) {
                    Log.w("VideoTranscoder", "Resolution override failed: ${e.message}")
                }

                val build = transBuilder?.javaClass?.getMethod("build")
                val transformer = build?.invoke(transBuilder)

                // Listener proxy (dynamic to support multiple versions)
                try {
                    val listenerIface = transformerCls.declaredClasses
                        .firstOrNull { it.simpleName == "Listener" }
                    val addListener = transformerCls.getMethod("addListener", listenerIface)
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        listenerIface?.classLoader,
                        arrayOf(listenerIface)
                    ) { _, method, args ->
                        when (method.name) {
                            "onCompleted" -> {
                                Log.i("VideoTranscoder", "✅ Transcode done: $output")
                                result.success(output)
                            }
                            "onError" -> {
                                val ex = args?.lastOrNull()?.toString()
                                Log.e("VideoTranscoder", "❌ Transcode error: $ex")
                                result.error("TRANSFORM_ERROR", ex, null)
                            }
                        }
                        null
                    }
                    addListener.invoke(transformer, proxy)
                } catch (_: Exception) {
                    // Safe to ignore if listener API differs
                }

                // Start transformation
                val start = transformerCls.methods.firstOrNull {
                    it.name == "start" && it.parameterTypes.size == 2
                }
                start?.invoke(transformer, composition, output)

            } catch (e: Exception) {
                Log.e("VideoTranscoder", "❌ Reflect fail: ${e.message}")
                result.error("REFLECT_FAIL", e.message, null)
            }

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
