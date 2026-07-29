import AVFoundation
import Foundation
import MediaToolbox

struct RealtimeCompressionConfiguration {
    let outputLevel: Float
    let strength: Float
    let thresholdDB: Float
    let attackTime: Double
    let releaseTime: Double
    let ceilingDB: Float
}

struct LiveVisualizationConfiguration {
    let fps: Double
    let waveformWindow: Double
    let fftSize: Int
    let bars: Int
    let smoothing: Float
    let logarithmic: Bool
}

enum StreamingAudioTapError: LocalizedError {
    case couldNotCreate(OSStatus)
    case missingTap

    var errorDescription: String? {
        switch self {
        case .couldNotCreate(let status):
            "Could not start real-time audio processing (\(status))."
        case .missingTap:
            "Could not create the real-time audio processor."
        }
    }
}

final class StreamingAudioTap {
    let tap: MTAudioProcessingTap

    private let processor: StreamingAudioTapProcessor

    init(
        initialGain: Float,
        compression: RealtimeCompressionConfiguration,
        visualization: LiveVisualizationConfiguration?,
        onVisualization: @escaping ([Float], [Float]) -> Void
    ) throws {
        let processor = StreamingAudioTapProcessor(
            initialGain: initialGain,
            compression: compression,
            visualization: visualization,
            onVisualization: onVisualization
        )
        self.processor = processor

        let retainedProcessor = Unmanaged.passRetained(processor)
        let clientInfo = retainedProcessor.toOpaque()
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: clientInfo,
            init: { _, clientInfo, storageOut in
                storageOut.pointee = clientInfo
            },
            finalize: { tap in
                let storage = MTAudioProcessingTapGetStorage(tap)
                Unmanaged<StreamingAudioTapProcessor>.fromOpaque(storage).release()
            },
            prepare: { tap, _, processingFormat in
                let processor = StreamingAudioTap.processor(for: tap)
                processor.prepare(format: processingFormat.pointee)
            },
            unprepare: { tap in
                StreamingAudioTap.processor(for: tap).unprepare()
            },
            process: { tap, frameCount, _, bufferList, frameCountOut, flagsOut in
                let status = MTAudioProcessingTapGetSourceAudio(
                    tap,
                    frameCount,
                    bufferList,
                    flagsOut,
                    nil,
                    frameCountOut
                )
                guard status == noErr else {
                    frameCountOut.pointee = 0
                    return
                }
                StreamingAudioTap.processor(for: tap).process(
                    bufferList: bufferList,
                    frameCount: frameCountOut.pointee
                )
            }
        )

        var createdTap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PreEffects,
            &createdTap
        )
        guard status == noErr else {
            retainedProcessor.release()
            throw StreamingAudioTapError.couldNotCreate(status)
        }
        guard let createdTap else {
            retainedProcessor.release()
            throw StreamingAudioTapError.missingTap
        }
        tap = createdTap
    }

    func update(
        compression: RealtimeCompressionConfiguration,
        visualization: LiveVisualizationConfiguration?
    ) {
        processor.update(compression: compression, visualization: visualization)
    }

    private static func processor(for tap: MTAudioProcessingTap) -> StreamingAudioTapProcessor {
        Unmanaged<StreamingAudioTapProcessor>
            .fromOpaque(MTAudioProcessingTapGetStorage(tap))
            .takeUnretainedValue()
    }
}

private final class StreamingAudioTapProcessor {
    private let settingsLock = NSLock()
    private var compression: RealtimeCompressionConfiguration
    private var visualization: LiveVisualizationConfiguration?
    private let onVisualization: ([Float], [Float]) -> Void

    private var format = AudioStreamBasicDescription()
    private var currentGain: Float
    private var recentMonoSamples: [Float] = []
    private var lastSpectrum: [Float] = []
    private var lastVisualizationTime: TimeInterval = 0

    init(
        initialGain: Float,
        compression: RealtimeCompressionConfiguration,
        visualization: LiveVisualizationConfiguration?,
        onVisualization: @escaping ([Float], [Float]) -> Void
    ) {
        currentGain = initialGain
        self.compression = compression
        self.visualization = visualization
        self.onVisualization = onVisualization
    }

    func update(
        compression: RealtimeCompressionConfiguration,
        visualization: LiveVisualizationConfiguration?
    ) {
        settingsLock.lock()
        self.compression = compression
        self.visualization = visualization
        settingsLock.unlock()
    }

    func prepare(format: AudioStreamBasicDescription) {
        self.format = format
        recentMonoSamples.removeAll(keepingCapacity: true)
        lastSpectrum.removeAll(keepingCapacity: true)
        lastVisualizationTime = 0
    }

    func unprepare() {
        recentMonoSamples.removeAll(keepingCapacity: false)
        lastSpectrum.removeAll(keepingCapacity: false)
    }

