import SwiftUI

struct WhatsNextView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("What's Next")
                .navigationBarTitleDisplayMode(.inline)
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
            List {
                let recent = player.recentTrackIDs
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

                let upcoming = player.upNextTrackIDs
                Section("Up Next") {
                    if upcoming.isEmpty {
                        Text("End of playlist.")
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
