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
    private static let serverVisualMagic: UInt32 = 0x46415631 // FAV1
    private static let serverVisualVersion: UInt32 = 1
    private static let serverVisualWaveformPoints = 128

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

    static func loudness(forServerPath path: String) -> LoudnessCacheEntry? {
        read(LoudnessCacheEntry.self, from: loudnessURL(forKey: remoteKey(path)))
    }

    /// Converts the shared `{rms, peak}` response from `/api/profile/*` into
    /// Apple's existing loudness model.
    static func serverLoudness(
        from data: Data,
        analyzedSeconds: Double = 10
    ) -> LoudnessCacheEntry? {
        guard
            let profile = try? JSONDecoder().decode(ServerLoudnessProfile.self, from: data),
            profile.rms.isFinite,
            profile.peak.isFinite,
            profile.rms >= 0,
            profile.peak >= 0
        else { return nil }
        return LoudnessCacheEntry(
            meanDB: Float(20 * log10(max(profile.rms, 0.000_001))),
            peak: Float(profile.peak),
            analyzedSeconds: max(0, analyzedSeconds),
            created: Date()
        )
    }

    static func storeServerLoudness(
        _ data: Data,
        for url: URL,
        analyzedSeconds: Double = 10
    ) throws -> Bool {
        guard let entry = serverLoudness(from: data, analyzedSeconds: analyzedSeconds) else {
            return false
        }
        try store(entry, for: url)
        return true
    }

    static func visual(for url: URL, settings: VisualCacheSettings) -> VisualCacheEntry? {
        read(VisualCacheEntry.self, from: visualURL(for: url, settings: settings))
    }

    static func visual(forServerPath path: String, settings: VisualCacheSettings) -> VisualCacheEntry? {
        read(VisualCacheEntry.self, from: visualURL(forKey: remoteKey(path), settings: settings))
    }

    /// Decodes the compact format returned by `/api/apple-visual/*`.
    /// Server frames are quantized on disk and expanded to the existing
    /// in-memory cache model here, keeping playback code format-agnostic.
    static func serverVisual(
        from data: Data,
        settings: VisualCacheSettings
    ) -> VisualCacheEntry? {
        var reader = ServerVisualReader(data: data)
        guard
            reader.readUInt32() == serverVisualMagic,
            reader.readUInt32() == serverVisualVersion,
            let fps = reader.readDouble(),
            let waveformMilliseconds = reader.readDouble(),
            let fftSize = reader.readUInt32(),
            let waveformPoints = reader.readUInt32(),
            let bars = reader.readUInt32(),
            let flags = reader.readUInt32(),
            let frameCount = reader.readUInt32(),
            let frameInterval = reader.readDouble(),
            let createdSeconds = reader.readDouble(),
            abs(fps - settings.fps) < 0.001,
            abs(waveformMilliseconds / 1_000 - settings.waveformWindow) < 0.000_001,
            fftSize == UInt32(settings.fftSize),
            waveformPoints == UInt32(serverVisualWaveformPoints),
            bars == UInt32(settings.bars),
            (flags & 1 != 0) == settings.logarithmic,
            frameCount > 0,
            frameInterval > 0,
            Int(frameCount) <= Int.max / max(1, serverVisualWaveformPoints + settings.bars),
            reader.remaining == Int(frameCount) * (serverVisualWaveformPoints + settings.bars)
        else { return nil }

        var frames: [VisualCacheFrame] = []
        frames.reserveCapacity(Int(frameCount))
        for _ in 0..<Int(frameCount) {
            var waveform: [Float] = []
            waveform.reserveCapacity(serverVisualWaveformPoints)
            for _ in 0..<serverVisualWaveformPoints {
                guard let byte = reader.readByte() else { return nil }
                waveform.append(Float(Int8(bitPattern: byte)) / 127)
            }
            var spectrum: [Float] = []
            spectrum.reserveCapacity(settings.bars)
            for _ in 0..<settings.bars {
                guard let byte = reader.readByte() else { return nil }
                spectrum.append(Float(byte) / 255)
            }
            frames.append(VisualCacheFrame(waveform: waveform, spectrum: spectrum))
        }
        return VisualCacheEntry(
            frameInterval: frameInterval,
            frames: frames,
            created: Date(timeIntervalSince1970: createdSeconds)
        )
    }

    static func storeServerVisual(
        _ data: Data,
        for url: URL,
        settings: VisualCacheSettings
    ) throws -> Bool {
        guard let entry = serverVisual(from: data, settings: settings) else { return false }
        try store(entry, for: url, settings: settings)
        return true
    }

    /// Encodes into the same compact `FAV1` format the server's own
    /// precompute writer produces, so a locally-analyzed track can be shared
    /// with this user's other Apple devices, not just re-read by this one.
    static func encodeServerVisual(_ entry: VisualCacheEntry, settings: VisualCacheSettings) -> Data {
        var writer = ServerVisualWriter()
        writer.writeUInt32(serverVisualMagic)
        writer.writeUInt32(serverVisualVersion)
        writer.writeDouble(settings.fps)
        writer.writeDouble(settings.waveformWindow * 1_000)
        writer.writeUInt32(UInt32(settings.fftSize))
        writer.writeUInt32(UInt32(serverVisualWaveformPoints))
        writer.writeUInt32(UInt32(settings.bars))
        writer.writeUInt32(settings.logarithmic ? 1 : 0)
        writer.writeUInt32(UInt32(entry.frames.count))
        writer.writeDouble(entry.frameInterval)
        writer.writeDouble(entry.created.timeIntervalSince1970)
        for frame in entry.frames {
            for point in 0..<serverVisualWaveformPoints {
                let sample = point < frame.waveform.count ? frame.waveform[point] : 0
                let clamped = max(-1, min(1, sample))
                let quantized = Int8(clamping: Int((clamped * 127).rounded()))
                writer.writeByte(UInt8(bitPattern: quantized))
            }
            for bar in 0..<settings.bars {
                let value = bar < frame.spectrum.count ? frame.spectrum[bar] : 0
                let clamped = max(0, min(1, value))
                writer.writeByte(UInt8(clamping: Int((clamped * 255).rounded())))
            }
        }
        return writer.data
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

    static func store(_ entry: LoudnessCacheEntry, forServerPath path: String) throws {
        try write(entry, to: loudnessURL(forKey: remoteKey(path)))
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

    static func store(
        _ entry: VisualCacheEntry,
        forServerPath path: String,
        settings: VisualCacheSettings
    ) throws {
        try write(entry, to: visualURL(forKey: remoteKey(path), settings: settings))
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
        loudnessURL(forKey: fileIdentity(for: url))
    }

    private static func loudnessURL(forKey key: String) -> URL {
        loudnessDirectory.appendingPathComponent(key + ".cache")
    }

    private static func visualURL(for url: URL, settings: VisualCacheSettings) -> URL {
        visualURL(forKey: fileIdentity(for: url), settings: settings)
    }

    private static func visualURL(forKey key: String, settings: VisualCacheSettings) -> URL {
        let raw = key + "|" + settings.identity
        let key = SHA256.hash(data: Data(raw.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return visualDirectory.appendingPathComponent(key + ".cache")
    }

    private static func remoteKey(_ path: String) -> String {
        SHA256.hash(data: Data("remote|\(path)".utf8))
            .map { String(format: "%02x", $0) }
            .joined()
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

private struct ServerVisualReader {
    let data: Data
    private(set) var offset = 0

    var remaining: Int { data.count - offset }

    mutating func readByte() -> UInt8? {
        guard offset < data.count else { return nil }
        defer { offset += 1 }
        return data[data.startIndex + offset]
    }

    mutating func readUInt32() -> UInt32? {
        guard
            let byte0 = readByte(),
            let byte1 = readByte(),
            let byte2 = readByte(),
            let byte3 = readByte()
        else { return nil }
        return UInt32(byte0) << 24
            | UInt32(byte1) << 16
            | UInt32(byte2) << 8
            | UInt32(byte3)
    }

    mutating func readDouble() -> Double? {
        guard let high = readUInt32(), let low = readUInt32() else { return nil }
        return Double(bitPattern: UInt64(high) << 32 | UInt64(low))
    }
}

private struct ServerVisualWriter {
    var data = Data()

    mutating func writeByte(_ byte: UInt8) {
        data.append(byte)
    }

    mutating func writeUInt32(_ value: UInt32) {
        writeByte(UInt8((value >> 24) & 0xff))
        writeByte(UInt8((value >> 16) & 0xff))
        writeByte(UInt8((value >> 8) & 0xff))
        writeByte(UInt8(value & 0xff))
    }

    mutating func writeDouble(_ value: Double) {
        let bits = value.bitPattern
        writeUInt32(UInt32((bits >> 32) & 0xffff_ffff))
        writeUInt32(UInt32(bits & 0xffff_ffff))
    }
}

private struct ServerLoudnessProfile: Decodable {
    let rms: Double
    let peak: Double
}
