import SwiftUI

struct AskLiamView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var pendingTask: Task<Void, Never>?
    @State private var resultMessage: String?
    @State private var isWorking = false
    @State private var workingMessage = ""

    var body: some View {
        NavigationStack {
            Form {
                if let resultMessage {
                    Section("Result") {
                        Text(resultMessage)
                    }
                } else if isWorking {
                    Section {
                        VStack(spacing: 16) {
                            ProgressView()
                                .controlSize(.large)
                            Text(workingMessage)
                                .font(.headline)
                                .multilineTextAlignment(.center)
                            Text("This can take several minutes. Keep this screen open while Liam finishes.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    }
                } else {
                    Section {
                        TextField(
                            "e.g. Make me a playlist of upbeat piano music",
                            text: $input,
                            axis: .vertical
                        )
                        .lineLimit(3...8)
                        .submitLabel(.go)
                        .onSubmit {
                            if !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                submit()
                            }
                        }
                    } header: {
                        Text("What playlist should Liam make?")
                    } footer: {
                        Text("Liam will either create and open a new playlist, or explain why nothing was created.")
                    }
                }
            }
            .navigationTitle("Ask Liam")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        dismiss()
                    }
                    .disabled(isWorking)
                }
            }
            .safeAreaInset(edge: .bottom) {
                bottomAction
            }
        }
        .interactiveDismissDisabled(isWorking)
    }

    @ViewBuilder
    private var bottomAction: some View {
        VStack(spacing: 0) {
            Divider()
            if isWorking {
                Button("Cancel Request", role: .cancel) {
                    pendingTask?.cancel()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                .padding()
            } else if resultMessage != nil {
                Button("Close") {
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .padding()
            } else {
                Button {
                    submit()
                } label: {
                    Label("Create Playlist", systemImage: "paperplane.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .padding()
            }
        }
        .background(.bar)
    }

    private func submit() {
        guard !isWorking else { return }
        guard let client = player.serverClient else {
            resultMessage = "Fred Server is not configured. No playlist was created."
            return
        }
        let prompt = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }
        resultMessage = nil
        workingMessage = "Liam is thinking about your playlist…"
        isWorking = true
        let task = Task {
            do {
                let response = try await client.askLiam(
                    deviceID: player.deviceID,
                    message: prompt
                )
                try Task.checkCancellation()
                workingMessage = "Liam replied. Matching tracks from the Fred Server library…"
                resultMessage = try await handle(response: response, client: client)
            } catch is CancellationError {
                resultMessage = "The request was cancelled. No playlist was created."
            } catch {
                resultMessage = "Liam could not make a playlist: \(error.localizedDescription)\n\nNo playlist was created."
            }
            isWorking = false
            pendingTask = nil
        }
        pendingTask = task
    }

    private func handle(
        response: AskLiamResponse,
        client: FredServerClient
    ) async throws -> String {
        guard let suggestion = response.playlist, !suggestion.tracks.isEmpty else {
            let reply = response.reply.trimmingCharacters(in: .whitespacesAndNewlines)
            return reply.isEmpty
                ? "Liam did not return a playlist. Nothing was created."
                : "\(reply)\n\nNo playlist was created."
        }

        let library = try await client.fetchLibrary()
        let wantedPaths = Set(suggestion.tracks)
        let matchedTracks = library.filter { wantedPaths.contains($0.path) }
        guard !matchedTracks.isEmpty else {
            return "Liam suggested ‘\(suggestion.name)’, but none of its tracks were found in the Fred Server library. No playlist was created."
        }

        let playlistName = uniquePlaylistName(for: suggestion.name)
        player.stop()
        guard player.playlist.createPlaylist(name: playlistName) else {
            return "FredPlayer could not create ‘\(playlistName)’. No playlist was created."
        }
        let added = player.playlist.addServerTracks(matchedTracks)
        return "Created ‘\(playlistName)’ with \(added) tracks and switched to it."
    }

    private func uniquePlaylistName(for suggestedName: String) -> String {
        let trimmed = suggestedName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = trimmed.isEmpty ? "Liam Playlist" : trimmed
        let existing = Set(player.playlist.playlists.map { $0.name.lowercased() })
        if !existing.contains(base.lowercased()) { return base }
        for suffix in 2..<1_000 {
            let candidate = "\(base) (\(suffix))"
            if !existing.contains(candidate.lowercased()) { return candidate }
        }
        return "\(base) \(UUID().uuidString.prefix(8))"
    }
}
