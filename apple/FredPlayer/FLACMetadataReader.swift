import Foundation

struct AudioMetadata {
    let title: String?
    let artist: String?
    let album: String?
    let trackNumber: Int?
}

enum FLACMetadataReader {
    static func read(from url: URL) -> AudioMetadata? {
        guard
            let handle = try? FileHandle(forReadingFrom: url),
            (try? handle.read(upToCount: 4)) == Data("fLaC".utf8)
        else { return nil }
        defer { try? handle.close() }

        while let header = try? handle.read(upToCount: 4), header.count == 4 {
            let isLast = header[0] & 0x80 != 0
            let type = header[0] & 0x7f
            let length = Int(header[1]) << 16 | Int(header[2]) << 8 | Int(header[3])

            guard let block = try? handle.read(upToCount: length), block.count == length else {
                return nil
            }
            if type == 4 { return parseVorbisComments(block) }
            if isLast { break }
        }
        return nil
    }

    private static func parseVorbisComments(_ data: Data) -> AudioMetadata? {
        var offset = 0
        guard let vendorLength = readUInt32(from: data, offset: &offset) else { return nil }
        guard advance(Int(vendorLength), in: data, offset: &offset) else { return nil }
        guard let commentCount = readUInt32(from: data, offset: &offset) else { return nil }

        var values: [String: String] = [:]
        for _ in 0..<commentCount {
            guard
                let length = readUInt32(from: data, offset: &offset),
                offset + Int(length) <= data.count
            else { break }
            let commentData = data[offset..<(offset + Int(length))]
            offset += Int(length)
            guard
                let comment = String(data: commentData, encoding: .utf8),
                let separator = comment.firstIndex(of: "=")
            else { continue }
            values[String(comment[..<separator]).uppercased()] =
                String(comment[comment.index(after: separator)...])
        }

        return AudioMetadata(
            title: values["TITLE"],
            artist: values["ARTIST"] ?? values["ALBUMARTIST"],
            album: values["ALBUM"],
            trackNumber: values["TRACKNUMBER"].flatMap {
                Int($0.split(separator: "/").first ?? "")
            }
        )
    }

    private static func readUInt32(from data: Data, offset: inout Int) -> UInt32? {
        guard offset + 4 <= data.count else { return nil }
        let value = UInt32(data[offset])
            | UInt32(data[offset + 1]) << 8
            | UInt32(data[offset + 2]) << 16
            | UInt32(data[offset + 3]) << 24
        offset += 4
        return value
    }

    private static func advance(_ count: Int, in data: Data, offset: inout Int) -> Bool {
        guard count >= 0, offset + count <= data.count else { return false }
        offset += count
        return true
    }
}
