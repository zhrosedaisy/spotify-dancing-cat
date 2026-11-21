// AudioTap: captures Spotify's audio (or the whole system's, if Spotify isn't running) with
// ScreenCaptureKit and writes it to stdout as raw 44.1 kHz mono 32-bit float (little-endian) PCM.
// The Java side reads this audio and tracks the beat. Usage: audiotap [bundle-id]
//
// On first run macOS asks for "Screen & System Audio Recording" permission.

import Foundation
import ScreenCaptureKit
import CoreMedia

func log(_ message: String) {
    FileHandle.standardError.write("[audiotap] \(message)\n".data(using: .utf8)!)
}

final class AudioOutput: NSObject, SCStreamOutput, SCStreamDelegate {
    private let out = FileHandle.standardOutput

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, sampleBuffer.isValid else { return }
        do {
            try sampleBuffer.withAudioBufferList { list, _ in
                // We asked for mono; if more channels arrive anyway, only write the first one
                guard let first = list.first, let data = first.mData else { return }
                out.write(Data(bytes: data, count: Int(first.mDataByteSize)))
            }
        } catch {
            log("buffer-error \(error.localizedDescription)")
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("stopped \(error.localizedDescription)")
        exit(2)
    }
}

@main
struct AudioTap {
    static func main() async {
        let bundleId = CommandLine.arguments.dropFirst().first
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            guard let display = content.displays.first else {
                log("no-display")
                exit(1)
            }
            let apps = content.applications.filter { $0.bundleIdentifier == bundleId }
            let filter: SCContentFilter
            if apps.isEmpty {
                log("source=system (\(bundleId ?? "-") not found)")
                filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
            } else {
                log("source=\(bundleId!)")
                filter = SCContentFilter(display: display, including: apps, exceptingWindows: [])
            }

            let config = SCStreamConfiguration()
            config.capturesAudio = true
            config.sampleRate = 44100
            config.channelCount = 1
            config.excludesCurrentProcessAudio = true
            // We don't need video: smallest size, 1 frame per second
            config.width = 2
            config.height = 2
            config.minimumFrameInterval = CMTime(value: 1, timescale: 1)

            let output = AudioOutput()
            let stream = SCStream(filter: filter, configuration: config, delegate: output)
            try stream.addStreamOutput(output, type: .audio, sampleHandlerQueue: DispatchQueue(label: "audiotap.audio"))
            try await stream.startCapture()
            log("ready")

            // When the Java app exits, stdin closes -> exit too
            DispatchQueue.global().async {
                while readLine() != nil {}
                exit(0)
            }
            while true {
                try await Task.sleep(nanoseconds: 3_600_000_000_000)
            }
        } catch {
            let ns = error as NSError
            if ns.domain == SCStreamErrorDomain && ns.code == SCStreamError.userDeclined.rawValue {
                log("permission-denied")
                exit(3)
            }
            log("error \(ns.domain) \(ns.code) \(error.localizedDescription)")
            exit(1)
        }
    }
}
