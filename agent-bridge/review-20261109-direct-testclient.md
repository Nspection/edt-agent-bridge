# Review: direct TestClient first stage

Scope: direct client service/journal/worker, shared form routing, manager registration reuse and optional dependency/package boundary. Primary review and independent read-only agent review completed; the independent reviewer also rechecked both fixes. User EDT and databases were not used.

1. **P2, fixed:** a request write/flush failure occurred outside `Pipe.call`'s channel-closing catch. The uncertain result could leave the pipe apparently available. All send/read/submission exceptions now close the private channel; the journal retains the unknown result and never resends it.
2. **P2, fixed:** concurrent starts could check fewer than four clients before either created a reservation. A static lock now protects count plus directory reservation across service instances. An incomplete reservation counts toward the limit. A concurrent regression starts two reservations with three existing entries and requires exactly one success and four resulting directories.

Independent recheck confirmed both fixes and found no remaining confirmed blocker in the reviewed paths. The protocol action/commit pair is two different native phases; vendor send_cmd does not automatically resend mutations. Snapshot ownership/staleness checks, batch stopping and missing journal result behavior were checked. Default Vanessa routing remains available.

Validation: 680 Java tests (including the concurrent reservation regression), seven Python worker contracts, standalone package boundary, exact packaged worker bytes and private p2 verifyOnly. Runtime acceptance of the first-stage behavior and cleanup is documented in [validation](direct-testclient-stage1-validation.md). This release's two review fixes affect reservation and failure handling; the earlier three-route live measurement is not described as live fault injection of those fixes.

Remaining limits: optional explicit Python environment; Windows thin client; runtime acceptance on 8.5.1.1522 only; ordinary managed-form controls; no table row actions; batches have no rollback; unknown writes require investigation rather than a new key/client retry.
