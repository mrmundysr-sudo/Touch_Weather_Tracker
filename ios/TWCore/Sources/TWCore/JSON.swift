import Foundation

/// Minimal dynamic JSON value with forgiving accessors (absent keys read as `.null`), so adapters can mirror the Android
/// `JsonElement` code without a Codable type for every upstream payload.
public enum JSON: Decodable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let d = try? c.decode(Double.self) { self = .number(d) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSON].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSON].self) { self = .object(o) }
        else { self = .null }
    }

    public static func parse(_ data: Data) throws -> JSON { try JSONDecoder().decode(JSON.self, from: data) }
    public static func parse(_ text: String) throws -> JSON { try parse(Data(text.utf8)) }

    public subscript(key: String) -> JSON { if case .object(let o) = self { return o[key] ?? .null }; return .null }
    public subscript(index: Int) -> JSON { if case .array(let a) = self, a.indices.contains(index) { return a[index] }; return .null }

    public var string: String? { if case .string(let s) = self { return s }; return nil }
    /// String value, or the textual form of a number (some NHC/ArcGIS fields are strings, others numbers).
    public var text: String? {
        switch self {
        case .string(let s): return s
        case .number(let d): return d == d.rounded() && abs(d) < 1e15 ? String(Int64(d)) : String(d)
        default: return nil
        }
    }
    public var double: Double? {
        switch self {
        case .number(let d): return d
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }
    public var int: Int? { double.map { Int($0) } }
    public var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var array: [JSON]? { if case .array(let a) = self { return a }; return nil }
    public var object: [String: JSON]? { if case .object(let o) = self { return o }; return nil }
    public var isNull: Bool { if case .null = self { return true }; return false }
    /// Trimmed non-empty string, else nil.
    public var clean: String? {
        guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty, t != "null" else { return nil }
        return t
    }
}
