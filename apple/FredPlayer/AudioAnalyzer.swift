import Accelerate
import Foundation

enum AudioAnalyzer {
    static func waveform(_ samples: [Float], points: Int) -> [Float] {
        guard !samples.isEmpty, points > 0 else { return [] }
        let stride = max(1, samples.count / points)
        return (0..<points).map { point in
            let start = min(point * stride, samples.count - 1)
            let end = min(start + stride, samples.count)
            return samples[start..<end].reduce(0) { $0 + $1 } / Float(max(1, end - start))
        }
    }

    static func spectrum(
        _ samples: [Float],
        fftSize: Int,
        bars: Int,
        logarithmic: Bool
    ) -> [Float] {
        guard fftSize > 1, bars > 0, samples.count >= fftSize else {
            return Array(repeating: 0, count: bars)
        }
        let half = fftSize / 2
        let log2Size = vDSP_Length(log2(Float(fftSize)))
        guard let setup = vDSP_create_fftsetup(log2Size, FFTRadix(kFFTRadix2)) else {
            return Array(repeating: 0, count: bars)
        }
        defer { vDSP_destroy_fftsetup(setup) }

        var window = Array(repeating: Float(0), count: fftSize)
        vDSP_hann_window(&window, vDSP_Length(fftSize), Int32(vDSP_HANN_NORM))
        var real = Array(samples.prefix(fftSize))
        vDSP_vmul(real, 1, window, 1, &real, 1, vDSP_Length(fftSize))
        var imaginary = Array(repeating: Float(0), count: fftSize)
        var magnitudes = Array(repeating: Float(0), count: half)

        real.withUnsafeMutableBufferPointer { realBuffer in
            imaginary.withUnsafeMutableBufferPointer { imaginaryBuffer in
                var split = DSPSplitComplex(
                    realp: realBuffer.baseAddress!,
                    imagp: imaginaryBuffer.baseAddress!
                )
                vDSP_fft_zip(setup, &split, 1, log2Size, FFTDirection(FFT_FORWARD))
                vDSP_zvabs(&split, 1, &magnitudes, 1, vDSP_Length(half))
            }
        }
        var scale = Float(1) / Float(fftSize)
        vDSP_vsmul(magnitudes, 1, &scale, &magnitudes, 1, vDSP_Length(half))

        return (0..<bars).map { bar in
            let lower: Int
            let upper: Int
            if logarithmic {
                lower = Int(pow(Float(half), Float(bar) / Float(bars)))
                upper = max(lower + 1, Int(pow(Float(half), Float(bar + 1) / Float(bars))))
            } else {
                lower = bar * half / bars
                upper = max(lower + 1, (bar + 1) * half / bars)
            }
            let range = max(1, lower)..<min(half, max(lower + 1, upper))
            let magnitude = magnitudes[range].max() ?? 0
            return min(1, max(0, (20 * log10(max(magnitude, 0.000_001)) + 72) / 72))
        }
    }

    static func smoothed(_ values: [Float], previous: [Float], amount: Float) -> [Float] {
        guard previous.count == values.count else { return values }
        return zip(values, previous).map { current, old in
            current * (1 - amount) + old * amount
        }
    }
}
