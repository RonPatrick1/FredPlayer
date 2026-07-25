import AVFoundation
import CryptoKit
import Foundation

struct LoudnessCacheEntry: Codable {
    let meanDB: Float
    let peak: Float
    let analyzedSeconds: Double
    let created: Date
}

struct VisualCacheFrame: Codable {
    let waveform: [Float]
    let spectrum: [Float]
}

struct VisualCacheEntry: Codable {
    let frameInterval: Double
    let frames: [VisualCacheFrame]
    let created: Date
}

struct VisualCacheSettings {
    let fps: Double
    let waveformWindow: Double
    let fftSize: Int
    let bars: Int
    let logarithmic: Bool

    var identity: String {
        "\(fps)-\(waveformWindow)-\(fftSize)-\(bars)-\(logarithmic)"
    }
}

enum MediaCache {
    private static let manager = FileManager.default

    static func fileIdentity(for url: URL) -> String {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        let raw = [
            url.standardizedFileURL.path,
            String(values?.fileSize ?? 0),
            String(values?.contentModificationDate?.timeIntervalSince1970 ?? 0)
        ].joined(separator: "|")
        return SHA256.hash(data: Data(raw.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    static func loudness(for url: URL) -> LoudnessCacheEntry? {
        read(LoudnessCacheEntry.self, from: loudnessURL(for: url))
    }

    static func visual(for url: URL, settings: VisualCacheSettings) -> VisualCacheEntry? {
        read(VisualCacheEntry.self, from: visualURL(for: url, settings: settings))
    }

    static func analyzeLoudness(url: URL, seconds: Double?) throws -> LoudnessCacheEntry {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let limit = seconds.map { AVAudioFramePosition($0 * format.sampleRate) } ?? file.length
        let framesToRead = min(file.length, limit)
        let chunkSize: AVAudioFrameCount = 32_768
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkSize) else {
            throw CocoaError(.fileReadUnknown)
        }

        var remaining = framesToRead
        var sumSquares = Double(0)
        var peak = Float(0)
        var sampleCount = 0
        while remaining > 0 {
            buffer.frameLength = 0
            try file.read(into: buffer, frameCount: AVAudioFrameCount(min(Int64(chunkSize), remaining)))
            guard buffer.frameLength > 0, let channels = buffer.floatChannelData else { break }
            for channelIndex in 0..<Int(format.channelCount) {
                let channel = channels[channelIndex]
                for index in 0..<Int(buffer.frameLength) {
                    let sample = channel[index]
                    sumSquares += Double(sample * sample)
                    peak = max(peak, abs(sample))
                }
                sampleCount += Int(buffer.frameLength)
            }
            remaining -= AVAudioFramePosition(buffer.frameLength)
        }

        let rms = sqrt(sumSquares / Double(max(1, sampleCount)))
        return LoudnessCacheEntry(
            meanDB: Float(20 * log10(max(rms, 0.000_001))),
            peak: peak,
            analyzedSeconds: Double(framesToRead) / format.sampleRate,
            created: Date()
        )
    }

    static func analyzeVisuals(
        url: URL,
        settings: VisualCacheSettings
    ) throws -> VisualCacheEntry {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let requestedStep = Int(format.sampleRate / max(1, settings.fps))
        let frameSize = max(settings.fftSize, requestedStep)
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(frameSize)
        ) else {
            throw CocoaError(.fileReadUnknown)
        }

        var frames: [VisualCacheFrame] = []
        while file.framePosition < file.length {
            buffer.frameLength = 0
            try file.read(into: buffer, frameCount: AVAudioFrameCount(frameSize))
            guard buffer.frameLength > 0, let channel = buffer.floatChannelData?[0] else { break }
            let samples = Array(
                UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength))
            )
            let waveCount = min(
                samples.count,
                max(1, Int(format.sampleRate * settings.waveformWindow))
            )
            frames.append(
                VisualCacheFrame(
                    waveform: AudioAnalyzer.waveform(
                        Array(samples.suffix(waveCount)),
                        points: 128
                    ),
                    spectrum: AudioAnalyzer.spectrum(
                        samples,
                        fftSize: min(settings.fftSize, samples.count),
                        bars: settings.bars,
                        logarithmic: settings.logarithmic
                    )
                )
            )
        }
        return VisualCacheEntry(
            frameInterval: Double(frameSize) / format.sampleRate,
            frames: frames,
            created: Date()
        )
    }

    static func store(_ entry: LoudnessCacheEntry, for url: URL) throws {
        try write(entry, to: loudnessURL(for: url))
        prune(directory: loudnessDirectory, above: 5_000, keeping: 4_000)
    }

    static func store(
        _ entry: VisualCacheEntry,
        for url: URL,
        settings: VisualCacheSettings
    ) throws {
        try write(entry, to: visualURL(for: url, settings: settings))
        prune(directory: visualDirectory, above: 5_000, keeping: 4_500)
    }

    static func status() -> (loudness: Int, visual: Int, bytes: Int64) {
        let loudnessFiles = files(in: loudnessDirectory)
        let visualFiles = files(in: visualDirectory)
        let bytes = (loudnessFiles + visualFiles).reduce(Int64(0)) {
            $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return (loudnessFiles.count, visualFiles.count, bytes)
    }

    private static var rootDirectory: URL {
        manager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FredPlayerMediaCache", isDirectory: true)
    }
    private static var loudnessDirectory: URL {
        rootDirectory.appendingPathComponent("Loudness", isDirectory: true)
    }
    private static var visualDirectory: URL {
        rootDirectory.appendingPathComponent("Visual", isDirectory: true)
    }

    private static func loudnessURL(for url: URL) -> URL {
        loudnessDirectory.appendingPathComponent(fileIdentity(for: url) + ".cache")
    }

    private static func visualURL(for url: URL, settings: VisualCacheSettings) -> URL {
        let raw = fileIdentity(for: url) + "|" + settings.identity
        let key = SHA256.hash(data: Data(raw.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return visualDirectory.appendingPathComponent(key + ".cache")
    }

    private static func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? PropertyListDecoder().decode(type, from: data)
    }

    private static func write<T: Encodable>(_ value: T, to url: URL) throws {
        try manager.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let encoder = PropertyListEncoder()
        encoder.outputFormat = .binary
        try encoder.encode(value).write(to: url, options: .atomic)
    }

    private static func files(in directory: URL) -> [URL] {
        (try? manager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )) ?? []
    }

    private static func prune(directory: URL, above: Int, keeping: Int) {
        let entries = files(in: directory)
        guard entries.count > above else { return }
        let newest = entries.sorted {
            let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
                ?? .distantPast
            let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
                ?? .distantPast
            return left > right
        }
        for url in newest.dropFirst(keeping) {
            try? manager.removeItem(at: url)
        }
    }
}
