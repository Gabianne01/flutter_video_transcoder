package com.noevo.video_transcoder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Flutter method channel:
 *   Method: "transcodeToSafeH264"
 *   Args:
 *     - input: String (absolute path or SAF param)
 *     - output: String
 *     - maxHeight: Int?  (optional, default 720)
 *     - crf: Int?        (optional, default 23)
 */
class VideoTranscoderPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "video_transcoder")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "transcodeToSafeH264") {
            result.notImplemented()
            return
        }

        val input = call.argument<String>("input")
        val output = call.argument<String>("output")

        if (input.isNullOrBlank() || output.isNullOrBlank()) {
            result.error("BAD_ARGS", "input and output paths are required", null)
            return
        }

        val maxHeight = call.argument<Int>("maxHeight") ?: 720
        val crf = call.argument<Int>("crf") ?: 23

        try {
            val inputFile = File(input)
            if (!inputFile.exists()) {
                result.error("NO_INPUT", "Input file not found at $input", null)
                return
            }

            val outputFile = File(output)
            outputFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    result.error("NO_OUTPUT_DIR", "Unable to create output directory", null)
                    return
                }
            }

            // Read original dimensions via Android, not FFmpeg.
            val dims = readVideoDimensions(context, inputFile)
            if (dims == null || dims.width <= 0 || dims.height <= 0) {
                // Fallback: just copy via FFmpeg without filters.
                Log.w(TAG, "Unable to read dimensions. Falling back to simple re-encode.")
                runFfmpeg(
                    inputPath = inputFile.absolutePath,
                    outputPath = outputFile.absolutePath,
                    width = null,
                    height = null,
                    maxHeight = maxHeight,
                    crf = crf,
                    result = result
                )
                return
            }

            val (displayW, displayH) = dims
            Log.i(TAG, "Input display size = ${displayW}x$displayH")

            // Downscale only if taller than maxHeight; otherwise keep original size.
            val (targetW, targetH) = computeTargetSize(displayW, displayH, maxHeight)
            Log.i(TAG, "Target (before even clamp) = ${targetW}x$targetH")

            // Make sure both are even; libx264 only needs mod2, not mod16.
            val evenW = targetW and 1.inv()
            val evenH = targetH and 1.inv()

            Log.i(TAG, "Final encode size = ${evenW}x$evenH (even-clamped)")

            runFfmpeg(
                inputPath = inputFile.absolutePath,
                outputPath = outputFile.absolutePath,
                width = evenW,
                height = evenH,
                maxHeight = maxHeight,
                crf = crf,
                result = result
            )

        } catch (t: Throwable) {
            Log.e(TAG, "SETUP_FAIL", t)
            result.error("SETUP_FAIL", t.message, null)
        }
    }

    // ------------------------------------------------------------------------
    // Helper: read dimensions using MediaMetadataRetriever
    // ------------------------------------------------------------------------

    private data class Size(val width: Int, val height: Int)

    private fun readVideoDimensions(context: Context, file: File): Size? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.fromFile(file))

            val rawW = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawH = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            // Display orientation, not coded orientation.
            val displayW = if (rotation == 90 || rotation == 270) rawH else rawW
            val displayH = if (rotation == 90 || rotation == 270) rawW else rawH

            if (displayW <= 0 || displayH <= 0) null
            else Size(displayW, displayH)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read video metadata", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------------
    // Helper: compute target size with aspect ratio preserved
    // ------------------------------------------------------------------------

    private fun computeTargetSize(
        width: Int,
        height: Int,
        maxHeight: Int
    ): Size {
        if (height <= maxHeight) {
            // No downscale; keep original (then clamp to even later).
            return Size(width, height)
        }

        val scale = maxHeight.toFloat() / height.toFloat()
        val scaledW = (width * scale).toInt().coerceAtLeast(2)
        val scaledH = maxHeight.coerceAtLeast(2)
        return Size(scaledW, scaledH)
    }

    // ------------------------------------------------------------------------
    // Helper: run FFmpegKit
    // ------------------------------------------------------------------------

    private fun runFfmpeg(
        inputPath: String,
        outputPath: String,
        width: Int?,
        height: Int?,
        maxHeight: Int,
        crf: Int,
        result: MethodChannel.Result
    ) {
        // Build argument list.
        val args = mutableListOf<String>()

        // Overwrite output.
        args += "-y"
        // Lower noise; we log failures explicitly.
        args += "-hide_banner"
        args += "-loglevel"; args += "error"

        // Input.
        args += "-i"; args += inputPath

        // Video filter: only if we know width/height.
        if (width != null && height != null && width > 0 && height > 0) {
            args += "-vf"
            // No cropping, just scale + pixel format.
            args += "scale=${width}:${height},format=yuv420p"
        } else {
            // We still enforce yuv420p for max compatibility.
            args += "-vf"
            args += "format=yuv420p"
        }

        // Video codec & quality.
        args += "-c:v"; args += "libx264"
        args += "-preset"; args += "veryfast"
        args += "-crf"; args += crf.coerceIn(18, 30).toString()

        // Limit decoder load a bit by capping fps if someone shot 240fps nonsense.
        args += "-r"; args += "30"

        // Audio: re-encode to AAC at safe bitrate.
        args += "-c:a"; args += "aac"
        args += "-b:a"; args += "128k"

        // Web-friendly MP4 layout, faster start.
        args += "-movflags"; args += "+faststart"

        // Limit encoder threads a bit to avoid hammering the device.
        args += "-threads"; args += "2"

        // Output.
        args += outputPath

        Log.i(TAG, "FFmpeg command args = ${args.joinToString(" ")}")

        // Run async; ffmpeg-kit handles the native threading.
        FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { session: FFmpegSession ->
                val rc = session.returnCode

                if (ReturnCode.isSuccess(rc)) {
                    Log.i(TAG, "FFmpeg success, rc=${rc.value}")
                    postResultSuccess(result, outputPath)
                } else if (ReturnCode.isCancel(rc)) {
                    Log.w(TAG, "FFmpeg cancelled, rc=${rc.value}")
                    postResultError(result, "CANCELLED", "FFmpeg execution was cancelled")
                } else {
                    val failStack = session.failStackTrace ?: "Unknown error"
                    Log.e(
                        TAG,
                        "FFmpeg failed, rc=${rc?.value}, state=${session.state}, stack=$failStack"
                    )
                    postResultError(result, "FFMPEG_FAIL", failStack)
                }
            },
            { log ->
                // If you want verbose logging, change loglevel to info/debug above.
                Log.d(TAG, "ffmpeg: ${log.message}")
            },
            { statistics ->
                // Hook this if at some point you want progress reporting on Dart side.
                Log.v(TAG, "ffmpeg stats: time=${statistics.time}, size=${statistics.size}")
            }
        )
    }

    private fun postResultSuccess(result: MethodChannel.Result, output: String) {
        mainHandler.post {
            try {
                result.success(output)
            } catch (e: Exception) {
                Log.e(TAG, "Error returning success to Flutter", e)
            }
        }
    }

    private fun postResultError(
        result: MethodChannel.Result,
        code: String,
        message: String
    ) {
        mainHandler.post {
            try {
                result.error(code, message, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error returning error to Flutter", e)
            }
        }
    }

    companion object {
        private const val TAG = "VideoTranscoderPlugin"
    }
}
