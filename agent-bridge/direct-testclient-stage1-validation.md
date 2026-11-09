# Direct TestClient first-stage validation — 2026-11-09

Candidate: **1.4.0.20261109zzztcstage1dev7**, branch `codex/direct-testclient-stage1`, based on main `1c7500dd234910407cffd3e22e27fd7b595339c2`. Local candidate only: no commit, publication, user installation or user EDT restart in this stage.

## Runtime acceptance

Own isolated EDT workspace `EDT-Agent-Bridge-Dev`, two newly provisioned disposable file bases, native-created `AuditItems` catalog with generated forms. Platform **8.5.1.1522**, thin clients, `/i85`. Each successful trial created a new synthetic item, filled its name, saved and closed it, reopened it, checked the persisted name, and closed it. Direct single additionally read code. Three complete trials per route passed.

| Route | Median summed MCP call time | Calls/cycle | Request JSON bytes | Response JSON bytes |
|---|---:|---:|---:|---:|
| Existing Vanessa | 4.985 s | 14 | 1,479 | 15,565 median |
| Direct, separate actions | 2.971 s | 14 | 2,058 | 14,749 median |
| Direct, five-action batch | 1.001 s | 3 | 1,139 | 6,615 |

Vanessa trial times: 5.522 / 4.969 / 4.985 s. Direct separate: 2.869 / 3.157 / 2.971 s. Direct batches: 1.155 / 0.980 / 1.001 s. These sum measured call durations, excluding harness persistence overhead. Runs were sequential after build/test/setup activity; client/manager startup, catalog discovery, warmup and final cleanup were excluded from all routes. All clients used the same disposable target base; Vanessa used a byte-identical private platform copy for isolated manager setup, direct used the selected SDK platform. Vanessa had its owned debugger session during its run; this is a comparison of the supported routes, not an isolated wire-only microbenchmark.

The batch reduced calls by 79%, combined argument/result JSON by approximately 55%, and median call time by approximately 80% compared with this Vanessa route. Separate direct actions gave little total JSON reduction: batching is the main benefit for exchange size. Bytes are normalized UTF-8 JSON arguments and parsed results, excluding tool schemas, MCP envelopes, model history and image data. **No claim of measured billable token savings.** Small local sample; no promise for complex production forms.

Vanessa warmup required fresh caption calibration (its semantic title omits the dirty ` *`) and removal of a search filter in the disposable client. Those incomplete calibration cycles were excluded. Rejected save was verified `action_dispatched=false`; a later failed reopen was investigated by reading the list, without replaying the save. No ambiguous action was retried.

## Regression and negative evidence

- **679 Java tests passed**, including four journal/preflight contracts; standalone product boundary passed.
- **7 Python worker tests passed**, including stale/foreign snapshots, wrong window, lost mutation reply and fail-closed batch behavior.
- Live wrong-window request on final candidate was rejected with `action_dispatched=false`.
- Live stale-snapshot input on dev6 was rejected; saved name remained unchanged. Final candidate changed process-verification overhead, not this snapshot logic.
- Repeated completed batch key on dev6 and final candidate returned the existing durable result without scheduling another job.
- Final package contains the exact checked worker/vendor bytes, no `__pycache__` or `.pyc`. Native p2 director **verifyOnly** passed against a private copy of the installed profile; it did not install into the user EDT.
- Lost-response/restart recovery is covered by worker/journal contracts. This stage did **not** perform a live network-failure injection; no claim of production fault-injection acceptance.

One cleanup calibration encountered a short-lived Windows log lock. Original client identity, exit and residual owned files were checked before retry; final cleanup includes bounded file-release waits. An initial manager extension setup exposed duplicate SDK base references for an already registered manager; `RunnerExtensionService` now reuses its native registration. Preparation passed on the own manager after this fix.

## Cleanup

Both disposable databases were removed through `edt_infobase_cleanup`; receipts explicitly confirmed database removal, launcher registration removal, owned application settings removal and cleanup verification. Both audit projects were removed through Eclipse APIs. Every owned direct client was cleaned. Vanessa run reached terminal `cancelled`, `owned_processes_closed=true`; cancellation was requested after all three measured cycles passed.

The own fixture/platform-copy root, Vanessa controller workspace, temporary Python environment and downloaded research copy were removed. A temporary exact manager-only trust rule needed for Vanessa was restored to the original configuration bytes with before/after SHA256 guards; its own backup files were removed. Installed platform configuration was not changed. The private EDT was closed and its original `bundles.info` restored byte for byte. User EDT and user databases were not operated on.

Retained: source changes, local build/update-site ZIP, benchmark receipts, cleaned-client audit tombstones and the reviewed synthetic screenshot under ignored `.runs/direct-testclient-stage1`. Main receipts: `state.json`, `direct.json`, `va-bench.json`, `direct-bench7.json`, `benchmark-summary.json`, `tests-final7.log`; package receipts are under `.runs/agent-bridge/20261109zzztcstage1dev7`. Runtime dependencies must be prepared again before another own test.

See [usage and limits](direct-testclient-stage1.md). This first stage does not replace Vanessa's broader scenarios, table actions or supported testing ecosystem.
