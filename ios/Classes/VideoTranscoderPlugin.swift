import Flutter
import AVFoundation

public class VideoTranscoderPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "video_transcoder", binaryMessenger: registrar.messenger())
    let instance = VideoTranscoderPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    if call.method == "transcodeToSafeH264",
       let args = call.arguments as? [String: String],
       let input = args["input"],
       let output = args["output"] {
      let asset = AVAsset(url: URL(fileURLWithPath: input))
      if let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) {
        export.outputURL = URL(fileURLWithPath: output)
        export.outputFileType = .mp4
        export.exportAsynchronously {
          if export.status == .completed {
            result(output)
          } else {
            result(FlutterError(code: "EXPORT_ERROR", message: export.error?.localizedDescription, details: nil))
          }
        }
      } else {
        result(FlutterError(code: "SESSION_FAIL", message: "Cannot create export session", details: nil))
      }
    } else {
      result(FlutterMethodNotImplemented)
    }
  }
}
