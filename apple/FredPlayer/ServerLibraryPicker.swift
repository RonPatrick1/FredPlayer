import SwiftUI

struct ServerLibraryPicker: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var tracks: [ServerLibraryTrack] = []
    @State private var selected: Set<String> = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    private var folders: [(name: String, tracks: [ServerLibraryTrack])] {
        Dictionary(grouping: tracks) { ($0.path as NSString).deletingLastPathComponent }
            .map { (name: $0.key.isEmpty ? "Music" : $0.key, tracks: $0.value) }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView("Loading library…")
                } else if let errorMessage {
                    ContentUnavailableView("Couldn’t Load Library", systemImage: "exclamationmark.triangle", description: Text(errorMessage))
                } else {
                    List {
                        ForEach(folders, id: \.name) { folder in
                            Section {
                                ForEach(folder.tracks) { track in
                                    Button { toggle(track.path) } label: {
                                        HStack {
                                            VStack(alignment: .leading) {
                                                Text(track.title?.isEmpty == false ? track.title! : (track.path as NSString).lastPathComponent)
                                                let subtitle = [track.artist, track.album].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " — ")
                                                if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
                                            }
                                            Spacer()
                                            if selected.contains(track.path) { Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint) }
                                        }
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
                }
            }
            .navigationTitle("Server Library")
            .safeAreaInset(edge: .bottom) {
                Button("Add \(selected.count) Tracks") {
                    addSelectedTracks()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .disabled(selected.isEmpty)
                .padding()
                .background(.bar)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selected.count == tracks.count && !tracks.isEmpty ? "Deselect All" : "Select All") {
                        if selected.count == tracks.count {
                            selected.removeAll()
                        } else {
                            selected = Set(tracks.map(\.path))
                        }
                    }
                    .disabled(tracks.isEmpty)
                }
            }
            .task { await load() }
        }
    }

    private func toggle(_ path: String) {
        if selected.contains(path) { selected.remove(path) } else { selected.insert(path) }
    }

    private func folderIsSelected(_ folder: (name: String, tracks: [ServerLibraryTrack])) -> Bool {
        folder.tracks.allSatisfy { selected.contains($0.path) }
    }

    private func toggle(_ folder: (name: String, tracks: [ServerLibraryTrack])) {
        let paths = Set(folder.tracks.map(\.path))
        if folderIsSelected(folder) {
            selected.subtract(paths)
        } else {
            selected.formUnion(paths)
        }
    }

    private func addSelectedTracks() {
        let count = player.playlist.addServerTracks(tracks.filter { selected.contains($0.path) })
        player.playlist.operationMessage = count == 0
            ? "The selected tracks are already in the playlist."
            : "Added \(count) tracks to the playlist."
        dismiss()
    }

    private func load() async {
        guard let client = player.serverClient else {
            errorMessage = FredServerError.invalidBaseURL.localizedDescription
            isLoading = false
            return
        }
        do { tracks = try await client.fetchLibrary() } catch { errorMessage = error.localizedDescription }
        isLoading = false
    }
}
