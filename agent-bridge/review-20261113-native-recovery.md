# Project audit: Native recovery and owned cleanup

Requested reviewer: GPT-6 Astra, medium. Independent static project audit and repeated reviews of the implementation; the main agent performed its own diff review and runtime acceptance. No claim that a finite review proves absence of all project defects.

## Findings addressed

- P1 single Native result/ACK loss: freeze completion, retry only completion, stop dispatch when an unresolved single delivery is followed by Poll.
- P1 external cleanup ownership: early durable whole-tree inventory; staged changes restricted to exact generated outputs and hashes, initial descriptor identity checked against native UUID, fail closed for legacy/unproven or altered resources.
- Windows recovery: exact PID/start/session-argument identity and existing WMI fallback; remove stale PID only after proven stop.
- Direct startup/stop/cleanup: one lock order, terminal stopping state, helper retirement and restoration of persisted visibility lease.
- Readiness uses execution authorization/blockers, not merely compatible source.
- Atomic host.json replacement preserves previous ownership proof when replacement fails.
- Additional review regressions fixed: preserve 1–2 MiB result support and large batch read/select; no old-stage sealing of a new channel; parse and hash the same bounded descriptor bytes; preserve conservative partial preparation cleanup.

## Acceptance

1278 Java tests and 179 Python tests; 33 managed controller + 3 CLI + 13 startup contracts, OSGi/product boundary/provenance. p2 resolver succeeds in verifyOnly mode on a copied profile.

Owned isolated EDT and disposable 1C 8.3.27.2325/8.5.1.1522: normal execution, completion POST loss before persistence, ACK loss after persistence, frozen byte-identical retry and once-only mutation counter, >2 MiB explicit omission, intact 1.2 MiB response, large batch read/select, hard completion failure observed through wait only, live registry-loss WMI stop and PID retirement. Direct stop concurrency/visibility tests cover starting/stopping/stopped and repeated stop then cleanup. External sentinel tests preserve unknown root/sibling files and all prior bytes; clean temporary external project unregisters and removes sources after the test sentinels are removed.

Native DLL/ZIP are unchanged pinned zpopup1 artifacts; fresh EPF built from the current HostForm.bsl. User EDT, databases, x5 and antivirus settings were not changed. Historical antivirus classification remains unknown; no false-positive assertion.

## Limits

The current DLL cannot distinguish lost initial batch delivery from batch heartbeat; an unknown mutation is never replayed. Old external hosts without early ownership proof are preserved for review. Tests for Direct lifecycle use owned synthetic dead-process records and do not claim a new complete TestClient UI acceptance run. GitHub OAuth/SWT flows were outside this regression scope.

On 8.5 the first cleanup comparison met a temporary Designer configuration lock. A later explicit read-only comparison succeeded; cleanup resumed its original checkpoint without repeating FULL. Both hosts finally reported source_removed, database_verified_absent and cleaned. Both disposable bases were removed through fresh lease preview/apply; own EDT stopped. No forced disconnect, foreign process signal, or internal SDK workaround was introduced.
