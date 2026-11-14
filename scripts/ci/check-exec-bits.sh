#!/usr/bin/env bash
# Regression guard: files that CI/CD executes directly must carry the executable bit IN GIT (mode 100755).
# Windows checkouts lose the bit silently, and a hosted Linux runner then fails with "Permission denied" (workflow run 43599319418).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
bad=0
for f in mvnw scripts/ci/*.sh; do
  mode=$(git ls-files -s -- "$f" | cut -d' ' -f1)
  if [ "$mode" != "100755" ]; then echo "not executable in git: $f (mode ${mode:-untracked}); fix: git update-index --chmod=+x $f" >&2; bad=1; fi
done
[ "$bad" = 0 ] && echo "executable bits ok"
exit "$bad"
