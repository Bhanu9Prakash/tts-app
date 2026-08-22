#!/usr/bin/env bash
#
# Download each catalogue model and check its SHA-256 against what is pinned.
#
# Two modes, chosen automatically per model:
#
#   pinned    -> assert. A mismatch fails, because it means the published
#                archive changed under a URL we trust.
#   UNPINNED  -> report. Prints the hash and size to paste into
#                CandidateModels.kt.
#
# This is the mechanism behind the claim in docs/MODEL_COMPARISON.md that our
# checksums were computed from artifacts that were actually published, rather
# than copied from a README. It runs in CI on every push, so a silent upstream
# substitution is caught rather than assumed away.
#
# Usage:
#   tools/verify-model-checksums.sh [--max-bytes N] [--keep]
#
#     --max-bytes N  skip models whose expectedSizeBytes exceeds N. Used in CI
#                    to avoid pulling the ~1 GB model on every push.
#     --keep         keep downloads (default: delete after hashing)
#
set -euo pipefail

CATALOGUE="model-manager/src/main/kotlin/dev/voicecomposer/models/CandidateModels.kt"
MAX_BYTES=0
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --max-bytes) MAX_BYTES="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -f "$CATALOGUE" ]] || { echo "Run from the repository root." >&2; exit 1; }

WORK="$(mktemp -d)"
trap '[[ $KEEP -eq 1 ]] || rm -rf "$WORK"' EXIT

# Parse the catalogue rather than duplicating the model list here, so this
# script cannot drift from what the app actually offers.
mapfile -t IDS   < <(grep -oP '^\s*id = "\K[^"]+'          "$CATALOGUE")
mapfile -t URLS  < <(grep -oP '^\s*sourceUrl = "\K[^"]+'   "$CATALOGUE")
mapfile -t SHAS  < <(grep -oP '^\s*sha256 = \K\S+'         "$CATALOGUE" | tr -d ',' | tr -d '"')
mapfile -t SIZES < <(grep -oP '^\s*expectedSizeBytes = \K[0-9_]+' "$CATALOGUE" | tr -d '_')

n=${#IDS[@]}
if [[ $n -eq 0 || ${#URLS[@]} -ne $n || ${#SHAS[@]} -ne $n || ${#SIZES[@]} -ne $n ]]; then
  echo "Catalogue parse mismatch: ids=$n urls=${#URLS[@]} shas=${#SHAS[@]} sizes=${#SIZES[@]}" >&2
  echo "The catalogue format changed - fix the greps rather than pinning by hand." >&2
  exit 1
fi

failures=0
unpinned=0
skipped=0

echo "Checking $n models from $CATALOGUE"
echo

for i in $(seq 0 $((n - 1))); do
  id="${IDS[$i]}"; url="${URLS[$i]}"; pinned="${SHAS[$i]}"; declared="${SIZES[$i]}"

  if [[ "$MAX_BYTES" -gt 0 && "$declared" -gt "$MAX_BYTES" ]]; then
    printf '  SKIP    %-34s (declared %s bytes > limit %s)\n' "$id" "$declared" "$MAX_BYTES"
    skipped=$((skipped + 1))
    continue
  fi

  file="$WORK/$id.zip"
  # --fail so an HTML error page is never hashed as if it were a model.
  if ! curl --fail --location --silent --show-error --output "$file" "$url"; then
    printf '  FAIL    %-34s download failed: %s\n' "$id" "$url"
    failures=$((failures + 1))
    continue
  fi

  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  size="$(stat -c%s "$file")"

  if [[ "$pinned" == "UNPINNED" ]]; then
    unpinned=$((unpinned + 1))
    printf '  REPORT  %-34s\n' "$id"
    printf '          sha256 = "%s",\n' "$actual"
    printf '          expectedSizeBytes = %s,\n' "$(printf '%s' "$size" | sed ':a;s/\B[0-9]\{3\}\>/_&/;ta')"
  elif [[ "$pinned" != "$actual" ]]; then
    failures=$((failures + 1))
    printf '  FAIL    %-34s checksum mismatch\n' "$id"
    printf '          pinned %s\n' "$pinned"
    printf '          actual %s\n' "$actual"
  elif [[ "$declared" != "$size" ]]; then
    failures=$((failures + 1))
    printf '  FAIL    %-34s size mismatch: declared %s, actual %s\n' "$id" "$declared" "$size"
  else
    printf '  OK      %-34s %s\n' "$id" "$actual"
  fi

  [[ $KEEP -eq 1 ]] || rm -f "$file"
done

echo
echo "checked=$((n - skipped)) skipped=$skipped unpinned=$unpinned failures=$failures"

if [[ $failures -gt 0 ]]; then
  echo "FAILED: a published archive does not match what this repository pins." >&2
  exit 1
fi

if [[ $unpinned -gt 0 ]]; then
  echo "$unpinned model(s) are still UNPINNED. Paste the reported values into $CATALOGUE."
  echo "Until then ModelDownloadGuard refuses to download them, which is intended."
fi
