import CarPlay
import Combine
import UIKit

@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var listTemplate: CPListTemplate?
    private var tracksObservation: AnyCancellable?
    private var shuffleObservation: AnyCancellable?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController

        let template = CPListTemplate(
            title: "Playlist",
            sections: [makeSection(tracks: PlayerController.shared.playlist.tracks)]
        )
        listTemplate = template
        interfaceController.setRootTemplate(template, animated: false, completion: nil)

        tracksObservation = PlayerController.shared.playlist.$tracks
            .sink { [weak self] tracks in
                self?.listTemplate?.updateSections([self?.makeSection(tracks: tracks) ?? CPListSection(items: [])])
            }

        shuffleObservation = PlayerController.shared.$shuffleEnabled
            .sink { shuffleEnabled in
                CPNowPlayingTemplate.shared.updateNowPlayingButtons([Self.shuffleButton(enabled: shuffleEnabled)])
            }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        tracksObservation = nil
        shuffleObservation = nil
        listTemplate = nil
        self.interfaceController = nil
    }

    private static func shuffleButton(enabled: Bool) -> CPNowPlayingImageButton {
        let symbolName = enabled ? "shuffle.circle.fill" : "shuffle"
        let image = UIImage(systemName: symbolName) ?? UIImage(systemName: "shuffle")!
        return CPNowPlayingImageButton(image: image) { _ in
            PlayerController.shared.toggleShuffle()
        }
    }

    private func makeSection(tracks: [PlaylistTrack]) -> CPListSection {
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
