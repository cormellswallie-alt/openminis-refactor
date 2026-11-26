import Foundation

/// Status of a single live checklist item (Antigravity `update_steps` port).
enum ChecklistStepStatus: String, Codable, CaseIterable, Sendable {
    case pending
    case inProgress = "in_progress"
    case done
    case skipped
    case failed

    var sfSymbolName: String {
        switch self {
        case .pending: return "circle"
        case .inProgress: return "arrow.triangle.2.circlepath.circle.fill"
        case .done: return "checkmark.circle.fill"
        case .skipped: return "forward.circle.fill"
        case .failed: return "xmark.circle.fill"
        }
    }
}

struct ChecklistStep: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let title: String
    let status: ChecklistStepStatus

    init(id: String? = nil, title: String, status: ChecklistStepStatus) {
        self.title = title
        self.status = status
        self.id = id ?? "\(title)|\(status.rawValue)"
    }
}

struct StepChecklistState: Codable, Equatable, Sendable {
    let steps: [ChecklistStep]
    let updatedAt: Date

    var totalCount: Int { steps.count }
    var doneCount: Int { steps.filter { $0.status == .done }.count }
    var finishedCount: Int { steps.filter { $0.status == .done || $0.status == .skipped }.count }
    var inProgressSteps: [ChecklistStep] { steps.filter { $0.status == .inProgress } }
    var failedCount: Int { steps.filter { $0.status == .failed }.count }

    var progressFraction: Double {
        guard totalCount > 0 else { return 0 }
        return Double(finishedCount) / Double(totalCount)
    }

    var headlineText: String {
        if let active = inProgressSteps.first {
            return active.title
        }
        if failedCount > 0 {
            return "\(failedCount) step(s) failed (\(doneCount)/\(totalCount))"
        }
        if finishedCount == totalCount && totalCount > 0 {
            return "All \(totalCount) steps completed"
        }
        return steps.first(where: { $0.status == .pending })?.title ?? "Task checklist"
    }

    var summaryForToolResult: String {
        let lines = steps.enumerated().map { idx, step in
            let marker: String
            switch step.status {
            case .done: marker = "[x]"
            case .inProgress: marker = "[~]"
            case .pending: marker = "[ ]"
            case .skipped: marker = "[-]"
            case .failed: marker = "[!]"
            }
            return "\(idx + 1). \(marker) \(step.title) (\(step.status.rawValue))"
        }
        return "Checklist updated (\(doneCount)/\(totalCount) done):\n" + lines.joined(separator: "\n")
    }
}

/// Parses and validates `update_steps` arguments (supporting both native JSON arrays and stringified JSON arrays).
enum UpdateStepsParser {
    static let maxSteps = 12
    static let maxTitleChars = 120

    enum ParseResult: Equatable, Sendable {
        case success([ChecklistStep])
        case failure(String)
    }

    static func parse(jsonString: String) -> ParseResult {
        guard let data = jsonString.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return .failure("Error: Invalid JSON arguments for update_steps.")
        }
        return parse(arguments: root)
    }

    static func parse(arguments: [String: Any]) -> ParseResult {
        guard let rawSteps = arguments["steps"] else {
            return .failure("Error: Missing required 'steps' array.")
        }

        let arrayObj: [Any]
        if let arr = rawSteps as? [Any] {
            arrayObj = arr
        } else if let str = rawSteps as? String,
                  let data = str.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [Any] {
            arrayObj = parsed
        } else {
            return .failure("Error: 'steps' must be a JSON array of {title, status} objects.")
        }

        guard !arrayObj.isEmpty else {
            return .failure("Error: 'steps' must contain at least 1 step.")
        }
        guard arrayObj.count <= maxSteps else {
            return .failure("Error: 'steps' may contain at most \(maxSteps) steps (got \(arrayObj.count)).")
        }

        var parsedSteps: [ChecklistStep] = []
        parsedSteps.reserveCapacity(arrayObj.count)

        for (index, item) in arrayObj.enumerated() {
            guard let dict = item as? [String: Any] else {
                return .failure("Error: Step #\(index + 1) must be an object with 'title' and 'status'.")
            }
            let rawTitle = (dict["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rawTitle.isEmpty else {
                return .failure("Error: Step #\(index + 1) has an empty 'title'.")
            }
            let rawStatus = (dict["status"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard let status = ChecklistStepStatus(rawValue: rawStatus) else {
                return .failure("Error: Step #\(index + 1) has invalid status '\(rawStatus)'. Expected one of: pending, in_progress, done, skipped, failed.")
            }
            let clippedTitle = String(rawTitle.prefix(maxTitleChars))
            parsedSteps.append(ChecklistStep(id: "\(index)-\(clippedTitle)", title: clippedTitle, status: status))
        }

        return .success(parsedSteps)
    }
}

/// Thread-safe per-session live checklist registry.
final class StepChecklistStore: @unchecked Sendable {
    static let shared = StepChecklistStore()

    private let lock = NSLock()
    private var states: [String: StepChecklistState] = [:]

    func state(for sessionId: String) -> StepChecklistState? {
        lock.lock()
        let value = states[sessionId]
        lock.unlock()
        return value
    }

    @discardableResult
    func update(sessionId: String, steps: [ChecklistStep], now: Date = Date()) -> StepChecklistState {
        let next = StepChecklistState(steps: steps, updatedAt: now)
        lock.lock()
        states[sessionId] = next
        lock.unlock()
        return next
    }

    func clear(sessionId: String) {
        lock.lock()
        states.removeValue(forKey: sessionId)
        lock.unlock()
    }
}
