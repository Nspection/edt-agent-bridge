# Direct TestClient: readiness, condition waits and compact results

Available in **1.4.0.20261110zzzwaits1**. Uses the existing owned direct client and managed Python environment. Vanessa remains the default transport; all options below require `transport=testclient`.

## Readiness and waiting

`edt_test_client action=start` now verifies a readable, nonempty semantic window after the protocol handshake, with a ten-second readiness budget. `connected` alone is no longer the startup acceptance condition; success reports `ui_ready=true`. The command never changes startup security/trust settings or automatically restarts an unsuccessful client.

`edt_form_state` accepts `wait_timeout_ms` (0..10000, default1500) for fresh observations. A complete negative reply to a read, a temporarily absent exact field or a form changing during observation can be observed again. A socket/protocol failure is not retried and makes the channel unavailable. The vendor exception CommandNotSentTimeout is handled separately: it guarantees no read bytes were sent, so the wait returns a known condition timeout and preserves the channel. An ambiguous or unsupported tree is not treated as a loading state. A timeout returns success=false/state=condition_timeout, never an invented empty value or a passed check. Saved snapshot details do not wait.

`edt_form_action action=wait` works alone or in an existing batch of up to16 steps. Its required `expected_window` is the exact target title, rather than an instruction to switch windows. Optional `expected_form` is the exact form name. Optional `expect.fields` compares exact displayed strings; `expect.elements` compares known boolean visible/enabled/readonly properties by exact element name. Unknown booleans cannot satisfy false. Field values and boolean states must refer to the same saved form signature (window key, form handle and title), verified before and after property reads. No BSL, expressions, coordinates or implicit selection changes are used by a wait.

```json
{
  "action": "wait",
  "expected_window": "Acceptance (Items)",
  "wait_timeout_ms": 5000,
  "expect": {
    "fields": {"Наименование": "Acceptance"},
    "elements": {"Наименование": {"visible": true, "enabled": true, "readonly": false}}
  }
}
```

A single wait requires an unexpired snapshot belonging to this client but deliberately allows the form to transition since that snapshot. A batch still checks its initial snapshot/window/form before any step. Mutating steps retain their exact pre-dispatch window/form checks. Their postconditions now wait for up to1500ms by default, overridable by step `wait_timeout_ms`. Only reads repeat; an input/click/save is sent once. Zero requests one immediate bounded observation (up to two seconds of wire I/O), not an infinite wait.

Each worker request has an overall25-second I/O budget, including all batch steps; per-step timeouts do not accumulate into an unlimited job. The pipe retains its35-second outer bound. Transport loss after dispatch remains outcome_unknown and blocks replay. A known condition timeout stops the batch, preserves completed results and does not roll back earlier writes. A wait-only timeout preserves the healthy channel. Inspect the original durable result before taking any follow-up action.

## Compact replies and full evidence

Set top-level `response_mode=summary` on a direct action/batch; default `full` preserves prior replies. The full response is persisted to the operation journal before returning the summary. Summaries contain success/unknown state, failed step index when known, finished/passed/verified/total step counts and per-step action, result, wait timing and semantic snapshot ID. Large field values, element trees and table rows are omitted. Timings describe observations and are not a performance SLA.

Read full immutable evidence using `edt_test_client action=result, client_id, operation_id`. A repeated identical idempotency key returns the terminal summary without executing again. Changing the payload, including response_mode, with the same key is refused; use `result` for full detail. Live state must be observed separately. The worker retains at most16 recent semantic snapshots; earlier results remain in the durable operation record until client cleanup. These IDs refer to semantic state, not PNG screenshots; existing native process/window screenshots and native-host screenshot batches are unchanged.

Existing request/reply size and table limitations remain. Batch completion is not a transaction or rollback, and only explicit verified conditions prove the business result.
