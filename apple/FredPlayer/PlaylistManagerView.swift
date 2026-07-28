import SwiftUI

struct PlaylistManagerView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var isCreatingPlaylist = false
    @State private var newPlaylistName = ""
    @State private var creationError: String?
    @State private var playlistToRename: MusicPlaylist?
    @State private var renameText = ""
    @State private var playlistToDelete: MusicPlaylist?

    var body: some View {
        NavigationStack {
            List {
                ForEach(player.playlist.playlists) { playlist in
                    HStack {
                        Button {
                            player.stop()
                            player.playlist.selectPlaylist(id: playlist.id)
                            dismiss()
                        } label: {
                            HStack {
                            VStack(alignment: .leading) {
                                Text(playlist.name)
                                Text("\(playlist.tracks.count) tracks")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if playlist.id == player.playlist.activePlaylistID {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Menu {
                            Button("Rename", systemImage: "pencil") {
                                renameText = playlist.name
                                playlistToRename = playlist
                            }
                            Button("Delete", systemImage: "trash", role: .destructive) {
                                playlistToDelete = playlist
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .imageScale(.large)
                        }
                    }
                }
                .onDelete { offsets in
                    let deletesActive = offsets.contains {
                        player.playlist.playlists[$0].id == player.playlist.activePlaylistID
                    }
                    if deletesActive { player.stop() }
                    player.playlist.deletePlaylists(at: offsets)
                }
            }
            .navigationTitle("Playlists")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("New Playlist", systemImage: "plus") {
                        newPlaylistName = ""
                        creationError = nil
                        isCreatingPlaylist = true
                    }
                }
            }
            .alert("New Playlist", isPresented: $isCreatingPlaylist) {
                TextField("Playlist Name", text: $newPlaylistName)
                Button("Cancel", role: .cancel) {}
                Button("Create") {
                    if !player.playlist.createPlaylist(name: newPlaylistName) {
                        creationError = "Enter a unique playlist name."
                    }
                }
            } message: {
                Text("Create an empty playlist and switch to it.")
            }
            .alert(
                "Couldn’t Create Playlist",
                isPresented: Binding(
                    get: { creationError != nil },
                    set: { if !$0 { creationError = nil } }
                )
            ) {
                Button("OK") { creationError = nil }
            } message: {
                Text(creationError ?? "")
            }
            .alert(
                "Rename Playlist",
                isPresented: Binding(
                    get: { playlistToRename != nil },
                    set: { if !$0 { playlistToRename = nil } }
                )
            ) {
                TextField("Playlist Name", text: $renameText)
                Button("Cancel", role: .cancel) { playlistToRename = nil }
                Button("Rename") {
                    guard let playlist = playlistToRename else { return }
                    if !player.playlist.renamePlaylist(id: playlist.id, name: renameText) {
                        creationError = "Enter a unique playlist name."
                    }
                    playlistToRename = nil
                }
            }
            .confirmationDialog(
                "Delete ‘\(playlistToDelete?.name ?? "")’?",
                isPresented: Binding(
                    get: { playlistToDelete != nil },
                    set: { if !$0 { playlistToDelete = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("Delete Playlist", role: .destructive) {
                    guard let playlist = playlistToDelete else { return }
                    if playlist.id == player.playlist.activePlaylistID { player.stop() }
                    player.playlist.deletePlaylist(id: playlist.id)
                    playlistToDelete = nil
                }
                Button("Cancel", role: .cancel) { playlistToDelete = nil }
            } message: {
                Text("The playlist will be deleted from this device. Its audio files and any shared server copy will not be removed.")
            }
        }
    }
}

struct SharedPlaylistsView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var summaries: [SharedPlaylistSummary] = []
    @State private var isLoading = true
    @State private var isWorking = false
    @State private var message: String?
    @State private var confirmingUpdate = false

    private var currentNameExists: Bool {
        summaries.contains {
            $0.name.caseInsensitiveCompare(player.playlist.activePlaylistName) == .orderedSame
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        requestShare()
                    } label: {
                        Label("Share Current Playlist", systemImage: "square.and.arrow.up")
                    }
                    .disabled(isWorking || player.playlist.tracks.isEmpty)
                } footer: {
                    Text("Sharing publishes a server copy. Deleting a playlist from a device never deletes that shared copy.")
                }

                Section("On Fred Server") {
                    if isLoading {
                        ProgressView("Loading shared playlists…")
                    } else if summaries.isEmpty {
                        Text("No playlists have been shared yet.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(summaries) { summary in
                            Button {
                                download(summary)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(summary.name)
                                        Text("\(summary.count) \(summary.count == 1 ? "track" : "tracks")")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "arrow.down.circle")
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(isWorking)
                        }
                    }
                }

                if let message {
                    Section { Text(message).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("Shared Playlists")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task { await load() }
            .confirmationDialog(
                "Update ‘\(player.playlist.activePlaylistName)’ on the server?",
                isPresented: $confirmingUpdate,
                titleVisibility: .visible
            ) {
                Button("Update Shared Copy") { shareCurrent() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This replaces the existing shared copy with the current playlist.")
            }
        }
    }

    private func requestShare() {
        guard player.playlist.activeServerPaths != nil else {
            message = "Every song must come from this Fred Server. Local files and songs from another server cannot be shared with other devices."
            return
        }
        if currentNameExists {
            confirmingUpdate = true
        } else {
            shareCurrent()
        }
    }

    private func shareCurrent() {
        guard
            let client = player.serverClient,
            let paths = player.playlist.activeServerPaths
        else { return }
        let name = player.playlist.activePlaylistName
        isWorking = true
        message = "Sharing \(name)…"
        Task {
            do {
                try await client.sharePlaylist(name: name, tracks: paths)
                message = "Shared ‘\(name)’. Other devices can now download it."
                await load(showProgress: false)
            } catch {
                message = "Couldn’t share playlist: \(error.localizedDescription)"
            }
            isWorking = false
        }
    }

    private func download(_ summary: SharedPlaylistSummary) {
        guard let client = player.serverClient else { return }
        isWorking = true
        message = "Downloading \(summary.name)…"
        Task {
            do {
                async let playlistRequest = client.fetchSharedPlaylist(name: summary.name)
                async let libraryRequest = client.fetchLibrary()
                let (shared, library) = try await (playlistRequest, libraryRequest)
                let byPath = Dictionary(uniqueKeysWithValues: library.map { ($0.path, $0) })
                let tracks = shared.tracks.compactMap { byPath[$0] }
                guard tracks.count == shared.tracks.count, !tracks.isEmpty else {
                    message = "The server copy contains songs that are no longer in its library."
                    isWorking = false
                    return
                }
                player.stop()
                let localName = player.playlist.installSharedPlaylist(name: shared.name, serverTracks: tracks)
                message = "Saved ‘\(localName)’ on this device. Local changes and deletion won’t affect the server copy."
            } catch {
                message = "Couldn’t download playlist: \(error.localizedDescription)"
            }
            isWorking = false
        }
    }

    private func load(showProgress: Bool = true) async {
        guard let client = player.serverClient else {
            message = FredServerError.invalidBaseURL.localizedDescription
            isLoading = false
            return
        }
        if showProgress { isLoading = true }
        do {
            summaries = try await client.fetchSharedPlaylists()
        } catch {
            message = "Couldn’t load shared playlists: \(error.localizedDescription)"
        }
        isLoading = false
    }
}
