import SwiftUI

/// Upgraded hybrid-memory search, category breakdown, and duplicate consolidation section
/// embedded cleanly inside `MemoryManagementView`.
struct NextMemoryInsightsSection: View {
    @State private var searchQuery: String = ""
    @State private var selectedCategory: NextMemoryCategory? = nil
    @State private var hits: [NextMemorySearchHit] = []
    @State private var totalActiveCount: Int = 0
    @State private var statusBanner: String? = nil

    private var isChinese: Bool {
        let lang = AppBundle.current.preferredLocalizations.first ?? Locale.preferredLanguages.first ?? "en"
        return lang.lowercased().hasPrefix("zh")
    }

    var body: some View {
        Section {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField(
                    isChinese ? "BM25 混合检索记忆…" : "Hybrid BM25 memory search…",
                    text: $searchQuery
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                if !searchQuery.isEmpty {
                    Button {
                        searchQuery = ""
                        refresh()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    categoryChip(nil, title: isChinese ? "全部 (\(totalActiveCount))" : "All (\(totalActiveCount))")
                    ForEach(NextMemoryCategory.allCases, id: \.self) { cat in
                        categoryChip(cat, title: cat.displayName)
                    }
                }
                .padding(.vertical, 2)
            }

            if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || selectedCategory != nil {
                if hits.isEmpty {
                    Text(isChinese ? "暂无匹配的记忆条目" : "No matching memory entries")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(hits.prefix(8), id: \.record.id) { hit in
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(hit.record.category.displayName)
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.purple)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Capsule().fill(Color.purple.opacity(0.12)))
                                Text(hit.record.source)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Button(role: .destructive) {
                                    _ = AdvancedMemoryManager.shared.delete(id: hit.record.id)
                                    refresh()
                                } label: {
                                    Image(systemName: "trash")
                                        .font(.caption2)
                                }
                                .buttonStyle(.plain)
                            }
                            Text(hit.record.content)
                                .font(.caption)
                                .lineLimit(3)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            Button {
                let removed = AdvancedMemoryManager.shared.consolidateDuplicates()
                refresh()
                statusBanner = removed > 0
                    ? (isChinese ? "已合并 \(removed) 条近重复记忆" : "Consolidated \(removed) near-duplicate memory entries")
                    : (isChinese ? "记忆库已精简，无重复条目" : "Memory index is clean — no duplicates found")
            } label: {
                Label(
                    isChinese ? "整理并合并重复记忆" : "Consolidate Near-Duplicate Memories",
                    systemImage: "sparkles.rectangle.stack"
                )
            }

            if let msg = statusBanner {
                Text(msg)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text(isChinese ? "智能记忆引擎（BM25 检索）" : "Hybrid Memory Engine (BM25)")
        }
        .onAppear { refresh() }
        .onChange(of: searchQuery) { _ in refresh() }
        .onChange(of: selectedCategory) { _ in refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .memoryFilesDidChange)) { _ in
            refresh()
        }
    }

    private func categoryChip(_ cat: NextMemoryCategory?, title: String) -> some View {
        let selected = selectedCategory == cat
        return Button {
            selectedCategory = cat
        } label: {
            Text(title)
                .font(.caption2.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? .white : .primary)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(Capsule().fill(selected ? Color.purple : Color(UIColor.secondarySystemBackground)))
        }
        .buttonStyle(.plain)
    }

    private func refresh() {
        let manager = AdvancedMemoryManager.shared
        totalActiveCount = manager.activeRecords().count
        hits = manager.search(query: searchQuery, category: selectedCategory, limit: 12, recordAccess: false)
    }
}
