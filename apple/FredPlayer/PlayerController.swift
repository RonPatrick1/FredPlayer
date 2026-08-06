import AVFoundation
import Combine
import MediaPlayer
import OSLog

private enum PlaybackPreparationError: LocalizedError {
    case noAudioTrack

    var errorDescription: String? {
        "The selected file does not contain a playable audio track."
    }
}

enum RepeatMode: Int {
    case off = 0
    case all = 1
    case one = 2

    var next: RepeatMode {
        switch self {
        case .off: return .all
        case .all: return .one
        case .one: return .off
        }
    }
}

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
    @Published private(set) var outputLatency: Double = 0
    @Published var playbackError: String?
    @Published var serverBaseURL = "" { didSet { saveSettings() } }
    @Published var serverToken = "" {
        didSet {
            guard !isRestoringSettings else { return }
            KeychainStore.set(serverToken, for: "server.token")
        }
    }
    @Published private(set) var deviceID = ""
    @Published var shuffleEnabled = true {
        didSet { saveSettings() }
    }
    @Published var repeatMode: RepeatMode = .all {
        didSet { saveSettings() }
    }

    @Published var outputLevel: Float = 0.8 {
        didSet {
            updateDynamics()
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
    @Published var visualizationFPS = 60.0 { didSet { updateDynamics(); saveSettings() } }
    @Published var waveformWindow = 0.08 { didSet { updateDynamics(); saveSettings() } }
    @Published var fftSize = 2048 { didSet { updateDynamics(); saveSettings() } }
    @Published var fftBarCount = 64 { didSet { updateDynamics(); saveSettings() } }
    @Published var fftSmoothing: Float = 0 { didSet { updateDynamics(); saveSettings() } }
    @Published var logarithmicFFT = true { didSet { updateDynamics(); saveSettings() } }
    @Published var startupScanSeconds = 10.0 { didSet { saveSettings() } }

    let playlist = PlaylistStore()

    var currentTrack: PlaylistTrack? {
        playlist.tracks.first { $0.id == currentTrackID }
    }

    var serverClient: FredServerClient? {
        guard let url = FredServerURLPolicy.validatedURL(serverBaseURL) else { return nil }
        return FredServerClient(baseURL: url, token: serverToken)
    }

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private var streamingPlayer: AVPlayer?
    private var streamingPlayerItem: AVPlayerItem?
    private var streamingAudioTap: StreamingAudioTap?
    private var streamingItemStatusObservation: NSKeyValueObservation?
    private var streamingTimeControlObservation: NSKeyValueObservation?
    private var streamingEndObserver: NSObjectProtocol?
    private var streamingFailureObserver: NSObjectProtocol?
    private var streamingVisualTask: Task<Void, Never>?
    private var streamingUsesLiveVisualization = false
    private let logger = Logger(subsystem: "com.ronpatrick.FredPlayer", category: "Playback")
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
        RemoteTrackCache.removeLegacyDownloads()
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
        tearDownStreamingPlayer()
        engine.mainMixerNode.removeTap(onBus: 0)
    }

    func togglePlayback() {
        if isPlaying {
            if let streamingPlayer {
                streamingPlayer.pause()
            } else {
                playerNode.pause()
            }
            isPlaying = false
            updateNowPlayingInfo()
        } else if currentTrackID != nil {
            do {
                if let streamingPlayer {
                    try activateAudioSession()
                    streamingPlayer.play()
                } else {
                    try startEngineIfNeeded()
                    playerNode.play()
                }
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

    func removeCurrentTrack() {
        guard let currentTrackID,
              let index = playlist.tracks.firstIndex(where: { $0.id == currentTrackID }) else { return }
        playlist.removeTracks(at: IndexSet(integer: index))
        // The shuffle bag may reference an index/ID that shifted or no
        // longer exists after the removal — simplest safe fix is to drop
        // it and let it get rebuilt fresh next time it's needed.
        shuffleBag.removeAll()
        guard !playlist.tracks.isEmpty else {
            stop()
            return
        }
        let nextIndex = min(index, playlist.tracks.count - 1)
        play(trackID: playlist.tracks[nextIndex].id)
    }

    func play(trackID: PlaylistTrack.ID) {
        play(trackID: trackID, recordHistory: true)
    }

    func stop() {
        playbackRequestID = UUID()
        playbackTask?.cancel()
        playbackTask = nil
        isLoadingRemoteTrack = false
        stopActiveTransport()
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
        if let streamingPlayer {
            let clamped = min(max(0, time), duration)
            currentTime = clamped
            streamingPlayer.seek(
                to: CMTime(seconds: clamped, preferredTimescale: 600),
                toleranceBefore: .zero,
                toleranceAfter: .zero
            )
            updateNowPlayingInfo()
            return
        }
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

    func cycleRepeatMode() {
        repeatMode = repeatMode.next
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
                    async let visualData = client.fetchVisual(serverPath: serverPath, settings: visualSettings)
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
        stopActiveTransport()
        currentTrackID = nil
        isPlaying = false
        isLoadingRemoteTrack = track.isRemote
        playbackError = nil

        playbackTask = Task { [weak self] in
            guard let self else { return }
            do {
                if let serverPath = track.serverPath {
                    try await prepareStreamingPlayback(
                        track: track,
                        serverPath: serverPath,
                        recordHistory: recordHistory
                    )
                } else {
                    let prepared = try prepareLocalPlayback(for: track)
                    try Task.checkCancellation()
                    beginLocalPlayback(
                        track: track,
                        url: prepared.url,
                        loudness: prepared.loudness,
                        visual: prepared.visual,
                        recordHistory: recordHistory
                    )
                }
            } catch is CancellationError {
                // A newer play request or Stop superseded this one.
            } catch {
                playbackError = error.localizedDescription
                logger.error("Playback preparation failed for \(track.filename, privacy: .private): \(error.localizedDescription, privacy: .public)")
            }
            if playbackRequestID == requestID {
                if streamingPlayer == nil { isLoadingRemoteTrack = false }
                playbackTask = nil
            }
        }
    }

    private func beginLocalPlayback(
        track: PlaylistTrack,
        url: URL,
        loudness: LoudnessCacheEntry?,
        visual: VisualCacheEntry?,
        recordHistory: Bool
    ) {
        securityScopedURL = url
        didAccessSecurityScope = url.startAccessingSecurityScopedResource()

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
            recordPlayback(track.id, enabled: recordHistory)
            logger.info("Playing: \(track.displayTitle, privacy: .private)")
        } catch {
            logger.error("Playback failed for \(track.filename, privacy: .private): \(error.localizedDescription, privacy: .public)")
            audioFile = nil
            releaseSecurityScope()
            currentTrackID = nil
            isPlaying = false
        }
        updateNowPlayingInfo()
    }

    private func prepareLocalPlayback(
        for track: PlaylistTrack
    ) throws -> (url: URL, loudness: LoudnessCacheEntry?, visual: VisualCacheEntry?) {
        let visualSettings = makeVisualCacheSettings()
        guard let url = playlist.resolvedURL(for: track) else { throw CocoaError(.fileNoSuchFile) }
        return (
            url,
            initialLoudness(for: url),
            MediaCache.visual(for: url, settings: visualSettings)
        )
    }

    private func prepareStreamingPlayback(
        track: PlaylistTrack,
        serverPath: String,
        recordHistory: Bool
    ) async throws {
        guard let client = serverClient else { throw FredServerError.invalidBaseURL }
        let visualSettings = makeVisualCacheSettings()
        let cachedVisual = MediaCache.visual(forServerPath: serverPath, settings: visualSettings)

        async let streamURL = client.streamingURL(serverPath: serverPath)
        async let profile = remoteLoudness(serverPath: serverPath, client: client)
        let url = try await streamURL
        let loudness = await profile
        try Task.checkCancellation()

        let asset = AVURLAsset(url: url)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard let audioTrack = audioTracks.first else { throw PlaybackPreparationError.noAudioTrack }
        try Task.checkCancellation()

        let usesLiveVisualization = cachedVisual == nil
        let tap = try StreamingAudioTap(
            initialGain: initialGain(for: loudness),
            compression: makeCompressionConfiguration(),
            visualization: usesLiveVisualization ? makeLiveVisualizationConfiguration() : nil
        ) { [weak self] waveform, spectrum in
            Task { @MainActor [weak self] in
                guard let self,
                      self.currentTrackID == track.id,
                      self.currentVisualCache == nil else { return }
                self.publishVisualFrame(waveform: waveform, spectrum: spectrum)
            }
        }
        let inputParameters = AVMutableAudioMixInputParameters(track: audioTrack)
        inputParameters.audioTapProcessor = tap.tap
        let audioMix = AVMutableAudioMix()
        audioMix.inputParameters = [inputParameters]
        let item = AVPlayerItem(asset: asset)
        item.audioMix = audioMix

        try beginStreamingPlayback(
            track: track,
            serverPath: serverPath,
            client: client,
            item: item,
            tap: tap,
            visual: cachedVisual,
            visualSettings: visualSettings,
            usesLiveVisualization: usesLiveVisualization,
            recordHistory: recordHistory
        )
        refreshCacheStatus()
    }

    private func beginStreamingPlayback(
        track: PlaylistTrack,
        serverPath: String,
        client: FredServerClient,
        item: AVPlayerItem,
        tap: StreamingAudioTap,
        visual: VisualCacheEntry?,
        visualSettings: VisualCacheSettings,
        usesLiveVisualization: Bool,
        recordHistory: Bool
    ) throws {
        try activateAudioSession()

        let player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = true
        player.volume = 1
        streamingPlayer = player
        streamingPlayerItem = item
        streamingAudioTap = tap
        streamingUsesLiveVisualization = usesLiveVisualization
        currentVisualCache = visual
        currentTime = 0
        duration = 0
        currentTrackID = track.id
        isPlaying = true
        recordPlayback(track.id, enabled: recordHistory)
        observeStreamingPlayback(player: player, item: item, trackID: track.id)
        player.play()

        if visual == nil {
            streamingVisualTask = Task { [weak self] in
                guard let self,
                      let fetched = await self.remoteVisual(
                        serverPath: serverPath,
                        client: client,
                        settings: visualSettings
                      ),
                      !Task.isCancelled,
                      self.currentTrackID == track.id,
                      self.streamingPlayerItem === item else { return }
                self.currentVisualCache = fetched
                self.streamingUsesLiveVisualization = false
                self.updateDynamics()
                self.refreshCacheStatus()
            }
        }
        logger.info("Streaming: \(track.displayTitle, privacy: .private)")
        updateNowPlayingInfo()
    }

    private func recordPlayback(_ id: PlaylistTrack.ID, enabled: Bool) {
        guard enabled else { return }
        if historyIndex < history.count - 1 {
            history.removeSubrange((historyIndex + 1)..<history.count)
        }
        history.append(id)
        historyIndex = history.count - 1
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
        guard let data = await client.fetchVisual(serverPath: serverPath, settings: settings),
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

    private func observeStreamingPlayback(
        player: AVPlayer,
        item: AVPlayerItem,
        trackID: PlaylistTrack.ID
    ) {
        streamingItemStatusObservation = item.observe(\.status, options: [.initial, .new]) {
            [weak self] observedItem, _ in
            Task { @MainActor [weak self] in
                guard let self, self.streamingPlayerItem === observedItem else { return }
                switch observedItem.status {
                case .readyToPlay:
                    let seconds = observedItem.duration.seconds
                    if seconds.isFinite && seconds > 0 { self.duration = seconds }
                    self.isLoadingRemoteTrack = false
                    self.updateNowPlayingInfo()
                case .failed:
                    self.handleStreamingFailure(
                        observedItem.error ?? PlaybackPreparationError.noAudioTrack
                    )
                case .unknown:
                    break
                @unknown default:
                    break
                }
            }
        }
        streamingTimeControlObservation = player.observe(\.timeControlStatus, options: [.new]) {
            [weak self] observedPlayer, _ in
            Task { @MainActor [weak self] in
                guard let self, self.streamingPlayer === observedPlayer else { return }
                switch observedPlayer.timeControlStatus {
                case .playing:
                    self.isLoadingRemoteTrack = false
                case .waitingToPlayAtSpecifiedRate:
                    if self.isPlaying { self.isLoadingRemoteTrack = true }
                case .paused:
                    if !self.isPlaying { self.isLoadingRemoteTrack = false }
                @unknown default:
                    break
                }
            }
        }
        streamingEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.trackDidFinish(trackID) }
        }
        streamingFailureObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            Task { @MainActor [weak self] in
                self?.handleStreamingFailure(error ?? FredServerError.invalidResponse)
            }
        }
    }

    private func handleStreamingFailure(_ error: Error) {
        guard streamingPlayer != nil else { return }
        logger.error("Streaming failed: \(error.localizedDescription, privacy: .public)")
        tearDownStreamingPlayer()
        currentTrackID = nil
        isPlaying = false
        isLoadingRemoteTrack = false
        playbackError = error.localizedDescription
        updateNowPlayingInfo()
    }

    private func stopActiveTransport() {
        playerNode.stop()
        audioFile = nil
        releaseSecurityScope()
        tearDownStreamingPlayer()
        currentVisualCache = nil
        currentLevelingGain = 1
        engine.mainMixerNode.outputVolume = outputLevel
    }

    private func tearDownStreamingPlayer() {
        streamingVisualTask?.cancel()
        streamingVisualTask = nil
        streamingItemStatusObservation?.invalidate()
        streamingItemStatusObservation = nil
        streamingTimeControlObservation?.invalidate()
        streamingTimeControlObservation = nil
        if let streamingEndObserver {
            NotificationCenter.default.removeObserver(streamingEndObserver)
        }
        streamingEndObserver = nil
        if let streamingFailureObserver {
            NotificationCenter.default.removeObserver(streamingFailureObserver)
        }
        streamingFailureObserver = nil
        streamingPlayer?.pause()
        streamingPlayer?.replaceCurrentItem(with: nil)
        streamingPlayer = nil
        streamingPlayerItem = nil
        streamingAudioTap = nil
        streamingUsesLiveVisualization = false
    }

    private func activateAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setActive(true)
        let outputs = session.currentRoute.outputs
            .map { "\($0.portName) [\($0.portType.rawValue)]" }
            .joined(separator: ", ")
        logger.info("Audio route: \(outputs, privacy: .public)")
    }

    private func startEngineIfNeeded() throws {
        try activateAudioSession()
        if !engine.isRunning { try engine.start() }
    }

    // Called only when a track finishes playing on its own — manual next()
    // always advances/wraps regardless of repeat mode; only the natural
    // end-of-track path should honor "repeat one" (replay) or "repeat off"
    // (stop instead of wrapping).
    private func trackDidFinish(_ id: PlaylistTrack.ID) {
        guard currentTrackID == id, isPlaying else { return }
        switch repeatMode {
        case .one:
            play(trackID: id, recordHistory: false)
        case .off:
            let atEnd = shuffleEnabled
                ? shuffleBag.isEmpty
                : (playlist.tracks.firstIndex { $0.id == id } ?? -1) >= playlist.tracks.count - 1
            if atEnd {
                stop()
            } else {
                playNext()
            }
        case .all:
            playNext()
        }
    }

    private func startProgressTimer() {
        // Keep cached visualization frames tied to the audio clock at display
        // cadence. The old 200 ms timer reduced a 60 FPS cache to 5 visible
        // updates per second and made synchronization errors much easier to see.
        progressTimer = Timer.scheduledTimer(withTimeInterval: 1 / 60, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isPlaying else { return }
                if let streamingPlayer = self.streamingPlayer {
                    let seconds = streamingPlayer.currentTime().seconds
                    if seconds.isFinite { self.currentTime = max(0, seconds) }
                    if let item = self.streamingPlayerItem {
                        let itemDuration = item.duration.seconds
                        if itemDuration.isFinite && itemDuration > 0 {
                            self.duration = itemDuration
                            self.currentTime = min(self.currentTime, itemDuration)
                        }
                    }
                } else {
                    guard let renderTime = self.playerNode.lastRenderTime,
                          let playerTime = self.playerNode.playerTime(forNodeTime: renderTime)
                    else { return }
                    self.currentTime = min(
                        Double(playerTime.sampleTime) / playerTime.sampleRate,
                        self.duration
                    )
                }
                if let cache = self.currentVisualCache, !cache.frames.isEmpty {
                    let index = min(
                        cache.frames.count - 1,
                        max(0, Int(self.currentTime / cache.frameInterval))
                    )
                    self.publishVisualFrame(
                        waveform: cache.frames[index].waveform,
                        spectrum: cache.frames[index].spectrum
                    )
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

        commandCenter.changeShuffleModeCommand.isEnabled = true
        commandCenter.changeShuffleModeCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangeShuffleModeCommandEvent else { return .commandFailed }
            Task { @MainActor [weak self] in
                self?.shuffleEnabled = event.shuffleType != .off
                self?.shuffleBag.removeAll()
            }
            return .success
        }
        commandCenter.changeRepeatModeCommand.isEnabled = true
        commandCenter.changeRepeatModeCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangeRepeatModeCommandEvent else { return .commandFailed }
            Task { @MainActor [weak self] in
                switch event.repeatType {
                case .one: self?.repeatMode = .one
                case .off: self?.repeatMode = .off
                default: self?.repeatMode = .all
                }
            }
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
        refreshOutputLatency()
    }

    /// Unlike Android, which has to play a recorded calibration chirp to
    /// measure Bluetooth output delay, `AVAudioSession` reports it directly —
    /// no calibration step needed. Kept fresh on every route change so the
    /// visualizer (delayed via `publishVisualFrame`) stays in sync with what
    /// the listener actually hears on Bluetooth speakers/headphones, not
    /// what was just decoded.
    private func refreshOutputLatency() {
        outputLatency = AVAudioSession.sharedInstance().outputLatency
    }

    private func observeAudioSession() {
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] notification in
                guard let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }
                switch reason {
                case .newDeviceAvailable, .routeConfigurationChange, .categoryChange, .override:
                    self?.refreshOutputLatency()
                    self?.scheduleRouteRecovery()
                default:
                    break
                }
            }
            .store(in: &audioSessionObservations)

        NotificationCenter.default.publisher(for: .AVAudioEngineConfigurationChange, object: engine)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.refreshOutputLatency()
                self?.scheduleRouteRecovery()
            }
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
                if let streamingPlayer {
                    streamingPlayer.pause()
                } else {
                    playerNode.pause()
                }
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
        streamingAudioTap?.update(
            compression: makeCompressionConfiguration(),
            visualization: streamingUsesLiveVisualization
                ? makeLiveVisualizationConfiguration()
                : nil
        )
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
                self.publishVisualFrame(waveform: wave, spectrum: smoothed)
            }
        }
    }

    /// Delays the visualizer's published frame by the current route's
    /// output latency, so what's on screen lines up with what's actually
    /// audible rather than with the moment the samples were decoded/tapped.
    /// Negligible for built-in speakers; can be 100-200ms+ on Bluetooth.
    private func publishVisualFrame(waveform: [Float], spectrum: [Float]) {
        guard outputLatency > 0.001 else {
            self.waveform = waveform
            self.spectrum = spectrum
            return
        }
        let delay = outputLatency
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard let self else { return }
            self.waveform = waveform
            self.spectrum = spectrum
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

    private func makeCompressionConfiguration() -> RealtimeCompressionConfiguration {
        RealtimeCompressionConfiguration(
            outputLevel: outputLevel,
            strength: levelingStrength,
            thresholdDB: compressorThreshold,
            attackTime: attackTime,
            releaseTime: releaseTime,
            ceilingDB: outputCeiling
        )
    }

    private func makeLiveVisualizationConfiguration() -> LiveVisualizationConfiguration {
        LiveVisualizationConfiguration(
            fps: visualizationFPS,
            waveformWindow: waveformWindow,
            fftSize: fftSize,
            bars: fftBarCount,
            smoothing: fftSmoothing,
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
            "player.repeatMode": RepeatMode.all.rawValue,
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
            "server.baseURL": ""
        ])
        shuffleEnabled = settings.bool(forKey: "player.shuffleEnabled")
        repeatMode = RepeatMode(rawValue: settings.integer(forKey: "player.repeatMode")) ?? .all
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
        if let protectedToken = KeychainStore.string(for: "server.token") {
            serverToken = protectedToken
            settings.removeObject(forKey: "server.token")
        } else {
            let legacyToken = settings.string(forKey: "server.token") ?? ""
            serverToken = legacyToken
            if !legacyToken.isEmpty,
               KeychainStore.set(legacyToken, for: "server.token") {
                settings.removeObject(forKey: "server.token")
            }
        }
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
        settings.set(repeatMode.rawValue, forKey: "player.repeatMode")
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
        settings.set(deviceID, forKey: "server.deviceID")
    }
}
