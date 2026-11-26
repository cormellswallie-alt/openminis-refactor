import Foundation
import SQLite3
import SwiftUI

// MARK: - SQLite Maintenance Report & ChatStore Extension

struct NextDatabaseMaintenanceReport: Equatable, Sendable {
    let bytesBefore: Int64
    let bytesAfter: Int64
    let walCheckpointed: Bool
    let vacuumSucceeded: Bool
    let optimizeSucceeded: Bool

    var freedBytes: Int64 { max(0, bytesBefore - bytesAfter) }
}

extension ChatStore {
    /// Run `PRAGMA wal_checkpoint(TRUNCATE)`, `VACUUM`, and `PRAGMA optimize` on `minis.db`
    /// to reclaim dead pages, truncate bloated WAL journals, and refresh query-planner stats.
    func runDatabaseMaintenance() -> NextDatabaseMaintenanceReport {
        let before = totalDatabaseFootprintBytes()
        guard let db else {
            return NextDatabaseMaintenanceReport(
                bytesBefore: before,
                bytesAfter: before,
                walCheckpointed: false,
                vacuumSucceeded: false,
                optimizeSucceeded: false
            )
        }
        let walOk = sqlite3_exec(db, "PRAGMA wal_checkpoint(TRUNCATE);", nil, nil, nil) == SQLITE_OK
        let vacuumOk = sqlite3_exec(db, "VACUUM;", nil, nil, nil) == SQLITE_OK
        let optOk = sqlite3_exec(db, "PRAGMA optimize;", nil, nil, nil) == SQLITE_OK
        let after = totalDatabaseFootprintBytes()
        return NextDatabaseMaintenanceReport(
            bytesBefore: before,
            bytesAfter: after,
            walCheckpointed: walOk,
            vacuumSucceeded: vacuumOk,
            optimizeSucceeded: optOk
        )
    }

    private func totalDatabaseFootprintBytes() -> Int64 {
        let fm = FileManager.default
        let paths = [dbURL.path, dbURL.path + "-wal", dbURL.path + "-shm"]
        var total: Int64 = 0
        for p in paths {
            if let attrs = try? fm.attributesOfItem(atPath: p),
               let sz = attrs[.size] as? Int64 {
                total += sz
            }
        }
        return total
    }
}

// MARK: - Device Storage Capacity Metrics

struct NextDeviceStorageMetrics: Equatable, Sendable {
    let availableBytes: Int64
    let totalBytes: Int64

    static func current() -> NextDeviceStorageMetrics {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        if let vals = try? home.resourceValues(forKeys: [
            .volumeAvailableCapacityForImportantUsageKey,
            .volumeAvailableCapacityKey,
            .volumeTotalCapacityKey,
        ]) {
            let avail = vals.volumeAvailableCapacityForImportantUsage
                ?? Int64(vals.volumeAvailableCapacity ?? 0)
            let total = Int64(vals.volumeTotalCapacity ?? 0)
            return NextDeviceStorageMetrics(availableBytes: max(0, avail), totalBytes: max(0, total))
        }
        return NextDeviceStorageMetrics(availableBytes: 0, totalBytes: 0)
    }
}

// MARK: - Visual Segmented Breakdown Bar for StorageManagementView

struct NextStorageSegment: Identifiable {
    let id: String
    let label: String
    let bytes: Int64
    let color: Color
}

struct NextStorageBreakdownBar: View {
    let segments: [NextStorageSegment]
    let totalBytes: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                let width = max(1, geo.size.width)
                let denom = Double(max(1, totalBytes))
                HStack(spacing: 1.5) {
                    ForEach(segments.filter { $0.bytes > 0 }) { seg in
                        let frac = max(0.02, Double(seg.bytes) / denom)
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(seg.color)
                            .frame(width: max(3, width * CGFloat(frac)))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .clipShape(Capsule())
                .background(Capsule().fill(Color(UIColor.tertiarySystemFill)))
            }
            .frame(height: 10)

            let nonZero = segments.filter { $0.bytes > 0 }
            if !nonZero.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 95), spacing: 6)], alignment: .leading, spacing: 4) {
                    ForEach(nonZero) { seg in
                        HStack(spacing: 4) {
                            Circle().fill(seg.color).frame(width: 7, height: 7)
                            Text(seg.label)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}
