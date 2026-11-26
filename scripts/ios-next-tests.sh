#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_BIN="${RUNNER_TEMP:-/tmp}/openminis-next-ios-tests"

echo "==> Compiling OpenMinis Next iOS Core + Unit Test Suite with swiftc..."
swiftc -O -parse-as-library \
  "$REPO/src/ios/Next/Core/AdvancedMemoryEngine.swift" \
  "$REPO/src/ios/Next/Core/ExecutionModePolicy.swift" \
  "$REPO/src/ios/Next/Core/UpdateStepsEngine.swift" \
  "$REPO/src/ios/Next/Core/ToolResultSteering.swift" \
  "$REPO/src/ios/Next/Core/CodingWorkflowCore.swift" \
  "$REPO/src/ios/Next/Core/ProviderBaseUrlNormalizer.swift" \
  "$REPO/src/ios/Next/Core/FeatureTutorialCatalog.swift" \
  "$REPO/src/ios/MinisTests/Standalone/OpenMinisNextSuiteTests.swift" \
  -o "$OUT_BIN"

echo "==> Running OpenMinis Next iOS Unit Test Suite..."
"$OUT_BIN"
rm -f "$OUT_BIN"
