import 'package:flutter/services.dart';

class VideoTranscoder {
  static const _channel = MethodChannel('video_transcoder');

  /// Natural downscale:
  /// - never upscales
  /// - preserves aspect ratio
  /// - aligns to 16px to avoid chroma smear
  /// - H.264 + AAC
  ///
  /// [maxHeight] – maximum output height (e.g. 720).
  /// [bitrate]   – target video bitrate in bits/sec (e.g. 1.5 Mbps).
  static Future<String?> transcodeToSafeH264(
    String input,
    String output, {
    int maxHeight = 720,
    int bitrate = 1_500_000,
  }) async {
    return await _channel.invokeMethod('transcodeToSafeH264', {
      'input': input,
      'output': output,
      'maxHeight': maxHeight,
      'bitrate': bitrate,
    });
  }
}

