import Foundation

struct ServerLibraryTrack: Codable, Identifiable, Hashable {
    let path: String
    let title: String?
    let artist: String?
    let album: String?
    let genre: String?

    var id: String { path }
}

struct TrackProfile: Codable {
    let rms: Float
    let peak: Float
}

struct LyricsWord: Codable, Identifiable {
    let time: Double
    let text: String

    var id: Double { time }
}

struct LyricsPhrase: Codable, Identifiable {
    let start: Double
    let end: Double
    let text: String
    let words: [LyricsWord]

    var id: Double { start }
}

private struct LyricsSidecar: Codable {
    let version: String?
    let language: String?
    let sections: [String: [LyricsPhrase]]
}

struct AskLiamPlaylist: Codable {
    let name: String
    let tracks: [String]
}

struct AskLiamResponse: Codable {
    let reply: String
    let playlist: AskLiamPlaylist?
}

struct SharedPlaylistSummary: Codable, Identifiable, Hashable {
    let name: String
    let count: Int
    let shared: Bool?
    let updatedAt: String?

    var id: String { name }
}

struct SharedPlaylistDocument: Codable {
    let name: String
    let tracks: [String]
    let shared: Bool?
    let updatedAt: String?
}

enum FredServerError: LocalizedError {
    case invalidBaseURL
    case invalidResponse
    case httpStatus(Int, String?)

    var errorDescription: String? {
        switch self {
        case .invalidBaseURL: "The server URL is invalid."
        case .invalidResponse: "The server returned an invalid response."
        case .httpStatus(let status, let detail):
            if let detail, !detail.isEmpty {
                "The server returned HTTP \(status): \(detail)"
            } else {
                "The server returned HTTP \(status)."
            }
        }
    }
}

struct FredServerClient {
    let baseURL: URL
    let token: String

    func fetchLibrary() async throws -> [ServerLibraryTrack] {
        let (data, response) = try await URLSession.shared.data(for: request(path: "api/library"))
        try validate(response)
        return try JSONDecoder().decode([ServerLibraryTrack].self, from: data)
    }

