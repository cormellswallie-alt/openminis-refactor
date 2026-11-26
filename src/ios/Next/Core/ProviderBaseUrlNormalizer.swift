import Foundation

/// Pure-Swift normalizer and validator for user-entered custom API Base URLs across
/// OpenAI-, Anthropic-, Gemini-, SiliconFlow-, DeepSeek-, Zhipu-, Volcengine Ark-,
/// DashScope-, Ollama/LM Studio-, OneAPI/NewAPI-, and Azure endpoints.
enum ProviderBaseUrlNormalizer {
    private static let invisibleScalars: Set<Unicode.Scalar> = [
        "\u{200B}", "\u{200C}", "\u{200D}", "\u{FEFF}", "\u{00A0}"
    ]

    private static let leafEndpointSuffixes = [
        "/chat/completions",
        "/completions",
        "/responses",
        "/messages",
        "/models",
        "/embeddings",
        "/images/generations",
    ]

    private static let versionSuffixRegex = try! NSRegularExpression(
        pattern: #"/v\d+(?:alpha\d*|beta\d*)?$"#,
        options: [.caseInsensitive]
    )

    private static let schemeRegex = try! NSRegularExpression(
        pattern: #"^[a-zA-Z][a-zA-Z0-9+.\-]*://"#,
        options: []
    )

    /// Clean a user-entered custom base URL before saving or building requests.
    static func sanitizeInput(_ raw: String, azureMode: Bool = false) -> String {
        let stripped = stripInvisible(raw).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !stripped.isEmpty else { return "" }

        let nsStripped = stripped as NSString
        let hasScheme = schemeRegex.firstMatch(in: stripped, range: NSRange(location: 0, length: nsStripped.length)) != nil
        let url: String
        if hasScheme {
            url = stripped
        } else {
            let scheme = isLikelyLocalHost(stripped) ? "http://" : "https://"
            url = scheme + stripped
        }

        if azureMode { return url }

        // Separate query / fragment before stripping trailing slashes or leaf paths.
        let splitIdx = url.firstIndex(where: { $0 == "?" || $0 == "#" })
        var pathPart = splitIdx.map { String(url[..<$0]) } ?? url
        let queryPart = splitIdx.map { String(url[$0...]) } ?? ""

        while pathPart.hasSuffix("/") { pathPart.removeLast() }
        let lower = pathPart.lowercased()
        for leaf in leafEndpointSuffixes {
            if lower.hasSuffix(leaf) {
                pathPart = String(pathPart.dropLast(leaf.count))
                while pathPart.hasSuffix("/") { pathPart.removeLast() }
                break
            }
        }
        return pathPart + queryPart
    }

    /// Compute the effective API base URL from `customBaseURL` and `appendV1Suffix`.
    /// Never appends `/v1` when the URL already ends with a version segment (`/v1`, `/v1beta`, `/v2`, `/v3`, `/v4`, ...).
    static func effectiveBaseUrl(
        customBaseURL: String?,
        appendV1Suffix: Bool,
        azureMode: Bool = false
    ) -> String? {
        var base = sanitizeInput(customBaseURL ?? "", azureMode: azureMode)
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty else { return nil }
        if !appendV1Suffix || azureMode { return base }
        if base.contains("?") { return base }
        let ns = base as NSString
        let endsWithVersion = versionSuffixRegex.firstMatch(in: base, range: NSRange(location: 0, length: ns.length)) != nil
        return endsWithVersion ? base : "\(base)/v1"
    }

    /// Validate a non-blank user-entered URL. Returns `nil` when empty or valid, or an actionable error message.
    static func validateInput(_ raw: String, azureMode: Bool = false) -> String? {
        let cleaned = stripInvisible(raw).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        if cleaned.contains(where: { $0.isWhitespace || $0.isNewline }) {
            return "Base URL cannot contain spaces or line breaks."
        }
        let normalized = sanitizeInput(cleaned, azureMode: azureMode)
        guard let components = URLComponents(string: normalized),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return "URL must start with http:// or https://."
        }
        guard let host = components.host, !host.isEmpty else {
            return "URL must include a valid host name or IP address."
        }
        return nil
    }

    /// Detect SiliconFlow endpoints (`api.siliconflow.cn` or `api.siliconflow.com`).
    static func isSiliconFlow(_ baseURL: String?) -> Bool {
        guard let lower = baseURL?.lowercased() else { return false }
        return lower.contains("siliconflow.cn") || lower.contains("siliconflow.com")
    }

    /// Append SiliconFlow's `?sub_type=chat` query filter on `/models` requests so embedding/reranker/TTS models don't clutter chat pickers.
    static func siliconFlowChatModelsURL(from url: String) -> String {
        guard isSiliconFlow(url), url.lowercased().hasSuffix("/models"), !url.contains("sub_type=") else {
            return url
        }
        return url + (url.contains("?") ? "&" : "?") + "sub_type=chat"
    }

    private static func stripInvisible(_ s: String) -> String {
        String(s.unicodeScalars.filter { !invisibleScalars.contains($0) })
    }

    private static func isLikelyLocalHost(_ hostAndPath: String) -> Bool {
        let authority = hostAndPath
            .split(separator: "/", maxSplits: 1).first.map(String.init) ?? hostAndPath
        let beforeQuery = authority
            .split(separator: "?", maxSplits: 1).first.map(String.init) ?? authority
        let lower = beforeQuery.lowercased()
        let host: String
        if lower.hasPrefix("["), let close = lower.firstIndex(of: "]") {
            host = String(lower[lower.index(after: lower.startIndex)..<close])
        } else {
            host = lower.split(separator: ":", maxSplits: 1).first.map(String.init) ?? lower
        }
        if host == "localhost" || host == "::1" || host.hasSuffix(".local") { return true }
        if host.hasPrefix("127.") || host.hasPrefix("10.") || host.hasPrefix("192.168.") { return true }
        let parts = host.split(separator: ".")
        if parts.count == 4, parts[0] == "172", let second = Int(parts[1]), (16...31).contains(second) {
            return true
        }
        return false
    }
}
