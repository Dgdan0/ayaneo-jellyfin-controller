import Foundation
import Security

/// The hub token lives in the Keychain, never in UserDefaults: the same token
/// can delete media and control downloads on the user's real stack.
enum Keychain {
    /// The app's own id: renamed (JellyHub, com.dgdan.jellyhub), it starts with
    /// no token, and a debug launch seeds it again from apple/dev.env.
    private static let service = Bundle.main.bundleIdentifier ?? "com.dgdan.jellyhub"
    private static let account = "hub-token"

    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static var token: String? {
        get {
            var item: CFTypeRef?
            var request = query
            request[kSecReturnData as String] = true
            request[kSecMatchLimit as String] = kSecMatchLimitOne
            guard SecItemCopyMatching(request as CFDictionary, &item) == errSecSuccess,
                  let data = item as? Data else { return nil }
            return String(data: data, encoding: .utf8)
        }
        set {
            SecItemDelete(query as CFDictionary)
            guard let newValue, !newValue.isEmpty else { return }
            var item = query
            item[kSecValueData as String] = Data(newValue.utf8)
            // Readable after the first unlock, so background downloads can use it;
            // never synced to iCloud or restored onto another device.
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(item as CFDictionary, nil)
        }
    }
}
