# EDT Agent Bridge development

This repository is the standalone MCP plugin. Do not use a separate CodePilot source checkout as a build dependency. Preserve user EDT/workspaces/databases; use only owned isolated test environments.

Before writing any run output read [agent-bridge/run-storage.md](agent-bridge/run-storage.md). Start a task with `python -B tools/agent_runs.py new --task <task>`, resolve output/cache paths with `path`/`cache`. `.runs` is a small ignored text pointer, not a directory. Never create `.runs/`, a junction or a symlink. New generated files, downloads, snapshots, p2 workspaces/profiles, SDKs, Node/Python/PowerShell distributions and nested Git worktrees must stay outside the checkout. Use `AGENT_RUNS_ROOT` / `AGENT_CACHE_ROOT` only for explicit external directories.

Use `python -B` and PYTHONDONTWRITEBYTECODE=1 for ad-hoc Python work. Historical `.runs/...` note paths can be read through `python -B tools/agent_runs.py resolve <path>`; do not modify pinned migration evidence. Old ad-hoc scripts may require adapting their literal paths before reuse.

Run the bounded workspace guard before builds/large scans. Retention is explicit preview then `prune --apply`, default keep5. Preserve pinned history, active leases, user data and registered worktrees. Do not delete repository `.git`, rewrite history, change protection settings or blindly clean caches.

Separate tooling/docs/migration commits from plugin behavior fixes. Before release review the actual diff, perform independent review requested by the user, and verify the exact artifact provenance and public p2 site. Never equate static checks with live client/database acceptance.
