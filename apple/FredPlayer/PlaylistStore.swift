import Foundation
import Combine
import OSLog
import AVFoundation

@MainActor
final class PlaylistStore: ObservableObject {
    @Published private(set) var tracks: [PlaylistTrack] = []
    @Published private(set) var copiedLibrary: [LocalMusicFile] = []
    @Published private(set) var isAddingCopiedMusic = false
    @Published var operationMessage: String?

    private let logger = Logger(subsystem: "com.example.FredPlayer", category: "Playlist")
    private let defaultsKey = "playlist.v1"

    init() {
        restore()
    }

    func importFiles(_ urls: [URL]) {
        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed { url.stopAccessingSecurityScopedResource() }
            }

            do {
                let bookmark = try url.bookmarkData(
                    options: .minimalBookmark,
                    includingResourceValuesForKeys: nil,
                    relativeTo: nil
                )
                guard !tracks.contains(where: { $0.bookmark == bookmark }) else {
                    logger.info("Skipped duplicate import: \(url.lastPathComponent, privacy: .public)")
                    continue
                }
                tracks.append(PlaylistTrack(filename: url.lastPathComponent, bookmark: bookmark))
                logger.info("Imported: \(url.lastPathComponent, privacy: .public)")
            } catch {
                logger.error("Import failed for \(url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
        save()
    }

    func scanCopiedMusic() {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let classicalURL = documentsURL.appendingPathComponent("Classical", isDirectory: true)
        let keys: [URLResourceKey] = [.isRegularFileKey]

        guard let enumerator = FileManager.default.enumerator(
            at: classicalURL,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else {
            logger.error("Copied music folder not found: \(classicalURL.path, privacy: .public)")
            return
        }

        var files: [LocalMusicFile] = []

        for case let url as URL in enumerator {
            guard
                (try? url.resourceValues(forKeys: Set(keys)).isRegularFile) == true
            else { continue }

            do {
                _ = try AVAudioFile(forReading: url)
                let flacMetadata = FLACMetadataReader.read(from: url)
                let metadata = AVURLAsset(url: url).commonMetadata
                files.append(
                    LocalMusicFile(
                        url: url,
                        title: flacMetadata?.title
                            ?? metadataValue(.commonIdentifierTitle, in: metadata),
                        artist: flacMetadata?.artist
                            ?? metadataValue(.commonIdentifierArtist, in: metadata),
                        album: flacMetadata?.album
                            ?? metadataValue(.commonIdentifierAlbumName, in: metadata),
                        trackNumber: flacMetadata?.trackNumber
                    )
                )
            } catch {
                logger.error("Skipped unreadable copied file \(url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }

        copiedLibrary = files.sorted {
            if $0.folderID == $1.folderID {
                if let left = $0.trackNumber, let right = $1.trackNumber, left != right {
                    return left < right
                }
                return $0.displayTitle.localizedStandardCompare($1.displayTitle) == .orderedAscending
            }
            return $0.folderName.localizedStandardCompare($1.folderName) == .orderedAscending
        }
        logger.info("Found \(files.count) copied music files")
    }

    func addCopiedMusic(ids: Set<LocalMusicFile.ID>) {
        isAddingCopiedMusic = true
        Task { @MainActor in
            await Task.yield()
            let count = performAddCopiedMusic(ids: ids)
            isAddingCopiedMusic = false
            operationMessage = count == 0
                ? "The selected tracks are already in the playlist."
                : "Added \(count) tracks to the playlist."
        }
    }

    private func performAddCopiedMusic(ids: Set<LocalMusicFile.ID>) -> Int {
        let existingPaths = Set(tracks.compactMap {
            resolveBookmark(for: $0, logSuccess: false)?.standardizedFileURL.path
        })
        var addedCount = 0

        for file in copiedLibrary where ids.contains(file.id) && !existingPaths.contains(file.id) {
            do {
                let bookmark = try file.url.bookmarkData(
                    options: .minimalBookmark,
                    includingResourceValuesForKeys: nil,
                    relativeTo: nil
                )
                tracks.append(
                    PlaylistTrack(
                        filename: file.url.lastPathComponent,
                        bookmark: bookmark,
                        title: file.title,
                        artist: file.artist,
                        album: file.album
                    )
                )
                addedCount += 1
            } catch {
                logger.error("Could not add \(file.url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
        save()
        return addedCount
    }

    func removeTracks(at offsets: IndexSet) {
        tracks.remove(atOffsets: offsets)
        save()
    }

    func clearPlaylist() {
        tracks.removeAll()
        save()
        logger.info("Playlist cleared")
    }

    func resolvedURL(for track: PlaylistTrack) -> URL? {
        resolveBookmark(for: track, logSuccess: true)
    }

    private func resolveBookmark(for track: PlaylistTrack, logSuccess: Bool) -> URL? {
        var stale = false
        do {
            let url = try URL(
                resolvingBookmarkData: track.bookmark,
                options: .withoutUI,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            if logSuccess {
                logger.info("Restored bookmark for: \(track.filename, privacy: .public), stale: \(stale)")
            }
            if stale { refreshBookmark(for: track, url: url) }
            return url
        } catch {
            logger.error("Bookmark restore failed for \(track.filename, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    private func restore() {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey) else {
            logger.info("No saved playlist found")
            return
        }
        do {
            tracks = try JSONDecoder().decode([PlaylistTrack].self, from: data)
            logger.info("Restored playlist with \(self.tracks.count) tracks")
        } catch {
            logger.error("Playlist restore failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func save() {
        do {
            UserDefaults.standard.set(try JSONEncoder().encode(tracks), forKey: defaultsKey)
        } catch {
            logger.error("Playlist save failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func refreshBookmark(for track: PlaylistTrack, url: URL) {
        do {
            let bookmark = try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
            guard let index = tracks.firstIndex(of: track) else { return }
            tracks[index] = PlaylistTrack(
                id: track.id,
                filename: track.filename,
                bookmark: bookmark,
                title: track.title,
                artist: track.artist,
                album: track.album
            )
            save()
        } catch {
            logger.error("Bookmark refresh failed for \(track.filename, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    private func metadataValue(
        _ identifier: AVMetadataIdentifier,
        in metadata: [AVMetadataItem]
    ) -> String? {
        AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: identifier)
            .first?
            .stringValue
    }
}
