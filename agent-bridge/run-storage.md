# Agent run storage

Run artifacts live outside the checkout. The `.runs` entry is an ignored UTF-8 text pointer, never a directory, junction or symlink. Do not append filesystem children to it. Resolve paths through the helper.

```powershell
python -B tools/agent_runs.py new --task github-review
$run = python -B tools/agent_runs.py path
$cache = python -B tools/agent_runs.py cache
python -B tools/build-agent-bridge.py --qualifier development --edt-home $env:CODEPILOT_EDT_HOME --java-home $env:JAVA_HOME
python -B tools/test-agent-bridge.py --build "$run/agent-bridge/development" --java-home $env:JAVA_HOME
python -B tools/agent_runs.py resolve .runs/bug-report-ux/completion.json
python -B tools/agent_runs.py guard
```

Default: `%LOCALAPPDATA%/edt-agent-bridge/runs/<UTC-date>-<task>`; on non-Windows hosts use `~/.local/share/edt-agent-bridge`. `AGENT_RUNS_ROOT` overrides the exact current run directory. It must be outside the checkout and its ancestors. New runs accept only new/empty directories or previously owned markers, never adopt a nonempty user directory. `AGENT_CACHE_ROOT` overrides the cache directory (default `%LOCALAPPDATA%/edt-agent-bridge/cache`). Put downloaded SDKs, PowerShell/Node/Python distributions, p2 target/profile caches and downloaded ZIP/DT snapshots there. Generated build/test outputs belong to the current run. Keep user bases and EDT workspaces separate from both.

Existing `--build .runs/...` arguments resolve through the pointer. The `resolve` command also searches the pinned migration location for old note paths after the pointer advances. Historical receipts, absolute paths and manifests are preserved verbatim. Old verified builds are read-only: new tooling/source provenance requires a fresh build in a new run. Legacy ad-hoc scripts from the archived directory may contain literal old paths and must be adapted before re-execution; do not replace the pointer with a symlink or restore the huge directory as a compatibility workaround. Historical evidence can always be opened via `resolve`.

Python entry points disable in-tree bytecode and propagate PYTHONDONTWRITEBYTECODE to child processes; use `python -B` for ad-hoc commands. Shell wrappers use `agent-runs.sh`; PowerShell wrappers use the same Python resolver. Build/test/package hold an active lease so retention preserves a running job even if the pointer moves.

## Retention

```powershell
python -B tools/agent_runs.py prune
python -B tools/agent_runs.py prune --keep 5 --apply
```

Default is a preview, keeping the last 5 runs (or `AGENT_RUNS_KEEP`). `--parent` chooses a run collection. The current run counts toward the limit; pinned history, active leases, pointer/cache ancestors and registered Git-worktrees are always protected. Only direct children with a valid marker belonging to this repository are candidates. A shared storage lock covers lease changes, pointer changes and the final recheck/delete. A stale `.active` lease is deliberately preserved after a crash: investigate the recorded PID/path before removing that lease. No scheduled or implicit pruning runs. The migrated legacy collection is pinned and is not deleted by retention.

## Migration and recovery

```powershell
python -B tools/agent_runs.py migrate --destination "$env:LOCALAPPDATA/edt-agent-bridge/runs/2026-11-16-legacy-migration"
python -B tools/agent_runs.py migrate --destination <same-path> --resume
```

Stop only owned artifact writers first. Migration inventories every file, directory and link into an external JSONL manifest, then atomically renames the entire `.runs` directory on the same volume. There is no recursive source deletion or copy-and-delete gap. It verifies each data file's Windows file ID, device, size and modification timestamp; Git-regenerated worktree `.git` control files are instead validated against their registered common Git directory, all directory/link types, the complete reverse path set, and every registered worktree's repaired path. Optional `--sha256` also reads and verifies all file contents; it can be much slower on hundreds of thousands of files under antivirus scanning. The pointer and pinned ownership marker are published only after verification. Interrupted complete inventories/moves resume from the saved phase; partial inventories remain external and should be preserved before restarting inventory. No main `.git` directory is moved or deleted; `git worktree repair` only updates Git's registered paths for the relocated worktrees.

The guard stops early above 20,000 objects or 1 GiB outside the main `.git`, before build source hashing. It is wired into Bridge and Native builds; run it manually before provisioning/indexing a checkout. It counts directories too and never follows symlinks. No hooks are silently installed or trusted.
