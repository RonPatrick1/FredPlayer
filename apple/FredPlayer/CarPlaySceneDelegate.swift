import CarPlay
import Combine
import UIKit

@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var playlistsListTemplate: CPListTemplate?
    private var tracksListTemplate: CPListTemplate?
    private var playlistsObservation: AnyCancellable?
    private var tracksObservation: AnyCancellable?
    private var nowPlayingButtonsObservation: AnyCancellable?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController

        let root = CPListTemplate(
            title: "Playlists",
            sections: [makePlaylistsSection(playlists: PlayerController.shared.playlist.playlists)]
        )
        playlistsListTemplate = root
        interfaceController.setRootTemplate(root, animated: false, completion: nil)

        // Covers both the playlist list (name/track-count/active marker)
        // and, once one has been drilled into, the currently pushed
        // tracks list — mirrors Android Auto's two-level playlists ->
        // tracks browse model instead of always showing just whatever's
        // active.
        playlistsObservation = PlayerController.shared.playlist.$playlists
            .combineLatest(PlayerController.shared.playlist.$activePlaylistID)
            .sink { [weak self] playlists, _ in
                self?.playlistsListTemplate?.updateSections([self?.makePlaylistsSection(playlists: playlists) ?? CPListSection(items: [])])
            }

        tracksObservation = PlayerController.shared.playlist.$tracks
            .sink { [weak self] tracks in
                guard let self, self.tracksListTemplate != nil else { return }
                self.tracksListTemplate?.updateSections([self.makeTracksSection(tracks: tracks)])
            }

        nowPlayingButtonsObservation = PlayerController.shared.$shuffleEnabled
            .combineLatest(PlayerController.shared.$repeatMode)
            .sink { shuffleEnabled, repeatMode in
                CPNowPlayingTemplate.shared.updateNowPlayingButtons([
                    Self.shuffleButton(enabled: shuffleEnabled),
                    Self.repeatButton(mode: repeatMode)
                ])
            }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        playlistsObservation = nil
        tracksObservation = nil
        nowPlayingButtonsObservation = nil
        playlistsListTemplate = nil
        tracksListTemplate = nil
        self.interfaceController = nil
    }

    private static func shuffleButton(enabled: Bool) -> CPNowPlayingImageButton {
        let symbolName = enabled ? "shuffle.circle.fill" : "shuffle"
        let image = UIImage(systemName: symbolName) ?? UIImage(systemName: "shuffle")!
        return CPNowPlayingImageButton(image: image) { _ in
            PlayerController.shared.toggleShuffle()
        }
    }

    private static func repeatButton(mode: RepeatMode) -> CPNowPlayingImageButton {
        let symbolName: String
        switch mode {
        case .off: symbolName = "repeat"
        case .all: symbolName = "repeat.circle.fill"
        case .one: symbolName = "repeat.1.circle.fill"
        }
        let image = UIImage(systemName: symbolName) ?? UIImage(systemName: "repeat")!
        return CPNowPlayingImageButton(image: image) { _ in
            PlayerController.shared.cycleRepeatMode()
        }
    }

    private func makePlaylistsSection(playlists: [MusicPlaylist]) -> CPListSection {
        let activeID = PlayerController.shared.playlist.activePlaylistID
        let items = playlists.map { playlist -> CPListItem in
            let isActive = playlist.id == activeID
            let count = playlist.tracks.count
            let item = CPListItem(
                text: (isActive ? "✓ " : "") + playlist.name,
                detailText: "\(count) song\(count == 1 ? "" : "s")"
            )
            item.accessoryType = .disclosureIndicator
            item.handler = { [weak self] _, completion in
                self?.showTracks(for: playlist)
                completion()
            }
            return item
        }
        return CPListSection(items: items)
    }

    // Switching the active playlist mirrors what the in-app playlist
    // manager does (stop, then PlaylistStore.selectPlaylist) — CarPlay has
    // no concept of browsing a playlist without making it the active one.
    private func showTracks(for playlist: MusicPlaylist) {
        guard let interfaceController else { return }
        if playlist.id != PlayerController.shared.playlist.activePlaylistID {
            PlayerController.shared.stop()
            PlayerController.shared.playlist.selectPlaylist(id: playlist.id)
        }
        let template = CPListTemplate(
            title: playlist.name,
            sections: [makeTracksSection(tracks: PlayerController.shared.playlist.tracks)]
        )
        tracksListTemplate = template
        interfaceController.pushTemplate(template, animated: true, completion: nil)
    }

    private func makeTracksSection(tracks: [PlaylistTrack]) -> CPListSection {
        let items = tracks.map { track -> CPListItem in
            let item = CPListItem(text: track.displayTitle, detailText: track.displaySubtitle)
            item.handler = { [weak self] _, completion in
                self?.play(trackID: track.id)
                completion()
            }
            return item
        }
        return CPListSection(items: items)
    }

    private func play(trackID: PlaylistTrack.ID) {
        PlayerController.shared.play(trackID: trackID)
        guard let interfaceController else { return }
        let nowPlaying = CPNowPlayingTemplate.shared
        if interfaceController.templates.last !== nowPlaying {
            interfaceController.pushTemplate(nowPlaying, animated: true, completion: nil)
        }
    }
}
