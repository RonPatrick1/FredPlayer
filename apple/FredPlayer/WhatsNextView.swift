import SwiftUI

struct WhatsNextView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("What's Next")
                .navigationBarTitleDisplayMode(.inline)
                .searchable(text: $searchText, prompt: "Search history and up next")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                    }
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        if player.playlist.tracks.isEmpty {
            ContentUnavailableView(
                "Nothing Queued",
                systemImage: "list.bullet",
                description: Text("Add some tracks to see what's next.")
            )
        } else {
            // Filters History/Up Next by title+artist. Now Playing always
            // stays visible regardless of the query — it's a single
            // status row, not part of the searchable list.
            let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
            List {
                let recent = filtered(player.recentTrackIDs, query: query)
                if !recent.isEmpty {
                    Section("History") {
                        ForEach(recent, id: \.self) { trackID in
                            row(for: trackID, current: false)
                        }
                    }
                }

                Section("Now Playing") {
                    if let current = player.currentTrack {
                        row(for: current.id, current: true)
                    } else {
                        Text("Nothing playing.")
                            .foregroundStyle(.secondary)
                    }
                }

                let upcoming = filtered(player.upNextTrackIDs, query: query)
                Section("Up Next") {
                    if upcoming.isEmpty {
                        Text(query.isEmpty ? "End of playlist." : "No matches.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(upcoming, id: \.self) { trackID in
                            row(for: trackID, current: false)
                        }
                    }
                }
            }
        }
    }

    private func filtered(_ ids: [PlaylistTrack.ID], query: String) -> [PlaylistTrack.ID] {
        guard !query.isEmpty else { return ids }
        return ids.filter { matches($0, query: query) }
    }

    private func matches(_ trackID: PlaylistTrack.ID, query: String) -> Bool {
        guard let track = player.playlist.tracks.first(where: { $0.id == trackID }) else { return false }
        let haystack = track.displayTitle + "\n" + (track.displaySubtitle ?? "")
        return haystack.range(of: query, options: .caseInsensitive) != nil
    }

    @ViewBuilder
    private func row(for trackID: PlaylistTrack.ID, current: Bool) -> some View {
        if let track = player.playlist.tracks.first(where: { $0.id == trackID }) {
            Button {
                guard !current else { return }
                player.play(trackID: trackID)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.displayTitle)
                        .foregroundStyle(current ? Color.accentColor : Color.primary)
                    if let subtitle = track.displaySubtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(current)
        }
    }
}
