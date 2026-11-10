# Review: TestClient readiness, waits and summaries

Main review and an independent read-only reviewer examined the final diff. Three confirmed findings were fixed:

- **P1: form-instance mixing.** Field values came from capture, but element properties came from a later form lookup compared only by public title/name. Reopening the same form could combine two instances. Conditions now use saved snapshot elements and verify the full window-key/form-handle/title signature before and after property reads. Replacement before or during the reads cannot pass.
- **P2: local unsent read timeout.** The vendor CommandNotSentTimeout guarantees the command was not sent, but previously closed a healthy channel. It is now handled only inside observational waiting as a known condition_timeout. Socket/receive/protocol failures still propagate and fail closed, including outcome_unknown after dispatch. An exhausted overall budget starts no new observation.
- **P2: single-summary counters.** Successful standalone actions returned zero step counts. A standalone summary now has one step and total_steps=1; its failure index is0. Full field/table payload remains in the durable journal.

Regression cases cover both same-title replacements, deadline before read, deadline after an acknowledged mutation, receive timeout, exhausted overall budget, ambiguous condition elements and single-summary success/failure. Existing read/write and batch idempotency protections are retained. Final automated, live, package and cleanup results are recorded in [validation](direct-testclient-waits-validation.md).

The independent reviewer rechecked every fix and independently ran30 worker +16 table Python tests. No further confirmed blocker was found. The subsequent narrow managed-environment next_call/tool-description change was independently checked as read-only; it does not add installation effects to inspect/status/start. Final acceptance also verifies that hint and the standalone counters on the actual release candidate.
