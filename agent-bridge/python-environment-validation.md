# Managed Python environment validation — 2026-11-10

Branch `codex/direct-testclient-python-env`, based on main `3746f716e0846decfa5d3026ad29358c49a0cf93`. Local candidate **1.4.0.20261110pyenvfinal2** only. No commit, push, release, user installation or user EDT/base operation was performed in this stage. This local test qualifier is not a public release version; a future release must sort above the current public1.4.0.20261110testclient2.

## Functional acceptance

Own `EDT-Agent-Bridge-Dev` workspace and own newly provisioned disposable file base on Windows x64, CPython3.13 and platform8.5.1.1522. The fixture contained only the native-created minimal configuration; no application data or custom BSL was needed.

- Automatic source discovery without python_path found the installed interpreter. Explicit prepare created an owned venv and installed only the hash-locked package set. Two immediate requests returned the same job ID; no duplicate pip process was scheduled.
- A repeated prepare returned the same ID, ready=true/reused=true/created=false. The install log hash stayed unchanged.
- A ready environment prepared on dev2 was reused after a private EDT restart into final1. The exact final2 candidate independently repeated preparation/warm reuse, managed start and cleanup; its helper's restart-labelled readiness check was a same-process recheck after preparation, not an additional claimed final2 restart run.
- Native direct TestClient start omitted both python_path and environment_id and returned the managed ID and connected=true. Semantic window observation confirmed the own PythonEnvironmentFixture client.
- Environment cleanup while that client was live failed before deletion. Environment readiness and the connected client were unchanged. After client cleanup, environment cleanup succeeded and removed the venv.
- A separate live changed-file test on final1/dev2-era state added an owned test note: readiness became false and cleanup failed while preserving the note and the venv. Removing just the test note restored readiness. Final2 contracts also enforce inventory-only deletion, path bounds and explicit-managed-path lease association.
- The initial long workspace path produced Windows error206 while pip installed a deep pywin32 license path. The failed job was inspected and its own environment cleaned through the API. The short LocalAppData workspace-scoped cache fixed the real installation; no global long-path or security setting was changed.

## Automated/package checks

**700 Java tests** pass, including17 environment contracts: reuse without installation, isolated/hash-locked pip target, changed/extra file preservation, in-use and concurrent start/probe leases, failed install/status/cleanup, partial cleanup, path traversal protection, legacy explicit path, managed explicit-path association, source interpreter changes, unsupported source, workspace ownership and retained-environment bounds. Existing **7 worker +16 table Python tests** also pass. Standalone product boundary and p2 director verifyOnly use a private copy of a profile and do not install into the user EDT.

The final JAR must contain the exact resource lock bytes and no Python environment/wheels/cache/pyc. Package artifacts are under ignored `.runs/agent-bridge/20261110pyenvfinal2`; main receipts/scripts/logs are under `.runs/python-environment` (`state.json`, `first-attempt-state.json`, `final1-state.json`, `prepare-live.py`, `client-live.py`, `tests-final2.log`, `worker-tests-final2.log`, `table-tests-final2.log`).

## Cleanup

Both own disposable native fixtures were removed with native cleanup receipts confirming database/launcher/application-settings removal and audit-project removal. All own clients and both completed managed-environment attempts were cleaned. The failed original deep-path environment had its own verified cleanup. Temporary fixture roots and the workspace-specific test cache witness are removed after the processes exit; the private EDT is closed and original bundles.info restored byte for byte. Safe logs/build artifacts and cleaned-client audit tombstones are retained as evidence.

The own private SDK installation/workspace pre-existed and is preserved. User EDT, user bases, trust configuration and global Python package installation were not operated on. No runtime acceptance is claimed for other Python versions/architectures, offline downloads, interrupted OS/JVM installation or arbitrary user-corrupted caches. Interrupted/changed ownership is fail-closed; no automatic destructive recovery is provided.

## Public release acceptance

Release candidate **1.4.0.20261110zzzpyenv1** was reviewed and independently rechecked. **702 Java tests** (19 environment contracts), **7 worker +16 table Python tests**, standalone boundary and private p2 verifyOnly passed. The review added explicit Windows AMD64 preflight and fail-closed interrupted subprocess handling; an uncertain process outcome never produces a deletable inventory.

Fresh own platform8.5.1.1522 fixture repeated automatic discovery, locked preparation, duplicate-job reuse, warm reuse without reinstallation, managed client start without python_path, in-use cleanup rejection and subsequent client/environment/base/project cleanup. Semantic observation confirmed the fixture window after native window readiness. The first earlier read-only observation failed at preflight with action_dispatched=false/outcome_unknown=false; its original client was inspected and cleaned before a fresh controlled start. Connected alone is not claimed as semantic UI readiness, and this release does not add a UI-readiness wait to TestClient.

The private EDT was closed and its original bundles.info restored byte for byte. Own fixture root, Python cache witness and temporary snapshot were removed; safe receipts remain in ignored `.runs/release-pyenv`. User EDT and bases were not operated on.
