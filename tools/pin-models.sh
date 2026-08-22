#!/usr/bin/env bash
#
# Pin SHA-256 checksums for the models in the catalogue.
#
# Every entry in CandidateModels.kt currently carries the sentinel UNPINNED, and
# ModelDownloadGuard refuses to download any of them. That is deliberate: this
# source tree was produced in an environment with no network route to the model
# hosts, and shipping a checksum that had never been verified against a real
# download would look like a guarantee while being worthless.
#
# Run this on a machine that can reach the hosts. It downloads each artifact,
# computes its SHA-256 and actual size, and prints the Kotlin lines to paste in.
# It does not edit the catalogue for you - a human should look at the sizes and
# confirm they are plausible before pinning.
#
# Usage:
#   tools/pin-models.sh [output-dir]
#
set -euo pipefail

OUT_DIR="${1:-$(mktemp -d)}"
mkdir -p "$OUT_DIR"

CATALOGUE="model-manager/src/main/kotlin/dev/voicecomposer/models/CandidateModels.kt"

if [[ ! -f "$CATALOGUE" ]]; then
  echo "Run this from the repository root (cannot find $CATALOGUE)." >&2
  exit 1
fi

# Extract id / sourceUrl pairs from the catalogue rather than duplicating them
# here, so this script cannot drift from what the app actually offers.
mapfile -t IDS < <(grep -oP '^\s*id = "\K[^"]+' "$CATALOGUE")
mapfile -t URLS < <(grep -oP '^\s*sourceUrl = "\K[^"]+' "$CATALOGUE")

if [[ ${#IDS[@]} -ne ${#URLS[@]} ]]; then
  echo "Parsed ${#IDS[@]} ids but ${#URLS[@]} urls - the catalogue format changed." >&2
  echo "Fix the greps in this script rather than pinning by hand." >&2
  exit 1
fi

echo "Pinning ${#IDS[@]} models into $OUT_DIR"
echo

for i in "${!IDS[@]}"; do
  id="${IDS[$i]}"
  url="${URLS[$i]}"
  file="$OUT_DIR/$id"

  echo "==> $id"
  echo "    $url"

  if [[ ! -f "$file" ]]; then
    # --fail so an HTML error page is never hashed as if it were a model.
    curl --fail --location --progress-bar --output "$file" "$url"
  else
    echo "    (already downloaded)"
  fi

  sha="$(sha256sum "$file" | cut -d' ' -f1)"
  size="$(stat -c%s "$file")"

  echo
  echo "    sha256 = \"$sha\","
  echo "    expectedSizeBytes = ${size}L,"
  echo
done

cat <<'EOF'
------------------------------------------------------------------
Next steps:

1. Sanity-check each size above against what the publisher advertises.
   A file that is far too small is usually an error page, not a model.
2. Paste the sha256 and expectedSizeBytes lines into CandidateModels.kt,
   replacing the UNPINNED sentinels.
3. Run: ./gradlew :model-manager:test

   The test `no shipped catalog entry claims a checksum it cannot back up`
   asserts that no entry carries a well-formed hash. It WILL fail once you
   pin real values - that is its purpose. Delete that test in the same
   commit that pins the checksums, and say in the commit message which
   artifacts you downloaded and when.
------------------------------------------------------------------
EOF
