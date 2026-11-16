# Shared shell entry; caller defines ROOT_DIR. No artifacts are written here.
AGENT_RUNS_DIR="$("${PYTHON:-python}" -B "$ROOT_DIR/tools/agent_runs.py" output-root)"
AGENT_CACHE_DIR="$("${PYTHON:-python}" -B "$ROOT_DIR/tools/agent_runs.py" cache)"
if command -v cygpath >/dev/null 2>&1; then
  AGENT_RUNS_DIR="$(cygpath -u "$AGENT_RUNS_DIR")"
  AGENT_CACHE_DIR="$(cygpath -u "$AGENT_CACHE_DIR")"
fi
export AGENT_RUNS_DIR AGENT_CACHE_DIR
export PYTHONDONTWRITEBYTECODE=1
