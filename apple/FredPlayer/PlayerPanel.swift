import SwiftUI

struct PlayerPanel: View {
    @EnvironmentObject private var player: PlayerController
    @State private var removeConfirmationPresented = false
    @State private var seekValue: Double = 0
    @State private var isSeeking = false

    var body: some View {
        GeometryReader { geometry in
            let isWide = geometry.size.width > geometry.size.height
            VStack(spacing: isWide ? 8 : 12) {
                if isWide {
                    HStack(spacing: 14) {
                        artworkAndMetadata
                            .frame(width: geometry.size.width * 0.48)
                        VisualizerView(waveform: player.waveform, spectrum: player.spectrum)
                    }
                    .frame(maxHeight: .infinity)
                } else {
                    artworkAndMetadata
                        .aspectRatio(1, contentMode: .fit)
                    timeRow
                    VisualizerView(waveform: player.waveform, spectrum: player.spectrum)
                        .frame(minHeight: 120, maxHeight: .infinity)
                }
                transportControls
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .confirmationDialog(
            "Remove the current track from this playlist?",
            isPresented: $removeConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) { player.removeCurrentTrack() }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var artworkAndMetadata: some View {
        ZStack {
            artworkView
            Color.black.opacity(0.58)
            VStack(spacing: 7) {
                Text(playbackState)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white.opacity(0.78))
                Text(player.currentTrack?.displayTitle ?? "No song selected")
                    .font(.title2.bold())
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .minimumScaleFactor(0.75)
                if let track = player.currentTrack {
                    Text([track.artist, track.album]
                        .compactMap { $0?.isEmpty == false ? $0 : nil }
                        .joined(separator: "\n"))
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.82))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                }
                Text("\(player.playlist.activePlaylistName) · \(player.playlist.tracks.count) \(player.playlist.tracks.count == 1 ? "song" : "songs")")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                seekSlider
            }
            .padding(20)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var playbackState: String {
        if player.isLoadingRemoteTrack { return "Buffering…" }
        if player.isPlaying { return "Playing" }
        return player.currentTrack == nil ? "Stopped" : "Paused"
    }

    private var seekSlider: some View {
        Slider(
            value: Binding(
                get: { isSeeking ? seekValue : player.currentTime },
                set: { seekValue = $0 }
            ),
            in: 0...max(1, player.duration),
            onEditingChanged: { editing in
                if editing {
                    seekValue = player.currentTime
                    isSeeking = true
                } else {
                    player.seek(to: seekValue)
                    isSeeking = false
                }
            }
        )
        .tint(.accentColor)
        .disabled(player.currentTrack == nil)
        .accessibilityLabel("Track position")
    }

    private var timeRow: some View {
        HStack {
            Text(format(isSeeking ? seekValue : player.currentTime))
            Spacer()
            Text(format(player.duration))
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
    }

    private var transportControls: some View {
        HStack(spacing: 6) {
            transportButton(
                "shuffle",
                label: "Shuffle",
                active: player.shuffleEnabled,
                action: player.toggleShuffle
            )
            transportButton("backward.fill", label: "Previous", action: player.previous)
            transportButton(
                player.isPlaying ? "pause.fill" : "play.fill",
                label: player.isPlaying ? "Pause" : "Play",
                prominent: true,
                action: player.togglePlayback
            )
            transportButton("forward.fill", label: "Next", action: player.next)
            transportButton("stop.fill", label: "Stop", action: player.stop)
            transportButton(
                player.repeatMode == .one ? "repeat.1" : "repeat",
                label: "Repeat",
                active: player.repeatMode != .off,
                action: player.cycleRepeatMode
            )
            transportButton("trash", label: "Remove from playlist") {
                removeConfirmationPresented = true
            }
            .disabled(player.currentTrack == nil)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func transportButton(
        _ systemName: String,
        label: String,
        active: Bool = false,
        prominent: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        let foreground = prominent ? Color.white : (active ? Color.accentColor : Color.primary)
        let background = prominent ? Color.accentColor : Color.secondary.opacity(0.14)
        let content = Image(systemName: systemName)
            .font(.system(size: prominent ? 21 : 17, weight: .semibold))
            .foregroundStyle(foreground)
            .frame(width: prominent ? 46 : 42, height: prominent ? 48 : 42)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

        if prominent {
            Button(action: action) {
                content
            }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
        } else {
            Button(action: action) {
                content
            }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
        }
    }

    private func format(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite else { return "0:00" }
        return String(format: "%d:%02d", Int(seconds) / 60, Int(seconds) % 60)
    }

    @ViewBuilder
    private var artworkView: some View {
        RoundedRectangle(cornerRadius: 8)
            .fill(.quaternary)
            .overlay {
                if let artwork = player.currentArtwork {
                    Image(uiImage: artwork)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    Image(systemName: "music.note")
                        .font(.system(size: 64, weight: .light))
                        .foregroundStyle(.secondary)
                }
            }
            .clipped()
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

struct PlayerSettingsView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearance") private var appearance = AppAppearance.system.rawValue
    let onManagePlaylists: () -> Void
    let onAddMusic: () -> Void
    let onServerSettings: () -> Void
    let onBrowseServer: () -> Void
    let onSharedPlaylists: () -> Void
    let onAskLiam: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Picker("Color Scheme", selection: $appearance) {
                        ForEach(AppAppearance.allCases) { option in
                            Text(option.title).tag(option.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                    Text("System follows the iPhone or iPad appearance setting.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Playlist & Library") {
                    Button(action: onManagePlaylists) {
                        Label("Choose or Manage Playlists", systemImage: "music.note.list")
                    }
                    Button(action: onAddMusic) {
                        Label("Add Music", systemImage: "plus")
                    }
                    Button("Clear Playlist", systemImage: "trash", role: .destructive) {
                        player.stop()
                        player.playlist.clearPlaylist()
                    }
                    .disabled(player.playlist.tracks.isEmpty)
                }

                Section("Fred Server") {
                    Button(action: onServerSettings) {
                        Label("Server Settings", systemImage: "server.rack")
                    }
                    Button(action: onBrowseServer) {
                        Label("Browse Server Library", systemImage: "music.note.house")
                    }
                    Button(action: onSharedPlaylists) {
                        Label("Shared Playlists", systemImage: "person.2")
                    }
                    Button(action: onAskLiam) {
                        Label("Ask Liam", systemImage: "bubble.left.and.text.bubble.right")
                    }
                }

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

                Section("About") {
                    Link("Privacy Policy", destination: URL(string: "https://patrick-lamphier.com/fredplayer-privacy")!)
                    Link("Support", destination: URL(string: "https://patrick-lamphier.com/fredplayer-support")!)
                }
            }
            .navigationTitle("Settings")
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
