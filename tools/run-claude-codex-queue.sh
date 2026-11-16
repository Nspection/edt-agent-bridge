#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/tools/agent-runs.sh"

export FLOW_SCRIPT="${FLOW_SCRIPT:-$ROOT_DIR/tools/run-claude-codex-flow.sh}"
export FLOW_RUNS_ROOT="${FLOW_RUNS_ROOT:-$AGENT_RUNS_DIR/claude-codex-flow}"
export QUEUE_ROOT="${QUEUE_ROOT:-$AGENT_RUNS_DIR/claude-codex-queue}"
export QUEUE_DIR="${QUEUE_DIR:-$QUEUE_ROOT/queue}"
export QUEUE_RUNS_DIR="${QUEUE_RUNS_DIR:-$QUEUE_ROOT/runs}"
export IMPLEMENTER="${IMPLEMENTER:-claude}"
export CLAUDE_MODEL="${CLAUDE_MODEL:-claude-sonnet-4-6}"
export CLAUDE_OUTPUT_FORMAT="${CLAUDE_OUTPUT_FORMAT:-stream-json}"
export CLAUDE_PERMISSION_MODE="${CLAUDE_PERMISSION_MODE:-acceptEdits}"
export CLAUDE_ALLOWED_TOOLS="${CLAUDE_ALLOWED_TOOLS:-Read,Edit,MultiEdit,Write,Glob,Grep,LS,Bash}"
export CLAUDE_EFFORT="${CLAUDE_EFFORT:-medium}"

exec bash "$ROOT_DIR/tools/run-qwen-codex-queue.sh" "$@"
