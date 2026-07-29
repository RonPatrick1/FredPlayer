import SwiftUI

struct ServerLibraryPicker: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var tracks: [ServerLibraryTrack] = []
    @State private var selected: Set<String> = []
    @State private var currentFolderPath = ""
    @State private var searchText = ""
    @State private var isLoading = true
    @State private var errorMessage: String?

    private struct FolderSummary: Identifiable {
        let path: String
        let name: String
        let count: Int
        var id: String { path }
    }

    private var currentFolderName: String {
        currentFolderPath.isEmpty
            ? "Server Library"
            : (currentFolderPath as NSString).lastPathComponent
    }

    private var tracksInCurrentFolder: [ServerLibraryTrack] {
        tracks.filter { isInCurrentFolder($0) }
    }

    private var childFolders: [FolderSummary] {
        let prefix = currentFolderPath.isEmpty ? "" : currentFolderPath + "/"
        var counts: [String: Int] = [:]
        for track in tracksInCurrentFolder {
            let trackFolder = folderPath(for: track)
            guard trackFolder != currentFolderPath,
                  trackFolder.hasPrefix(prefix) else { continue }
            let remainder = String(trackFolder.dropFirst(prefix.count))
            guard let childName = remainder.split(separator: "/").first else { continue }
            let childPath = prefix + String(childName)
            counts[childPath, default: 0] += 1
        }
        return counts.map {
            FolderSummary(
                path: $0.key,
                name: ($0.key as NSString).lastPathComponent,
                count: $0.value
            )
        }
        .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private var visibleTracks: [ServerLibraryTrack] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidates = query.isEmpty
            ? tracks.filter { folderPath(for: $0) == currentFolderPath }
            : tracksInCurrentFolder.filter { matchesSearch($0, query: query) }
        return candidates.sorted {
            displayTitle($0).localizedStandardCompare(displayTitle($1)) == .orderedAscending
        }
    }

    private var currentFolderIsSelected: Bool {
        !tracksInCurrentFolder.isEmpty &&
            tracksInCurrentFolder.allSatisfy { selected.contains($0.path) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView("Loading library…")
                } else if let errorMessage {
                    ContentUnavailableView(
                        "Couldn’t Load Library",
                        systemImage: "exclamationmark.triangle",
                        description: Text(errorMessage)
                    )
                } else if tracks.isEmpty {
                    ContentUnavailableView(
                        "Server Library Is Empty",
                        systemImage: "music.note"
                    )
                } else {
                    libraryList
                }
            }
            .navigationTitle(currentFolderName)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: $searchText,
                prompt: "Title, artist, album, or folder"
            )
            .safeAreaInset(edge: .bottom) {
                addControls
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(currentFolderPath.isEmpty ? "Cancel" : "Back") {
                        if currentFolderPath.isEmpty {
                            dismiss()
                        } else {
                            currentFolderPath = parentFolder(of: currentFolderPath)
                            searchText = ""
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(currentFolderIsSelected ? "Deselect Folder" : "Select Folder") {
                        toggleCurrentFolderSelection()
                    }
                    .disabled(tracksInCurrentFolder.isEmpty)
                }
            }
            .task { await load() }
        }
    }

    private var libraryList: some View {
        List {
            if searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               !childFolders.isEmpty {
                Section("Folders") {
                    ForEach(childFolders) { folder in
                        HStack {
                            Button {
                                currentFolderPath = folder.path
                                searchText = ""
                            } label: {
                                HStack {
                                    Image(systemName: "folder")
                                    VStack(alignment: .leading) {
                                        Text(folder.name)
                                        Text("\(folder.count) \(folder.count == 1 ? "track" : "tracks")")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)

                            Button {
                                toggleFolderSelection(folder.path)
                            } label: {
                                Image(systemName: folderIsSelected(folder.path)
                                      ? "checkmark.circle.fill"
                                      : "circle")
                                    .font(.title3)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(folderIsSelected(folder.path)
                                                ? "Deselect folder"
                                                : "Select folder")
                        }
                    }
                }
            }

            Section(searchText.isEmpty ? "Tracks" : "Search Results") {
                if visibleTracks.isEmpty {
                    Text(searchText.isEmpty
                         ? "No tracks are stored directly in this folder."
                         : "No matching tracks in this folder.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(visibleTracks) { track in
                        Button { toggle(track.path) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(displayTitle(track))
                                    let subtitle = trackSubtitle(track)
                                    if !subtitle.isEmpty {
                                        Text(subtitle)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Image(systemName: selected.contains(track.path)
                                      ? "checkmark.circle.fill"
                                      : "circle")
                                    .foregroundStyle(selected.contains(track.path)
                                                     ? Color.accentColor
                                                     : Color.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var addControls: some View {
        VStack(spacing: 8) {
            Button(currentFolderPath.isEmpty
                   ? "Add All Music (\(tracksInCurrentFolder.count))"
                   : "Add Entire Folder (\(tracksInCurrentFolder.count))") {
                add(tracksInCurrentFolder)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .disabled(tracksInCurrentFolder.isEmpty)

            Button("Add Selected Tracks (\(selected.count))") {
                add(tracks.filter { selected.contains($0.path) })
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .disabled(selected.isEmpty)
        }
        .padding()
        .background(.bar)
    }

    private func folderPath(for track: ServerLibraryTrack) -> String {
        (track.path as NSString).deletingLastPathComponent
    }

    private func isInCurrentFolder(_ track: ServerLibraryTrack) -> Bool {
        currentFolderPath.isEmpty ||
            track.path.hasPrefix(currentFolderPath + "/")
    }

    private func parentFolder(of folder: String) -> String {
        (folder as NSString).deletingLastPathComponent
    }

    private func displayTitle(_ track: ServerLibraryTrack) -> String {
        if let title = track.title, !title.isEmpty { return title }
        return (track.path as NSString).lastPathComponent
    }

    private func trackSubtitle(_ track: ServerLibraryTrack) -> String {
        [track.artist, track.album]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " — ")
    }

    private func matchesSearch(_ track: ServerLibraryTrack, query: String) -> Bool {
        [track.path, track.title, track.artist, track.album]
            .compactMap { $0 }
            .contains { $0.localizedCaseInsensitiveContains(query) }
    }

    private func toggle(_ path: String) {
        if selected.contains(path) { selected.remove(path) } else { selected.insert(path) }
    }

    private func folderTracks(_ folder: String) -> [ServerLibraryTrack] {
        tracks.filter { $0.path.hasPrefix(folder + "/") }
    }

    private func folderIsSelected(_ folder: String) -> Bool {
        let contents = folderTracks(folder)
        return !contents.isEmpty && contents.allSatisfy { selected.contains($0.path) }
    }

    private func toggleFolderSelection(_ folder: String) {
        let contents = folderTracks(folder)
        let paths = Set(contents.map(\.path))
        if paths.isSubset(of: selected) {
            selected.subtract(paths)
        } else {
            selected.formUnion(paths)
        }
    }

    private func toggleCurrentFolderSelection() {
        let paths = Set(tracksInCurrentFolder.map(\.path))
        if paths.isSubset(of: selected) {
            selected.subtract(paths)
        } else {
            selected.formUnion(paths)
        }
    }

    private func add(_ serverTracks: [ServerLibraryTrack]) {
        let count = player.playlist.addServerTracks(serverTracks)
        player.playlist.operationMessage = count == 0
            ? "Those tracks are already in the playlist."
            : "Added \(count) tracks to the playlist."
        dismiss()
    }

    private func load() async {
        guard let client = player.serverClient else {
            errorMessage = FredServerError.invalidBaseURL.localizedDescription
            isLoading = false
            return
        }
        do {
            tracks = try await client.fetchLibrary()
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}
