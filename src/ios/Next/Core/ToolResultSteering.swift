import Foundation

/// A user-submitted mid-loop steering note waiting to be injected into the next tool result boundary.
struct ToolResultSteeringNote: Identifiable, Equatable, Sendable {
    let id: String
    let text: String
    let createdAt: Date

    init(id: String = UUID().uuidString, text: String, createdAt: Date = Date()) {
        self.id = id
        self.text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        self.createdAt = createdAt
    }
}

/// Opt-in, user-initiated mid-loop steering queue (never auto-triggered).
/// While an agent run is active, the user can open the steering bar and queue a course-correction note;
/// the agent loop drains queued notes and appends them cleanly to the next tool result so the model
/// adapts immediately without restarting or losing accumulated work.
final class ToolResultSteeringQueue: @unchecked Sendable {
    static let shared = ToolResultSteeringQueue()

    private let lock = NSLock()
    private var queues: [String: [ToolResultSteeringNote]] = [:]

    @discardableResult
    func enqueue(sessionId: String, text: String, now: Date = Date()) -> ToolResultSteeringNote? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let note = ToolResultSteeringNote(text: trimmed, createdAt: now)
        lock.lock()
        queues[sessionId, default: []].append(note)
        lock.unlock()
        return note
    }

    func pendingNotes(for sessionId: String) -> [ToolResultSteeringNote] {
        lock.lock()
        let copy = queues[sessionId] ?? []
        lock.unlock()
        return copy
    }

    func hasPending(for sessionId: String) -> Bool {
        lock.lock()
        let nonEmpty = !(queues[sessionId]?.isEmpty ?? true)
        lock.unlock()
        return nonEmpty
    }

    func drain(for sessionId: String) -> [ToolResultSteeringNote] {
        lock.lock()
        let drained = queues.removeValue(forKey: sessionId) ?? []
        lock.unlock()
        return drained
    }

    func remove(sessionId: String, noteId: String) {
        lock.lock()
        queues[sessionId]?.removeAll { $0.id == noteId }
        if queues[sessionId]?.isEmpty == true {
            queues.removeValue(forKey: sessionId)
        }
        lock.unlock()
    }

    func clear(sessionId: String) {
        lock.lock()
        queues.removeValue(forKey: sessionId)
        lock.unlock()
    }

    /// Format drained steering notes for clean injection at a tool_result boundary.
    static func formatToolResultInjection(_ notes: [ToolResultSteeringNote]) -> String? {
        guard !notes.isEmpty else { return nil }
        let body: String
        if notes.count == 1 {
            body = notes[0].text
        } else {
            body = notes.enumerated().map { "\($0.offset + 1). \($0.element.text)" }.joined(separator: "\n")
        }
        return """

        <user-steering>
        The user sent a live course-correction while your task is running. Follow this immediately from the next step onward without restarting completed work:
        \(body)
        </user-steering>
        """
    }
}
