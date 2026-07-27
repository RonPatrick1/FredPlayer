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
    @Published private(set) var isLoadingRemoteTrack = false
    @Published var playbackError: String?
    @Published var serverBaseURL = "" { didSet { saveSettings() } }
    @Published var serverToken = "" { didSet { saveSettings() } }
    @Published private(set) var deviceID = ""
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
    @Published var visualizationFPS = 60.0 { didSet { saveSettings() } }
    @Published var waveformWindow = 0.08 { didSet { saveSettings() } }
    @Published var fftSize = 2048 { didSet { saveSettings() } }
    @Published var fftBarCount = 64 { didSet { saveSettings() } }
    @Published var fftSmoothing: Float = 0 { didSet { saveSettings() } }
    @Published var logarithmicFFT = true { didSet { saveSettings() } }
    @Published var startupScanSeconds = 10.0 { didSet { saveSettings() } }

    let playlist = PlaylistStore()

    var currentTrack: PlaylistTrack? {
        playlist.tracks.first { $0.id == currentTrackID }
    }

    var serverClient: FredServerClient? {
        let value = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, let url = URL(string: value), url.scheme != nil else { return nil }
        return FredServerClient(baseURL: url, token: serverToken)
    }

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let logger = Logger(subsystem: "com.example.FredPlayer", category: "Playback")
    private var audioFile: AVAudioFile?
    private var securityScopedURL: URL?
    private var didAccessSecurityScope = false
    private var playlistObservation: AnyCancellable?
    private var audioSessionObservations = Set<AnyCancellable>()
    private var progressTimer: Timer?
    private var shuffleBag: [PlaylistTrack.ID] = []
    private var history: [PlaylistTrack.ID] = []
    private var historyIndex = -1
    private var lastSpectrum: [Float] = []
    private var lastVisualizationTime: TimeInterval = 0
    private var currentLevelingGain: Float = 1
    private var currentVisualCache: VisualCacheEntry?
    private var cachePreparationTask: Task<Void, Never>?
    private var playbackTask: Task<Void, Never>?
    private var routeRecoveryTask: Task<Void, Never>?
    private var shouldResumeAfterInterruption = false
    private var playbackRequestID = UUID()
    private let settings = UserDefaults.standard
    private var isRestoringSettings = false

    init() {
        restoreSettings()
        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: nil)
        playerNode.volume = 1
        engine.mainMixerNode.outputVolume = outputLevel
        configureAudioSession()
        observeAudioSession()
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
        playbackTask?.cancel()
        routeRecoveryTask?.cancel()
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
        playbackRequestID = UUID()
        playbackTask?.cancel()
        playbackTask = nil
        isLoadingRemoteTrack = false
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
        let work = playlist.tracks
        guard !work.isEmpty else { return }
        let visualSettings = makeVisualCacheSettings()
        cachePreparationProgress = 0
        cachePreparationLabel = "Preparing caches…"

        cachePreparationTask = Task { [weak self] in
            guard let self else { return }
            for (index, track) in work.enumerated() {
                guard !Task.isCancelled else { break }
                self.cachePreparationLabel = "Preparing \(track.displayTitle)"
                if let serverPath = track.serverPath, let client = self.serverClient {
                    async let profile = client.fetchProfile(serverPath: serverPath)
                    async let visualData = client.fetchVisual(serverPath: serverPath)
                    if MediaCache.loudness(forServerPath: serverPath) == nil,
                       let fetched = await profile {
                        try? MediaCache.store(Self.loudnessEntry(from: fetched), forServerPath: serverPath)
                    }
                    if MediaCache.visual(forServerPath: serverPath, settings: visualSettings) == nil,
                       let data = await visualData,
                       let entry = MediaCache.serverVisual(from: data, settings: visualSettings) {
                        try? MediaCache.store(entry, forServerPath: serverPath, settings: visualSettings)
                    }
                } else if let url = self.playlist.resolvedURL(for: track) {
                    await Task.detached(priority: .utility) {
                        let accessed = url.startAccessingSecurityScopedResource()
                        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                        if MediaCache.loudness(for: url) == nil,
                           let entry = try? MediaCache.analyzeLoudness(url: url, seconds: nil) {
                            try? MediaCache.store(entry, for: url)
                        }
                        if MediaCache.visual(for: url, settings: visualSettings) == nil,
                           let entry = try? MediaCache.analyzeVisuals(url: url, settings: visualSettings) {
                            try? MediaCache.store(entry, for: url, settings: visualSettings)
                        }
                    }.value
                }
                self.cachePreparationProgress = Double(index + 1) / Double(work.count)
                self.refreshCacheStatus()
            }
            self.cachePreparationProgress = nil
            self.cachePreparationLabel = ""
            self.cachePreparationTask = nil
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
        guard let track = playlist.tracks.first(where: { $0.id == trackID }) else { return }
        let requestID = UUID()
        playbackRequestID = requestID
        playbackTask?.cancel()
        playerNode.stop()
        releaseSecurityScope()
        isPlaying = false
        isLoadingRemoteTrack = track.isRemote
        playbackError = nil

        playbackTask = Task { [weak self] in
            guard let self else { return }
            do {
                let prepared = try await preparePlayback(for: track)
                try Task.checkCancellation()
                beginPlayback(
                    track: track,
                    url: prepared.url,
                    loudness: prepared.loudness,
                    visual: prepared.visual,
                    recordHistory: recordHistory
                )
            } catch is CancellationError {
                // A newer play request or Stop superseded this one.
            } catch {
                playbackError = error.localizedDescription
                logger.error("Playback preparation failed for \(track.filename, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
            if playbackRequestID == requestID {
                isLoadingRemoteTrack = false
                playbackTask = nil
            }
        }
    }

    private func beginPlayback(
        track: PlaylistTrack,
        url: URL,
        loudness: LoudnessCacheEntry?,
        visual: VisualCacheEntry?,
        recordHistory: Bool
    ) {
        if !track.isRemote {
            securityScopedURL = url
            didAccessSecurityScope = url.startAccessingSecurityScopedResource()
        }

        do {
            let file = try AVAudioFile(forReading: url)
            audioFile = file
            currentLevelingGain = initialGain(for: loudness)
            currentVisualCache = visual
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

    private func preparePlayback(
        for track: PlaylistTrack
    ) async throws -> (url: URL, loudness: LoudnessCacheEntry?, visual: VisualCacheEntry?) {
        let visualSettings = makeVisualCacheSettings()
        guard let serverPath = track.serverPath else {
            guard let url = playlist.resolvedURL(for: track) else { throw CocoaError(.fileNoSuchFile) }
            return (
                url,
                initialLoudness(for: url),
                MediaCache.visual(for: url, settings: visualSettings)
            )
        }
        guard let client = serverClient else { throw FredServerError.invalidBaseURL }

        async let downloaded = RemoteTrackCache.download(serverPath: serverPath, client: client)
        async let profile = remoteLoudness(serverPath: serverPath, client: client)
        async let visual = remoteVisual(serverPath: serverPath, client: client, settings: visualSettings)
        let url = try await downloaded
        var loudness = await profile
        var visualEntry = await visual

        if loudness == nil {
            loudness = await Task.detached(priority: .utility) {
                try? MediaCache.analyzeLoudness(url: url, seconds: nil)
            }.value
            if let loudness {
                try? MediaCache.store(loudness, forServerPath: serverPath)
                let wire = Self.profile(from: loudness)
                Task { _ = await client.uploadProfile(wire, serverPath: serverPath) }
            }
        }
        if visualEntry == nil {
            visualEntry = await Task.detached(priority: .utility) {
                try? MediaCache.analyzeVisuals(url: url, settings: visualSettings)
            }.value
            if let visualEntry {
                try? MediaCache.store(visualEntry, forServerPath: serverPath, settings: visualSettings)
                let data = MediaCache.encodeServerVisual(visualEntry, settings: visualSettings)
                Task { _ = await client.uploadVisual(data, serverPath: serverPath) }
            }
        }
        refreshCacheStatus()
        return (url, loudness, visualEntry)
    }

    private func remoteLoudness(serverPath: String, client: FredServerClient) async -> LoudnessCacheEntry? {
        if let cached = MediaCache.loudness(forServerPath: serverPath) { return cached }
        guard let profile = await client.fetchProfile(serverPath: serverPath) else { return nil }
        let entry = Self.loudnessEntry(from: profile)
        try? MediaCache.store(entry, forServerPath: serverPath)
        return entry
    }

    private func remoteVisual(
        serverPath: String,
        client: FredServerClient,
        settings: VisualCacheSettings
    ) async -> VisualCacheEntry? {
        if let cached = MediaCache.visual(forServerPath: serverPath, settings: settings) { return cached }
        guard let data = await client.fetchVisual(serverPath: serverPath),
              let entry = MediaCache.serverVisual(from: data, settings: settings) else { return nil }
        try? MediaCache.store(entry, forServerPath: serverPath, settings: settings)
        return entry
    }

    static func profile(from entry: LoudnessCacheEntry) -> TrackProfile {
        TrackProfile(rms: pow(10, entry.meanDB / 20), peak: entry.peak)
    }

    static func loudnessEntry(from profile: TrackProfile) -> LoudnessCacheEntry {
        LoudnessCacheEntry(
            meanDB: 20 * log10(max(profile.rms, 0.000_001)),
            peak: profile.peak,
            analyzedSeconds: 0,
            created: Date()
        )
    }

    private func startEngineIfNeeded() throws {
        let session = AVAudioSession.sharedInstance()
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
            }
        }
    }

    private func configureRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor [weak self] in
                guard let self, !self.isPlaying else { return }
                self.togglePlayback()
            }
            return .success
        }
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor [weak self] in
                guard let self, self.isPlaying else { return }
                self.togglePlayback()
            }
            return .success
        }
        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor [weak self] in self?.togglePlayback() }
            return .success
        }
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor [weak self] in self?.next() }
            return .success
        }
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard self != nil else { return .commandFailed }
            Task { @MainActor [weak self] in self?.previous() }
            return .success
        }
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard self != nil, let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            Task { @MainActor [weak self] in self?.seek(to: event.positionTime) }
            return .success
        }

        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
        commandCenter.seekForwardCommand.isEnabled = false
        commandCenter.seekBackwardCommand.isEnabled = false
        commandCenter.changePlaybackRateCommand.isEnabled = false
        commandCenter.likeCommand.isEnabled = false
        commandCenter.dislikeCommand.isEnabled = false
        commandCenter.bookmarkCommand.isEnabled = false
    }

    private func updateNowPlayingInfo() {
        guard let currentTrackID, let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyExternalContentIdentifier: currentTrackID.uuidString,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyPlaybackQueueCount: playlist.tracks.count
        ]
        if let index = playlist.tracks.firstIndex(where: { $0.id == currentTrackID }) {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = index
        }
        info[MPMediaItemPropertyTitle] = track.displayTitle
        info[MPMediaItemPropertyArtist] = track.artist
        info[MPMediaItemPropertyAlbumTitle] = track.album
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        } catch {
            logger.error("Audio session configuration failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func observeAudioSession() {
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] notification in
                guard let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }
                switch reason {
                case .newDeviceAvailable, .routeConfigurationChange, .categoryChange, .override:
                    self?.scheduleRouteRecovery()
                default:
                    break
                }
            }
            .store(in: &audioSessionObservations)

        NotificationCenter.default.publisher(for: .AVAudioEngineConfigurationChange, object: engine)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.scheduleRouteRecovery() }
            .store(in: &audioSessionObservations)

        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] notification in self?.handleAudioInterruption(notification) }
            .store(in: &audioSessionObservations)
    }

    private func scheduleRouteRecovery() {
        guard isPlaying, audioFile != nil else { return }
        routeRecoveryTask?.cancel()
        let resumeTime = currentTime
        routeRecoveryTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled, let self, self.isPlaying else { return }
            self.seek(to: resumeTime)
            self.logger.info("Playback recovered after audio route change")
        }
    }

    private func handleAudioInterruption(_ notification: Notification) {
        guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
        switch type {
        case .began:
            shouldResumeAfterInterruption = isPlaying
            if isPlaying {
                playerNode.pause()
                isPlaying = false
                updateNowPlayingInfo()
            }
        case .ended:
            let optionsValue = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            guard shouldResumeAfterInterruption, options.contains(.shouldResume) else {
                shouldResumeAfterInterruption = false
                return
            }
            shouldResumeAfterInterruption = false
            togglePlayback()
        @unknown default:
            break
        }
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
            "player.visualizationFPS": 60.0,
            "player.waveformWindow": 0.08,
            "player.fftSize": 2048,
            "player.fftBarCount": 64,
            "player.fftSmoothing": 0.0,
            "player.logarithmicFFT": true,
            "player.startupScanSeconds": 10.0,
            "server.baseURL": "",
            "server.token": ""
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
        serverBaseURL = settings.string(forKey: "server.baseURL") ?? ""
        serverToken = settings.string(forKey: "server.token") ?? ""
        if let savedID = settings.string(forKey: "server.deviceID"), !savedID.isEmpty {
            deviceID = savedID.replacingOccurrences(of: "-", with: "").lowercased()
            settings.set(deviceID, forKey: "server.deviceID")
        } else {
            deviceID = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            settings.set(deviceID, forKey: "server.deviceID")
        }
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
        settings.set(serverBaseURL, forKey: "server.baseURL")
        settings.set(serverToken, forKey: "server.token")
        settings.set(deviceID, forKey: "server.deviceID")
    }
}
