import Foundation

/// Pure-Swift DEFLATE (RFC 1951) + gzip (RFC 1952) decoder (port of zlib's `puff`). Used for NHC ATCF `.dat.gz` files so the same code runs on iOS and Linux.
public enum Gzip {
    public struct DecodeError: Error { public let message: String }

    public static func decompress(_ data: Data) throws -> Data {
        let b = [UInt8](data)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { throw DecodeError(message: "not gzip") }
        let flags = b[3]
        var p = 10
        if flags & 0x04 != 0 { guard p + 2 <= b.count else { throw DecodeError(message: "truncated") }; p += 2 + Int(b[p]) + Int(b[p + 1]) << 8 }
        if flags & 0x08 != 0 { while p < b.count && b[p] != 0 { p += 1 }; p += 1 }
        if flags & 0x10 != 0 { while p < b.count && b[p] != 0 { p += 1 }; p += 1 }
        if flags & 0x02 != 0 { p += 2 }
        guard p < b.count else { throw DecodeError(message: "truncated") }
        var inf = Inflater(input: b, pos: p)
        try inf.run()
        return Data(inf.out)
    }
}

private struct Huffman {
    var count = [Int](repeating: 0, count: 16)
    var symbol: [Int]
    init(lengths: [Int]) {
        symbol = [Int](repeating: 0, count: lengths.count)
        for l in lengths { count[l] += 1 }
        var offs = [Int](repeating: 0, count: 16)
        for len in 1..<15 { offs[len + 1] = offs[len] + count[len] }
        for (sym, l) in lengths.enumerated() where l != 0 { symbol[offs[l]] = sym; offs[l] += 1 }
    }
}

private struct Inflater {
    let input: [UInt8]
    var pos: Int
    var bitBuf = 0, bitCnt = 0
    var out: [UInt8] = []
    init(input: [UInt8], pos: Int) { self.input = input; self.pos = pos; out.reserveCapacity(input.count * 6) }

    static let lenBase = [3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258]
    static let lenExtra = [0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0]
    static let distBase = [1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577]
    static let distExtra = [0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13]
    static let fixedLit: Huffman = { var l = [Int](repeating: 8, count: 288); for i in 144..<256 { l[i] = 9 }; for i in 256..<280 { l[i] = 7 }; return Huffman(lengths: l) }()
    static let fixedDist = Huffman(lengths: [Int](repeating: 5, count: 30))

    mutating func bits(_ need: Int) throws -> Int {
        var val = bitBuf
        while bitCnt < need {
            guard pos < input.count else { throw Gzip.DecodeError(message: "out of input") }
            val |= Int(input[pos]) << bitCnt; pos += 1; bitCnt += 8
        }
        bitBuf = val >> need; bitCnt -= need
        return val & ((1 << need) - 1)
    }

    mutating func decode(_ h: Huffman) throws -> Int {
        var code = 0, first = 0, index = 0
        for len in 1...15 {
            code |= try bits(1)
            let c = h.count[len]
            if code - c < first { return h.symbol[index + (code - first)] }
            index += c; first += c; first <<= 1; code <<= 1
        }
        throw Gzip.DecodeError(message: "bad code")
    }

    mutating func codes(_ lit: Huffman, _ dist: Huffman) throws {
        while true {
            var sym = try decode(lit)
            if sym < 256 { out.append(UInt8(sym)) }
            else if sym == 256 { return }
            else {
                sym -= 257
                guard sym < 29 else { throw Gzip.DecodeError(message: "bad length") }
                let len = Self.lenBase[sym] + (try bits(Self.lenExtra[sym]))
                let ds = try decode(dist)
                guard ds < 30 else { throw Gzip.DecodeError(message: "bad dist") }
                let d = Self.distBase[ds] + (try bits(Self.distExtra[ds]))
                guard d <= out.count else { throw Gzip.DecodeError(message: "dist too far") }
                let start = out.count - d
                for i in 0..<len { out.append(out[start + i]) }
            }
        }
    }

    mutating func run() throws {
        var last = 0
        repeat {
            last = try bits(1)
            switch try bits(2) {
            case 0:
                bitBuf = 0; bitCnt = 0
                guard pos + 4 <= input.count else { throw Gzip.DecodeError(message: "stored") }
                let len = Int(input[pos]) | Int(input[pos + 1]) << 8; pos += 4
                guard pos + len <= input.count else { throw Gzip.DecodeError(message: "stored len") }
                out.append(contentsOf: input[pos..<(pos + len)]); pos += len
            case 1: try codes(Self.fixedLit, Self.fixedDist)
            case 2:
                let nlen = try bits(5) + 257, ndist = try bits(5) + 1, ncode = try bits(4) + 4
                let order = [16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15]
                var lengths = [Int](repeating: 0, count: 19)
                for i in 0..<ncode { lengths[order[i]] = try bits(3) }
                let lencode = Huffman(lengths: lengths)
                var l = [Int](repeating: 0, count: nlen + ndist)
                var idx = 0
                while idx < nlen + ndist {
                    let sym = try decode(lencode)
                    if sym < 16 { l[idx] = sym; idx += 1 }
                    else {
                        var prev = 0, rep = 0
                        if sym == 16 { guard idx > 0 else { throw Gzip.DecodeError(message: "no prev") }; prev = l[idx - 1]; rep = 3 + (try bits(2)) }
                        else if sym == 17 { rep = 3 + (try bits(3)) } else { rep = 11 + (try bits(7)) }
                        guard idx + rep <= nlen + ndist else { throw Gzip.DecodeError(message: "too many lengths") }
                        for _ in 0..<rep { l[idx] = prev; idx += 1 }
                    }
                }
                try codes(Huffman(lengths: Array(l[0..<nlen])), Huffman(lengths: Array(l[nlen...])))
            default: throw Gzip.DecodeError(message: "bad block type")
            }
        } while last == 0
    }
}
