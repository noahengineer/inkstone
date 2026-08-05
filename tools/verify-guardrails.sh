#!/usr/bin/env bash
#
# Proves the two M0 guardrails by planting a violation, asserting the build
# fails, and reverting. ROADMAP M0 requires this demonstration; running it in CI
# means it stays true.
#
#   tools/verify-guardrails.sh imports   # Guardrail 1 — any OS
#   tools/verify-guardrails.sh ios       # Guardrail 2 — any OS, see note below
#   tools/verify-guardrails.sh all
#
# Note on Guardrail 2 and the host OS: Kotlin/Native cross-compiles klibs for
# Apple targets on Windows and Linux; only *linking* a framework needs macOS.
# So the substance of the guardrail — "does a JVM-only dependency break the iOS
# build?" — is testable everywhere, using the compile task off macOS and the
# link task on it. Off macOS this script says so rather than pretending.
#
set -uo pipefail

cd "$(dirname "$0")/.."

GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; DIM=$'\033[2m'; OFF=$'\033[0m'
pass() { echo "${GREEN}PASS${OFF}  $1"; }
fail() { echo "${RED}FAIL${OFF}  $1"; exit 1; }
info() { echo "${DIM}····${OFF}  $1"; }

VICTIM_KT="core/model/src/commonMain/kotlin/dev/inkstone/core/model/GuardrailProbe.kt"
SYNC_BUILD="core/sync/build.gradle.kts"
SYNC_BACKUP="$(mktemp)"
: > "$SYNC_BACKUP"

cleanup() {
  rm -f "$VICTIM_KT"
  if [ -s "$SYNC_BACKUP" ]; then
    cp "$SYNC_BACKUP" "$SYNC_BUILD"
    rm -f "$SYNC_BACKUP"
  fi
}
trap cleanup EXIT

if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=./gradlew.bat; fi

# ---------------------------------------------------------------------------
# Guardrail 1 — android.*/androidx.* in shared :core code must fail the build.
# ---------------------------------------------------------------------------
verify_imports() {
  info "planting 'import android.content.Context' in :core:model commonMain"
  cat > "$VICTIM_KT" <<'EOF'
package dev.inkstone.core.model

import android.content.Context

internal val guardrailProbe: Context? = null
EOF

  if "$GRADLE" --quiet checkCoreIsPlatformFree > /tmp/guardrail-imports.log 2>&1; then
    cat /tmp/guardrail-imports.log
    fail "Guardrail 1 did NOT fail on a planted android.content.Context import."
  fi

  if ! grep -q "core guardrail FAILED" /tmp/guardrail-imports.log; then
    cat /tmp/guardrail-imports.log
    fail "Guardrail 1 failed, but not with its own message — check the real cause."
  fi

  rm -f "$VICTIM_KT"
  info "reverted; re-running to confirm the build is green again"
  "$GRADLE" --quiet checkCoreIsPlatformFree || fail "Guardrail 1 still failing after revert."
  pass "Guardrail 1 — rejects platform imports in shared :core code, and is green without them."
}

# ---------------------------------------------------------------------------
# Guardrail 2 — a JVM-only dependency in :core:sync commonMain must break the
# iOS build. This is the check the import scan cannot make: OkHttp contains no
# android.* import at all, so Guardrail 1 stays perfectly green while the iPad
# path is already dead.
# ---------------------------------------------------------------------------
verify_ios() {
  if [ "$(uname -s)" = "Darwin" ]; then
    IOS_TASK=":core:sync:linkDebugFrameworkIosSimulatorArm64"
    info "macOS host — using the full framework link"
  else
    IOS_TASK=":core:sync:compileKotlinIosSimulatorArm64"
    info "$(uname -s) host — Kotlin/Native cannot LINK Apple frameworks here, so"
    info "using the klib compile instead. Catches the same class of breakage;"
    info "the macOS CI job still runs the real link."
  fi

  info "baseline: $IOS_TASK must succeed before we break it"
  "$GRADLE" --quiet "$IOS_TASK" \
    || fail "Baseline iOS build already failing; fix that before testing the guardrail."

  cp "$SYNC_BUILD" "$SYNC_BACKUP"
  info "planting an OkHttp dependency in :core:sync commonMain"
  sed -i 's#^\( *\)implementation(libs\.ktor\.client\.core)#&\n\1implementation("com.squareup.okhttp3:okhttp:4.12.0")#' \
    "$SYNC_BUILD"
  grep -q 'okhttp3:okhttp' "$SYNC_BUILD" || fail "Could not plant the dependency — anchor line not found."

  if "$GRADLE" --quiet "$IOS_TASK" > /tmp/guardrail-ios.log 2>&1; then
    cat /tmp/guardrail-ios.log
    fail "Guardrail 2 did NOT fail with a JVM-only dependency in :core:sync commonMain."
  fi

  info "confirming Guardrail 1 stayed green throughout — this is the whole point"
  "$GRADLE" --quiet checkCoreIsPlatformFree \
    || fail "Guardrail 1 unexpectedly failed; the demonstration is no longer meaningful."

  cp "$SYNC_BACKUP" "$SYNC_BUILD"
  : > "$SYNC_BACKUP"
  info "reverted; re-running to confirm the iOS build is green again"
  "$GRADLE" --quiet "$IOS_TASK" || fail "iOS build still failing after revert."
  pass "Guardrail 2 — a JVM-only dependency breaks the iOS build while the import check stays green."
}

case "${1:-all}" in
  imports) verify_imports ;;
  ios)     verify_ios ;;
  all)     verify_imports; verify_ios ;;
  *)       echo "usage: $0 [imports|ios|all]"; exit 2 ;;
esac
