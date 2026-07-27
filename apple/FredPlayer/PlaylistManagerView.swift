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
                Text("The playlist will be deleted. Its audio files will not be removed.")
            }
        }
    }
}
