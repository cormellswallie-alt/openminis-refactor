import SwiftUI

// MARK: - 1. Pinned Collapsible Live Task Checklist Banner (Antigravity update_steps)

struct NextStepChecklistBanner: View {
    let sessionKey: String
    @ObservedObject private var store = NextSessionStateStore.shared
    @State private var isExpanded = false

    var body: some View {
        if let state = store.checklist(for: sessionKey), !state.steps.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: state.failedCount > 0 ? "exclamationmark.circle.fill" : "checklist")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(state.failedCount > 0 ? .red : .accentColor)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(state.headlineText)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            ProgressView(value: state.progressFraction)
                                .tint(state.failedCount > 0 ? .red : .accentColor)
                        }

                        Text("\(state.doneCount)/\(state.totalCount)")
                            .font(.caption2.monospacedDigit().weight(.semibold))
                            .foregroundStyle(.secondary)

                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)

                if isExpanded {
                    Divider()
                    VStack(alignment: .leading, spacing: 5) {
                        ForEach(Array(state.steps.enumerated()), id: \.element.id) { idx, step in
                            HStack(alignment: .top, spacing: 7) {
                                Image(systemName: step.status.sfSymbolName)
                                    .font(.system(size: 12))
                                    .foregroundStyle(color(for: step.status))
                                    .padding(.top, 1)
                                Text("\(idx + 1). \(step.title)")
                                    .font(.caption)
                                    .foregroundStyle(step.status == .done || step.status == .skipped ? .secondary : .primary)
                                    .strikethrough(step.status == .skipped, color: .secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 0)
                            }
                        }
                        HStack {
                            Spacer()
                            Button {
                                withAnimation {
                                    isExpanded = false
                                    store.clearChecklist(for: sessionKey)
                                }
                            } label: {
                                Label("Clear", systemImage: "xmark.circle")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.top, 2)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background {
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.regularMaterial)
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.accentColor.opacity(0.06))
            }
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color.accentColor.opacity(0.24), lineWidth: 0.75)
            )
            .shadow(color: .black.opacity(0.06), radius: 8, x: 0, y: 2)
            .padding(.horizontal, 12)
            .padding(.top, 6)
            .padding(.bottom, 2)
        }
    }

    private func color(for status: ChecklistStepStatus) -> Color {
        switch status {
        case .pending: return .secondary
        case .inProgress: return .blue
        case .done: return .green
        case .skipped: return .orange
        case .failed: return .red
        }
    }
}

// MARK: - 2. Plan Mode Pill & Opt-In Mid-Loop Steering Bar

struct NextComposerControlStrip: View {
    @ObservedObject var vm: AIChatViewModel
    @ObservedObject private var store = NextSessionStateStore.shared
    @State private var steeringDraft: String = ""

    private var sessionKey: String { vm.nextSessionKey }
    private var mode: AgentExecutionMode { store.mode(for: sessionKey) }
    private var isSteeringExpanded: Bool { store.isSteeringExpanded(for: sessionKey) }
    private var pendingSteers: [ToolResultSteeringNote] { store.pendingSteeringNotes(for: sessionKey) }

    private var isChinese: Bool {
        let lang = AppBundle.current.preferredLocalizations.first ?? Locale.preferredLanguages.first ?? "en"
        return lang.lowercased().hasPrefix("zh")
    }

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                // Plan Mode toggle pill
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        let next = store.togglePlanMode(for: sessionKey)
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        vm.transientNotice = next == .plan
                            ? (isChinese ? "已开启规划模式（只读，禁止改写文件）" : "Plan Mode active (read-only)")
                            : (isChinese ? "已切回常规模式（可读写）" : "Normal Mode restored")
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: mode == .plan ? "map.fill" : "map")
                            .font(.system(size: 11, weight: .semibold))
                        Text(mode == .plan
                             ? (isChinese ? "规划模式 · 只读" : "Plan · Read-Only")
                             : (isChinese ? "规划模式" : "Plan"))
                            .font(.system(size: 11, weight: .medium))
                    }
                    .foregroundStyle(mode == .plan ? Color.orange : Color.secondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(
                        Capsule().fill(mode == .plan ? Color.orange.opacity(0.16) : Color(UIColor.secondarySystemBackground).opacity(0.85))
                    )
                    .overlay(
                        Capsule().strokeBorder(mode == .plan ? Color.orange.opacity(0.45) : Color.secondary.opacity(0.2), lineWidth: 0.75)
                    )
                }
                .buttonStyle(.plain)

                // Opt-in Steering button (only shown when agent is actively running)
                if vm.isProcessing {
                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            store.setSteeringExpanded(!isSteeringExpanded, for: sessionKey)
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "steeringwheel")
                                .font(.system(size: 11, weight: .semibold))
                            Text(isChinese ? "主动纠偏" : "Steer")
                                .font(.system(size: 11, weight: .medium))
                            if !pendingSteers.isEmpty {
                                Text("\(pendingSteers.count)")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 1)
                                    .background(Capsule().fill(Color.accentColor))
                            }
                        }
                        .foregroundStyle(isSteeringExpanded ? Color.accentColor : Color.primary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(
                            Capsule().fill(isSteeringExpanded ? Color.accentColor.opacity(0.16) : Color(UIColor.secondarySystemBackground).opacity(0.85))
                        )
                        .overlay(
                            Capsule().strokeBorder(isSteeringExpanded ? Color.accentColor.opacity(0.5) : Color.secondary.opacity(0.2), lineWidth: 0.75)
                        )
                    }
                    .buttonStyle(.plain)
                }

                Spacer()
            }
            .padding(.horizontal, 14)

            // Opt-in steering input panel (user must explicitly open it)
            if vm.isProcessing && isSteeringExpanded {
                VStack(alignment: .leading, spacing: 6) {
                    if !pendingSteers.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(pendingSteers) { note in
                                    HStack(spacing: 4) {
                                        Text(note.text)
                                            .font(.caption2)
                                            .lineLimit(1)
                                        Button {
                                            store.removeSteering(sessionKey: sessionKey, noteId: note.id)
                                        } label: {
                                            Image(systemName: "xmark.circle.fill")
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(Capsule().fill(Color.accentColor.opacity(0.12)))
                                }
                            }
                        }
                    }

                    HStack(spacing: 8) {
                        TextField(
                            isChinese ? "输入纠偏指令（下个工具结果自动注入）…" : "Send course correction for next tool turn…",
                            text: $steeringDraft
                        )
                        .font(.subheadline)
                        .textFieldStyle(.plain)
                        .submitLabel(.send)
                        .onSubmit { submitSteering() }

                        Button {
                            submitSteering()
                        } label: {
                            Image(systemName: "arrow.up.circle.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(steeringDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Color.secondary.opacity(0.4) : Color.accentColor)
                        }
                        .disabled(steeringDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color(UIColor.secondarySystemBackground))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color.accentColor.opacity(0.35), lineWidth: 0.75)
                )
                .padding(.horizontal, 14)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .padding(.bottom, 2)
    }

    private func submitSteering() {
        let trimmed = steeringDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if store.enqueueSteering(sessionKey: sessionKey, text: trimmed) != nil {
            steeringDraft = ""
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            vm.transientNotice = isChinese ? "已加入纠偏队列，将在下一工具结果生效" : "Steering queued for next tool result"
        }
    }
}
