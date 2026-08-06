import SwiftUI

struct LyricsView: View {
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @State private var phrases: [LyricsPhrase] = []
    @State private var isLoading = true
    @State private var activeIndex: Int?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Lyrics")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                    }
                }
        }
        .task(id: player.currentTrack?.id) {
            await loadLyrics()
        }
        .onChange(of: player.currentTime) { _, newValue in
            updateActiveIndex(for: newValue)
        }
    }

    @ViewBuilder
    private var content: some View {
        if let track = player.currentTrack {
            VStack(spacing: 16) {
                VStack(spacing: 2) {
                    Text(track.displayTitle).font(.headline)
                    if let subtitle = track.displaySubtitle {
                        Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .padding(.top, 4)

                lyricsBody
            }
        } else {
            ContentUnavailableView(
                "Nothing Playing",
                systemImage: "music.note",
                description: Text("Play a track to see its lyrics.")
            )
        }
    }

    @ViewBuilder
    private var lyricsBody: some View {
        if isLoading {
            Spacer()
            ProgressView("Loading lyrics…")
            Spacer()
        } else if phrases.isEmpty {
            Spacer()
            ContentUnavailableView(
                "No Lyrics Available",
                systemImage: "quote.bubble",
                description: Text("This track doesn't have synced lyrics yet.")
            )
            Spacer()
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 26) {
                        ForEach(Array(phrases.enumerated()), id: \.offset) { index, phrase in
                            phraseView(phrase, index: index)
                                .id(index)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 160)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: activeIndex) { _, newValue in
                    guard let newValue else { return }
                    withAnimation(.easeInOut(duration: 0.35)) {
                        proxy.scrollTo(newValue, anchor: UnitPoint(x: 0.5, y: 0.35))
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func phraseView(_ phrase: LyricsPhrase, index: Int) -> some View {
        let isActive = index == activeIndex
        let isPast = (activeIndex ?? -1) > index
        Group {
            if isActive {
                activeWords(phrase)
            } else {
                Text(phrase.text)
            }
        }
        .font(isActive ? .title2.bold() : .title3)
        .foregroundStyle(isActive ? Color.primary : Color.secondary.opacity(isPast ? 0.45 : 0.75))
        .scaleEffect(isActive ? 1 : 0.97, anchor: .leading)
        .animation(.easeInOut(duration: 0.25), value: isActive)
    }

    // Builds one Text by concatenating per-word segments, each with its own
    // color — SwiftUI preserves per-segment styling across `+` concatenation,
    // which is what makes the progressive karaoke-style fill possible without
    // a custom Layout.
    private func activeWords(_ phrase: LyricsPhrase) -> Text {
        phrase.words.enumerated().reduce(Text("")) { partial, entry in
            let (index, word) = entry
            let sung = word.time <= player.currentTime
            let separator = index == 0 ? "" : " "
            return partial + Text(separator + word.text)
                .foregroundStyle(sung ? Color.primary : Color.secondary.opacity(0.5))
        }
    }

    private func updateActiveIndex(for time: TimeInterval) {
        var index: Int?
        for (i, phrase) in phrases.enumerated() {
            if phrase.start <= time {
                index = i
            } else {
                break
            }
        }
        if index != activeIndex {
            activeIndex = index
        }
    }

    private func loadLyrics() async {
        activeIndex = nil
        guard let track = player.currentTrack else {
            phrases = []
            isLoading = false
            return
        }
        isLoading = true
        defer { isLoading = false }
        guard let serverPath = track.serverPath, let client = player.serverClient else {
            phrases = []
            return
        }
        phrases = await client.fetchLyrics(serverPath: serverPath) ?? []
        updateActiveIndex(for: player.currentTime)
    }
}
