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

struct AskLiamPlaylist: Codable {
    let name: String
    let tracks: [String]
}

struct AskLiamResponse: Codable {
    let reply: String
    let playlist: AskLiamPlaylist?
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

    func streamURL(forServerPath path: String) -> URL {
        endpoint("stream/\(encodedPath(path))")
    }

    func downloadTrack(serverPath: String) async throws -> URL {
        let (url, response) = try await URLSession.shared.download(
            for: authorizedRequest(url: streamURL(forServerPath: serverPath))
        )
        try validate(response)
        return url
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

    func fetchVisual(serverPath: String) async -> Data? {
        do {
            let path = encodedPath(serverPath)
            let (data, response) = try await URLSession.shared.data(for: request(path: "api/apple-visual/\(path)"))
            try validate(response)
            return data
        } catch { return nil }
    }

    func uploadVisual(_ data: Data, serverPath: String) async -> Bool {
        do {
            var request = request(path: "api/apple-visual/\(encodedPath(serverPath))")
            request.httpMethod = "PUT"
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            let (_, response) = try await URLSession.shared.data(for: request)
            try validate(response)
            return true
        } catch { return false }
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