    func process(bufferList: UnsafeMutablePointer<AudioBufferList>, frameCount: CMItemCount) {
        guard frameCount > 0,
              format.mFormatID == kAudioFormatLinearPCM,
              format.mBitsPerChannel == 32,
              format.mFormatFlags & kAudioFormatFlagIsFloat != 0 else { return }

        let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
        var sumSquares: Double = 0
        var peak: Float = 0
        var sampleCount = 0
        for buffer in buffers {
            guard let data = buffer.mData else { continue }
            let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            let samples = data.assumingMemoryBound(to: Float.self)
            for index in 0..<count {
                let sample = samples[index]
                sumSquares += Double(sample * sample)
                peak = max(peak, abs(sample))
            }
            sampleCount += count
        }
        guard sampleCount > 0 else { return }

        settingsLock.lock()
        let compression = self.compression
        let visualization = self.visualization
        settingsLock.unlock()

        let rms = Float(sqrt(sumSquares / Double(sampleCount)))
        let rmsDB = 20 * log10(max(rms, 0.000_001))
        var desiredGainDB: Float = 0
        if rmsDB > compression.thresholdDB {
            desiredGainDB = -(rmsDB - compression.thresholdDB) * compression.strength
        }
        if peak > 0 {
            let ceilingLinear = pow(10, compression.ceilingDB / 20)
            desiredGainDB = min(
                desiredGainDB,
                20 * log10(max(ceilingLinear / peak, 0.000_001))
            )
        }

        let desiredGain = pow(10, desiredGainDB / 20)
        let responseTime = desiredGain < currentGain
            ? compression.attackTime
            : compression.releaseTime
        let bufferDuration = Double(frameCount) / max(1, format.mSampleRate)
        let smoothing = Float(exp(-bufferDuration / max(0.001, responseTime)))
        currentGain = desiredGain * (1 - smoothing) + currentGain * smoothing
        let appliedGain = min(1, max(0, compression.outputLevel * currentGain))

        for buffer in buffers {
            guard let data = buffer.mData else { continue }
            let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            let samples = data.assumingMemoryBound(to: Float.self)
            for index in 0..<count {
                samples[index] = min(1, max(-1, samples[index] * appliedGain))
            }
        }

        guard let visualization else { return }
        appendMonoSamples(from: buffers, frameCount: Int(frameCount))
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastVisualizationTime >= 1 / max(1, visualization.fps) else { return }
        lastVisualizationTime = now

        let waveformSampleCount = max(1, Int(format.mSampleRate * visualization.waveformWindow))
        let retainedSampleCount = max(waveformSampleCount, visualization.fftSize)
        if recentMonoSamples.count > retainedSampleCount {
            recentMonoSamples.removeFirst(recentMonoSamples.count - retainedSampleCount)
        }
        guard recentMonoSamples.count >= visualization.fftSize else { return }

        let waveformSamples = Array(recentMonoSamples.suffix(waveformSampleCount))
        let fftSamples = Array(recentMonoSamples.suffix(visualization.fftSize))
        let waveform = AudioAnalyzer.waveform(waveformSamples, points: 128)
        let spectrum = AudioAnalyzer.smoothed(
            AudioAnalyzer.spectrum(
                fftSamples,
                fftSize: visualization.fftSize,
                bars: visualization.bars,
                logarithmic: visualization.logarithmic
            ),
            previous: lastSpectrum,
            amount: visualization.smoothing
        )
        lastSpectrum = spectrum
        onVisualization(waveform, spectrum)
    }

    private func appendMonoSamples(
        from buffers: UnsafeMutableAudioBufferListPointer,
        frameCount: Int
    ) {
        let channelCount = max(1, Int(format.mChannelsPerFrame))
        let isNonInterleaved = format.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        var mono = Array(repeating: Float(0), count: frameCount)

        if isNonInterleaved {
            var contributingBuffers = 0
            for buffer in buffers {
                guard let data = buffer.mData else { continue }
                let samples = data.assumingMemoryBound(to: Float.self)
                let available = min(frameCount, Int(buffer.mDataByteSize) / MemoryLayout<Float>.size)
                for frame in 0..<available { mono[frame] += samples[frame] }
                contributingBuffers += 1
            }
            if contributingBuffers > 1 {
                let divisor = Float(contributingBuffers)
                for frame in mono.indices { mono[frame] /= divisor }
            }
        } else if let buffer = buffers.first, let data = buffer.mData {
            let samples = data.assumingMemoryBound(to: Float.self)
            let availableSamples = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            let availableFrames = min(frameCount, availableSamples / channelCount)
            for frame in 0..<availableFrames {
                var sum: Float = 0
                for channel in 0..<channelCount {
                    sum += samples[frame * channelCount + channel]
                }
                mono[frame] = sum / Float(channelCount)
            }
        }
        recentMonoSamples.append(contentsOf: mono)
    }
}
