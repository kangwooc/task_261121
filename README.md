# task

## AI 활용 및 세션 로깅

- **사용 도구**: Claude Code (CLI), Codex CLI (설계 문서 교차 리뷰)
- **로깅 방식**: Claude Code hooks (`.claude/settings.json`) → `.claude/hooks/save-transcript.sh`
  - `Stop`, `SubagentStop`, `PreCompact`, `SessionEnd` 이벤트마다 Claude Code가 기록하는 세션 원본 트랜스크립트(JSONL)를 `ai-logs/claude-code/<session-id>.jsonl` 로 복사합니다.
  - 트랜스크립트에는 입력한 프롬프트, AI 응답, 에이전트가 실행한 명령과 파일 수정 내역(tool call/result)이 시간 순으로 모두 포함됩니다.
  - 서브에이전트 세션은 `ai-logs/claude-code/<session-id>-subagents/` 에 저장됩니다.
  - 이 프로젝트 디렉터리에서 실행된 Codex CLI 세션(`~/.codex/sessions/**/*.jsonl` 중 `cwd`가 프로젝트 루트인 것)도 같은 훅이 `ai-logs/codex/` 로 복사합니다.
- **마스킹**: 복사 시 API 키/토큰, 이메일, 로컬 사용자명, 개인 이름 등 민감 정보만 `***` 로 치환하며, 그 외 편집은 하지 않습니다.
- 요구 도구: `jq`, `perl`
