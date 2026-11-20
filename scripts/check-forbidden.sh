#!/usr/bin/env bash
# Fails if forbidden names appear in repo file contents or relative paths. Patterns are escapes only.
set -euo pipefail
cd "$(dirname "$0")/.."
export LC_ALL=en_US.UTF-8
bad=0
while IFS= read -r -d '' f; do
  if printf '%s' "$f" | perl -CSD -ne 'exit(/(?i)\x63\x6c\x6f(?![a-z])|\x{d074}\x{b85c}\x{bc84}\x{cd94}\x{c5bc}\x{d328}\x{c158}|\x{d074}\x{b85c}(?!\x{b4dc})/ ? 0 : 1)'; then
    echo "$f: (path)"; bad=1
  fi
  grep -Iq . "$f" || continue
  perl -CSD -ne 'if (/(?i)\x63\x6c\x6f(?![a-z])|\x{d074}\x{b85c}\x{bc84}\x{cd94}\x{c5bc}\x{d328}\x{c158}|\x{d074}\x{b85c}(?!\x{b4dc})/) { print "$ARGV:$.\n"; $f=1 } END { exit($f ? 1 : 0) }' "$f" || bad=1
done < <(find . \( -name .git -o -name build -o -name .gradle -o -name .playwright-mcp -o -name .superpowers \) -prune -o -type f -print0)
exit $bad
