import Foundation

enum RemoteTrackCache {
    private static let manager = FileManager.default

    // Whole-track downloads were used before progressive AVPlayer playback.
    // Clear that obsolete cache once at startup so upgrades reclaim the space.
    static func removeLegacyDownloads() {
        try? manager.removeItem(at: rootDirectory)
    }

    private static var rootDirectory: URL {
        manager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FredPlayerStreamCache", isDirectory: true)
    }
}
