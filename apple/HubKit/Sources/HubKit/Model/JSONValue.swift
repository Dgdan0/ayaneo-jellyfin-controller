import Foundation

/// Any JSON, kept as it came: a reader's locator, a listening place (#25).
/// Whole numbers stay whole, because the hub reads `offsetMs` as an integer;
/// and a number equals the same number however it was written ("1", "1.0"),
/// because a place read back from a file must equal the place written there.
public enum JSONValue: Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public subscript(key: String) -> JSONValue? {
        if case .object(let fields) = self { return fields[key] }
        return nil
    }

    public var stringValue: String? {
        if case .string(let text) = self { return text }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let flag) = self { return flag }
        return nil
    }

    /// A whole number, or a decimal one that is whole.
    public var int64Value: Int64? {
        switch self {
        case .int(let value): return value
        case .double(let value):
            guard value.rounded() == value, abs(value) < 9.0e15 else { return nil }
            return Int64(value)
        default: return nil
        }
    }

    public var doubleValue: Double? {
        switch self {
        case .int(let value): Double(value)
        case .double(let value): value
        default: nil
        }
    }

    public var objectValue: [String: JSONValue]? {
        if case .object(let fields) = self { return fields }
        return nil
    }

    /// The JSON text, for a request body.
    public func encoded() -> Data {
        (try? JSONEncoder().encode(self)) ?? Data("null".utf8)
    }
}

extension JSONValue: Equatable {
    public static func == (lhs: JSONValue, rhs: JSONValue) -> Bool {
        switch (lhs, rhs) {
        case (.null, .null): true
        case let (.bool(a), .bool(b)): a == b
        case let (.string(a), .string(b)): a == b
        case let (.array(a), .array(b)): a == b
        case let (.object(a), .object(b)): a == b
        default:
            if let a = lhs.doubleValue, let b = rhs.doubleValue { a == b } else { false }
        }
    }
}

extension JSONValue: Hashable {
    public func hash(into hasher: inout Hasher) {
        switch self {
        case .null: hasher.combine(0)
        case .bool(let flag): hasher.combine(flag)
        case .int, .double: hasher.combine(doubleValue)
        case .string(let text): hasher.combine(text)
        case .array(let items): hasher.combine(items)
        case .object(let fields): hasher.combine(fields)
        }
    }
}

extension JSONValue: Codable {
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let flag = try? container.decode(Bool.self) {
            self = .bool(flag)
        } else if let whole = try? container.decode(Int64.self) {
            self = .int(whole)
        } else if let number = try? container.decode(Double.self) {
            self = .double(number)
        } else if let text = try? container.decode(String.self) {
            self = .string(text)
        } else if let items = try? container.decode([JSONValue].self) {
            self = .array(items)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let flag): try container.encode(flag)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .string(let text): try container.encode(text)
        case .array(let items): try container.encode(items)
        case .object(let fields): try container.encode(fields)
        }
    }
}
