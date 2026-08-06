import SwiftUI

struct PlayerPanel: View {
    @EnvironmentObject private var player: PlayerController
    @State private var settingsPresented = false
    @State private var removeConfirmationPresented = false
    @State private var lyricsPresented = false

    var body: some View {
        VStack(spacing: 10) {
            if let track = player.currentTrack {
                VStack(spacing: 2) {
                    Text(track.displayTitle)
                        .font(.headline)
                        .lineLimit(1)
                    if let subtitle = track.displaySubtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                VisualizerView(waveform: player.waveform, spectrum: player.spectrum)
                    .frame(height: 110)

                ProgressView(value: player.currentTime, total: max(1, player.duration))
                HStack {
                    Text(format(player.currentTime))
                    Spacer()
                    Text(format(player.duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }

            HStack(spacing: 22) {
                Button(action: player.toggleShuffle) {
                    Image(systemName: "shuffle")
                        .foregroundStyle(player.shuffleEnabled ? Color.accentColor : Color.secondary)
                }
                Button(action: player.previous) { Image(systemName: "backward.fill") }
                Button(action: player.togglePlayback) {
                    Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 38))
                }
                Button(action: player.next) { Image(systemName: "forward.fill") }
                Button(action: player.stop) { Image(systemName: "stop.fill") }
                Button(action: player.cycleRepeatMode) {
                    Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                        .foregroundStyle(player.repeatMode == .off ? Color.secondary : Color.accentColor)
                }
                Button {
                    removeConfirmationPresented = true
                } label: {
                    Image(systemName: "trash")
                }
                .disabled(player.currentTrack == nil)
                Button {
                    lyricsPresented = true
                } label: {
                    Image(systemName: "quote.bubble")
                }
                Button {
                    settingsPresented = true
                } label: {
                    Image(systemName: "gearshape")
                }
            }
            .font(.title3)
            .buttonStyle(.plain)
            .disabled(player.playlist.tracks.isEmpty)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
        .sheet(isPresented: $settingsPresented) {
            PlayerSettingsView()
                .environmentObject(player)
        }
        .confirmationDialog(
            "Remove the current track from this playlist?",
            isPresented: $removeConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) { player.removeCurrentTrack() }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $lyricsPresented) {
            LyricsView()
                .environmentObject(player)
        }
    }

    private func format(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite else { return "0:00" }
        return String(format: "%d:%02d", Int(seconds) / 60, Int(seconds) % 60)
    }
}

private struct VisualizerView: View {
    let waveform: [Float]
    let spectrum: [Float]

    var body: some View {
        VStack(spacing: 4) {
            Canvas { context, size in
                guard waveform.count > 1 else { return }
                var path = Path()
                for (index, value) in waveform.enumerated() {
                    let x = size.width * CGFloat(index) / CGFloat(waveform.count - 1)
                    let y = size.height * (0.5 - CGFloat(value) * 0.45)
                    index == 0 ? path.move(to: CGPoint(x: x, y: y)) : path.addLine(to: CGPoint(x: x, y: y))
                }
                context.stroke(path, with: .color(.cyan), lineWidth: 1.5)
            }
            .background(Color.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 6))

            GeometryReader { geometry in
                HStack(alignment: .bottom, spacing: 1) {
                    ForEach(Array(spectrum.enumerated()), id: \.offset) { index, value in
                        RoundedRectangle(cornerRadius: 1)
                            .fill(
                                Color(
                                    hue: 0.72 - 0.72 * Double(index) / Double(max(1, spectrum.count - 1)),
                                    saturation: 0.9,
                                    brightness: 0.95
                                )
                            )
                            .frame(height: max(1, geometry.size.height * CGFloat(value)))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
            .background(Color.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 6))
        }
    }
}

private struct PlayerSettingsView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearance") private var appearance = AppAppearance.system.rawValue

    var body: some View {
        NavigationStack {
            Form {
                Section("Audio") {
                    control("Output Level", value: $player.outputLevel, range: 0...1, format: "%.0f%%", scale: 100)
                    Text("Real-time PCM gain riding reduces loud passages using the threshold, strength, attack, release, and ceiling settings.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    control("Leveling Strength", value: $player.levelingStrength, range: 0...1, format: "%.0f%%", scale: 100)
                    control("Compressor Threshold", value: $player.compressorThreshold, range: -40...0, format: "%.1f dB")
                    doubleControl("Attack Time", value: $player.attackTime, range: 0.001...0.2, format: "%.3f s")
                    doubleControl("Release Time", value: $player.releaseTime, range: 0.05...3, format: "%.2f s")
                    control("Output Ceiling", value: $player.outputCeiling, range: -12...0, format: "%.1f dB")
                    doubleControl("Startup Scan", value: $player.startupScanSeconds, range: 0...30, format: "%.0f s")
                }

                Section("Appearance") {
                    Picker("Color Scheme", selection: $appearance) {
                        ForEach(AppAppearance.allCases) { option in
                            Text(option.title).tag(option.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Visualization") {
                    doubleControl("Update FPS", value: $player.visualizationFPS, range: 5...60, format: "%.0f")
                    doubleControl("Waveform Window", value: $player.waveformWindow, range: 0.02...0.09, format: "%.2f s")
                    Picker("FFT Size", selection: $player.fftSize) {
                        Text("512").tag(512)
                        Text("1024").tag(1024)
                        Text("2048").tag(2048)
                    }
                    control("FFT Bars", value: Binding(
                        get: { Float(player.fftBarCount) },
                        set: { player.fftBarCount = Int($0) }
                    ), range: 16...64, format: "%.0f")
                    control("FFT Smoothing", value: $player.fftSmoothing, range: 0...0.95, format: "%.0f%%", scale: 100)
                    Toggle("Logarithmic FFT Scale", isOn: $player.logarithmicFFT)
                }

                Section("Cache") {
                    LabeledContent("Loudness") {
                        Text("\(player.loudnessCacheCount) tracks")
                    }
                    LabeledContent("Visual") {
                        Text("\(player.visualCacheCount) tracks/settings")
                    }
                    LabeledContent("Disk Usage") {
                        Text(ByteCountFormatter.string(
                            fromByteCount: player.cacheBytes,
                            countStyle: .file
                        ))
                    }
                    LabeledContent("Pruning") {
                        Text("Loudness 5000→4000\nVisual 5000→4500")
                            .multilineTextAlignment(.trailing)
                    }
                    if let progress = player.cachePreparationProgress {
                        ProgressView(
                            player.cachePreparationLabel,
                            value: progress
                        )
                    } else {
                        Button("Prepare Playlist Caches") {
                            player.prepareCaches()
                        }
                    }
                }
            }
            .navigationTitle("Player Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func control(
        _ title: String,
        value: Binding<Float>,
        range: ClosedRange<Float>,
        format: String,
        scale: Float = 1
    ) -> some View {
        VStack(alignment: .leading) {
            HStack {
                Text(title)
                Spacer()
                Text(String(format: format, value.wrappedValue * scale))
                    .foregroundStyle(.secondary)
            }
            Slider(value: value, in: range)
        }
    }

    private func doubleControl(
        _ title: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        format: String
    ) -> some View {
        VStack(alignment: .leading) {
            HStack {
                Text(title)
                Spacer()
                Text(String(format: format, value.wrappedValue))
                    .foregroundStyle(.secondary)
            }
            Slider(value: value, in: range)
        }
    }
}
