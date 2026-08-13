import SwiftUI

#if os(iOS)
import UIKit
#endif

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

                if showsPhoneTransportControls {
                    phoneTransportControls
                }

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

    private var showsPhoneTransportControls: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .phone
        #else
        false
        #endif
    }

    private var phoneTransportControls: some View {
        HStack(spacing: 32) {
            Button {
                player.previous()
            } label: {
                Image(systemName: "backward.fill")
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Previous")

            Button {
                player.togglePlayback()
            } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .frame(width: 56, height: 56)
            }
            .buttonStyle(.borderedProminent)
            .clipShape(Circle())
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

            Button {
                player.next()
            } label: {
                Image(systemName: "forward.fill")
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Next")
        }
        .buttonStyle(.bordered)
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
                    LazyVStack(alignment: .center, spacing: 26) {
                        ForEach(Array(phrases.enumerated()), id: \.offset) { index, phrase in
                            phraseView(phrase, index: index)
                                .id(index)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 160)
                    .frame(maxWidth: .infinity, alignment: .center)
                }
                .onChange(of: activeIndex) { _, newValue in
                    guard let newValue else { return }
                    withAnimation(.spring(response: 0.48, dampingFraction: 0.82)) {
                        proxy.scrollTo(newValue, anchor: UnitPoint(x: 0.5, y: 0.35))
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func phraseView(_ phrase: LyricsPhrase, index: Int) -> some View {
        let current = activeIndex ?? -1
        let distance = current < 0 ? 1 : index - current
        let isActive = index == activeIndex
        let isPast = distance < 0
        let isAdjacent = abs(distance) == 1

        Group {
            if isActive {
                activeWords(phrase)
            } else {
                Text(phrase.text)
            }
        }
        .font(isActive ? .title2.bold() : .title3)
        .foregroundStyle(
            isActive
                ? Color.primary
                : Color.secondary.opacity(isPast ? 0.42 : 0.68)
        )
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity, alignment: .center)
        .fixedSize(horizontal: false, vertical: true)
        .scaleEffect(
            isActive ? 1.16 : (isAdjacent ? 0.90 : 0.82),
            anchor: .center
        )
        .rotation3DEffect(
            .degrees(isActive ? 0 : (isPast ? -24 : 24)),
            axis: (x: 1, y: 0, z: 0),
            anchor: isPast ? .bottom : .top,
            perspective: 0.72
        )
        .offset(y: isActive ? 0 : (isPast ? -8 : 8))
        .zIndex(isActive ? 2 : 0)
        .animation(
            .spring(response: 0.46, dampingFraction: 0.80),
            value: activeIndex
        )
    }

    // Builds one Text by concatenating per-word segments, each with its own
    // color — SwiftUI preserves per-segment styling across `+` concatenation,
    // which is what makes the progressive karaoke-style fill possible without
    // a custom Layout.
    private func activeWords(_ phrase: LyricsPhrase) -> Text {
        let adjustedTime = compensatedTime(player.currentTime)
        return phrase.words.enumerated().reduce(Text("")) { partial, entry in
            let (index, word) = entry
            // A word's own timestamp marks when Whisper detected it
            // starting, but that has a known tendency to anticipate the
            // word slightly rather than land on its audible onset. Using
            // the NEXT word's start (or the phrase's end for the last
            // word) as the "fully sung" boundary instead removes that
            // early bias.
            let sungBoundary = index + 1 < phrase.words.count ? phrase.words[index + 1].time : phrase.end
            let sung = sungBoundary <= adjustedTime
            let separator = index == 0 ? "" : " "
            return partial + Text(separator + word.text)
                .foregroundStyle(sung ? Color.primary : Color.secondary.opacity(0.5))
        }
    }

    // Same calibrated output latency the visualizer already delays its
    // published frame by (see PlayerController.publishVisualFrame) —
    // without it, lyrics highlight ahead of what's actually audible on
    // routes with real output latency (Bluetooth etc).
    private func compensatedTime(_ time: TimeInterval) -> TimeInterval {
        max(0, time - player.outputLatency)
    }

    private func updateActiveIndex(for time: TimeInterval) {
        let adjustedTime = compensatedTime(time)
        var index: Int?
        for (i, phrase) in phrases.enumerated() {
            if phrase.start <= adjustedTime {
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
