import AVFoundation
import Combine
import MediaPlayer
import OSLog

@MainActor
final class PlayerController: ObservableObject {
    static let shared = PlayerController()

    @Published private(set) var isPlaying = false
    @Published private(set) var currentTrackID: PlaylistTrack.ID?
    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var waveform: [Float] = Array(repeating: 0, count: 128)
    @Published private(set) var spectrum: [Float] = Array(repeating: 0, count: 32)
    @Published private(set) var loudnessCacheCount = 0
    @Published private(set) var visualCacheCount = 0
    @Published private(set) var cacheBytes: Int64 = 0
    @Published private(set) var cachePreparationProgress: Double?
    @Published private(set) var cachePreparationLabel = ""
    @Published var shuffleEnabled = true {
        didSet { saveSettings() }
    }

    @Published var outputLevel: Float = 0.8 {
        didSet {
            saveSettings()
        }
    }
    @Published var levelingStrength: Float = 0.65 {
        didSet {
            updateDynamics()
            saveSettings()
        }
    }
    @Published var compressorThreshold: Float = -18 {
        didSet {
            updateDynamics()
            saveSettings()
        }
    }
    @Published var attackTime: Double = 0.02 {
        didSet {
            updateDynamics()
            saveSettings()
        }
    }
    @Published var releaseTime: Double = 0.5 {
        didSet {
            updateDynamics()
            saveSettings()
        }
    }
    @Published var outputCeiling: Float = -1 {
        didSet {
            updateDynamics()
            saveSettings()
        }
    }
    @Published var visualizationFPS = 24.0 { didSet { saveSettings() } }
    @Published var waveformWindow = 0.08 { didSet { saveSettings() } }
    @Published var fftSize = 1024 { didSet { saveSettings() } }
    @Published var fftBarCount = 32 { didSet { saveSettings() } }
    @Published var fftSmoothing: Float = 0.72 { didSet { saveSettings() } }
    @Published var logarithmicFFT = true { didSet { saveSettings() } }
    @Published var startupScanSeconds = 10.0 { didSet { saveSettings() } }

    let playlist = PlaylistStore()

    var currentTrack: PlaylistTrack? {
        playlist.tracks.first { $0.id == currentTrackID }
    }

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let logger = Logger(subsystem: "com.example.FredPlayer", category: "Playback")
    private var audioFile: AVAudioFile?
    private var securityScopedURL: URL?
    private var didAccessSecurityScope = false
    private var playlistObservation: AnyCancellable?
    private var progressTimer: Timer?
    private var shuffleBag: [PlaylistTrack.ID] = []
    private var history: [PlaylistTrack.ID] = []
    private var historyIndex = -1
    private var lastSpectrum: [Float] = []
    private var lastVisualizationTime: TimeInterval = 0
    private var currentLevelingGain: Float = 1
    private var currentVisualCache: VisualCacheEntry?
    private var cachePreparationTask: Task<Void, Never>?
    private let settings = UserDefaults.standard
    private var isRestoringSettings = false

    init() {
        restoreSettings()
        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: nil)
        playerNode.volume = 1
        engine.mainMixerNode.outputVolume = outputLevel
        installVisualizationTap()
        engine.prepare()
        startProgressTimer()
        configureRemoteCommands()

