import Foundation

struct PlaylistTrack: Codable, Identifiable, Equatable {
    let id: UUID
    let filename: String
    let bookmark: Data
    let title: String?
    let artist: String?
    let album: String?

    init(
        id: UUID = UUID(),
        filename: String,
        bookmark: Data,
        title: String? = nil,
        artist: String? = nil,
        album: String? = nil
    ) {
        self.id = id
        self.filename = filename
        self.bookmark = bookmark
        self.title = title
        self.artist = artist
        self.album = album
    }

    var displayTitle: String { title?.nonEmpty ?? filename }

    var displaySubtitle: String? {
        [artist?.nonEmpty, album?.nonEmpty]
            .compactMap { $0 }
            .joined(separator: " — ")
            .nonEmpty
    }
}

struct LocalMusicFile: Identifiable {
    let url: URL
    let title: String?
    let artist: String?
    let album: String?
    let trackNumber: Int?

    var id: String { url.standardizedFileURL.path }
    var displayTitle: String { title?.nonEmpty ?? url.lastPathComponent }
    var folderID: String { url.deletingLastPathComponent().standardizedFileURL.path }
    var folderName: String { album?.nonEmpty ?? url.deletingLastPathComponent().lastPathComponent }

    var displaySubtitle: String? {
        [artist?.nonEmpty, album?.nonEmpty]
            .compactMap { $0 }
            .joined(separator: " — ")
            .nonEmpty
    }
}

struct LocalMusicFolder: Identifiable {
    let id: String
    let name: String
    let files: [LocalMusicFile]
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
