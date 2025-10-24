package com.noevo.video_transcoder

import android.net.Uri
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
                val mediaItemCls = Class.forName("androidx.media3.common.MediaItem")
                val mimeTypesCls = Class.forName("androidx.media3.common.MimeTypes")
                val editedMediaItemCls = Class.forName("androidx.media3.transformer.EditedMediaItem")
                val compositionCls = Class.forName("androidx.media3.transformer.Composition")
                val transformerCls = Class.forName("androidx.media3.transformer.Transformer")

                // Create MediaItem.fromUri(Uri)
                val fromUri = mediaItemCls.getMethod("fromUri", Uri::class.java)
                val mediaItem = fromUri.invoke(null, Uri.fromFile(File(input)))

                // Create EditedMediaItem.Builder(mediaItem).build()
                val builderCtor = editedMediaItemCls.getDeclaredClasses()
                    .firstOrNull { it.simpleName == "Builder" }?.getConstructor(mediaItemCls)
                val editedBuilder = builderCtor?.newInstance(mediaItem)
                val buildEdited = editedBuilder?.javaClass?.getMethod("build")
                val edited = buildEdited?.invoke(editedBuilder)

                // Create Composition.Builder(listOf(edited)).build()
                val builderCtor2 = compositionCls.getDeclaredClasses()
                    .firstOrNull { it.simpleName == "Builder" }?.getConstructor(List::class.java)
                val compBuilder = builderCtor2?.newInstance(listOf(edited))
                val buildComp = compBuilder?.javaClass?.getMethod("build")
                val composition = buildComp?.invoke(compBuilder)

                // Build Transformer.Builder(context)
                val builderCtor3 = transformerCls.getDeclaredClasses()
                    .firstOrNull { it.simpleName == "Builder" }?.getConstructor(android.content.Context::class.java)
                val transBuilder = builderCtor3?.newInstance(context)

                val setVideo = transBuilder?.javaClass?.getMethod("setVideoMimeType", String::class.java)
                val setAudio = transBuilder?.javaClass?.getMethod("setAudioMimeType", String::class.java)

                val videoH264 = mimeTypesCls.getField("VIDEO_H264").get(null) as String
                val audioAac = mimeTypesCls.getField("AUDIO_AAC").get(null) as String

                setVideo?.invoke(transBuilder, videoH264)
                setAudio?.invoke(transBuilder, audioAac)

                val build = transBuilder?.javaClass?.getMethod("build")
                val transformer = build?.invoke(transBuilder)

                // Try to attach a listener if supported
                try {
                    val listenerIface = transformerCls.getDeclaredClasses()
                        .firstOrNull { it.simpleName == "Listener" }
                    val addListener = transformerCls.getMethod("addListener", listenerIface)
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        listenerIface?.classLoader,
                        arrayOf(listenerIface)
                    ) { _, method, args ->
                        when (method.name) {
                            "onCompleted" -> result.success(output)
                            "onError" -> {
                                val ex = args?.lastOrNull()?.toString()
                                result.error("TRANSFORM_ERROR", ex, null)
                            }
                        }
                        null
                    }
                    addListener.invoke(transformer, proxy)
                } catch (_: Exception) {
                    // safe to ignore
                }

                // Start the transformation
                val start = transformerCls.methods.firstOrNull {
                    it.name == "start" && it.parameterTypes.size == 2
                }
                start?.invoke(transformer, composition, output)

            } catch (e: Exception) {
                result.error("REFLECT_FAIL", e.message, null)
            }

        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}
}
