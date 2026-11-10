# Managed Python for direct TestClient

Prepare once per EDT workspace:

```json
{"action":"prepare_environment"}
```

Call `edt_test_client` with this payload, save `job_id`, then wait via `edt_operation_status`. Require terminal DONE and `result.ready=true`. The result supplies `environment_id`, the owned `python_path`, pinned package versions, and `created`/`reused`. Same-argument preparations already in progress reuse the original job. A conflicting concurrent preparation is refused.

Subsequent direct starts only need:

```json
{"action":"start","project":"YourPermittedTestProject"}
```

This selects the prepared active environment; it never installs dependencies or updates the database. Existing native-EQUAL/project/base/PID checks still apply. Optionally pass `environment_id` to select a specific ready environment. Existing external `python_path` remains supported without installation. If it addresses this workspace's managed cache, it retains the managed lease and validation; borrowing a different workspace's managed cache is refused.

## Preparation and status

`prepare_environment` searches bounded candidates in PATH and standard Windows Python registration. It skips WindowsApps aliases and accepts standard non-free-threaded CPython3.11–3.14 Windows AMD64 with compatible win_amd64 wheels. An explicit absolute `python_path` can select the source interpreter. Its base interpreter creates a separate venv with copies, without global site packages. No base Python installer is downloaded or run. Missing/unsupported Python returns needs_setup; an available source with venv/ensurepip is required.

The package lock pins pywin32=312, cryptography=50.0.2, cffi=2.1.1 and pycparser=3.12. All permitted Windows wheels have SHA256 entries in the packaged `resources/test-client/python-requirements.lock`. Installation uses isolated pip, a fixed HTTPS PyPI index, binary wheels only, required hashes, no dependency discovery/source builds, no cache and no compile; `pip check`, imports and a crypto primitive are verified afterward. Inherited pip/Python configuration is removed or ignored. Venv installation/probe/managed-worker temporary data stays in the owned environment.

The short cache path is `%LOCALAPPDATA%/EDT-Agent-Bridge/py/<workspace-hash-prefix>/<environment-id>/`. A full workspace-identity witness prevents sharing between workspaces. The key includes source interpreter identity/hash, version and lock digest. This avoids Windows MAX_PATH problems from deep workspace paths. Up to4 retained environments are allowed; unused ones can be explicitly cleaned. No existing environment is automatically upgraded/replaced or removed.

`edt_test_client action=environment_status` checks the active environment; optional `environment_id` selects another. It installs nothing. `ready=true` requires matching owned file inventory, lock, base executable, venv prefix/version and exact imported dependency versions. Busy/preparing, failed, incomplete, changed, missing and multiple-unselected environments remain explicit non-ready states. A failed setup retains its ID so it can be inspected and explicitly cleaned; it is not silently retried.

Readiness survives EDT restart through owned records and files; no interpreter-path search is needed by the agent after the normal first setup. It remains a fresh validation, not permission to run an arbitrary database. Preparation does not start 1C.

## Cleanup and recovery

```json
{"action":"cleanup_environment","environment_id":"<id from preparation>"}
```

Wait for terminal DONE and `result.cleaned=true`. Cleanup refuses a running/starting owned client or active preparation. Start/probe use leases to prevent races with deletion. Stop/clean the owned TestClient first. Unknown/modified package files, extra files/directories and moved paths are preserved for review. Cleanup deletes only the recorded inventory inside the exact owned environment, not a newly rescanned tree. A known partial cleanup can resume while still refusing changed or extra files.

Preparation failures retain an owned log and file manifest. A failed completed install can be cleaned and then prepared again; investigate the original job first. Any abnormal subprocess completion (timeout, output limit, interruption or unproven termination) is persisted as interrupted, without a provisional cleanup inventory; automatic cleanup/reuse is refused. Best-effort termination is not reported as proof that every descendant has exited. Cleanup is not a rollback of 1C data and does not remove databases/projects. Tests must separately clean their owned client, database, project and temporary runtime resources.

## Evidence and limits

See [validation](python-environment-validation.md). Live acceptance used CPython3.13 and Windows x64/1C8.5.1.1522; other supported wheel targets were not separately runtime-tested. The environment is optional: default Vanessa behavior is unchanged. This feature prepares Python for the existing direct transport; it does not add reference/object selection or broaden table editing support.

Package metadata/hashes were taken from the primary [pywin32 release metadata](https://pypi.org/pypi/pywin32/312/json), [cryptography release metadata](https://pypi.org/pypi/cryptography/50.0.2/json), [cffi release metadata](https://pypi.org/pypi/cffi/2.1.1/json) and [pycparser release metadata](https://pypi.org/pypi/pycparser/3.12/json). Wheels are downloaded during explicit preparation, not bundled in the plugin.
