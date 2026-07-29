import Foundation

enum FredServerURLPolicy {
    static func validatedURL(_ value: String) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              var components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              let host = components.host,
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil else {
            return nil
        }
        while components.path.count > 1 && components.path.hasSuffix("/") {
            components.path.removeLast()
        }
        if scheme == "https" {
            return components.url
        }
#if DEBUG
        let loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if scheme == "http" && loopback {
            return components.url
        }
#endif
        return nil
    }
}