        playlistObservation = playlist.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
        refreshCacheStatus()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            self?.prepareCaches()
        }
    }

    deinit {
        cachePreparationTask?.cancel()
        progressTimer?.invalidate()
        engine.mainMixerNode.removeTap(onBus: 0)
    }

    func togglePlayback() {
        if isPlaying {
            playerNode.pause()
            isPlaying = false
            updateNowPlayingInfo()
        } else if currentTrackID != nil {
            do {
                try startEngineIfNeeded()
                playerNode.play()
                isPlaying = true
                updateNowPlayingInfo()
            } catch {
                logger.error("Resume failed: \(error.localizedDescription, privacy: .public)")
            }
        } else {
            playNext()
        }
    }

    func previous() {
        guard historyIndex > 0 else { return }
        historyIndex -= 1
        play(trackID: history[historyIndex], recordHistory: false)
    }

    func next() { playNext() }

    func play(trackID: PlaylistTrack.ID) {
        play(trackID: trackID, recordHistory: true)
    }

    func stop() {
        playerNode.stop()
        audioFile = nil
        releaseSecurityScope()
        currentTrackID = nil
        isPlaying = false
        currentTime = 0
        duration = 0
        waveform = Array(repeating: 0, count: 128)
        spectrum = Array(repeating: 0, count: fftBarCount)
        currentLevelingGain = 1
        currentVisualCache = nil
        engine.mainMixerNode.outputVolume = outputLevel
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        logger.info("Playback stopped")
    }

    func seek(to time: TimeInterval) {
        guard let file = audioFile, let trackID = currentTrackID else { return }
        let sampleRate = file.processingFormat.sampleRate
        let clamped = min(max(0, time), duration)
        let startFrame = AVAudioFramePosition(clamped * sampleRate)
        let frameCount = AVAudioFrameCount(file.length - startFrame)
        guard frameCount > 0 else { return }

        playerNode.stop()
        playerNode.scheduleSegment(
            file,
            startingFrame: startFrame,
            frameCount: frameCount,
            at: nil
        ) { [weak self] in
            Task { @MainActor in self?.trackDidFinish(trackID) }
        }
        currentTime = clamped

        if isPlaying {
            do {
                try startEngineIfNeeded()
                playerNode.play()
            } catch {
                logger.error("Seek resume failed: \(error.localizedDescription, privacy: .public)")
            }
        }
        updateNowPlayingInfo()
    }

    func toggleShuffle() {
        shuffleEnabled.toggle()
        shuffleBag.removeAll()
    }

    func prepareCaches() {
        guard cachePreparationTask == nil else { return }
        let work = playlist.tracks.compactMap { track -> (String, URL)? in
            guard let url = playlist.resolvedURL(for: track) else { return nil }
            return (track.displayTitle, url)
        }
        guard !work.isEmpty else { return }
        let visualSettings = makeVisualCacheSettings()
        cachePreparationProgress = 0
        cachePreparationLabel = "Preparing caches…"

        cachePreparationTask = Task { [weak self] in
            for (index, item) in work.enumerated() {
                guard !Task.isCancelled else { break }
                cachePreparationLabel = "Preparing \(item.0)"
                let url = item.1
                await Task.detached(priority: .utility) {
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                    if MediaCache.loudness(for: url) == nil,
                       let entry = try? MediaCache.analyzeLoudness(url: url, seconds: nil) {
                        try? MediaCache.store(entry, for: url)
                    }
                    if MediaCache.visual(for: url, settings: visualSettings) == nil,
                       let entry = try? MediaCache.analyzeVisuals(
                        url: url,
                        settings: visualSettings
                       ) {
                        try? MediaCache.store(entry, for: url, settings: visualSettings)
                    }
                }.value
                cachePreparationProgress = Double(index + 1) / Double(work.count)
                refreshCacheStatus()
            }
            cachePreparationProgress = nil
            cachePreparationLabel = ""
            cachePreparationTask = nil
        }
    }

    private func playNext() {
        guard !playlist.tracks.isEmpty else { return }

        if historyIndex >= 0, historyIndex < history.count - 1 {
            historyIndex += 1
            play(trackID: history[historyIndex], recordHistory: false)
            return
        }

        let id: PlaylistTrack.ID
        if shuffleEnabled {
            if shuffleBag.isEmpty {
                shuffleBag = playlist.tracks.map(\.id).shuffled()
                if let currentTrackID, shuffleBag.count > 1,
                   let currentIndex = shuffleBag.firstIndex(of: currentTrackID) {
                    shuffleBag.swapAt(currentIndex, shuffleBag.count - 1)
                }
            }
            id = shuffleBag.removeFirst()
        } else {
            let index = playlist.tracks.firstIndex { $0.id == currentTrackID } ?? -1
            id = playlist.tracks[(index + 1) % playlist.tracks.count].id
        }
        play(trackID: id, recordHistory: true)
    }

    private func play(trackID: PlaylistTrack.ID, recordHistory: Bool) {
        guard
            let track = playlist.tracks.first(where: { $0.id == trackID }),
            let url = playlist.resolvedURL(for: track)
        else { return }

        playerNode.stop()
        releaseSecurityScope()
        securityScopedURL = url
        didAccessSecurityScope = url.startAccessingSecurityScopedResource()

        do {
            let file = try AVAudioFile(forReading: url)
            audioFile = file
            let loudness = initialLoudness(for: url)
            currentLevelingGain = initialGain(for: loudness)
            currentVisualCache = MediaCache.visual(
                for: url,
                settings: makeVisualCacheSettings()
            )
            duration = Double(file.length) / file.processingFormat.sampleRate
            currentTime = 0
            try startEngineIfNeeded()
            playerNode.scheduleFile(file, at: nil) { [weak self] in
                Task { @MainActor in self?.trackDidFinish(track.id) }
            }
            playerNode.play()
            currentTrackID = track.id
            isPlaying = true

            if recordHistory {
                if historyIndex < history.count - 1 {
                    history.removeSubrange((historyIndex + 1)..<history.count)
                }
                history.append(track.id)
                historyIndex = history.count - 1
            }
            logger.info("Playing: \(track.displayTitle, privacy: .public)")
        } catch {
            logger.error("Playback failed for \(track.filename, privacy: .public): \(error.localizedDescription, privacy: .public)")
            audioFile = nil
            releaseSecurityScope()
            currentTrackID = nil
            isPlaying = false
        }
        updateNowPlayingInfo()
    }

    private func startEngineIfNeeded() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default)
        try session.setActive(true)
        if !engine.isRunning { try engine.start() }
        let outputs = session.currentRoute.outputs
            .map { "\($0.portName) [\($0.portType.rawValue)]" }
            .joined(separator: ", ")
        logger.info("Audio route: \(outputs, privacy: .public)")
    }

    private func trackDidFinish(_ id: PlaylistTrack.ID) {
        guard currentTrackID == id, isPlaying else { return }
        playNext()
    }

    private func startProgressTimer() {
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard
                    let self,
                    self.isPlaying,
                    let renderTime = self.playerNode.lastRenderTime,
                    let playerTime = self.playerNode.playerTime(forNodeTime: renderTime)
                else { return }
                self.currentTime = min(
                    Double(playerTime.sampleTime) / playerTime.sampleRate,
                    self.duration
                )
                if let cache = self.currentVisualCache, !cache.frames.isEmpty {
                    let index = min(
                        cache.frames.count - 1,
                        max(0, Int(self.currentTime / cache.frameInterval))
                    )
                    self.waveform = cache.frames[index].waveform
                    self.spectrum = cache.frames[index].spectrum
                }
                self.updateNowPlayingInfo()
            }
        }
    }

    private func configureRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            if !self.isPlaying { self.togglePlayback() }
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            if self.isPlaying { self.togglePlayback() }
            return .success
        }
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.togglePlayback()
            return .success
        }
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.next()
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.previous()
            return .success
        }
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self, let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self.seek(to: event.positionTime)
            return .success
        }
    }

    private func updateNowPlayingInfo() {
        guard currentTrackID != nil else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0
        ]
        info[MPMediaItemPropertyTitle] = currentTrack?.displayTitle
        info[MPMediaItemPropertyArtist] = currentTrack?.artist
        info[MPMediaItemPropertyAlbumTitle] = currentTrack?.album
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func updateDynamics() {
        // Gain riding is applied from the PCM analysis tap on the working mixer path.
    }

    private func installVisualizationTap() {
        engine.mainMixerNode.installTap(onBus: 0, bufferSize: 4096, format: nil) { [weak self] buffer, _ in
            guard
                let self,
                let channel = buffer.floatChannelData?[0],
                buffer.frameLength > 0
            else { return }

            let now = ProcessInfo.processInfo.systemUptime
            guard now - self.lastVisualizationTime >= 1 / max(1, self.visualizationFPS) else { return }
            self.lastVisualizationTime = now
            let available = Int(buffer.frameLength)
            let requestedFFT = min(self.fftSize, available)
            let samples = Array(UnsafeBufferPointer(start: channel, count: available))
            self.applyLeveling(
                samples: samples,
                sampleRate: buffer.format.sampleRate
            )
            let waveformSamples = min(
                available,
                max(1, Int(buffer.format.sampleRate * self.waveformWindow))
            )
            let wave = AudioAnalyzer.waveform(Array(samples.suffix(waveformSamples)), points: 128)
            let rawSpectrum = AudioAnalyzer.spectrum(
                samples,
                fftSize: requestedFFT,
                bars: self.fftBarCount,
                logarithmic: self.logarithmicFFT
            )
            let smoothed = AudioAnalyzer.smoothed(
                rawSpectrum,
                previous: self.lastSpectrum,
                amount: self.fftSmoothing
            )
            self.lastSpectrum = smoothed

            Task { @MainActor [weak self] in
                guard let self, self.currentVisualCache == nil else { return }
                self.waveform = wave
                self.spectrum = smoothed
            }
        }
    }

    private func releaseSecurityScope() {
        if didAccessSecurityScope { securityScopedURL?.stopAccessingSecurityScopedResource() }
        securityScopedURL = nil
        didAccessSecurityScope = false
    }

    private func applyLeveling(samples: [Float], sampleRate: Double) {
        guard !samples.isEmpty else { return }
        let sumSquares = samples.reduce(Float(0)) { $0 + $1 * $1 }
        let rms = sqrt(sumSquares / Float(samples.count))
        let peak = samples.reduce(Float(0)) { max($0, abs($1)) }
        let rmsDB = 20 * log10(max(rms, 0.000_001))

        var desiredGainDB: Float = 0
        if rmsDB > compressorThreshold {
            desiredGainDB = -(rmsDB - compressorThreshold) * levelingStrength
        }

        let ceilingLinear = pow(10, outputCeiling / 20)
        if peak > 0 {
            desiredGainDB = min(
                desiredGainDB,
                20 * log10(max(ceilingLinear / peak, 0.000_001))
            )
        }

        let desiredGain = pow(10, desiredGainDB / 20)
        let time = desiredGain < currentLevelingGain ? attackTime : releaseTime
        let bufferDuration = Double(samples.count) / sampleRate
        let smoothing = Float(exp(-bufferDuration / max(0.001, time)))
        currentLevelingGain =
            desiredGain * (1 - smoothing) + currentLevelingGain * smoothing
        engine.mainMixerNode.outputVolume = min(
            1,
            max(0, outputLevel * currentLevelingGain)
        )
    }

    private func initialLoudness(for url: URL) -> LoudnessCacheEntry? {
        if let cached = MediaCache.loudness(for: url) { return cached }
        guard startupScanSeconds > 0 else { return nil }
        guard let scanned = try? MediaCache.analyzeLoudness(
            url: url,
            seconds: startupScanSeconds
        ) else { return nil }
        try? MediaCache.store(scanned, for: url)
        refreshCacheStatus()
        return scanned
    }

    private func initialGain(for loudness: LoudnessCacheEntry?) -> Float {
        guard let loudness else { return 1 }
        let reductionDB = min(0, (compressorThreshold - loudness.meanDB) * levelingStrength)
        let ceilingGain = loudness.peak > 0
            ? pow(10, outputCeiling / 20) / loudness.peak
            : 1
        return min(1, pow(10, reductionDB / 20), ceilingGain)
    }

    private func makeVisualCacheSettings() -> VisualCacheSettings {
        VisualCacheSettings(
            fps: visualizationFPS,
            waveformWindow: waveformWindow,
            fftSize: fftSize,
            bars: fftBarCount,
            logarithmic: logarithmicFFT
        )
    }

    private func refreshCacheStatus() {
        let status = MediaCache.status()
        loudnessCacheCount = status.loudness
        visualCacheCount = status.visual
        cacheBytes = status.bytes
    }

    private func restoreSettings() {
        isRestoringSettings = true
        defer { isRestoringSettings = false }
        settings.register(defaults: [
            "player.shuffleEnabled": true,
            "player.outputLevel": 0.8,
            "player.levelingStrength": 0.65,
            "player.compressorThreshold": -18.0,
            "player.attackTime": 0.02,
            "player.releaseTime": 0.5,
            "player.outputCeiling": -1.0,
            "player.visualizationFPS": 24.0,
            "player.waveformWindow": 0.08,
            "player.fftSize": 1024,
            "player.fftBarCount": 32,
            "player.fftSmoothing": 0.72,
            "player.logarithmicFFT": true,
            "player.startupScanSeconds": 10.0
        ])
        shuffleEnabled = settings.bool(forKey: "player.shuffleEnabled")
        outputLevel = settings.float(forKey: "player.outputLevel")
        levelingStrength = settings.float(forKey: "player.levelingStrength")
        compressorThreshold = settings.float(forKey: "player.compressorThreshold")
        attackTime = settings.double(forKey: "player.attackTime")
        releaseTime = settings.double(forKey: "player.releaseTime")
        outputCeiling = settings.float(forKey: "player.outputCeiling")
        visualizationFPS = settings.double(forKey: "player.visualizationFPS")
        waveformWindow = settings.double(forKey: "player.waveformWindow")
        fftSize = settings.integer(forKey: "player.fftSize")
        fftBarCount = settings.integer(forKey: "player.fftBarCount")
        fftSmoothing = settings.float(forKey: "player.fftSmoothing")
        logarithmicFFT = settings.bool(forKey: "player.logarithmicFFT")
        startupScanSeconds = settings.double(forKey: "player.startupScanSeconds")
    }

    private func saveSettings() {
        guard !isRestoringSettings else { return }
        settings.set(shuffleEnabled, forKey: "player.shuffleEnabled")
        settings.set(outputLevel, forKey: "player.outputLevel")
        settings.set(levelingStrength, forKey: "player.levelingStrength")
        settings.set(compressorThreshold, forKey: "player.compressorThreshold")
        settings.set(attackTime, forKey: "player.attackTime")
        settings.set(releaseTime, forKey: "player.releaseTime")
        settings.set(outputCeiling, forKey: "player.outputCeiling")
        settings.set(visualizationFPS, forKey: "player.visualizationFPS")
        settings.set(waveformWindow, forKey: "player.waveformWindow")
        settings.set(fftSize, forKey: "player.fftSize")
        settings.set(fftBarCount, forKey: "player.fftBarCount")
        settings.set(fftSmoothing, forKey: "player.fftSmoothing")
        settings.set(logarithmicFFT, forKey: "player.logarithmicFFT")
        settings.set(startupScanSeconds, forKey: "player.startupScanSeconds")
    }
}