    func rescanLibrary() async throws -> Int {
        var request = request(path: "api/rescan")
        request.httpMethod = "POST"
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response)
        return try JSONDecoder().decode(RescanResponse.self, from: data).count
    }

    private struct RescanResponse: Decodable {
        let count: Int
    }

    func fetchSharedPlaylists() async throws -> [SharedPlaylistSummary] {
        let (data, response) = try await URLSession.shared.data(for: request(path: "api/playlists"))
        try validate(response, errorData: data)
        return try JSONDecoder().decode([SharedPlaylistSummary].self, from: data)
    }

    func fetchSharedPlaylist(name: String) async throws -> SharedPlaylistDocument {
        let (data, response) = try await URLSession.shared.data(
            for: request(path: "api/playlists/\(encodedPath(name))")
        )
        try validate(response, errorData: data)
        return try JSONDecoder().decode(SharedPlaylistDocument.self, from: data)
    }

    func sharePlaylist(name: String, tracks: [String]) async throws {
        var request = request(path: "api/playlists")
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(SharePlaylistRequest(name: name, tracks: tracks))
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, errorData: data)
    }

    private struct SharePlaylistRequest: Encodable {
        let name: String
        let tracks: [String]
    }

    func streamingURL(serverPath: String) async throws -> URL {
        var request = request(path: "api/stream-ticket")
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(StreamTicketRequest(path: serverPath))
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, errorData: data)
        let ticket = try JSONDecoder().decode(StreamTicketResponse.self, from: data)
        var components = URLComponents(
            url: endpoint(ticket.path),
            resolvingAgainstBaseURL: false
        )!
        components.queryItems = [
            URLQueryItem(name: "expires", value: String(ticket.expires)),
            URLQueryItem(name: "signature", value: ticket.signature)
        ]
        guard let url = components.url else { throw FredServerError.invalidResponse }
        return url
    }

    private struct StreamTicketRequest: Encodable { let path: String }
    private struct StreamTicketResponse: Decodable {
        let path: String
        let expires: Int
        let signature: String
    }

    func fetchProfile(serverPath: String) async -> TrackProfile? {
        do {
            let (data, response) = try await URLSession.shared.data(
                for: request(path: "api/profile/\(profilePath(serverPath))")
            )
            try validate(response)
            return try JSONDecoder().decode(TrackProfile.self, from: data)
        } catch { return nil }
    }

    func uploadProfile(_ profile: TrackProfile, serverPath: String) async -> Bool {
        do {
            var request = request(path: "api/profile/\(profilePath(serverPath))")
            request.httpMethod = "PUT"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(profile)
            let (_, response) = try await URLSession.shared.data(for: request)
            try validate(response)
            return true
        } catch { return false }
    }

    // Prefers the "Original" section (matching source-language lyrics);
    // falls back to whatever section is present for a translation-only
    // sidecar. Returns nil for both real errors and "no lyrics for this
    // track" (HTTP 404) — the caller only needs to distinguish "loading"
    // from "nothing to show", not the reason.
    func fetchLyrics(serverPath: String) async -> [LyricsPhrase]? {
        do {
            let (data, response) = try await URLSession.shared.data(
                for: request(path: "api/lyrics/\(encodedPath(serverPath))")
            )
            try validate(response)
            let sidecar = try JSONDecoder().decode(LyricsSidecar.self, from: data)
            return sidecar.sections["Original"] ?? sidecar.sections.values.first
        } catch { return nil }
    }

    func fetchVisual(serverPath: String, settings: VisualCacheSettings) async -> Data? {
        do {
            let path = encodedPath(serverPath)
            let variant = Self.appleVariantKey(settings)
            let (data, response) = try await URLSession.shared.data(
                for: request(path: "api/apple-visual-variant/\(variant)/\(path)")
            )
            try validate(response)
            return data
        } catch { return nil }
    }

    func uploadVisual(_ data: Data, serverPath: String, settings: VisualCacheSettings) async -> Bool {
        do {
            let variant = Self.appleVariantKey(settings)
            var request = request(path: "api/apple-visual-variant/\(variant)/\(encodedPath(serverPath))")
            request.httpMethod = "PUT"
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            let (_, response) = try await URLSession.shared.data(for: request)
            try validate(response)
            return true
        } catch { return false }
    }

    /// Matches the server's `appleVariantKey` (server/precompute-cache.js)
    /// exactly, including its plain-integer formatting for whole-number
    /// fps/waveform values — Swift's `Double` interpolation would otherwise
    /// emit "60.0" where the server writes "60", missing every precomputed
    /// variant directory.
    private static func appleVariantKey(_ settings: VisualCacheSettings) -> String {
        let fps = Int(settings.fps.rounded())
        let waveformMs = Int((settings.waveformWindow * 1_000).rounded())
        let logFlag = settings.logarithmic ? 1 : 0
        return "fps\(fps)-wave\(waveformMs)-fft\(settings.fftSize)-bars\(settings.bars)-log\(logFlag)"
    }

    func askLiam(deviceID: String, message: String) async throws -> AskLiamResponse {
        var request = request(path: "api/ask-liam")
        request.httpMethod = "POST"
        request.timeoutInterval = 660
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(AskRequest(deviceID: deviceID, message: message))
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 660
        configuration.timeoutIntervalForResource = 660
        let session = URLSession(configuration: configuration)
        let (data, response) = try await session.data(for: request)
        try validate(response, errorData: data)
        return try JSONDecoder().decode(AskLiamResponse.self, from: data)
    }

    private struct AskRequest: Encodable {
        let deviceID: String
        let message: String

        enum CodingKeys: String, CodingKey {
            case deviceID = "device_id"
            case message
        }
    }

    private func request(path: String) -> URLRequest {
        authorizedRequest(url: endpoint(path))
    }

    private func authorizedRequest(url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func endpoint(_ path: String) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        let basePath = components.percentEncodedPath.hasSuffix("/")
            ? String(components.percentEncodedPath.dropLast())
            : components.percentEncodedPath
        components.percentEncodedPath = basePath + "/" + path
        return components.url!
    }

    private func profilePath(_ path: String) -> String {
        let extensionless = (path as NSString).deletingPathExtension
        return encodedPath(extensionless)
    }

    private func encodedPath(_ path: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let segments = path.split(separator: "/", omittingEmptySubsequences: false)
        var encoded: [String] = []
        for segment in segments {
            let value = String(segment)
            encoded.append(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)
        }
        return encoded.joined(separator: "/")
    }

    private struct ServerErrorBody: Decodable {
        let error: String?
    }

    private func validate(_ response: URLResponse, errorData: Data? = nil) throws {
        guard let response = response as? HTTPURLResponse else { throw FredServerError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else {
            let decodedError = errorData.flatMap {
                try? JSONDecoder().decode(ServerErrorBody.self, from: $0)
            }
            let detail = decodedError?.error
            throw FredServerError.httpStatus(response.statusCode, detail)
        }
    }
}
