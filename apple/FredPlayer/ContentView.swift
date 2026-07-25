import SwiftUI
import UniformTypeIdentifiers
import OSLog

struct ContentView: View {
    @EnvironmentObject private var player: PlayerController
    @State private var isImporterPresented = false
    @State private var isCopiedLibraryPresented = false
    @State private var isClearConfirmationPresented = false
    private let logger = Logger(subsystem: "com.example.FredPlayer", category: "Import")

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                playlistContent
                copiedLibraryButton
                importButton
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                PlayerPanel()
                    .environmentObject(player)
            }
            .sheet(isPresented: $isCopiedLibraryPresented) {
                CopiedMusicPicker()
                    .environmentObject(player)
            }
            .navigationTitle("FredPlayer")
            .toolbar {
                if !player.playlist.tracks.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("Clear Playlist", role: .destructive) {
                                isClearConfirmationPresented = true
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
            }
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
            .overlay {
                if player.playlist.isAddingCopiedMusic {
                    ZStack {
                        Color.black.opacity(0.2).ignoresSafeArea()
                        ProgressView("Adding tracks…")
                            .padding()
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    }
                }
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
        }
    }

    @ViewBuilder
    private var playlistContent: some View {
        if player.playlist.tracks.isEmpty {
            ContentUnavailableView(
                "No Music Yet",
                systemImage: "music.note.list",
                description: Text("Import MP3 or FLAC files to begin.")
            )
        } else {
            List {
                ForEach(player.playlist.tracks) { track in
                    Button {
                        player.play(trackID: track.id)
                    } label: {
                        HStack {
                            Image(systemName: player.currentTrackID == track.id ? "speaker.wave.2.fill" : "music.note")
                                .foregroundStyle(player.currentTrackID == track.id ? Color.accentColor : Color.secondary)
                            VStack(alignment: .leading) {
                                Text(track.displayTitle)
                                    .lineLimit(1)
                                if let subtitle = track.displaySubtitle {
                                    Text(subtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                .onDelete(perform: player.playlist.removeTracks)
            }
        }
    }

    private var importButton: some View {
        Button("Import Audio Files", systemImage: "plus") {
            isImporterPresented = true
        }
        .buttonStyle(.borderedProminent)
        .padding(.bottom)
    }

    private var copiedLibraryButton: some View {
        Button("Browse Copied Music", systemImage: "internaldrive") {
            player.playlist.scanCopiedMusic()
            isCopiedLibraryPresented = true
        }
        .buttonStyle(.bordered)
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
            .navigationTitle("Copied Music")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Button(selectedIDs.count == player.playlist.copiedLibrary.count ? "Deselect All" : "Select All") {
                        if selectedIDs.count == player.playlist.copiedLibrary.count {
                            selectedIDs.removeAll()
                        } else {
                            selectedIDs = Set(player.playlist.copiedLibrary.map(\.id))
                        }
                    }
                    Button("Add \(selectedIDs.count)") {
                        player.playlist.addCopiedMusic(ids: selectedIDs)
                        dismiss()
                    }
                    .disabled(selectedIDs.isEmpty)
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
