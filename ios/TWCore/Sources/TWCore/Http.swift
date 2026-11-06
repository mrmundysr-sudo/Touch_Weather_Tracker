import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Thrown for any failed upstream call so the UI can show an honest message.
public struct SourceError: Error, LocalizedError, Sendable {
    public let source: String
    public let message: String
    public init(_ source: String, _ message: String) { self.source = source; self.message = message }
    public var errorDescription: String? { message }
}

public protocol HTTPTransport: Sendable {
    func get(_ url: URL, accept: String?) async throws -> (Data, Int)
}

public struct URLSessionTransport: HTTPTransport {
    public static let userAgent = "TouchWeatherTracker/0.1 (iOS; github.com/mrmundysr-sudo/Touch_Weather_Tracker)"
    private let session: URLSession
    public init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 25
        cfg.timeoutIntervalForResource = 40
        cfg.requestCachePolicy = .reloadRevalidatingCacheData
        session = URLSession(configuration: cfg)
    }
    public func get(_ url: URL, accept: String?) async throws -> (Data, Int) {
        var req = URLRequest(url: url)
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(accept ?? "application/geo+json, application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: req)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
}

/// One shared HTTP entry point. All upstream sources are free public services; nothing personal is sent.
public enum Http {
    nonisolated(unsafe) public static var transport: HTTPTransport = URLSessionTransport()

    /// GET returning bytes. Retries once on 5xx / network errors.
    public static func data(_ source: String, _ urlString: String, accept: String? = nil) async throws -> Data {
        guard let url = URL(string: urlString) else { throw SourceError(source, "Bad URL for \(source)") }
        var last: Error?
        for attempt in 0..<2 {
            do {
                let (d, code) = try await transport.get(url, accept: accept)
                if (200..<300).contains(code) { return d }
                if (500..<600).contains(code) && attempt == 0 { last = SourceError(source, "HTTP \(code)") }
                else { throw SourceError(source, "HTTP \(code) from \(source)") }
            } catch let e as SourceError { throw e }
            catch { last = error }
            if attempt == 0 { try? await Task.sleep(nanoseconds: 600_000_000) }
        }
        throw SourceError(source, "Could not reach \(source)")
    }

    public static func text(_ source: String, _ url: String, accept: String? = nil) async throws -> String {
        String(decoding: try await data(source, url, accept: accept), as: UTF8.self)
    }

    public static func json(_ source: String, _ url: String) async throws -> JSON {
        do { return try JSON.parse(try await data(source, url)) }
        catch let e as SourceError { throw e }
        catch { throw SourceError(source, "Unreadable response from \(source)") }
    }
}
