import CryptoKit
import Foundation

enum RemoteTrackCache {
    private static let manager = FileManager.default

    static func cachedFileURL(for serverPath: String) -> URL? {
        let url = fileURL(for: serverPath)
        guard manager.fileExists(atPath: url.path) else { return nil }
        try? manager.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return url
    }

    static func download(serverPath: String, client: FredServerClient) async throws -> URL {
        if let cached = cachedFileURL(for: serverPath) { return cached }
        let temporaryURL = try await client.downloadTrack(serverPath: serverPath)
        let destination = fileURL(for: serverPath)
        try manager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        if manager.fileExists(atPath: destination.path) { try manager.removeItem(at: destination) }
        try manager.moveItem(at: temporaryURL, to: destination)
        prune(maxBytes: 2_000_000_000)
        return destination
    }

    static func status() -> (files: Int, bytes: Int64) {
        let files = cacheFiles()
        return (files.count, files.reduce(0) { $0 + fileSize($1) })
    }

    static func prune(maxBytes: Int64) {
        var files = cacheFiles().sorted { modified($0) < modified($1) }
        var bytes = files.reduce(Int64(0)) { $0 + fileSize($1) }
        while bytes > maxBytes, let oldest = files.first {
            bytes -= fileSize(oldest)
            try? manager.removeItem(at: oldest)
            files.removeFirst()
        }
    }

    private static var rootDirectory: URL {
        manager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FredPlayerStreamCache", isDirectory: true)
    }

    private static func fileURL(for path: String) -> URL {
        let key = SHA256.hash(data: Data(path.utf8)).map { String(format: "%02x", $0) }.joined()
        let ext = (path as NSString).pathExtension
        return rootDirectory.appendingPathComponent(ext.isEmpty ? key : "\(key).\(ext)")
    }

    private static func cacheFiles() -> [URL] {
        (try? manager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []
    }

    private static func fileSize(_ url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    private static func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }
}
