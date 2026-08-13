import SwiftUI
import UniformTypeIdentifiers
import OSLog

struct ContentView: View {
    private enum PresentedSheet: String, Identifiable {
        case copiedLibrary
        case serverSettings
        case serverLibrary
        case askLiam
        case playlistManager
        case sharedPlaylists
        case playerSettings
        case lyrics
        case whatsNext

        var id: String { rawValue }

        var requiresServer: Bool {
            switch self {
            case .serverLibrary, .askLiam, .sharedPlaylists:
                true
            case .copiedLibrary, .serverSettings, .playlistManager,
                    .playerSettings, .lyrics, .whatsNext:
                false
            }
        }
    }

    @EnvironmentObject private var player: PlayerController
    @State private var isImporterPresented = false
    @State private var isFolderImporterPresented = false
    @State private var isScanningFolder = false
    @State private var isMusicSourcePresented = false
    @State private var isClearConfirmationPresented = false
    @State private var presentedSheet: PresentedSheet?
    @State private var pendingServerSheet: PresentedSheet?
    @State private var queuedSheetAfterDismiss: PresentedSheet?
    @State private var showMusicSourceAfterDismiss = false
    private let logger = Logger(subsystem: "com.ronpatrick.FredPlayer", category: "Import")

    var body: some View {
        NavigationStack {
            VStack(spacing: 10) {
                mainHeader
                PlayerPanel()
                    .environmentObject(player)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .background(Color(uiColor: .systemBackground))
            .sheet(item: $presentedSheet, onDismiss: handleSheetDismissal) { sheet in
                sheetContent(for: sheet)
                    .environmentObject(player)
            }
            .toolbar(.hidden, for: .navigationBar)
            .confirmationDialog(
                "Remove every track from the playlist?",
                isPresented: $isClearConfirmationPresented,
                titleVisibility: .visible
            ) {
                Button("Clear Playlist", role: .destructive) {
                    player.stop()
                    player.playlist.clearPlaylist()
                }
            }
            .confirmationDialog(
                "Choose a music source",
                isPresented: $isMusicSourcePresented,
                titleVisibility: .visible
            ) {
                Button("FredPlayer Library", systemImage: "internaldrive") {
                    player.playlist.scanCopiedMusic()
                    present(.copiedLibrary)
                }
                Button("Choose from Files", systemImage: "folder") {
                    isImporterPresented = true
                }
                Button("Add Folder", systemImage: "folder.badge.plus") {
                    isFolderImporterPresented = true
                }
                Button("Fred Server", systemImage: "server.rack") {
                    present(.serverLibrary)
                }
                Button("Cancel", role: .cancel) {}
            }
            .overlay {
                if player.playlist.isAddingCopiedMusic || player.isLoadingRemoteTrack || isScanningFolder {
                    ZStack {
                        Color.black.opacity(0.2).ignoresSafeArea()
                        ProgressView(isScanningFolder ? "Scanning folder…"
                            : player.isLoadingRemoteTrack ? "Buffering track…" : "Adding tracks…")
                            .padding()
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
            .alert(
                "Playback Error",
                isPresented: Binding(
                    get: { player.playbackError != nil },
                    set: { if !$0 { player.playbackError = nil } }
                )
            ) {
                Button("OK") { player.playbackError = nil }
            } message: {
                Text(player.playbackError ?? "")
            }
            .alert(
                "Playlist Updated",
                isPresented: Binding(
                    get: { player.playlist.operationMessage != nil },
                    set: { if !$0 { player.playlist.operationMessage = nil } }
                )
            ) {
                Button("OK") { player.playlist.operationMessage = nil }
            } message: {
                Text(player.playlist.operationMessage ?? "")
            }
            .fileImporter(
                isPresented: $isImporterPresented,
                allowedContentTypes: [.audio],
                allowsMultipleSelection: true
            ) { result in
                switch result {
                case .success(let urls):
                    player.playlist.importFiles(urls)
                case .failure(let error):
                    logger.error("Document picker failed: \(error.localizedDescription, privacy: .public)")
                }
            }
            .fileImporter(
                isPresented: $isFolderImporterPresented,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let folder = urls.first { importFolder(folder) }
                case .failure(let error):
                    logger.error("Folder picker failed: \(error.localizedDescription, privacy: .public)")
                }
            }
        }
    }

    private var mainHeader: some View {
        HStack(spacing: 8) {
            Text("FredPlayer")
                .font(.system(size: 28, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            Spacer(minLength: 6)
            headerButton("quote.bubble", label: "Lyrics") {
                present(.lyrics)
            }
            headerButton("list.bullet", label: "What's Next") {
                present(.whatsNext)
            }
            headerButton("gearshape", label: "Settings") {
                present(.playerSettings)
            }
        }
    }

    private func headerButton(
        _ systemName: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 42, height: 42)
                .background(Color.secondary.opacity(0.14))
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private func sheetContent(for sheet: PresentedSheet) -> some View {
        switch sheet {
        case .copiedLibrary:
            CopiedMusicPicker()
        case .serverSettings:
            ServerSettingsView()
        case .serverLibrary:
            ServerLibraryPicker()
        case .askLiam:
            AskLiamView()
        case .playlistManager:
            PlaylistManagerView()
        case .sharedPlaylists:
            SharedPlaylistsView()
        case .playerSettings:
            PlayerSettingsView(
                onManagePlaylists: { transitionFromSettings(to: .playlistManager) },
                onAddMusic: transitionFromSettingsToMusicSource,
                onServerSettings: { transitionFromSettings(to: .serverSettings) },
                onBrowseServer: { transitionFromSettings(to: .serverLibrary) },
                onSharedPlaylists: { transitionFromSettings(to: .sharedPlaylists) },
                onAskLiam: { transitionFromSettings(to: .askLiam) }
            )
        case .lyrics:
            LyricsView()
        case .whatsNext:
            WhatsNextView()
        }
    }

    private func present(_ sheet: PresentedSheet) {
        if sheet.requiresServer && player.serverClient == nil {
            pendingServerSheet = sheet
            presentedSheet = .serverSettings
        } else {
            pendingServerSheet = nil
            presentedSheet = sheet
        }
    }

    private func continuePendingServerDestination() {
        guard let pendingServerSheet else { return }
        self.pendingServerSheet = nil
        guard player.serverClient != nil else { return }

        // Let SwiftUI finish dismissing Server Settings before presenting
        // the destination that originally sent the user there.
        Task { @MainActor in
            await Task.yield()
            presentedSheet = pendingServerSheet
        }
    }

    private func transitionFromSettings(to sheet: PresentedSheet) {
        queuedSheetAfterDismiss = sheet
        presentedSheet = nil
    }

    private func transitionFromSettingsToMusicSource() {
        showMusicSourceAfterDismiss = true
        presentedSheet = nil
    }

    private func handleSheetDismissal() {
        if let queuedSheetAfterDismiss {
            self.queuedSheetAfterDismiss = nil
            Task { @MainActor in
                await Task.yield()
                present(queuedSheetAfterDismiss)
            }
            return
        }
        if showMusicSourceAfterDismiss {
            showMusicSourceAfterDismiss = false
            Task { @MainActor in
                await Task.yield()
                isMusicSourcePresented = true
            }
            return
        }
        continuePendingServerDestination()
    }

    private func importFolder(_ folder: URL) {
        isScanningFolder = true
        Task {
            let urls = await Task.detached(priority: .userInitiated) {
                Self.collectAudioFiles(in: folder)
            }.value
            isScanningFolder = false
            guard !urls.isEmpty else { return }
            player.playlist.importFiles(urls)
        }
    }

    // Recursively finds every audio file under an arbitrary folder tree,
    // matching Android's SAF-based folder import (collectAudioFromTree in
    // MainActivity.java). FileManager's enumerator already walks the
    // whole subtree on its own, so no manual recursion/depth tracking is
    // needed the way SAF's cursor-based API requires.
    private static let importableAudioExtensions: Set<String> = [
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus"
    ]

    private static func collectAudioFiles(in folder: URL) -> [URL] {
        let accessed = folder.startAccessingSecurityScopedResource()
        defer { if accessed { folder.stopAccessingSecurityScopedResource() } }

        guard let enumerator = FileManager.default.enumerator(
            at: folder,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return [] }

        var results: [URL] = []
        for case let url as URL in enumerator {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else { continue }
            if importableAudioExtensions.contains(url.pathExtension.lowercased()) {
                results.append(url)
            }
        }
        return results
    }

}

private struct CopiedMusicPicker: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var selectedIDs: Set<LocalMusicFile.ID> = []

    private var folders: [LocalMusicFolder] {
        Dictionary(grouping: player.playlist.copiedLibrary, by: \.folderID)
            .map { id, files in
                LocalMusicFolder(id: id, name: files[0].folderName, files: files)
            }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(folders) { folder in
                    Section {
                        ForEach(folder.files) { file in
                            Button {
                                toggle(file.id)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(file.displayTitle)
                                        if let subtitle = file.displaySubtitle {
                                            Text(subtitle)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    if selectedIDs.contains(file.id) {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(.tint)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        HStack {
                            Text(folder.name)
                            Spacer()
                            Button(folderIsSelected(folder) ? "Deselect Folder" : "Select Folder") {
                                toggle(folder)
                            }
                            .textCase(nil)
                        }
                    }
                }
            }
            .navigationTitle("FredPlayer Library")
            .safeAreaInset(edge: .bottom) {
                Button("Add \(selectedIDs.count) Tracks") {
                    player.playlist.addCopiedMusic(ids: selectedIDs)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .disabled(selectedIDs.isEmpty)
                .padding()
                .background(.bar)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selectedIDs.count == player.playlist.copiedLibrary.count ? "Deselect All" : "Select All") {
                        if selectedIDs.count == player.playlist.copiedLibrary.count {
                            selectedIDs.removeAll()
                        } else {
                            selectedIDs = Set(player.playlist.copiedLibrary.map(\.id))
                        }
                    }
                }
            }
        }
    }

    private func toggle(_ id: LocalMusicFile.ID) {
        if selectedIDs.contains(id) {
            selectedIDs.remove(id)
        } else {
            selectedIDs.insert(id)
        }
    }

    private func folderIsSelected(_ folder: LocalMusicFolder) -> Bool {
        folder.files.allSatisfy { selectedIDs.contains($0.id) }
    }

    private func toggle(_ folder: LocalMusicFolder) {
        let ids = Set(folder.files.map(\.id))
        if folderIsSelected(folder) {
            selectedIDs.subtract(ids)
        } else {
            selectedIDs.formUnion(ids)
        }
    }
}
