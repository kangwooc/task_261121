#!/usr/bin/env bash
# Claude Code hook: copy the raw session transcript (prompts, responses, tool calls/results)
# into ai-logs/claude-code/, masking secrets only. Runs on Stop / SubagentStop / PreCompact / SessionEnd.
set -euo pipefail

input=$(cat)
transcript=$(jq -r '.transcript_path // empty' <<<"$input")
session=$(jq -r '.session_id // empty' <<<"$input")
[[ -n "$transcript" && -f "$transcript" && -n "$session" ]] || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
out="$root/ai-logs/claude-code"
mkdir -p "$out"

mask() {
  perl -CSD -pe '
    s/sk-ant-[A-Za-z0-9_\-]{10,}/***/g;
    s/\bsk-[A-Za-z0-9_\-]{20,}/***/g;
    s/\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})/***/g;
    s/\bAKIA[0-9A-Z]{16}\b/***/g;
    s/\bxox[abpr]-[A-Za-z0-9\-]{10,}/***/g;
    s/(Bearer\s+)[A-Za-z0-9._\-]{16,}/$1***/g;
    s/(Google Account: )[^(\\"]+/$1*** /g;
    s/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/***/g;
    s/\Q$ENV{USER}\E/***/g if $ENV{USER};
    # forbidden names are written as escapes so the literal never appears in the repo
    s/(?i)\x63\x6c\x6f(?![a-z])/***/g;
    s/\x{d074}\x{b85c}\x{bc84}\x{cd94}\x{c5bc}\x{d328}\x{c158}|\x{d074}\x{b85c}(?!\x{b4dc})/***/g;
  '
}

mask <"$transcript" >"$out/$session.jsonl"

# Subagent transcripts live next to the main one: <dir>/<session>/subagents/*.jsonl
sub="${transcript%.jsonl}/subagents"
if [[ -d "$sub" ]]; then
  mkdir -p "$out/$session-subagents"
  for f in "$sub"/*.jsonl; do
    [[ -f "$f" ]] && mask <"$f" >"$out/$session-subagents/$(basename "$f")"
  done
fi

# Codex CLI sessions run from this project (invoked during the Claude session) -> ai-logs/codex/
codex_sessions="${CODEX_HOME:-$HOME/.codex}/sessions"
if [[ -d "$codex_sessions" ]]; then
  mkdir -p "$root/ai-logs/codex"
  grep -rlF --include='*.jsonl' "\"cwd\":\"$root\"" "$codex_sessions" 2>/dev/null | while read -r f; do
    mask <"$f" >"$root/ai-logs/codex/$(basename "$f")"
  done
fi
exit 0
