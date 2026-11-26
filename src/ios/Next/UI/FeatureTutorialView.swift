import SwiftUI

/// Searchable bilingual Feature Guide & Tutorials view launched from the bottom of Settings.
struct FeatureTutorialView: View {
    @State private var searchQuery: String = ""
    @State private var selectedCategory: TutorialCategory = .all
    @State private var expandedIds: Set<String> = ["quickstart-providers"]

    private var isChinese: Bool {
        let lang = AppBundle.current.preferredLocalizations.first ?? Locale.preferredLanguages.first ?? "en"
        return lang.lowercased().hasPrefix("zh")
    }

    private var filteredItems: [FeatureTutorialItem] {
        FeatureTutorialCatalog.search(query: searchQuery, category: selectedCategory)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Pinned instant search bar
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField(
                    isChinese ? "搜索功能、命令、硅基流动、Plan Mode…" : "Search features, commands, SiliconFlow, Plan Mode…",
                    text: $searchQuery
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                if !searchQuery.isEmpty {
                    Button {
                        searchQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(UIColor.secondarySystemBackground))
            )
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)

            // Category filter chips
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(TutorialCategory.allCases) { category in
                        let selected = selectedCategory == category
                        Button {
                            withAnimation(.easeInOut(duration: 0.15)) {
                                selectedCategory = category
                            }
                        } label: {
                            Text(category.title(chinese: isChinese))
                                .font(.caption.weight(selected ? .semibold : .regular))
                                .foregroundStyle(selected ? .white : .primary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    Capsule().fill(selected ? Color.accentColor : Color(UIColor.secondarySystemBackground))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
            }

            Divider()

            if filteredItems.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 36))
                        .foregroundStyle(.secondary)
                    Text(isChinese ? "没有找到匹配的功能说明" : "No matching guides found")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Button(isChinese ? "清空搜索" : "Reset Search") {
                        searchQuery = ""
                        selectedCategory = .all
                    }
                    .font(.footnote.weight(.medium))
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(filteredItems) { item in
                            tutorialCard(item)
                        }
                    }
                    .padding(16)
                }
            }
        }
        .navigationTitle(isChinese ? "功能教程与指南" : "Feature Guide")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func tutorialCard(_ item: FeatureTutorialItem) -> some View {
        let isExpanded = expandedIds.contains(item.id) || !searchQuery.trimmingCharacters(in: .whitespaces).isEmpty
        return VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if expandedIds.contains(item.id) {
                        expandedIds.remove(item.id)
                    } else {
                        expandedIds.insert(item.id)
                    }
                }
            } label: {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: item.icon)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 34, height: 34)
                        .background(categoryColor(item.category), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title(chinese: isChinese))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                        Text(item.summary(chinese: isChinese))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.leading)
                    }

                    Spacer(minLength: 4)

                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
            }
            .buttonStyle(.plain)

            if isExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(item.steps(chinese: isChinese).enumerated()), id: \.offset) { idx, step in
                        HStack(alignment: .top, spacing: 8) {
                            Text("\(idx + 1).")
                                .font(.caption.monospacedDigit().weight(.semibold))
                                .foregroundStyle(categoryColor(item.category))
                            Text(step)
                                .font(.caption)
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }

                    if let tip = item.tip(chinese: isChinese), !tip.isEmpty {
                        HStack(alignment: .top, spacing: 6) {
                            Image(systemName: "lightbulb.fill")
                                .font(.caption)
                                .foregroundStyle(.orange)
                            Text(tip)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(Color.orange.opacity(0.10))
                        )
                        .padding(.top, 4)
                    }
                }
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color(UIColor.secondarySystemGroupedBackground))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color(UIColor.separator).opacity(0.35), lineWidth: 0.6)
        )
    }

    private func categoryColor(_ category: TutorialCategory) -> Color {
        switch category {
        case .all: return .accentColor
        case .quickStart: return .blue
        case .agentWorkflow: return .purple
        case .memoryAndTools: return .teal
        case .modelsAndProviders: return .indigo
        case .storageAndSystem: return .orange
        }
    }
}
