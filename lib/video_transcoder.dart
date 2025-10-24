import 'package:flutter/services.dart';

class VideoTranscoder {
  static const _channel = MethodChannel('video_transcoder');

  static Future<String?> transcodeToSafeH264(
      String input, String output) async {
    return await _channel.invokeMethod('transcodeToSafeH264', {
      'input': input,
      'output': output,
    });
  }
}
